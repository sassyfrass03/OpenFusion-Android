#include "ffrunner.h"
#include <errno.h>
#include <limits.h>

NPP_t npp;
NPPluginFuncs pluginFuncs;
NPNetscapeFuncs netscapeFuncs;
NPSavedData saved;
NPSavedData *savedPtr;
NPObject browserObject;
NPClass browserClass;
NPWindow npWin;
NPObject *scriptableObject;

Arguments args = { .windowMode = WINDOWED };

#define NPIDENTIFIERCOUNT 32
#define NPSTRINGMAXSIZE 128

char npidentifiers[NPIDENTIFIERCOUNT][NPSTRINGMAXSIZE];

NPIdentifier
getNPIdentifier(const char *s)
{
    int i;

    assert(*s != '\0');
    assert(strlen(s) < NPSTRINGMAXSIZE);

    for (i = 0; i < NPIDENTIFIERCOUNT; i++) {
        if (strncmp(s, npidentifiers[i], NPSTRINGMAXSIZE) == 0)
            return (NPIdentifier)&npidentifiers[i];

        if (npidentifiers[i][0] == '\0')
            break;
    }
    /*
     * Make sure there's still room.
     * i would have already been incremented to NPIDENTIFIERCOUNT if
     * the loop went through all iterations.
     */
    assert(i < NPIDENTIFIERCOUNT);

    assert(npidentifiers[i][0] == '\0');
    strncpy(npidentifiers[i], s, NPSTRINGMAXSIZE);
    return &npidentifiers[i];
}

NPError
NPN_GetURLProc(NPP instance, const char* url, const char* window)
{
    dbglogmsg("< NPN_GetURLProc:%p, url: %s, window: %s\n", instance, url, window);

    register_get_request(url, false, NULL);

    return NPERR_NO_ERROR;
}

NPError
NPN_GetURLNotifyProc(NPP instance, const char* url, const char* window, void* notifyData)
{
    dbglogmsg("< NPN_GetURLNotifyProc:%p, url: %s, window: %s, notifyData: %p\n", instance, url, window, notifyData);

    register_get_request(url, true, notifyData);

    return NPERR_NO_ERROR;
}

NPError
NPN_PostURLProc(NPP instance, const char* url, const char* window, uint32_t len, const char* buf, NPBool file)
{
    dbglogmsg("< NPN_PostURLProc:%p, url: %s, window: %s, len: %d, buf: %s, file: %d\n",
            instance, url, window, len, buf, file);

    register_post_request(url, false, NULL, len, buf);

    return NPERR_NO_ERROR;
}

NPError
NPN_PostURLNotifyProc(NPP instance, const char* url, const char* window, uint32_t len, const char* buf, NPBool file, void* notifyData)
{
    dbglogmsg("< NPN_PostURLNotifyProc:%p, url: %s, window: %s, len: %d, buf: %s, file: %d, notifyData: %p\n",
            instance, url, window, len, buf, file, notifyData);

    register_post_request(url, true, notifyData, len, buf);

    return NPERR_NO_ERROR;
}

const char *
NPN_UserAgentProc(NPP instance)
{
    dbglogmsg("< NPN_UserAgentProc, NPP:%p\n", instance);
    return USERAGENT;
}

bool
NPN_GetPropertyProc(NPP npp, NPObject *obj, NPIdentifier propertyName, NPVariant *result)
{
    dbglogmsg("< NPN_GetPropertyProc\n");
    return false;
}

bool
NPN_InvokeProc(NPP npp, NPObject* obj, NPIdentifier methodName, const NPVariant *args, uint32_t argCount, NPVariant *result)
{
    dbglogmsg("< NPN_InvokeProc:%p, obj: %p, methodName: %p, argCount:%d\n", npp, obj, methodName, argCount);
    return false;
}

void
NPN_ReleaseVariantValueProc(NPVariant *variant)
{
    dbglogmsg("< NPN_ReleaseVariantValueProc\n");
}

NPObject *
NPN_CreateObjectProc(NPP npp, NPClass *aClass)
{
    NPObject *npobj;

    dbglogmsg("< NPN_CreateObjectProc\n");
    assert(aClass);

    if (aClass->allocate)
        npobj = aClass->allocate(npp, aClass);
    else
        npobj = malloc(sizeof(*npobj));

    if (npobj) {
        npobj->_class = aClass;
        npobj->referenceCount = 1;
    }

    return npobj;
}

NPObject *
NPN_RetainObjectProc(NPObject *obj)
{
    dbglogmsg("< NPN_RetainObjectProc\n");
    assert(obj);
    obj->referenceCount++;
    return obj;
}

void
NPN_ReleaseObjectProc(NPObject *obj)
{
    dbglogmsg("< NPN_ReleaseObjectProc\n");
    assert(obj);

    obj->referenceCount--;

    if (obj->referenceCount == 0) {
        /* should never ask to deallocate the (statically allocated) browser window object */
        assert(obj != &browserObject);

        if (obj->_class && obj->_class->deallocate)
            obj->_class->deallocate(obj);
        else
            free(obj);
    }
}

