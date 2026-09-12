#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <shellapi.h>
#include <wchar.h>
#include <stdlib.h>

/* Resolve everything beside the executable, independent of the working directory. */
static int failure(const wchar_t *message, DWORD code) {
    wchar_t details[2048];
    swprintf(details, 2048, L"%ls\n\nCódigo: %lu\nExtrae la carpeta portable completa y vuelve a intentar.", message, code);
    MessageBoxW(NULL, details, L"MusicVault", MB_OK | MB_ICONERROR);
    return code ? (int)code : 1;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR arguments, int show) {
    (void)instance; (void)previous; (void)show;
    wchar_t root[32768];
    DWORD length = GetModuleFileNameW(NULL, root, 32768);
    if (!length || length >= 32768) return failure(L"No se pudo localizar MusicVault.exe.", GetLastError());
    wchar_t *separator = wcsrchr(root, L'\\');
    if (!separator) return failure(L"La ruta de MusicVault no es válida.", 1);
    *separator = L'\0';
    int argc = 0, diagnostic = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv) {
        for (int i = 1; i < argc; ++i)
            if (!wcscmp(argv[i], L"--doctor") || !wcscmp(argv[i], L"--version")) diagnostic = 1;
        LocalFree(argv);
    }
    wchar_t python[32768], script[32768];
    if (swprintf(python, 32768, L"%ls\\python\\%ls", root, diagnostic ? L"python.exe" : L"pythonw.exe") < 0 ||
        swprintf(script, 32768, L"%ls\\launch.py", root) < 0)
        return failure(L"La ruta es demasiado larga.", ERROR_FILENAME_EXCED_RANGE);
    if (GetFileAttributesW(python) == INVALID_FILE_ATTRIBUTES || GetFileAttributesW(script) == INVALID_FILE_ATTRIBUTES)
        return failure(L"Faltan python o launch.py junto a MusicVault.exe.", ERROR_FILE_NOT_FOUND);
    size_t capacity = wcslen(python) + wcslen(script) + wcslen(arguments) + 9;
    if (capacity > 32767) return failure(L"Los argumentos son demasiado largos.", ERROR_FILENAME_EXCED_RANGE);
    wchar_t *command = calloc(capacity, sizeof(wchar_t));
    if (!command) return failure(L"No hay memoria suficiente para iniciar.", ERROR_NOT_ENOUGH_MEMORY);
    swprintf(command, capacity, L"\"%ls\" \"%ls\" %ls", python, script, arguments);
    STARTUPINFOW startup = {0};
    PROCESS_INFORMATION process = {0};
    startup.cb = sizeof(startup);
    if (diagnostic) {
        startup.dwFlags = STARTF_USESTDHANDLES;
        startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
        startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    }
    BOOL started = CreateProcessW(python, command, NULL, NULL, diagnostic, CREATE_NO_WINDOW,
                                 NULL, root, &startup, &process);
    DWORD error = GetLastError();
    free(command);
    if (!started) return failure(L"Windows no pudo iniciar MusicVault.", error);
    CloseHandle(process.hThread);
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD result = 1;
    GetExitCodeProcess(process.hProcess, &result);
    CloseHandle(process.hProcess);
    return (int)result;
}
