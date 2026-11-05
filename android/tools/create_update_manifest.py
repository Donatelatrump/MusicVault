#!/usr/bin/env python3
"""Generate GitHub Releases Android update metadata from the actual APKs.

Example: python3 android/tools/create_update_manifest.py --tag android-v0.7.0 \
  --apk arm64-v8a=android/app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  --apk armeabi-v7a=android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import quote


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--apk', action='append', required=True, help='ABI=path; use universal for the universal APK')
    parser.add_argument('--notes', default='')
    parser.add_argument('--output', type=Path, default=Path('musicvault-android-update.json'))
    parser.add_argument('--aapt', default=shutil.which('aapt'))
    args = parser.parse_args()
    aapt = args.aapt
    if not aapt:
        sdk_roots = [Path(value) for value in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT')) if value]
        local_properties = Path(__file__).resolve().parents[1] / 'local.properties'
        if local_properties.exists():
            for line in local_properties.read_text().splitlines():
                if line.startswith('sdk.dir='):
                    sdk_roots.append(Path(line.split('=', 1)[1]))
        sdk_roots.extend([Path.home() / 'Android/Sdk', Path('/usr/lib/android-sdk')])
        candidates = [tool for sdk in sdk_roots for tool in sorted(sdk.glob('build-tools/*/aapt'))]
        if not candidates:
            parser.error('Pass --aapt pointing to Android SDK build-tools/aapt')
        aapt = str(candidates[-1])
    metadata = None
    assets = []
    abis = set()
    for spec in args.apk:
        abi, raw_path = spec.split('=', 1)
        if abi not in {'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86', 'universal'} or abi in abis:
            parser.error(f'Invalid or duplicate ABI: {abi}')
        abis.add(abi)
        apk = Path(raw_path)
        output = subprocess.check_output([aapt, 'dump', 'badging', str(apk)], text=True)
        package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", output, re.M)
        if not package:
            parser.error(f'Cannot read APK metadata: {apk}')
        identity = (package[1], int(package[2]), package[3])
        if metadata and metadata != identity:
            parser.error('All APKs must have the same package, versionCode and versionName')
        metadata = identity
        native = re.search(r'^native-code:(.*)$', output, re.M)
        if abi != 'universal' and native and f"'{abi}'" not in native[1]:
            parser.error(f'APK does not contain {abi}: {apk}')
        with apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        assets.append(dict(abi=abi, url=f'https://github.com/Donatelatrump/MusicVault/releases/download/{quote(args.tag, safe="")}/{quote(apk.name)}',
                           size=apk.stat().st_size, sha256=digest))
    if len({a['url'] for a in assets}) != len(assets):
        parser.error('APK filenames must be unique within the release')
    result = dict(schemaVersion=1, packageName=metadata[0], versionCode=metadata[1],
                  versionName=metadata[2], notes=args.notes, assets=assets)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f'{args.output}: {metadata[0]} {metadata[2]} (versionCode {metadata[1]})')


if __name__ == '__main__':
    main()
