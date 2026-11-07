"""Generate the Windows updater manifest from the exact published installer."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--installer',type=Path,required=True)
    parser.add_argument('--tag',required=True)
    parser.add_argument('--notes',type=Path,required=True)
    args=parser.parse_args()
    version=runpy.run_path(str(ROOT/'musicvault/__init__.py'))
    payload={'schemaVersion':1,'packageName':'com.musicvault.windows',
             'versionCode':version['__version_code__'],'versionName':version['__version__'],
             'notes':args.notes.read_text(encoding='utf-8'),
             'assets':[{'arch':'x86_64','kind':'installer',
                        'url':f'https://github.com/Donatelatrump/MusicVault/releases/download/{args.tag}/{args.installer.name}',
                        'sha256':hashlib.sha256(args.installer.read_bytes()).hexdigest(),
                        'size':args.installer.stat().st_size}]}
    path=args.installer.parent/'musicvault-windows-update.json'
    path.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(path)


if __name__=='__main__':main()
