"""Stream a verified model into the existing PrismLocal manifest layout over ADB.

Never replaces existing models or preferences. Requires a debuggable package.
Writes into a fresh staging directory, verifies the phone SHA-256, then renames.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import uuid


def gguf_info(path):
    with path.open('rb') as f:
        def number(code):
            return struct.unpack('<'+code, f.read(struct.calcsize('<'+code)))[0]
        def string(keep=True):
            length = number('Q')
            if length > 8 * 1024**2:
                raise ValueError('Oversized GGUF string')
            if keep:
                return f.read(length).decode('utf-8')
            f.seek(length, 1)
        def value(kind, keep=True, depth=0):
            if depth > 4:
                raise ValueError('GGUF nesting limit')
            if kind == 8:
                return string(keep)
            if kind == 9:
                subtype, count = number('I'), number('Q')
                if count > 2_000_000:
                    raise ValueError('GGUF array limit')
                for _ in range(count):
                    value(subtype, False, depth+1)
                return None
            codes = {0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}
            return number(codes[kind])
        if f.read(4) != b'GGUF':
            raise ValueError('Invalid GGUF')
        version, tensors, pairs = number('I'), number('Q'), number('Q')
        if version not in (2, 3) or not 0 < tensors < 100_000 or pairs > 100_000:
            raise ValueError('Invalid GGUF counts')
        metadata = {}
        wanted = {'general.architecture','general.name','general.file_type','general.size_label','tokenizer.chat_template'}
        for _ in range(pairs):
            key, kind = string(), number('I')
            item = value(kind, key in wanted)
            if key in wanted:
                metadata[key] = item
        image = text = False
        for _ in range(tensors):
            name, dims = string(), number('I')
            if not 1 <= dims <= 8:
                raise ValueError('Invalid tensor dimensions')
            for _ in range(dims):
                if number('Q') <= 0:
                    raise ValueError('Invalid tensor shape')
            number('I')
            if number('Q') > path.stat().st_size:
                raise ValueError('Invalid tensor offset')
            image |= name.startswith(('model.diffusion_model.', 'diffusion_model.', 'cond_stage_model.', 'first_stage_model.'))
            text |= name == 'token_embd.weight' or name.startswith('blk.')
        kind = 'IMAGE' if image else 'TEXT' if text and metadata.get('general.architecture') else 'UNKNOWN'
        return version, kind, {
            'architecture': metadata.get('general.architecture'), 'name': metadata.get('general.name'),
            'file_type': metadata.get('general.file_type'), 'size_label': metadata.get('general.size_label'),
            'has_chat_template': bool(metadata.get('tokenizer.chat_template')),
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('key')
    parser.add_argument('--package', default='com.prismai.llmhost.dev.debug')
    parser.add_argument('--serial', default='A7UH025A28000522')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.]+', args.package) or not re.fullmatch(r'[A-Za-z0-9_-]+', args.serial):
        raise ValueError('Invalid ADB target')
    adb = str(Path(os.environ.get('ANDROID_HOME', str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk')))/'platform-tools/adb.exe')
    def run(*command, data=None, stdin=None, check=True):
        return subprocess.run([adb, '-s', args.serial, *command], input=data, stdin=stdin,
                              capture_output=True, check=check)
    def app(*command, **kwargs):
        return run('shell', 'run-as', args.package, *command, **kwargs)
    state_file = Path(f'build/model-install/{args.key}-state.json')
    item = json.loads(state_file.read_text(encoding='utf-8'))
    path = Path(item['local_path'])
    # Recheck integrity at the boundary; never trust a stale status file.
    import hashlib
    digest = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
    if digest != item['sha256_verified'] or path.stat().st_size != item['remote']['size']:
        raise ValueError('Local model changed after download verification')
    if path.suffix == '.gguf':
        version, kind, metadata = gguf_info(path)
    else:
        with path.open('rb') as f:
            length = struct.unpack('<Q', f.read(8))[0]
            if not 2 <= length <= 32 * 1024**2:
                raise ValueError('Invalid Safetensors header')
            tensors = json.loads(f.read(length))
        if not any(k.startswith(('model.diffusion_model.', 'diffusion_model.')) for k in tensors):
            raise ValueError('Expected a diffusion checkpoint')
        version, kind, metadata = 0, 'IMAGE', None
    if kind != item['type'].upper():
        raise ValueError('Actual tensor structure disagrees with requested model type: '+kind)
    model_id = path.stem
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', model_id):
        raise ValueError('Invalid model ID')
    root = 'files/models/'+model_id
    stored = 'model'+path.suffix
    existing = app('test', '-e', root, check=False)
    if existing.returncode == 0:
        active = json.loads(app('cat', root+'/manifest.json').stdout)
        record = active['versions'][active['active_version']]
        if record['sha256'] != digest:
            raise ValueError('Existing model has different content; left untouched')
        phone_sha = app('sha256sum', root+'/'+record['file']).stdout.decode().split()[0]
        if phone_sha != digest:
            raise ValueError('Existing phone file fails integrity verification')
    else:
        free = int(run('shell','df','-k','/data').stdout.decode().splitlines()[-1].split()[3])*1024
        if free < 2 * path.stat().st_size + 3 * 1024**3:
            raise OSError('Insufficient phone storage with 3 GiB reserve')
        stage = 'files/models/.install-'+uuid.uuid4().hex
        app('mkdir','-p',stage+'/versions/v1')
        # adb shell stdin on Windows can truncate binary input at Ctrl-Z.
        # adb push uses the binary sync protocol; the device-side pipe then
        # streams into run-as without loading the checkpoint into memory.
        remote = '/data/local/tmp/prism-model-'+uuid.uuid4().hex
        run('push', str(path), remote)
        try:
            run('shell',f"cat {remote} | run-as {args.package} sh -c 'cat > {stage}/versions/v1/{stored}'")
        finally:
            run('shell','rm','-f',remote)
        phone_sha = app('sha256sum',stage+'/versions/v1/'+stored).stdout.decode().split()[0]
        if phone_sha != digest:
            raise ValueError('Phone SHA-256 mismatch; isolated staging retained')
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        record = {'file':'versions/v1/'+stored,'original_file_name':path.name,'sha256':digest,
                  'bytes':path.stat().st_size,'imported_at':now,
                  'source':{'repo':item['repo'],'revision':item['revision'],'file':item['file']},
                  'validation':{'format':'GGUF' if version else 'SAFETENSORS','gguf_version':version,
                                'status':'verified','validated_at':now,'model_kind':kind,'metadata':metadata}}
        if kind == 'IMAGE':
            record['diffusion_profile'] = {'steps':4,'cfg':1.0,'sampler':'lcm'} if args.key == 'F' else {'steps':16,'cfg':7.0,'sampler':'euler_a'}
        manifest = {'schema_version':1,'model_id':model_id,'active_version':'v1','versions':{'v1':record}}
        # Small JSON still uses push, so non-ASCII names are byte-exact too.
        local_manifest = Path(f'build/model-install/{args.key}-manifest.json')
        local_manifest.write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
        remote_manifest = '/data/local/tmp/prism-manifest-'+uuid.uuid4().hex
        run('push',str(local_manifest),remote_manifest)
        try:
            run('shell',f"cat {remote_manifest} | run-as {args.package} sh -c 'cat > {stage}/manifest.json'")
        finally:
            run('shell','rm','-f',remote_manifest)
        # The destination was proven absent; never merge into an existing model.
        app('mv','-T',stage,root)
    item.update({'transferred':True,'phone_sha256_verified':phone_sha,'model_id':model_id,
                 'phone_relative_path':root+'/'+record['file'],'metadata':metadata})
    state_file.write_text(json.dumps(item,indent=2)+'\n',encoding='utf-8')
    print(f"PASS: {args.key}: transferred and registered {model_id}; phone SHA256 {phone_sha}",flush=True)


if __name__ == '__main__':
    main()