NPError
NPN_GetValueProc(NPP instance, NPNVariable variable, void *ret_value)
{
    NPObject **retPtr;

    dbglogmsg("< NPN_GetValueProc %d\n", variable);

    browserObject.referenceCount++;

    retPtr = (NPObject**)ret_value;
    *retPtr = &browserObject;

    return NPERR_NO_ERROR;
}

#define AUTH_CALLBACK_SCRIPT "authDoCallback(\"UnityEngine.GameObject\");"
#define HOMEPAGE_CALLBACK_SCRIPT "HomePage(\"UnityEngine.GameObject\");"
#define PAGEOUT_CALLBACK_SCRIPT "PageOut(\"UnityEngine.GameObject\");"
#define NAVIGATE_SCRIPT "location.href=\""

#define TARGET_REGISTER "https://audience.fusionfall.com/ff/regWizard.do?_flowId=fusionfall-registration-flow"
#define TARGET_MANAGE_ACCOUNT "https://audience.fusionfall.com/ff/login.do"
#define TARGET_COMMUNITY "http://forums.fusionfall.com"

#define DISCORD_LINK "https://discord.gg/DYavckB"

void
handle_navigation(char *target)
{
    if (strncmp(target, TARGET_REGISTER, sizeof(TARGET_REGISTER) - 2) == 0) {
        show_error_dialog("The register page is currently unimplemented.\n\n" \
            "You can still create an account: type your desired username and password into the provided boxes and click \"Log In\". " \
            "Your account will then be automatically created on the server. \nBe sure to remember these details!");
    } else if (strncmp(target, TARGET_MANAGE_ACCOUNT, sizeof(TARGET_MANAGE_ACCOUNT) - 2) == 0) {
        show_error_dialog("Account management is not available.");
    } else if (strncmp(target, TARGET_COMMUNITY, sizeof(TARGET_COMMUNITY) - 2) == 0) {
        open_link(DISCORD_LINK);
    } else {
        logmsg("Unhandled navigation target: %s\n", target);
    }
}

char *
get_navigation_target(const char *script)
{
    char *found;

    found = strstr(script, NAVIGATE_SCRIPT);
    if (!found) {
        return NULL;
    }

    return found + sizeof(NAVIGATE_SCRIPT) - 1;
}

void
unity_send_message(const char *class, const char *msg, NPVariant val)
{
    NPVariant args[3];
    NPVariant ret;

    assert(scriptableObject->_class);
    assert(scriptableObject->_class->hasMethod(scriptableObject, getNPIdentifier("SendMessage")));

    STRINGZ_TO_NPVARIANT(class, args[0]);
    STRINGZ_TO_NPVARIANT(msg, args[1]);
    args[2] = val;
    scriptableObject->_class->invoke(scriptableObject, getNPIdentifier("SendMessage"), args, ARRLEN(args), &ret);
}

void
auth(char *username, char *password)
{
    NPVariant val;
    logmsg("Auto-auth as %s\n", username);

    STRINGZ_TO_NPVARIANT(username, val);
    unity_send_message("GlobalManager", "SetTEGid", val);

    STRINGZ_TO_NPVARIANT(password, val);
    unity_send_message("GlobalManager", "SetAuthid", val);

    INT32_TO_NPVARIANT(0, val);
    unity_send_message("GlobalManager", "DoAuth", val);
}

static int
hex_digit(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static bool
form_decode(const char *src, size_t len, char *dst, size_t dstSize)
{
    size_t out = 0;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)src[i];
        if (c == '+') c = ' ';
        else if (c == '%') {
            if (i + 2 >= len) return false;
            int hi = hex_digit(src[i + 1]), lo = hex_digit(src[i + 2]);
            if (hi < 0 || lo < 0) return false;
            c = (unsigned char)((hi << 4) | lo); i += 2;
        }
        if (c == 0 || c == '\r' || c == '\n' || out + 1 >= dstSize) return false;
        dst[out++] = (char)c;
    }
    dst[out] = 0;
    return out > 0;
}

