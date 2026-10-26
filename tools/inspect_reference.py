"""Inventory a user-supplied XAPK without executing it or copying assets to the app.
REA raw evidence is kept in analysis/private; this script emits structural facts.
"""
import argparse
import hashlib
import io
import json
import re
import struct
import zipfile
from pathlib import Path


def dex_strings(data):
    if not data.startswith(b'dex\n'):
        return []
    count, offset = struct.unpack_from('<II', data, 56)
    result = []
    for index in range(count):
        pos = struct.unpack_from('<I', data, offset + index * 4)[0]
        # Skip UTF-16 length encoded in ULEB128; string bytes are MUTF-8.
        for _ in range(5):
            byte = data[pos]
            pos += 1
            if byte < 128:
                break
        end = data.find(b'\0', pos)
        value = data[pos:end].decode('utf-8', errors='replace')
        if re.fullmatch(r'Lcom/[A-Za-z0-9_/$]+;', value) and any(x in value.lower() for x in ('sygic', 'roadlords')):
            result.append(value)
    return result


def inspect(path):
    data = Path(path).read_bytes()
    archive = zipfile.ZipFile(io.BytesIO(data))
    manifest = json.loads(archive.read('manifest.json'))
    apks = []
    classes = set()
    for entry in archive.infolist():
        if not entry.filename.endswith('.apk'):
            continue
        payload = archive.read(entry.filename)
        apk = zipfile.ZipFile(io.BytesIO(payload))
        libs = [{'path': f.filename, 'size': f.file_size} for f in apk.infolist() if f.filename.endswith('.so')]
        dex = [f.filename for f in apk.infolist() if f.filename.endswith('.dex')]
        for dexfile in dex:
            classes.update(dex_strings(apk.read(dexfile)))
        apks.append({'name': entry.filename, 'sha256': hashlib.sha256(payload).hexdigest(), 'native_libraries': libs, 'dex_files': dex})
    return {'sha256': hashlib.sha256(data).hexdigest(), 'package': manifest.get('package_name'),
            'version': manifest.get('version_name'), 'declared_min_sdk': manifest.get('min_sdk_version'),
            'declared_target_sdk': manifest.get('target_sdk_version'), 'apks': apks,
            'reference_class_descriptors': sorted(classes),
            'limitations': ['Static inventory only. Class names and native library names do not establish runtime behavior.',
                            'No recovered implementation, map data, credentials or subscription changes are included in EuroRig.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('xapk')
    parser.add_argument('--output', default='analysis/private/reference-inventory.json')
    args = parser.parse_args()
    result = inspect(args.xapk)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(f"Inventoried {len(result['apks'])} APKs, {len(result['reference_class_descriptors'])} relevant class descriptors; {result['version']}")
