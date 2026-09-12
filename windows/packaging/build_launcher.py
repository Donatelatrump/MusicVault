"""Build the native Windows GUI launcher using MinGW-w64 on Linux or Windows."""
import argparse
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def build(cc=None, windres=None):
    cc = cc or shutil.which('x86_64-w64-mingw32-gcc') or shutil.which('gcc')
    windres = windres or shutil.which('x86_64-w64-mingw32-windres') or shutil.which('windres')
    if not cc or not windres:
        raise RuntimeError('Install MinGW-w64, or provide --cc and --windres.')
    directory = ROOT / 'build/launcher'
    directory.mkdir(parents=True, exist_ok=True)
    resource = directory / 'launcher.o'
    subprocess.run([windres, '--preprocessor', cc, '--preprocessor-arg=-E', '--preprocessor-arg=-xc', '--preprocessor-arg=-DRC_INVOKED', '-I', str(ROOT / 'packaging'), str(ROOT / 'packaging/launcher.rc'), str(resource)], check=True)
    executable = ROOT / 'MusicVault.exe'
    subprocess.run([cc, '-Os', '-Wall', '-Wextra', '-Werror', '-municode', '-mwindows', '-static',
                    '-Wl,--no-insert-timestamp', str(ROOT / 'packaging/launcher.c'), str(resource),
                    '-o', str(executable), '-lshell32', '-luser32'], check=True)
    return executable


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--cc')
    parser.add_argument('--windres')
    args = parser.parse_args()
    print(build(args.cc, args.windres))