static bool
auth_from_bridge(const char *url)
{
    HINTERNET internet = NULL, request = NULL;
    char body[16384 + 1];
    DWORD total = 0, count = 0, status = 0, statusSize = sizeof(status);
    bool success = false;

    internet = InternetOpenA(USERAGENT, INTERNET_OPEN_TYPE_DIRECT, NULL, NULL, 0);
    if (!internet) goto cleanup;
    request = InternetOpenUrlA(internet, url, NULL, 0,
        INTERNET_FLAG_RELOAD | INTERNET_FLAG_NO_CACHE_WRITE | INTERNET_FLAG_PRAGMA_NOCACHE, 0);
    if (!request) goto cleanup;
    if (!HttpQueryInfoA(request, HTTP_QUERY_FLAG_NUMBER | HTTP_QUERY_STATUS_CODE,
            &status, &statusSize, NULL) || status != HTTP_STATUS_OK) goto cleanup;

    while (total < sizeof(body) - 1) {
        if (!InternetReadFile(request, body + total, (DWORD)(sizeof(body) - 1 - total), &count)) goto cleanup;
        if (!count) break;
        total += count;
    }
    if (total == sizeof(body) - 1) {
        BYTE extra; DWORD more = 0;
        if (InternetReadFile(request, &extra, 1, &more) && more) goto cleanup;
    }
    body[total] = 0;

    const char *prefix = "username=";
    char *separator = strstr(body, "&cookie=");
    if (strncmp(body, prefix, strlen(prefix)) || !separator) goto cleanup;
    char username[512], cookie[8192];
    const char *userStart = body + strlen(prefix);
    const char *cookieStart = separator + strlen("&cookie=");
    if (!form_decode(userStart, (size_t)(separator - userStart), username, sizeof(username))) goto cleanup;
    if (!form_decode(cookieStart, strlen(cookieStart), cookie, sizeof(cookie))) goto cleanup;

    logmsg("Fresh game login received from Android bridge for %s.\n", username);
    auth(username, cookie);
    SecureZeroMemory(cookie, sizeof(cookie));
    success = true;

cleanup:
    if (request) InternetCloseHandle(request);
    if (internet) InternetCloseHandle(internet);
    if (!success) {
        logmsg("Fresh game login request failed. Keep the Android launcher bridge active and prepare once again if needed.\n");
        show_error_dialog("Could not get a fresh FusionFall login from the Android launcher. Keep the launcher/bridge running, then press Play once to restart it.");
    }
    return success;
}

bool
NPN_EvaluateProc(NPP npp, NPObject *obj, NPString *script, NPVariant *result)
{
    char *navigationTarget;

    dbglogmsg("< NPN_EvaluateProc %s\n", script->UTF8Characters);

    /* Evaluates JS calls, like MarkProgress(1), most of which doesn't need to do anything. */
    if (strncmp(script->UTF8Characters, HOMEPAGE_CALLBACK_SCRIPT, sizeof(HOMEPAGE_CALLBACK_SCRIPT)) == 0
        || strncmp(script->UTF8Characters, PAGEOUT_CALLBACK_SCRIPT, sizeof(PAGEOUT_CALLBACK_SCRIPT)) == 0) {
        /* Gracefully exit game. */
        PostQuitMessage(0);
    } else if (strncmp(script->UTF8Characters, AUTH_CALLBACK_SCRIPT, sizeof(AUTH_CALLBACK_SCRIPT)) == 0) {
        /* Execute authentication callback */
        if (args.authUrl != NULL) {
            auth_from_bridge(args.authUrl);
        } else if (args.tegId != NULL && args.authId != NULL) {
            auth(args.tegId, args.authId);
        }
    } else {
        /* If navigation, handle */
        navigationTarget = get_navigation_target(script->UTF8Characters);
        if (navigationTarget != NULL) {
            handle_navigation(navigationTarget);
        }
    }

    *result = (NPVariant){
        .type = NPVariantType_Void
    };

    return true;
}

NPIdentifier
NPN_GetStringIdentifierProc(const NPUTF8* name)
{
    dbglogmsg("< NPN_GetStringIdentifierProc %s\n", name);

    return getNPIdentifier(name);
}

void
NPN_GetStringIdentifiersProc(const NPUTF8** names, int32_t nameCount, NPIdentifier* identifiers)
{
    int i;

    dbglogmsg("< NPN_GetStringIdentifiersProc %d\n", nameCount);

    for (i = 0; i < nameCount; i++) {
        identifiers[i] = getNPIdentifier(names[i]);
    }
}

/*
 * Browser Object methods below here.
 */

NPObject *
NPAllocateFunction(NPP instance, NPClass *aClass)
{
    NPObject *npobj;

    dbglogmsg("< NPAllocateFunction %p\n", aClass);
    assert(aClass);

    if (aClass->allocate)
        npobj = aClass->allocate(instance, aClass);
    else
        npobj = malloc(sizeof(*npobj));

    if (npobj) {
        npobj->_class = aClass;
        npobj->referenceCount = 1;
    }

    return npobj;
}

void
NPDeallocateFunction(NPObject *npobj)
{
    dbglogmsg("< NPDeallocateFunction %p\n", npobj);
    assert(npobj != &browserObject);

    free(npobj);
}

void
NPInvalidateFunction(NPObject *npobj)
{
    dbglogmsg("< NPInvalidateFunction %p\n", npobj);
}

bool
NPHasMethodFunction(NPObject *npobj, NPIdentifier name)
{
    dbglogmsg("< NPHasMethodFunction %p %p\n", npobj, name);

    return 0;
}

bool
NPInvokeFunction(NPObject *npobj, NPIdentifier name, const NPVariant *args, uint32_t argCount, NPVariant *result)
{
    dbglogmsg("< NPInvokeFunction %p %p\n", npobj, name);

    return 0;
}

bool
NPInvokeDefaultFunction(NPObject *npobj, const NPVariant *args, uint32_t argCount, NPVariant *result)
{
    dbglogmsg("< NPInvokeDefaultFunction %p\n", npobj);

    return 0;
}

