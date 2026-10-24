#define _WIN32_WINNT 0x0602
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <wchar.h>
#include <stdlib.h>
#include <string.h>

/* Embed the private CPython runtime; no system Python or shell is used. */
typedef int (__cdecl *PythonMain)(int, wchar_t **);

static int fail(const wchar_t *message) {
    MessageBoxW(NULL, message, L"나무팜", MB_OK | MB_ICONERROR);
    return 1;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous,
                    PWSTR command_line, int show) {
    (void)instance; (void)previous; (void)command_line; (void)show;
    wchar_t executable[32768], library[32768];
    DWORD length = GetModuleFileNameW(NULL, executable, 32768);
    if (!length || length >= 32768) return fail(L"실행 파일 경로를 확인할 수 없습니다.");
    wcscpy(library, executable);
    wchar_t *last = wcsrchr(library, L'\\');
    if (!last) return fail(L"실행 파일 폴더를 확인할 수 없습니다.");
    last[1] = L'\0';
    const wchar_t *suffix = L"_runtime\\python312.dll";
    if (wcslen(library) + wcslen(suffix) >= 32768)
        return fail(L"프로그램 경로가 너무 깁니다.");
    wcscat(library, suffix);
    if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS))
        return fail(L"Windows 10/11 64비트 환경에서 실행해주세요.");
    /* Stable-ABI extensions also need python3.dll from the private runtime. */
    wchar_t *filename = wcsrchr(library, L'\\');
    *filename = L'\0';
    DLL_DIRECTORY_COOKIE runtime_directory = AddDllDirectory(library);
    *filename = L'\\';
    if (!runtime_directory) return fail(L"실행환경 폴더를 찾을 수 없습니다.");
    HMODULE python = LoadLibraryExW(library, NULL,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!python) return fail(L"실행환경을 불러올 수 없습니다.\n압축을 모두 풀고 NamuFarm-PC.exe와 _runtime 폴더를 함께 보관해주세요.");
    FARPROC entry = GetProcAddress(python, "Py_Main");
    PythonMain python_main = NULL;
    memcpy(&python_main, &entry, sizeof(python_main));
    if (!python_main) return fail(L"Python 실행환경이 올바르지 않습니다.");
    int count = 0;
    wchar_t **original = CommandLineToArgvW(GetCommandLineW(), &count);
    if (!original || count < 1) return fail(L"실행 인수를 확인할 수 없습니다.");
    wchar_t **arguments = calloc((size_t)count + 3, sizeof(wchar_t *));
    if (!arguments) { LocalFree(original); return fail(L"메모리가 부족합니다."); }
    arguments[0] = executable;
    arguments[1] = L"-c";
    arguments[2] =
        L"import os,sys,traceback\n"
        L"sys.frozen=True\n"
        L"sys.dont_write_bytecode=True\n"
        L"log_path=os.path.join(os.path.dirname(sys.executable),'NamuFarm-error.log')\n"
        L"log=None\n"
        L"try:\n"
        L" log=open(log_path,'w',encoding='utf-8')\n"
        L" sys.stderr=sys.stdout=log\n"
        L"except OSError:\n"
        L" pass\n"
        L"try:\n"
        L" from namufarm_desktop.app import main\n"
        L" result=main()\n"
        L"except SystemExit as error:\n"
        L" result=error.code\n"
        L"except BaseException:\n"
        L" traceback.print_exc()\n"
        L" result=1\n"
        L"finally:\n"
        L" if log is not None: log.close()\n"
        L"if result in (None,0):\n"
        L" try: os.remove(log_path)\n"
        L" except OSError: pass\n"
        L"raise SystemExit(result)\n";
    for (int i = 1; i < count; ++i) arguments[i + 2] = original[i];
    int result = python_main(count + 2, arguments);
    RemoveDllDirectory(runtime_directory);
    free(arguments);
    LocalFree(original);
    if (result) fail(L"나무팜 실행 중 오류가 발생했습니다.\n같은 폴더의 NamuFarm-error.log를 확인해주세요.");
    return result;
}
