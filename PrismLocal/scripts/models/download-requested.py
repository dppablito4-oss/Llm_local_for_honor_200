"""Download immutable Hugging Face files with resume and published SHA-256.

Input is the local sources.json inventory (HF model API with blobs=true).
Weights and installation evidence stay under ignored build/model-install/.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import time
import urllib.request


def validate(path):
    size = path.stat().st_size
    with path.open('rb') as f:
        header = f.read(24)
        if path.suffix == '.gguf':
            if header[:4] != b'GGUF' or struct.unpack_from('<I', header, 4)[0] not in (2, 3):
                raise ValueError('Invalid GGUF header')
            tensors, metadata = struct.unpack_from('<QQ', header, 8)
            if not 0 < tensors < 1_000_000 or metadata > 1_000_000:
                raise ValueError('Invalid GGUF counts')
            return {'format': 'gguf', 'tensors': tensors, 'metadata_count': metadata}
        length = struct.unpack_from('<Q', header)[0]
        if not 2 <= length <= min(32 * 1024 * 1024, size - 8):
            raise ValueError('Invalid Safetensors header length')
        f.seek(8)
        metadata = json.loads(f.read(length))
        tensors = {k: v for k, v in metadata.items() if k != '__metadata__'}
        if not tensors:
            raise ValueError('No Safetensors tensors')
        for tensor in tensors.values():
            start, end = tensor['data_offsets']
            if not 0 <= start <= end <= size - 8 - length:
                raise ValueError('Invalid Safetensors offsets')
        return {'format': 'safetensors', 'tensors': len(tensors)}


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(8 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('key')
    parser.add_argument('--inventory', default='scripts/models/requested-models.json')
    args = parser.parse_args()
    item = next(i for i in json.loads(Path(args.inventory).read_text(encoding='utf-8')) if i['key'] == args.key)
    if not item['remote']:
        raise ValueError('Exact file missing from published repository')
    name = item['file']
    if Path(name).name != name:
        raise ValueError('Expected a plain filename')
    folder = Path('build/model-install/downloads')
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / name
    partial = folder / (name + '.part')
    size = item['remote']['size']
    expected = item['remote']['lfs']['sha256']
    if not target.exists():
        offset = partial.stat().st_size if partial.exists() else 0
        if shutil.disk_usage(folder).free < size - offset + 2 * 1024**3:
            raise OSError('Insufficient local space with 2 GiB reserve')
        for attempt in range(8):
            offset = partial.stat().st_size if partial.exists() else 0
            if offset == size:
                break
            if offset > size:
                raise ValueError('Partial file exceeds expected size')
            url = f"https://huggingface.co/{item['repo']}/resolve/{item['revision']}/{name}?download=true&fresh={time.time_ns()}"
            req = urllib.request.Request(url, headers={'Range': f'bytes={offset}-', 'User-Agent': 'PrismLocal-model-install/1'})
            try:
                with urllib.request.urlopen(req, timeout=60) as response:
                    if response.status == 206:
                        if not response.headers.get('Content-Range', '').startswith(f'bytes {offset}-'):
                            raise ValueError('Server returned incorrect resume range')
                    elif response.status == 200:
                        offset = 0
                    else:
                        raise ValueError('Unexpected download HTTP status')
                    started, last, initial = time.monotonic(), time.monotonic(), offset
                    with partial.open('ab' if offset else 'wb') as out:
                        while chunk := response.read(8 * 1024 * 1024):
                            out.write(chunk)
                            offset += len(chunk)
                            if offset > size:
                                raise ValueError('Download exceeds expected file size')
                            if time.monotonic() - last >= 15:
                                print(f"{args.key}: {offset/1e9:.2f}/{size/1e9:.2f} GB; {(offset-initial)/(time.monotonic()-started)/1e6:.1f} MB/s", flush=True)
                                last = time.monotonic()
                if offset == size:
                    break
            except (OSError, TimeoutError) as error:
                print(f"{args.key}: retry {attempt+1}/8 ({type(error).__name__}); partial retained", flush=True)
                time.sleep(min(2 ** attempt, 20))
        if not partial.exists() or partial.stat().st_size != size:
            raise OSError('Download incomplete; rerun to resume')
        if sha256(partial) != expected:
            raise ValueError('SHA-256 mismatch; partial retained for inspection')
        partial.rename(target)
    if target.stat().st_size != size or sha256(target) != expected:
        raise ValueError('Existing file fails size/hash verification; left untouched')
    item.update({'downloaded': True, 'sha256_verified': expected, 'local_path': str(target), 'header': validate(target)})
    Path(f'build/model-install/{args.key}-state.json').write_text(json.dumps(item, indent=2)+'\n', encoding='utf-8')
    print(f"PASS: {args.key}: {name}, {size} bytes, SHA256 {expected}", flush=True)


if __name__ == '__main__':
    main()
