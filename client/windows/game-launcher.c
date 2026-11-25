#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

/* Read by the drivers from the executable that owns the game's GL context. */
__declspec(dllexport) DWORD NvOptimusEnablement = 1;
__declspec(dllexport) DWORD AmdPowerXpressRequestHighPerformance = 1;

typedef unsigned char JBool;
typedef struct {
    char *value;
    JBool wildcard;
} StandardArg;

typedef void (*InitArgs)(JBool, JBool);
typedef void (*ParseArgs)(char *);
typedef int (*ArgCount)(void);
typedef StandardArg *(*GetArgs)(void);
typedef int (*Launch)(int, char **, int, const char **, int, const char **,
                      const char *, const char *, const char *, const char *,
                      JBool, JBool, JBool, int);

static int fail(const char *reason) {
    fprintf(stderr, "BSCP_GAME_JVM: %s\n", reason);
    return 78;
}

int main(void) {
    const wchar_t *library = _wgetenv(L"BSCP_GAME_JLI");
    if (!library || !*library)
        return fail("bundled JLI path missing");
    wchar_t *bin = _wcsdup(library);
    if (!bin)
        return fail("could not allocate the bundled JVM path");
    wchar_t *separator = wcsrchr(bin, L'\\');
    wchar_t *forward = wcsrchr(bin, L'/');
    if (forward && (!separator || forward > separator)) separator = forward;
    if (!separator)
        return fail("the bundled JLI path is not absolute");
    *separator = 0;
    /* HotSpot also loads the stock instrumentation and JFR DLLs by name. */
    if (!SetDllDirectoryW(bin))
        return fail("could not set the bundled JVM library directory");
    free(bin);

    HMODULE jli = LoadLibraryExW(library, NULL,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (!jli)
        return fail("could not load the bundled JLI");

    InitArgs init = (InitArgs)GetProcAddress(jli, "JLI_InitArgProcessing");
    ParseArgs parse = (ParseArgs)GetProcAddress(jli, "JLI_CmdToArgs");
    ArgCount count = (ArgCount)GetProcAddress(jli, "JLI_GetStdArgc");
    GetArgs args = (GetArgs)GetProcAddress(jli, "JLI_GetStdArgs");
    Launch launch = (Launch)GetProcAddress(jli, "JLI_Launch");
    if (!init || !parse || !count || !args || !launch)
        return fail("unsupported JLI launcher API");

    const wchar_t *wide = GetCommandLineW();
    int length = WideCharToMultiByte(CP_ACP, 0, wide, -1, NULL, 0, NULL, NULL);
    char *command = length > 0 ? malloc((size_t)length) : NULL;
    if (!command || !WideCharToMultiByte(CP_ACP, 0, wide, -1, command, length, NULL, NULL))
        return fail("could not encode the command line");

    init(0, 0);
    parse(command);
    int argc = count();
    StandardArg *parsed = args();
    if (argc < 1 || !parsed)
        return fail("could not parse the command line");
    char **argv = calloc((size_t)argc + 1, sizeof(*argv));
    if (!argv)
        return fail("could not allocate the command line");
    for (int i = 0; i < argc; ++i)
        argv[i] = parsed[i].value;
    int status = launch(argc, argv, 0, NULL, 0, NULL, "17", "17", "java", "java", 0, 1, 0, 0);
    free(argv);
    free(command);
    return status;
}
