#include "ffrunner.h"

HWND hwnd;
bool pluginWindowReady;
static bool settingPluginWindow, pluginWindowAttached;
static HWND unityBrowserWindow;
static bool unityBrowserPromoted;
static bool unityFsWasVisible;

typedef struct UnityWindowFindContext {
    HWND found;
    const wchar_t *className;
    bool visibleOnly;
} UnityWindowFindContext;

static BOOL CALLBACK find_process_unity_window(HWND window, LPARAM opaque)
{
    UnityWindowFindContext *context = (UnityWindowFindContext*)opaque;
    wchar_t className[64] = {0};
    DWORD pid = 0;
    if (!context || context->found) return FALSE;
    GetWindowThreadProcessId(window, &pid);
    if (pid != GetCurrentProcessId()) return TRUE;
    if (context->visibleOnly && !IsWindowVisible(window)) return TRUE;
    if (!GetClassNameW(window, className, ARRLEN(className))) return TRUE;
    if (lstrcmpW(className, context->className) != 0) return TRUE;
    context->found = window;
    return FALSE;
}

static HWND find_process_top_level(const wchar_t *className, bool visibleOnly)
{
    UnityWindowFindContext context = {0};
    context.className = className;
    context.visibleOnly = visibleOnly;
    EnumWindows(find_process_unity_window, (LPARAM)&context);
    return context.found;
}

static HWND find_unity_browser_window(void)
{
    if (unityBrowserWindow && IsWindow(unityBrowserWindow)) return unityBrowserWindow;

    /* Unity Web Player actually nests its renderer as
     * host -> UnityIntermediate -> UnityWindow. EnumChildWindows walks all
     * descendant levels, whereas the old FindWindowEx call only checked the
     * host's immediate children and therefore never found the real renderer. */
    UnityWindowFindContext context = {0};
    context.className = L"UnityWindow";
    context.visibleOnly = false;
    EnumChildWindows(hwnd, find_process_unity_window, (LPARAM)&context);
    HWND child = context.found;
    if (!child) child = find_process_top_level(L"UnityWindow", false);
    unityBrowserWindow = child;
    unityBrowserPromoted = false;
    if (child)
        logmsg("Discovered UnityWindow renderer: hwnd=%p parent=%p host=%p.\n",
            child, GetParent(child), hwnd);
    return child;
}

static bool unity_fullscreen_visible(void)
{
    return find_process_top_level(L"UnityWindowFS", true) != NULL;
}