bool
NPHasPropertyFunction(NPObject *npobj, NPIdentifier name)
{
    dbglogmsg("< NPHasPropertyFunction %p\n", npobj);

    return 0;
}

bool
NPGetPropertyFunction(NPObject *npobj, NPIdentifier name, NPVariant *result)
{
    dbglogmsg("< NPGetPropertyFunction %p\n", npobj);

    return 0;
}

bool
NPSetPropertyFunction(NPObject *npobj, NPIdentifier name, const NPVariant *value)
{
    dbglogmsg("< NPSetPropertyFunction %p\n", npobj);

    return 0;
}

bool
NPRemovePropertyFunction(NPObject *npobj, NPIdentifier name)
{
    dbglogmsg("< NPRemovePropertyFunction %p\n", npobj);

    return 0;
}

bool
NPEnumerationFunction(NPObject *npobj, NPIdentifier **value, uint32_t *count)
{
    dbglogmsg("< NPEnumerationFunction %p\n", npobj);

    return 0;
}

bool
NPConstructFunction(NPObject *npobj, const NPVariant *args, uint32_t argCount, NPVariant *result)
{
    dbglogmsg("< NPConstructFunction %p\n", npobj);

    return 0;
}

void
initBrowserObject(void)
{
    browserObject._class = &browserClass;
    browserObject.referenceCount = 1;

    browserClass.structVersion = 3;
    browserClass.allocate = NPAllocateFunction;
    browserClass.deallocate = NPDeallocateFunction;
    browserClass.invalidate = NPInvalidateFunction;
    browserClass.hasMethod = NPHasMethodFunction;
    browserClass.invoke = NPInvokeFunction;
    browserClass.invokeDefault = NPInvokeDefaultFunction;
    browserClass.hasProperty = NPHasPropertyFunction;
    browserClass.getProperty = NPGetPropertyFunction;
    browserClass.setProperty = NPSetPropertyFunction;
    browserClass.removeProperty = NPRemovePropertyFunction;
    browserClass.enumerate = NPEnumerationFunction;
    browserClass.construct = NPConstructFunction;
}

void
initNetscapeFuncs(void)
{
    netscapeFuncs.size = 224;
    netscapeFuncs.version = 27;
    netscapeFuncs.geturl = NPN_GetURLProc;
    netscapeFuncs.posturl = NPN_PostURLProc;
    netscapeFuncs.uagent = NPN_UserAgentProc;
    netscapeFuncs.geturlnotify = NPN_GetURLNotifyProc;
    netscapeFuncs.posturlnotify = NPN_PostURLNotifyProc;
    netscapeFuncs.releaseobject = NPN_ReleaseObjectProc;
    netscapeFuncs.invoke = NPN_InvokeProc;
    netscapeFuncs.getproperty = NPN_GetPropertyProc;
    netscapeFuncs.createobject = NPN_CreateObjectProc;
    netscapeFuncs.retainobject = NPN_RetainObjectProc;
    netscapeFuncs.releasevariantvalue = NPN_ReleaseVariantValueProc;
    netscapeFuncs.getvalue = NPN_GetValueProc;
    netscapeFuncs.evaluate = NPN_EvaluateProc;
    netscapeFuncs.getstringidentifier = NPN_GetStringIdentifierProc;
    netscapeFuncs.getstringidentifiers = NPN_GetStringIdentifiersProc;
}

static void
usage(void)
{
    puts("FFRunner " FFRUNNER_BUILD " (x86 / 32-bit Unity Web Player)");
    puts("Usage: ffrunner-android.exe [OPTION...]");
    puts("  -m, --main URL          Main Unity content URL or file");
    puts("  -a, --address HOST:PORT Login server");
    puts("      --asseturl URL      Asset CDN URL");
    puts("  -e, --endpoint HOST     OFAPI host");
    puts("  -u, --username NAME     Auto-login username");
    puts("  -t, --token TOKEN       Legacy auto-login session token");
    puts("      --auth-url URL       Fetch a fresh game login from the Android bridge");
    puts("  -n, --name TEXT         Window title");
    puts("  -l, --log PATH          Log file");
    puts("  -v, --verbose           Verbose diagnostic logging");
    puts("  -i, --icon URL          Window icon");
    puts("      --width N          Client width for window modes (320..16384)");
    puts("      --height N         Client height for window modes (200..16384)");
    puts("      --borderless       Fill container display without borders (experimental)");
    puts("      --borderless-window Remove borders; use --width and --height");
    puts("      --windowed         Normal framed window (default); use --width and --height");
    puts("      --vram-mb N        Unity memory hint (128..2048); default 512 MB");
    puts("      --query-vram       Opt in to DXGI memory probing");
    puts("      --force-opengl     Use Unity's OpenGL fallback");
    puts("      --force-vulkan     Use d3d9_vulkan.dll");
    puts("      --loader-images    Use the endpoint's loading images");
    puts("      --xinput-bridge    Exclusive Xbox/XInput compatibility (hide Unity native pad)");
    puts("      --version          Print runner build and exit");
    puts("  -h, --help             Show this help");
}

