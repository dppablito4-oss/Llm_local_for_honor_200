#!/usr/bin/env python3
"""Check JNI members defined in final APK DEX files, after R8 shrinking.

Uses only the Python standard library. References in method_ids/field_ids are
insufficient: a constructor can be referenced but removed from class_data.
This complements, rather than replaces, the real device JNI smoke.
"""
import argparse
import re
import struct
import zipfile


PREFIX = "Lcom/prismai/llmhost/"
DIFFUSION = PREFIX + "diffusion/DiffusionNativeResult;"
DRAIN = PREFIX + "bridge/NativeDrainResult;"
BRIDGES = {
    PREFIX + "diffusion/NativeDiffusionBridge;": {"nativeCreate", "nativeDestroy", "nativeGenerate", "nativeCancel"},
    PREFIX + "bridge/NativeLlmBridge;": {"nativeCreateEngine", "nativeDestroyEngine", "nativeDrainDecodeAndState"},
}
DRAIN_FIELDS = {
    "tokensBuffer": "[I", "tokensCount": "I", "textBuffer": "[B",
    "textCount": "I", "textOverflow": "Ljava/lang/String;", "state": "I",
    "promptTokens": "I", "ttftMs": "J", "tokensPerSec": "F",
    "activeThreads": "I", "errorCode": "I", "produced": "J",
    "drained": "J", "pending": "Z",
}


class Dex:
    def __init__(self, data):
        if not data.startswith(b"dex\n"):
            raise ValueError("Unsupported DEX header")
        self.data = data
        string_count, string_off = self.pair(56)
        self.strings = []
        for i in range(string_count):
            offset = self.u32(string_off + 4 * i)
            _, offset = self.uleb(offset)
            # JNI names/descriptors checked here are ASCII. Replacement of
            # unrelated MUTF-8 strings does not affect those comparisons.
            self.strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", "replace"))
        type_count, type_off = self.pair(64)
        self.types = [self.strings[self.u32(type_off + 4 * i)] for i in range(type_count)]
        _, self.proto_off = self.pair(72)
        _, self.field_off = self.pair(80)
        _, self.method_off = self.pair(88)

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def pair(self, offset):
        return struct.unpack_from("<II", self.data, offset)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid ULEB128")

    def signature(self, proto_index):
        _, return_type, params_off = struct.unpack_from("<III", self.data, self.proto_off + 12 * proto_index)
        params = ""
        if params_off:
            params = "".join(self.types[struct.unpack_from("<H", self.data, params_off + 4 + 2 * i)[0]]
                             for i in range(self.u32(params_off)))
        return "(" + params + ")" + self.types[return_type]

    def definitions(self):
        count, offset = self.pair(96)
        targets = {DIFFUSION, DRAIN} | set(BRIDGES)
        for i in range(count):
            definition = offset + 32 * i
            name = self.types[self.u32(definition)]
            if name not in targets:
                continue
            cursor = self.u32(definition + 24)
            fields, methods = {}, {}
            if cursor:
                sizes = []
                for _ in range(4):
                    size, cursor = self.uleb(cursor)
                    sizes.append(size)
                for size in sizes[:2]:
                    index = 0
                    for _ in range(size):
                        delta, cursor = self.uleb(cursor)
                        _, cursor = self.uleb(cursor)  # access_flags
                        index += delta
                        _, type_index, name_index = struct.unpack_from("<HHI", self.data, self.field_off + 8 * index)
                        fields[self.strings[name_index]] = self.types[type_index]
                for size in sizes[2:]:
                    index = 0
                    for _ in range(size):
                        delta, cursor = self.uleb(cursor)
                        access, cursor = self.uleb(cursor)
                        _, cursor = self.uleb(cursor)  # code_off; native methods have none
                        index += delta
                        _, proto_index, name_index = struct.unpack_from("<HHI", self.data, self.method_off + 8 * index)
                        methods[(self.strings[name_index], self.signature(proto_index))] = access
            yield name, (fields, methods)


def check(apk):
    definitions = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                definitions.update(Dex(archive.read(name)).definitions())
    errors = []
    for carrier, signature in (
        (DIFFUSION, "(ZIIILjava/lang/String;[BLjava/lang/String;F)V"),
        (DRAIN, "()V"),
    ):
        if ("<init>", signature) not in definitions.get(carrier, ({}, {}))[1]:
            errors.append(f"missing defined constructor: {carrier} {signature}")
    fields = definitions.get(DRAIN, ({}, {}))[0]
    for name, descriptor in DRAIN_FIELDS.items():
        if fields.get(name) != descriptor:
            errors.append(f"missing JNI field: {DRAIN} {name}:{descriptor}")
    for bridge, names in BRIDGES.items():
        methods = definitions.get(bridge, ({}, {}))[1]
        for name in sorted(names):
            if not any(method_name == name and access & 0x100 for (method_name, _), access in methods.items()):
                errors.append(f"missing native definition: {bridge} {name}")
    if errors:
        raise ValueError(apk + ":\n" + "\n".join(errors))
    print(f"PASS: {apk}: defined JNI constructors, drain fields and native methods")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+")
    args = parser.parse_args()
    try:
        for apk in args.apks:
            check(apk)
    except (OSError, ValueError, zipfile.BadZipFile, struct.error, IndexError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()
