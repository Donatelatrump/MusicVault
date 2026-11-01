"""Apply a verified application-only patch using the installed Python runtime."""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile
import zipfile


def running_installation(root):
    """Find only processes belonging to this installation, excluding this helper."""
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)

    class ProcessEntry(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('usage', wintypes.DWORD),
                    ('pid', wintypes.DWORD), ('heap', ctypes.c_size_t),
                    ('module', wintypes.DWORD), ('threads', wintypes.DWORD),
                    ('parent', wintypes.DWORD), ('priority', wintypes.LONG),
                    ('flags', wintypes.DWORD), ('exe', wintypes.WCHAR * 260)]

    kernel.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    kernel.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessEntry)]
    kernel.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessEntry)]
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    snapshot = kernel.CreateToolhelp32Snapshot(2, 0)
    if snapshot == ctypes.c_void_p(-1).value:
        raise OSError('No se pudieron comprobar los procesos de MusicVault.')
    watched = {os.path.normcase(str(root / name)) for name in
               ('MusicVault.exe', 'python/python.exe', 'python/pythonw.exe')}
    entry = ProcessEntry()
    entry.size = ctypes.sizeof(entry)
    try:
        more = kernel.Process32FirstW(snapshot, ctypes.byref(entry))
        while more:
            if entry.pid != os.getpid():
                handle = kernel.OpenProcess(0x1000, False, entry.pid)
                if handle:
                    try:
                        name = ctypes.create_unicode_buffer(32768)
                        size = wintypes.DWORD(len(name))
                        if kernel.QueryFullProcessImageNameW(handle, 0, name, ctypes.byref(size)):
                            if os.path.normcase(str(Path(name.value).resolve())) in watched:
                                return True
                    finally:
                        kernel.CloseHandle(handle)
            more = kernel.Process32NextW(snapshot, ctypes.byref(entry))
        return False
    finally:
        kernel.CloseHandle(snapshot)


def apply(root, payload, manifest):
    root = root.resolve()
    expected = json.loads(manifest.read_text(encoding='utf-8'))
    lock = root / 'dependencies.lock.json'
    if not lock.is_file() or json.loads(lock.read_text()) != expected['dependencies']:
        raise ValueError('Esta actualizacion necesita MusicVault Windows 0.9.7. Para otra version instala el paquete completo.')
    for name in ('MusicVault.exe', 'launch.py', 'python/pythonw.exe', 'runtime/libmpv-2.dll'):
        if not (root / name).is_file():
            raise ValueError('La carpeta seleccionada no contiene una instalacion completa de MusicVault.')
    if running_installation(root):
        print('MusicVault esta abierto. Usa Salir en el icono de la bandeja y pulsa Reintentar.', flush=True)
        return 2
    files = expected['files']
    with tempfile.TemporaryDirectory(prefix='.musicvault-update-', dir=root) as folder:
        staging = Path(folder)
        fresh, backup = staging / 'new', staging / 'old'
        with zipfile.ZipFile(payload) as archive:
            if set(archive.namelist()) != set(files):
                raise ValueError('El contenido de la actualizacion no coincide con su manifiesto.')
            for name, digest in files.items():
                path = PurePosixPath(name)
                if (path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name
                        or not path.parts or not (path.parts[0] in
                        {'musicvault', 'MusicVault.exe', 'launch.py', 'README.md', 'VERIFICATION.md', 'THIRD_PARTY_NOTICES.md'}
                        or path.parts[:2] == ('runtime', 'newpipe'))):
                    raise ValueError('Ruta de actualizacion no permitida.')
                target = root / name
                if not target.resolve().is_relative_to(root):
                    raise ValueError('La actualizacion no puede escribir fuera de MusicVault.')
                content = archive.read(name)
                if hashlib.sha256(content).hexdigest() != digest:
                    raise ValueError('Archivo de actualizacion danado: ' + name)
                dest = fresh / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(content)
        # Keep originals before touching any installed file, so failures roll back.
        for name in files:
            target = root / name
            if target.exists():
                dest = backup / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(target, dest)
        changed = []
        try:
            for name in files:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                os.replace(fresh / name, target)
                changed.append(name)
        except Exception:
            for name in reversed(changed):
                original = backup / name
                if original.exists():
                    os.replace(original, root / name)
                else:
                    (root / name).unlink(missing_ok=True)
            raise
    print('Actualizacion instalada. Biblioteca, preferencias y dependencias conservadas.', flush=True)
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    try:
        raise SystemExit(apply(args.root, args.payload, args.manifest))
    except Exception as error:
        print(str(error), flush=True)
        raise SystemExit(1)