static uint32_t
parse_number(const char *text, uint32_t low, uint32_t high, const char *option)
{
    char *end;
    errno = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (errno || !*text || *text == '-' || *end || value < low || value > high) {
        fprintf(stderr, "Invalid %s: expected %u..%u.\n", option, low, high);
        exit(2);
    }
    return (uint32_t)value;
}

void
parse_args(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        char name[64];
        const char *arg = argv[i], *equals = strchr(arg, '=');
        size_t count = equals ? (size_t)(equals - arg) : strlen(arg);
        if (count >= sizeof(name)) { fprintf(stderr, "Option name too long.\n"); exit(2); }
        memcpy(name, arg, count); name[count] = 0;
        const char *value = equals ? equals + 1 : NULL;
        bool flag = true;
        if (!strcmp(name, "--help") || !strcmp(name, "-h")) { usage(); exit(0); }
        if (!strcmp(name, "--version")) { puts(FFRUNNER_BUILD " x86"); exit(0); }
        if (!strcmp(name, "--borderless")) args.windowMode = BORDERLESS_FULLSCREEN;
        else if (!strcmp(name, "--borderless-window")) args.windowMode = BORDERLESS_WINDOW;
        else if (!strcmp(name, "--windowed")) args.windowMode = WINDOWED;
        else if (!strcmp(name, "--query-vram")) args.queryVram = true;
        else if (!strcmp(name, "--verbose") || !strcmp(name, "-v")) args.verboseLogging = true;
        else if (!strcmp(name, "--force-opengl")) args.forceOpenGl = true;
        else if (!strcmp(name, "--force-vulkan")) args.forceVulkan = true;
        else if (!strcmp(name, "--loader-images")) args.useEndpointLoadingScreen = true;
        else if (!strcmp(name, "--xinput-bridge")) args.xinputBridge = true;
        else flag = false;
        if (flag) {
            if (value) { fprintf(stderr, "%s does not take a value.\n", name); exit(2); }
            continue;
        }
        if (!value) {
            if (++i >= argc || argv[i][0] == '-') { fprintf(stderr, "Missing value for %s.\n", name); exit(2); }
            value = argv[i];
        }
        if (!strcmp(name, "--main") || !strcmp(name, "-m")) args.mainPathOrAddress = (char*)value;
        else if (!strcmp(name, "--address") || !strcmp(name, "-a")) args.serverAddress = (char*)value;
        else if (!strcmp(name, "--asseturl")) args.assetUrl = (char*)value;
        else if (!strcmp(name, "--endpoint") || !strcmp(name, "-e")) args.endpointHost = (char*)value;
        else if (!strcmp(name, "--username") || !strcmp(name, "-u")) args.tegId = (char*)value;
        else if (!strcmp(name, "--token") || !strcmp(name, "-t")) args.authId = (char*)value;
        else if (!strcmp(name, "--auth-url")) args.authUrl = (char*)value;
        else if (!strcmp(name, "--log") || !strcmp(name, "-l")) args.logPath = (char*)value;
        else if (!strcmp(name, "--name") || !strcmp(name, "-n")) args.windowName = (char*)value;
        else if (!strcmp(name, "--icon") || !strcmp(name, "-i")) args.windowIcon = (char*)value;
        else if (!strcmp(name, "--width")) args.windowWidth = parse_number(value, 320, 16384, name);
        else if (!strcmp(name, "--height")) args.windowHeight = parse_number(value, 200, 16384, name);
        else if (!strcmp(name, "--vram-mb")) args.vramMb = parse_number(value, 128, 2048, name);
        else { fprintf(stderr, "Unknown option: %s. Use --help.\n", name); exit(2); }
    }
    if (args.forceOpenGl && args.forceVulkan) { fprintf(stderr, "Choose only one renderer override.\n"); exit(2); }
    if (!args.mainPathOrAddress) args.mainPathOrAddress = FALLBACK_SRC_URL;
    if (!args.assetUrl) args.assetUrl = FALLBACK_ASSET_URL;
    if (!args.serverAddress) args.serverAddress = FALLBACK_SERVER_ADDRESS;
    if (!args.logPath) args.logPath = LOG_FILE_PATH;
    if (!args.windowWidth) args.windowWidth = DEFAULT_WIDTH;
    if (!args.windowHeight) args.windowHeight = DEFAULT_HEIGHT;
    if (!args.windowName) args.windowName = DEFAULT_WINDOW_NAME;
    if (args.useEndpointLoadingScreen && !args.endpointHost) {
        fprintf(stderr, "--loader-images requires --endpoint.\n"); exit(2);
    }
}