static void maintain_unity_browser_surface(void)
{
    if (!pluginWindowReady || !hwnd) return;

    HWND render = find_unity_browser_window();
    if (!render) return;

    bool fsVisible = unity_fullscreen_visible();
    if (fsVisible != unityFsWasVisible) {
        logmsg("Unity fullscreen window %s; browser renderer hwnd=%p.\n",
            fsVisible ? "visible" : "hidden", render);
        unityFsWasVisible = fsVisible;
    }
    if (fsVisible) return;

    LONG_PTR style = GetWindowLongPtrW(render, GWL_STYLE);
    HWND parent = GetParent(render);
    if (!unityBrowserPromoted || (style & WS_CHILD) || parent == hwnd) {
        LONG_PTR newStyle = style;
        newStyle &= ~(WS_CHILD | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU |
                      WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
        newStyle |= WS_POPUP | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;

        /* Wine/GameNative presents the top-level Unity fullscreen surface
         * smoothly, while the embedded child Vulkan surface stutters and can
         * go black after a fullscreen->browser swap-chain handoff. Keep the
         * exact Unity HWND, but promote it to an owned top-level borderless
         * window before/while browser-mode presentation is active. */
        SetParent(render, NULL);
        SetWindowLongPtrW(render, GWL_STYLE, newStyle);
        SetWindowLongPtrW(render, GWLP_HWNDPARENT, (LONG_PTR)hwnd);
        unityBrowserPromoted = true;
        logmsg("Promoted UnityWindow renderer to top-level surface: hwnd=%p oldStyle=0x%08lx newStyle=0x%08lx.\n",
            render, (unsigned long)style, (unsigned long)newStyle);
    }

    RECT client = {0}, current = {0};
    POINT origin = {0, 0};
    if (!GetClientRect(hwnd, &client) || !ClientToScreen(hwnd, &origin)) return;
    int width = client.right - client.left;
    int height = client.bottom - client.top;
    if (width <= 0 || height <= 0) return;

    bool needsMove = true;
    if (GetWindowRect(render, &current)) {
        needsMove = current.left != origin.x || current.top != origin.y ||
                    current.right - current.left != width ||
                    current.bottom - current.top != height;
    }
    if (needsMove || !IsWindowVisible(render)) {
        SetWindowPos(render, HWND_TOP, origin.x, origin.y, width, height,
            SWP_NOACTIVATE | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
    }
}

void
update_plugin_window(bool minimized)
{
    RECT client;
    /* CreateWindow/ShowWindow can send synchronous messages before NPP_New. */
    if (!pluginWindowReady || settingPluginWindow || !pluginFuncs.setwindow || !npWin.window)
        return;
    if (!GetClientRect(hwnd, &client))
        return;
    /* Ignore transient zero-sized surfaces; retain the last valid dimensions
     * when minimized. Never resize the engine to a zero-sized render target. */
    uint32_t width = minimized ? npWin.width : (uint32_t)(client.right - client.left);
    uint32_t height = minimized ? npWin.height : (uint32_t)(client.bottom - client.top);
    if (!width || !height || width > 65535 || height > 65535) return;
    uint16_t right = minimized ? 0 : (uint16_t)width;
    uint16_t bottom = minimized ? 0 : (uint16_t)height;
    if (pluginWindowAttached && npWin.width == width && npWin.height == height
            && npWin.clipRect.right == right && npWin.clipRect.bottom == bottom)
        return;
    npWin.x = npWin.y = 0;
    npWin.width = width;
    npWin.height = height;
    npWin.clipRect.top = npWin.clipRect.left = 0;
    npWin.clipRect.right = right;
    npWin.clipRect.bottom = bottom;
    settingPluginWindow = true;
    NPError status = pluginFuncs.setwindow(&npp, &npWin);
    settingPluginWindow = false;
    pluginWindowAttached = pluginWindowReady && status == NPERR_NO_ERROR;
    if (status != NPERR_NO_ERROR) logmsg("Unity window attachment failed: %d\n", status);
}

void
refresh_plugin_window(void)
{
    if (!pluginWindowReady || settingPluginWindow || !npWin.window) return;
    /* Unity Web Player can render fullscreen in a separate top-level window.
     * On the return to browser mode the host HWND and dimensions are unchanged,
     * so the normal duplicate-size guard would skip NPP_SetWindow and leave the
     * restored browser surface black. Force one fresh attachment instead. */
    pluginWindowAttached = false;
    update_plugin_window(IsIconic(hwnd) != FALSE);
    InvalidateRect(hwnd, NULL, FALSE);
    UpdateWindow(hwnd);
}

void
detach_plugin_window(void)
{
    if (!pluginWindowReady) return;
    /* Clear first: the callback can synchronously dispatch window messages. */
    pluginWindowReady = false;
    pluginWindowAttached = false;
    if (pluginFuncs.setwindow) pluginFuncs.setwindow(&npp, NULL);
}

static bool
window_geometry(HWND window, uint32_t width, uint32_t height, DWORD style, RECT *rect)
{
    MONITORINFO monitor = {0};
    monitor.cbSize = sizeof(monitor);
    HMONITOR display = MonitorFromWindow(window, MONITOR_DEFAULTTOPRIMARY);
    if (!GetMonitorInfoW(display, &monitor)) return false;
    if (args.windowMode == BORDERLESS_FULLSCREEN) {
        *rect = monitor.rcMonitor;
        return true;
    }
    *rect = (RECT){0, 0, (LONG)width, (LONG)height};
    if (!AdjustWindowRectEx(rect, style, FALSE, 0)) return false;
    LONG outerWidth = rect->right - rect->left;
    LONG outerHeight = rect->bottom - rect->top;
    /* Signed arithmetic avoids wraparound when the requested size is larger. */
    rect->left = monitor.rcWork.left +
        ((monitor.rcWork.right - monitor.rcWork.left) - outerWidth) / 2;
    rect->top = monitor.rcWork.top +
        ((monitor.rcWork.bottom - monitor.rcWork.top) - outerHeight) / 2;
    rect->right = rect->left + outerWidth;
    rect->bottom = rect->top + outerHeight;
    return true;
}

LRESULT CALLBACK
window_proc(HWND window, UINT uMsg, WPARAM wParam, LPARAM lParam)
{
    PAINTSTRUCT ps;
    RECT target;
    switch (uMsg) {
    case WM_CLOSE:
        detach_plugin_window();
        DestroyWindow(window);
        return 0;
    case WM_DESTROY:
        KillTimer(window, UNITY_WINDOW_TIMER_ID);
        detach_plugin_window();
        unityBrowserWindow = NULL;
        unityBrowserPromoted = false;
        PostQuitMessage(0);
        return 0;
    case WM_PAINT:
        BeginPaint(window, &ps);
        FillRect(ps.hdc, &ps.rcPaint, (HBRUSH)GetStockObject(BLACK_BRUSH));
        EndPaint(window, &ps);
        return 0;
    case WM_SIZE:
        /* Display-mode switches can emit a storm of WM_SIZE messages while
         * Unity is recreating its D3D device. Debounce NPP_SetWindow so the
         * plug-in receives only the final stable size instead of being
         * re-entered repeatedly during reset. */
        if (pluginWindowReady) SetTimer(window, RESIZE_TIMER_ID, 75, NULL);
        break;
    case WM_APP + 1:
        update_plugin_window(IsIconic(window) != FALSE);
        return 0;
    case WM_TIMER:
        if (wParam == CONTROLLER_TIMER_ID) {
            controller_poll();
            return 0;
        }
        if (wParam == RESIZE_TIMER_ID) {
            KillTimer(window, RESIZE_TIMER_ID);
            update_plugin_window(IsIconic(window) != FALSE);
            return 0;
        }
        if (wParam == UNITY_WINDOW_TIMER_ID) {
            maintain_unity_browser_surface();
            return 0;
        }
        if (wParam == DISPLAY_TIMER_ID) {
            KillTimer(window, DISPLAY_TIMER_ID);
            if (args.windowMode == BORDERLESS_FULLSCREEN &&
                window_geometry(window, args.windowWidth, args.windowHeight,
                                (DWORD)GetWindowLongW(window, GWL_STYLE), &target)) {
                SetWindowPos(window, NULL, target.left, target.top,
                    target.right - target.left, target.bottom - target.top,
                    SWP_NOZORDER | SWP_NOACTIVATE);
            }
            return 0;
        }
        break;
    case WM_DPICHANGED:
        /* Use Windows' suggested DPI rectangle; do not force the configured
         * resolution while Unity is resetting its renderer. */
        if (lParam && args.windowMode == WINDOWED) {
            RECT *suggested = (RECT*)lParam;
            SetWindowPos(window, NULL, suggested->left, suggested->top,
                suggested->right - suggested->left, suggested->bottom - suggested->top,
                SWP_NOZORDER | SWP_NOACTIVATE);
        }
        return 0;
    case WM_DISPLAYCHANGE:
        /* Unity's Screen.SetResolution triggers this while its D3D device is
         * being reset. Older FFRunner builds synchronously resized the host
         * here, which can race a second mode change under Wine. Windowed and
         * borderless-window modes need no host resize. Fullscreen is realigned
         * only after the display has settled. */
        logmsg("Display change: %ld x %ld; deferring host geometry.\n",
            (LONG)LOWORD(lParam), (LONG)HIWORD(lParam));
        if (args.windowMode == BORDERLESS_FULLSCREEN)
            SetTimer(window, DISPLAY_TIMER_ID, 200, NULL);
        return 0;
    }
    if (ioMsg && uMsg == ioMsg) {
        Request *req = (Request*)lParam;
        handle_io_progress(req);
        SetEvent(req->readyEvent);
        return 0;
    }
    return DefWindowProcW(window, uMsg, wParam, lParam);
}

void
prepare_window(uint32_t width, uint32_t height, const char *iconFile)
{
    WNDCLASSW wc = {0};
    RECT bounds;
    HICON icon = NULL;
    DWORD style = (args.windowMode == WINDOWED ? WS_OVERLAPPEDWINDOW : WS_POPUP)
        | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;
    wc.lpfnWndProc = window_proc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = CLASS_NAME;
    wc.style = CS_DBLCLKS;
    wc.hCursor = LoadCursorW(NULL, MAKEINTRESOURCEW(32512));
    if (iconFile) {
        icon = (HICON)LoadImageA(NULL, iconFile, IMAGE_ICON, 0, 0,
                               LR_LOADFROMFILE | LR_DEFAULTSIZE);
        if (icon) DeleteFileA(iconFile);
    }
    wc.hIcon = icon ? icon : LoadIconW(wc.hInstance, MAKEINTRESOURCEW(0));
    if (!RegisterClassW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        logmsg("RegisterClass failed: %lu\n", GetLastError());
        return;
    }
    if (!window_geometry(NULL, width, height, style, &bounds)) {
        logmsg("Cannot read desktop geometry: %lu\n", GetLastError());
        return;
    }
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, args.windowName, -1, NULL, 0);
    UINT codepage = CP_UTF8;
    if (!count) { codepage = CP_ACP; count = MultiByteToWideChar(codepage, 0, args.windowName, -1, NULL, 0); }
    if (!count) return;
    wchar_t *title = calloc((size_t)count, sizeof(wchar_t));
    if (!title) return;
    MultiByteToWideChar(codepage, 0, args.windowName, -1, title, count);
    hwnd = CreateWindowExW(0, CLASS_NAME, title, style, bounds.left, bounds.top,
        bounds.right - bounds.left, bounds.bottom - bounds.top, NULL, NULL, wc.hInstance, NULL);
    free(title);
    if (!hwnd) { logmsg("CreateWindow failed: %lu\n", GetLastError()); return; }
    RECT client;
    if (GetClientRect(hwnd, &client)) {
        args.windowWidth = client.right - client.left;
        args.windowHeight = client.bottom - client.top;
    }
    logmsg("Window mode: %s; client size: %u x %u\n",
        args.windowMode == WINDOWED ? "windowed" :
        args.windowMode == BORDERLESS_WINDOW ? "borderless window" : "borderless fullscreen",
        args.windowWidth, args.windowHeight);
}

void
show_error_dialog(char *msg)
{
    MessageBoxA(hwnd, msg, "Sorry!", MB_OK | MB_ICONERROR | MB_TOPMOST);
}

void
open_link(char *url)
{
    ShellExecuteA(hwnd, "open", url, NULL, NULL, SW_NORMAL);
}

void
message_loop(void)
{
    MSG msg = {0};

    while (GetMessage(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);

        /*
         * HACK: under Wine, mouse scroll messages are buffered until mouse movement (cause unknown).
         * Posting a mouse movement message does not work as a workaround, and neither does sending
         * a 0-pixel raw input (maybe optimized out by Wine).
         * What DOES work is sending two net-zero mouse moves. There is not jitter and the
         * scrolling is processed immediately. ¯\_(ツ)_/¯
         */
        if (msg.message == WM_MOUSEWHEEL || msg.message == WM_MOUSEHWHEEL) {
            INPUT in[2] = {0};
            /* one pixel move left */
            in[0].type = INPUT_MOUSE;
            in[0].mi.dx = 1;
            in[0].mi.dy = 0;
            in[0].mi.dwFlags = MOUSEEVENTF_MOVE;
            //
            in[1].type = INPUT_MOUSE;
            in[1].mi.dx = -1;
            in[1].mi.dy = 0;
            in[1].mi.dwFlags = MOUSEEVENTF_MOVE;
            SendInput(2, in, sizeof(INPUT));
        }
    }
}

static
HMONITOR
get_primary_monitor()
{
    POINT ptZero = {0, 0};
    return MonitorFromPoint(ptZero, MONITOR_DEFAULTTOPRIMARY);
}

static
bool
get_vram_from_dxgi(HMODULE dxgi, uint64_t* vramBytes)
{
	*vramBytes = 0;
    HMONITOR monitor = get_primary_monitor();

    IDXGIFactory* factory = NULL;
    typedef HRESULT (WINAPI *CreateFactory)(REFIID, void**);
    CreateFactory createFactory = (CreateFactory)GetProcAddress(dxgi, "CreateDXGIFactory");
    if (!createFactory || FAILED(createFactory(&IID_IDXGIFactory, (void**)&factory))) return false;
    if (factory == NULL) {
        return false;
    }

    for (int i = 0; ; i++) {
        bool isPrimaryAdapter = false;
        IDXGIAdapter* adapter = NULL;
        if (FAILED(factory->lpVtbl->EnumAdapters(factory, i, &adapter)))
            break;

        for (int j = 0; ; j++) {
            IDXGIOutput* output = NULL;
            if (FAILED(adapter->lpVtbl->EnumOutputs(adapter, j, &output)))
                break;

            DXGI_OUTPUT_DESC outputDesc = { 0 };
            if (SUCCEEDED(output->lpVtbl->GetDesc(output, &outputDesc)))
            {
                if (outputDesc.Monitor == monitor)
                    isPrimaryAdapter = true;
            }

            output->lpVtbl->Release(output);
        }

        if (!isPrimaryAdapter) {
            adapter->lpVtbl->Release(adapter);
            continue;
        }

        DXGI_ADAPTER_DESC adapterDesc = { 0 };
        HRESULT hr = adapter->lpVtbl->GetDesc(adapter, &adapterDesc);
        adapter->lpVtbl->Release(adapter);
        if (SUCCEEDED(hr)) {
            *vramBytes = (uint64_t)adapterDesc.DedicatedVideoMemory + (uint64_t)adapterDesc.SharedSystemMemory;
            factory->lpVtbl->Release(factory);
            return true;
        }
    }

    factory->lpVtbl->Release(factory);
    return false;
}

void
apply_vram_fix()
{
    char existing[32], value[32];
    DWORD size = GetEnvironmentVariableA("UNITY_FF_VRAM_MB", existing, sizeof(existing));
    if (!args.vramMb && size) {
        logmsg("Preserving UNITY_FF_VRAM_MB from the container.\n");
        return;
    }
    uint64_t megabytes = args.vramMb ? args.vramMb : 512;
    if (!args.vramMb && args.queryVram) {
        HMODULE dxgi = LoadLibraryW(L"dxgi.dll");
        uint64_t bytes;
        if (dxgi && get_vram_from_dxgi(dxgi, &bytes) && bytes)
            megabytes = bytes >> 20;
        else logmsg("DXGI memory query unavailable; using 512 MB.\n");
        if (dxgi) FreeLibrary(dxgi);
        if (megabytes < 128) megabytes = 128;
        if (megabytes > 2048) megabytes = 2048;
    }
    /* This is a Unity allocation hint, not a physical GPU-memory reservation. */
    snprintf(value, sizeof(value), "%u", (unsigned)megabytes);
    SetEnvironmentVariableA("UNITY_FF_VRAM_MB", value);
    logmsg("Unity VRAM hint: %s MB (%s).\n", value,
        args.queryVram ? "requested query" : "DXGI startup probe skipped");
}
