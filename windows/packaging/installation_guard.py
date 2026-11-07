"""Refuse to replace an installation while its MusicVault processes are running."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import sys

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


if __name__ == '__main__':
    try:
        if running_installation(Path(sys.argv[1]).resolve()):
            print('MusicVault sigue abierto. Cierra la aplicacion para actualizar.')
            raise SystemExit(2)
    except OSError as error:
        print(str(error))
        raise SystemExit(1)