void
print_args()
{
    printf("main: %s\n", args.mainPathOrAddress);
    printf("log: %s\n", args.logPath);
    printf("verbose: %s\n", args.verboseLogging ? "true" : "false");
    printf("address: %s\n", args.serverAddress);
    printf("asseturl: %s\n", args.assetUrl);
    printf("endpoint: %s\n", args.endpointHost);
    printf("username: %s\n", args.tegId == NULL ? "(null)" : args.tegId);
    printf("token: %s\n", args.authId == NULL ? "(null)" : "********");
    printf("auth-bridge: %s\n", args.authUrl == NULL ? "off" : "configured");
    printf("xinput-bridge: %s\n", args.xinputBridge ? "on" : "off");
    printf("width: %d\n", args.windowWidth);
    printf("height: %d\n", args.windowHeight);
    printf("icon: %s\n", args.windowIcon);
    printf("loader-images: %s\n", args.useEndpointLoadingScreen ? "true" : "false");
    printf("force-vulkan: %s\n", args.forceVulkan ? "true" : "false");
    printf("force-opengl: %s\n", args.forceOpenGl ? "true" : "false");
    printf("name: %s\n", args.windowName);
}

void
enable_dpi_awareness(void)
{
    bool configured = false;
    HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore) {
        SetProcessDpiAwarenessFunc setAwareness =
            (SetProcessDpiAwarenessFunc)GetProcAddress(shcore, "SetProcessDpiAwareness");
        if (setAwareness) {
            HRESULT status = setAwareness(PROCESS_PER_MONITOR_DPI_AWARE);
            configured = SUCCEEDED(status) || status == E_ACCESSDENIED;
        }
        FreeLibrary(shcore);
    }
    if (!configured) configured = SetProcessDPIAware() != FALSE;
    logmsg("DPI awareness: %s\n", configured ? "enabled/already configured" : "runtime default");
}

HANDLE
gen_temp_file(char *outPath)
{
    char tempPath[MAX_PATH];
    char tempFile[MAX_PATH];
    HANDLE hFile;
    DWORD fileFlags = FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_TEMPORARY;

    if (!GetTempPathA(MAX_PATH, tempPath)) {
        logmsg("GetTempPathA failed: %d\n", GetLastError());
        return INVALID_HANDLE_VALUE;
    }

    if (!GetTempFileNameA(tempPath, "ff", 0, tempFile)) {
        logmsg("GetTempFileNameA failed: %d\n", GetLastError());
        return INVALID_HANDLE_VALUE;
    }

    logmsg("Generated temp file: %s\n", tempFile);
    hFile = CreateFileA(tempFile, GENERIC_READ | GENERIC_WRITE,
                        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, CREATE_ALWAYS, fileFlags, NULL);

    if (hFile == INVALID_HANDLE_VALUE) {
        logmsg("CreateFileA failed for temp file: %d\n", GetLastError());
        return INVALID_HANDLE_VALUE;
    }

    if (outPath) {
        strncpy(outPath, tempFile, MAX_PATH);
    }

    return hFile;
}

bool
fetch_icon(char *iconUrl, char *outPath)
{
    HANDLE iconFile;
    HANDLE doneSignal;
    HANDLE workerSignal;
    DWORD waitResult;

    iconFile = gen_temp_file(outPath);
    if (iconFile == INVALID_HANDLE_VALUE) {
        logmsg("Failed to create temp file for icon: %d\n", GetLastError());
        return false;
    }

    logmsg("Downloading icon to %s\n", outPath);
    doneSignal = CreateEventA(NULL, TRUE, FALSE, NULL);

    /*
     * Duplicate the handle so the worker has independent ownership.
     * If we time out and close our handle, the worker can still safely
     * SetEvent on its own copy without races.
     */

    DuplicateHandle(GetCurrentProcess(), doneSignal,
                         GetCurrentProcess(), &workerSignal,
                         0, FALSE, DUPLICATE_SAME_ACCESS);

    register_temp_request(iconUrl, iconFile, workerSignal);

    /* 3-second timeout for icon download so we don't hang if something goes wrong */
    waitResult = WaitForSingleObject(doneSignal, 3000);
    CloseHandle(doneSignal);

    if (waitResult == WAIT_OBJECT_0) {
        logmsg("Icon downloaded successfully.\n");
        CloseHandle(iconFile);
        return true;
    } else if (waitResult == WAIT_TIMEOUT) {
        logmsg("Failed to download icon within timeout.\n");
    } else {
        logmsg("Error while waiting for icon download: %d\n", GetLastError());
    }

    CloseHandle(iconFile);
    return false;
}

static BOOL WINAPI
console_ctrl_handler(DWORD ctrlType)
{
    switch (ctrlType) {
    case CTRL_C_EVENT:
    case CTRL_BREAK_EVENT:
    case CTRL_CLOSE_EVENT:
    case CTRL_LOGOFF_EVENT:
    case CTRL_SHUTDOWN_EVENT:
        logmsg("Console signal %d received, shutting down\n", ctrlType);
        if (hwnd) {
            PostMessageW(hwnd, WM_CLOSE, 0, 0);
        }
        // remove so next ctrl+c will force quit
        SetConsoleCtrlHandler(console_ctrl_handler, FALSE);
        return TRUE;
    default:
        return FALSE;
    }
}

void
setup_console() {
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        // reopen C stdio to the attached console
        FILE* f;
        freopen_s(&f, "CONIN$",  "r", stdin);
        freopen_s(&f, "CONOUT$", "w", stdout);
        freopen_s(&f, "CONOUT$", "w", stderr);

        // disable output buffering
        setvbuf(stdout, NULL, _IONBF, 0);

        SetConsoleCtrlHandler(console_ctrl_handler, TRUE);
    }
}

int
main(int argc, char **argv)
{
    char* srcUrl;
    DWORD err;
    wchar_t cwd[MAX_PATH];
    NPError ret;
    HMODULE loader;
    char iconFile[MAX_PATH];

    setup_console();
    parse_args(argc, argv);
    print_args();
    init_logging(args.logPath, args.verboseLogging);
    logmsg("FFRunner " FFRUNNER_BUILD " - x86 host for 32-bit Unity; ARM runtimes require x86/WoW64 support.\n");

    enable_dpi_awareness();

    MMRESULT timerPeriod = timeBeginPeriod(1);
    if (timerPeriod == TIMERR_NOERROR)
        logmsg("WinMM timer resolution: 1 ms requested for Unity frame pacing.\n");
    else
        logmsg("WinMM timer resolution request failed: %u\n", (unsigned)timerPeriod);

    char *winePrefix = getenv("WINEPREFIX");
    if (winePrefix) {
        logmsg("WINEPREFIX is set to: %s\n", winePrefix);
    } else {
        logmsg("WINEPREFIX is not set.\n");
    }

    if (args.serverAddress == NULL) {
        logmsg("No server address provided.");
        exit(1);
    }

    if (args.assetUrl == NULL) {
        logmsg("No asset URL provided.");
        exit(1);
    }

    if (GetCurrentDirectoryW(MAX_PATH, cwd)) {
        logmsg("setenv(\"UNITY_HOME_DIR\", \"%ls\")\n", cwd);
        SetEnvironmentVariableW(L"UNITY_HOME_DIR", cwd);
    }
    SetEnvironmentVariableA("UNITY_DISABLE_PLUGIN_UPDATES", "yes");
    SetEnvironmentVariableA("LANG", NULL); // webplayer crashes if this is set
    SetEnvironmentVariableA("UNITY_KEEP_LOG_FILES", "yes");

    /* Diagnostic-only: ask the existing legacy Unity Web Player loader to
     * write its internal log beside FFRunner. This does not change the loader,
     * player/Mono selection, renderer, windowing, or network behavior. Older
     * loaders that do not understand this variable simply ignore it. */
    {
        wchar_t unityLogPath[MAX_PATH];
        DWORD unityLogLen = GetFullPathNameW(L"unity-webplayer.log", MAX_PATH, unityLogPath, NULL);
        if (unityLogLen > 0 && unityLogLen < MAX_PATH) {
            SetEnvironmentVariableW(L"UNITY_WEBPLAYER_LOG_FILE", unityLogPath);
            logmsg("Unity Web Player diagnostic log requested: %ls\n", unityLogPath);
        } else {
            logmsg("Unity Web Player diagnostic log path could not be resolved.\n");
        }
    }
    /* DXVK 1.x honors this before normalizing D3D9 presentation parameters.
     * Keep Unity's in-game fullscreen choices from changing the Wine/Android
     * display mode; the host window remains responsible for presentation. */
    SetEnvironmentVariableA("DXVK_FORCE_WINDOWED", "1");
    logmsg("DXVK stable-display guard: DXVK_FORCE_WINDOWED=1\n");

    if (args.forceVulkan) {
        SetEnvironmentVariableA("UNITY_FF_DX_DLL", "d3d9_vulkan.dll");
    } else if (args.forceOpenGl) {
        SetEnvironmentVariableA("UNITY_FF_DX_DLL", "lmao");
    }
    apply_vram_fix();

    initNetscapeFuncs();
    initBrowserObject();

    /* In controller compatibility mode, discover the physical DirectInput
     * device names before Unity loads and disable only those joystick devices
     * for this executable. FFRunner then maps XInput to keyboard/mouse. */
    controller_prepare_keyboard_mouse_mode();

    logmsg("LoadLibraryW\n");
    loader = LoadLibraryW(L"loader\\npUnity3D32.dll");
    if (!loader) {
        err = GetLastError();
        logmsg("Failed to load plugin DLL: 0x%x\n", err);
        exit(1);
    }

    logmsg("GetProcAddress\n");
    NP_GetEntryPointsFuncOS NP_GetEntryPoints = (NP_GetEntryPointsFuncOS)GetProcAddress(loader, "NP_GetEntryPoints");
    NP_InitializeFuncOS NP_Initialize = (NP_InitializeFuncOS)GetProcAddress(loader, "NP_Initialize");
    NP_ShutdownFuncOS NP_Shutdown = (NP_ShutdownFuncOS)GetProcAddress(loader, "NP_Shutdown");

    if (!NP_GetEntryPoints || !NP_Initialize || !NP_Shutdown) {
        logmsg("Failed to find one or more plugin symbols. Invalid plugin DLL?\n");
        exit(1);
    }

    srcUrl = args.mainPathOrAddress;
    init_network(srcUrl);
    logmsg("Request pool initialized\n");

    char *iconToUse = NULL;
    if (args.windowIcon) {
        logmsg("Fetching icon: %s\n", args.windowIcon);
        if (fetch_icon(args.windowIcon, iconFile)) {
            iconToUse = iconFile;
        }
    }

    prepare_window(args.windowWidth, args.windowHeight, iconToUse);
    if (!hwnd) return 1;
    dbglogmsg("> NP_GetEntryPoints\n");
    ret = NP_GetEntryPoints(&pluginFuncs);
    dbglogmsg("  returned %d\n", ret);
    if (ret != NPERR_NO_ERROR || !pluginFuncs.newp || !pluginFuncs.setwindow || !pluginFuncs.destroy || !pluginFuncs.getvalue) {
        logmsg("Invalid Unity entry points: %d\n", ret); return 1;
    }

    dbglogmsg("> NP_Initialize\n");
    ret = NP_Initialize(&netscapeFuncs);
    dbglogmsg("  returned %d\n", ret);

    if (ret != NPERR_NO_ERROR) { logmsg("Unity initialization failed: %d\n", ret); return 1; }

    char width[16], height[16];
    snprintf(width, sizeof(width), "%d", args.windowWidth);
    snprintf(height, sizeof(height), "%d", args.windowHeight);

    char *argn[] = {
        "src",
        "width",
        "height",
        "bordercolor",
        "backgroundcolor",
        "disableContextMenu",
        "textcolor",
        "logoimage",
        "progressbarimage",
        "progressframeimage",
    };
    char *argp[] = {
        srcUrl,
        width,
        height,
        "000000",
        "000000",
        "true",
        "ccffff",
        "assets/img/unity-dexlabs.png",
        "assets/img/unity-loadingbar.png",
        "assets/img/unity-loadingframe.png",
    };
    assert(ARRLEN(argn) == ARRLEN(argp));

    savedPtr = &saved;

    dbglogmsg("> NPP_NewProc\n");
    ret = pluginFuncs.newp("application/vnd.ffuwp", &npp, 1, ARRLEN(argn), argn, argp, savedPtr);
    dbglogmsg("  returned %d\n", ret);

    if (ret != NPERR_NO_ERROR) { logmsg("Unity instance failed: %d\n", ret); NP_Shutdown(); return 1; }

    /* Match upstream: show the host after NPP_New, before NPP_SetWindow.
     * Window callbacks stay guarded until NPWindow is initialized below. */
    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    if (SetForegroundWindow(hwnd)) SetFocus(hwnd);
    logmsg("Before Unity window attachment: visible=%d foreground=%d\n",
        IsWindowVisible(hwnd) != FALSE, GetForegroundWindow() == hwnd);

    npWin = (NPWindow){
        .window = hwnd,
        .x = 0, .y = 0,
        .width = args.windowWidth, .height = args.windowHeight,
        .clipRect = {
            0, 0, args.windowHeight, args.windowWidth
        },
        .type = NPWindowTypeWindow
    };

    /* Initial plugin attachment happens only after NPP_New and NPWindow setup. */
    pluginWindowReady = true;
    update_plugin_window(false);
    /* Unity creates its browser renderer shortly after NPP_SetWindow. Poll at
     * a low rate so we can promote/restore that exact HWND without touching
     * the working fullscreen window. */
    SetTimer(hwnd, UNITY_WINDOW_TIMER_ID, 100, NULL);


    dbglogmsg("> NPP_GetValueProc\n");
    ret = pluginFuncs.getvalue(&npp, NPPVpluginScriptableNPObject, &scriptableObject);
    dbglogmsg("  returned %d and NPObject %p\n", ret, scriptableObject);
    if (ret != NPERR_NO_ERROR || !scriptableObject || !scriptableObject->_class) {
        logmsg("Unity script object unavailable: %d\n", ret);
        detach_plugin_window(); pluginFuncs.destroy(&npp, &savedPtr); NP_Shutdown(); return 1;
    }

    dbglogmsg("> scriptableObject.hasMethod style\n");
    ret = scriptableObject->_class->hasMethod(scriptableObject, getNPIdentifier("style"));
    dbglogmsg("  returned %d\n", ret);

    if (args.xinputBridge) controller_init(hwnd);
    message_loop();

    controller_shutdown();
    if (timerPeriod == TIMERR_NOERROR) timeEndPeriod(1);
    detach_plugin_window();
    dbglogmsg("> NPP_DestroyProc\n");
    ret = pluginFuncs.destroy(&npp, &savedPtr);
    dbglogmsg("  returned %d\n", ret);

    NP_Shutdown();
    return 0;
}

int
WINAPI
WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow)
{
    int argc;
    char **argv;

    argc = __argc;
    argv = __argv;

    return main(argc, argv);
}
