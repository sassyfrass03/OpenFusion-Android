#include "ffrunner.h"
#define DIRECTINPUT_VERSION 0x0800
#define COBJMACROS
#include <dinput.h>
#include <wchar.h>

/*
 * Keyboard/mouse controller adapter for Android/Winlator controller stacks.
 * FFRunner reads a normal XInput pad and emits only ordinary keyboard/mouse
 * input. FusionFall should never need to bind or understand the controller.
 */

#define OF_POLL_MS 16
#define OF_LEFT_DEADZONE 7849
#define OF_RIGHT_DEADZONE 8689
#define OF_TRIGGER_THRESHOLD 30

#define XBTN_DPAD_UP        0x0001
#define XBTN_DPAD_DOWN      0x0002
#define XBTN_DPAD_LEFT      0x0004
#define XBTN_DPAD_RIGHT     0x0008
#define XBTN_START          0x0010
#define XBTN_BACK           0x0020
#define XBTN_LEFT_THUMB     0x0040
#define XBTN_RIGHT_THUMB    0x0080
#define XBTN_LEFT_SHOULDER  0x0100
#define XBTN_RIGHT_SHOULDER 0x0200
#define XBTN_A              0x1000
#define XBTN_B              0x2000
#define XBTN_X              0x4000
#define XBTN_Y              0x8000

#define XERR_SUCCESS 0L

typedef struct OF_XINPUT_GAMEPAD {
    WORD wButtons;
    BYTE bLeftTrigger;
    BYTE bRightTrigger;
    SHORT sThumbLX;
    SHORT sThumbLY;
    SHORT sThumbRX;
    SHORT sThumbRY;
} OF_XINPUT_GAMEPAD;

typedef struct OF_XINPUT_STATE {
    DWORD dwPacketNumber;
    OF_XINPUT_GAMEPAD Gamepad;
} OF_XINPUT_STATE;

typedef DWORD (WINAPI *OF_XInputGetState)(DWORD, OF_XINPUT_STATE*);

static HMODULE xinputModule;
static OF_XInputGetState xinputGetState;
static HWND controllerWindow;
static bool connected;
static bool heldKeys[256];
static bool heldMouseLeft, heldMouseRight;
static bool heldReturnScan;
static WORD lastLoggedButtons = 0xffff;
static BYTE lastLoggedLeftTrigger = 0xff, lastLoggedRightTrigger = 0xff;

static void send_key(BYTE vk, bool down)
{
    if (heldKeys[vk] == down) return;
    INPUT input = {0};
    input.type = INPUT_KEYBOARD;
    input.ki.wVk = vk;
    if (!down) input.ki.dwFlags = KEYEVENTF_KEYUP;
    if (SendInput(1, &input, sizeof(input)) == 1) heldKeys[vk] = down;
}

static void send_return_scancode(bool down)
{
    if (heldReturnScan == down) return;
    INPUT input = {0};
    input.type = INPUT_KEYBOARD;
    input.ki.wScan = 0x1c; /* PC/AT Set-1 main Enter/Return scan code */
    input.ki.dwFlags = KEYEVENTF_SCANCODE | (down ? 0 : KEYEVENTF_KEYUP);
    if (SendInput(1, &input, sizeof(input)) == 1) heldReturnScan = down;
}

static void send_mouse_button(bool right, bool down)
{
    bool *held = right ? &heldMouseRight : &heldMouseLeft;
    if (*held == down) return;
    INPUT input = {0};
    input.type = INPUT_MOUSE;
    if (right) input.mi.dwFlags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
    else input.mi.dwFlags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
    if (SendInput(1, &input, sizeof(input)) == 1) *held = down;
}

static void release_all(void)
{
    for (int i = 0; i < 256; i++) if (heldKeys[i]) send_key((BYTE)i, false);
    if (heldMouseLeft) send_mouse_button(false, false);
    if (heldMouseRight) send_mouse_button(true, false);
    if (heldReturnScan) send_return_scancode(false);
}

static bool focused(void)
{
    /* Unity can promote its D3D fullscreen surface to a different top-level
     * window. The old root-window comparison treated that as focus loss and
     * released every translated controller key. Accept any foreground window
     * owned by this process; if Wine's desktop wrapper owns foreground focus,
     * keep translating while the FFRunner host remains visible. */
    HWND foreground = GetForegroundWindow();
    if (foreground) {
        DWORD pid = 0;
        GetWindowThreadProcessId(foreground, &pid);
        if (pid == GetCurrentProcessId()) return true;
        if (GetAncestor(foreground, GA_ROOT) == GetAncestor(controllerWindow, GA_ROOT))
            return true;
    }
    return IsWindowVisible(controllerWindow) && !IsIconic(controllerWindow);
}

static int axis_delta(SHORT value, int deadzone)
{
    int v = value;
    if (v > -deadzone && v < deadzone) return 0;
    /* 4..12 px per poll across the useful range. */
    int magnitude = abs(v);
    int scaled = 4 + ((magnitude - deadzone) * 8) / (32767 - deadzone);
    return v < 0 ? -scaled : scaled;
}


static const wchar_t *wineJoystickKey =
    L"Software\\Wine\\AppDefaults\\ffrunner-android.exe\\DirectInput\\Joysticks";
static const wchar_t *ownedJoystickKey =
    L"Software\\OpenFusionAndroid\\ControllerSuppression";

typedef struct ControllerSuppressContext {
    HKEY wine;
    HKEY owned;
    unsigned int count;
} ControllerSuppressContext;

static void utf8_from_wide(const wchar_t *src, char *dst, size_t size)
{
    if (!dst || !size) return;
    dst[0] = 0;
    if (!src) return;
    if (!WideCharToMultiByte(CP_UTF8, 0, src, -1, dst, (int)size, NULL, NULL)) dst[0] = 0;
}

static void restore_owned_legacy_suppression(void)
{
    HKEY owned = NULL, wine = NULL;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, ownedJoystickKey, 0,
            KEY_QUERY_VALUE | KEY_SET_VALUE, &owned) != ERROR_SUCCESS)
        return;
    RegOpenKeyExW(HKEY_CURRENT_USER, wineJoystickKey, 0, KEY_SET_VALUE, &wine);

    for (;;) {
        wchar_t name[512];
        DWORD chars = ARRLEN(name);
        LONG status = RegEnumValueW(owned, 0, name, &chars, NULL, NULL, NULL, NULL);
        if (status != ERROR_SUCCESS) break;
        if (wine) RegDeleteValueW(wine, name);
        RegDeleteValueW(owned, name);
    }
    if (wine) RegCloseKey(wine);
    RegCloseKey(owned);
    RegDeleteKeyW(HKEY_CURRENT_USER, ownedJoystickKey);
}

static void disable_legacy_name(ControllerSuppressContext *context, const wchar_t *name)
{
    if (!context || !context->wine || !context->owned || !name || !name[0]) return;

    DWORD type = 0, bytes = 0;
    LONG existing = RegQueryValueExW(context->wine, name, NULL, &type, NULL, &bytes);
    if (existing == ERROR_SUCCESS) {
        /* Do not overwrite a user's pre-existing Wine controller preference. */
        return;
    }

    const wchar_t disabled[] = L"disabled";
    if (RegSetValueExW(context->wine, name, 0, REG_SZ,
            (const BYTE*)disabled, (DWORD)sizeof(disabled)) == ERROR_SUCCESS) {
        const DWORD one = 1;
        RegSetValueExW(context->owned, name, 0, REG_DWORD,
            (const BYTE*)&one, sizeof(one));
        context->count++;
    }
}

static BOOL CALLBACK suppress_legacy_gamepad(const DIDEVICEINSTANCEW *device, VOID *opaque)
{
    ControllerSuppressContext *context = (ControllerSuppressContext*)opaque;
    char instance[512] = {0};
    char product[512] = {0};

    if (!device || !context) return DIENUM_CONTINUE;
    disable_legacy_name(context, device->tszInstanceName);
    if (device->tszProductName[0] && wcscmp(device->tszProductName, device->tszInstanceName))
        disable_legacy_name(context, device->tszProductName);

    utf8_from_wide(device->tszInstanceName, instance, sizeof(instance));
    utf8_from_wide(device->tszProductName, product, sizeof(product));
    logmsg("Legacy joystick discovered for keyboard/mouse mode: %s%s%s\n",
        instance[0] ? instance : "(unnamed)",
        product[0] && strcmp(instance, product) ? " / " : "",
        product[0] && strcmp(instance, product) ? product : "");
    return DIENUM_CONTINUE;
}

void controller_prepare_keyboard_mouse_mode(void)
{
    /* Undo only values that an earlier FFRunner run created. This makes the
     * checkbox reversible without disturbing the user's Wine preferences. */
    restore_owned_legacy_suppression();
    if (!args.xinputBridge) {
        logmsg("Controller keyboard/mouse adapter disabled; restored FFRunner-owned legacy joystick settings.\n");
        return;
    }

    ControllerSuppressContext context = {0};
    DWORD disposition = 0;
    LONG reg = RegCreateKeyExW(HKEY_CURRENT_USER, wineJoystickKey,
        0, NULL, 0, KEY_QUERY_VALUE | KEY_SET_VALUE, NULL, &context.wine, &disposition);
    if (reg != ERROR_SUCCESS) {
        logmsg("Could not open Wine per-app joystick settings: %ld\n", reg);
        return;
    }
    reg = RegCreateKeyExW(HKEY_CURRENT_USER, ownedJoystickKey,
        0, NULL, 0, KEY_QUERY_VALUE | KEY_SET_VALUE, NULL, &context.owned, &disposition);
    if (reg != ERROR_SUCCESS) {
        RegCloseKey(context.wine);
        logmsg("Could not create FFRunner controller-suppression marker: %ld\n", reg);
        return;
    }

    IDirectInput8W *directInput = NULL;
    HRESULT hr = DirectInput8Create(GetModuleHandleW(NULL), DIRECTINPUT_VERSION,
        &IID_IDirectInput8W, (void**)&directInput, NULL);
    if (SUCCEEDED(hr) && directInput) {
        hr = IDirectInput8_EnumDevices(directInput, DI8DEVCLASS_GAMECTRL,
            suppress_legacy_gamepad, &context, DIEDFL_ATTACHEDONLY);
        if (FAILED(hr)) logmsg("DirectInput controller enumeration failed: 0x%08lx\n", (unsigned long)hr);
        IDirectInput8_Release(directInput);
    } else {
        logmsg("Could not enumerate legacy DirectInput controllers: 0x%08lx\n", (unsigned long)hr);
    }

    logmsg("Controller keyboard/mouse mode suppressed %u legacy DirectInput name(s) for this app.\n",
        context.count);
    RegCloseKey(context.owned);
    RegCloseKey(context.wine);
    if (!context.count) RegDeleteKeyW(HKEY_CURRENT_USER, ownedJoystickKey);
}

bool controller_init(HWND window)
{
    static const wchar_t *libraries[] = { L"xinput1_4.dll", L"xinput1_3.dll", L"xinput9_1_0.dll" };
    wchar_t systemDir[MAX_PATH];
    controllerWindow = window;
    UINT systemLen = GetSystemDirectoryW(systemDir, ARRLEN(systemDir));
    if (!systemLen || systemLen >= ARRLEN(systemDir) - 20) {
        logmsg("XInput controller bridge could not resolve the Windows system directory.\n");
        return false;
    }
    for (size_t i = 0; i < ARRLEN(libraries); i++) {
        wchar_t fullPath[MAX_PATH];
        lstrcpynW(fullPath, systemDir, ARRLEN(fullPath));
        if (fullPath[lstrlenW(fullPath) - 1] != L'\\') lstrcatW(fullPath, L"\\");
        lstrcatW(fullPath, libraries[i]);
        /* Load the runtime's real XInput implementation by absolute path. */
        xinputModule = LoadLibraryW(fullPath);
        if (!xinputModule) continue;
        xinputGetState = (OF_XInputGetState)GetProcAddress(xinputModule, "XInputGetState");
        if (xinputGetState) {
            char name[32] = {0};
            WideCharToMultiByte(CP_UTF8, 0, libraries[i], -1, name, sizeof(name), NULL, NULL);
            logmsg("Controller keyboard/mouse adapter loaded through System32/%s.\n", name);
            SetTimer(window, CONTROLLER_TIMER_ID, OF_POLL_MS, NULL);
            return true;
        }
        FreeLibrary(xinputModule); xinputModule = NULL;
    }
    logmsg("Controller keyboard/mouse adapter requested, but no system XInput DLL was available.\n");
    return false;
}

void controller_poll(void)
{
    if (!xinputGetState || !controllerWindow) return;
    if (!focused()) { release_all(); return; }

    OF_XINPUT_STATE state = {0};
    DWORD result = xinputGetState(0, &state);
    if (result != XERR_SUCCESS) {
        if (connected) logmsg("XInput controller disconnected.\n");
        connected = false; release_all(); return;
    }
    if (!connected) logmsg("XInput controller connected; keyboard/mouse adapter active.\n");
    connected = true;
    OF_XINPUT_GAMEPAD *g = &state.Gamepad;
    bool ltDown = g->bLeftTrigger > OF_TRIGGER_THRESHOLD;
    bool rtDown = g->bRightTrigger > OF_TRIGGER_THRESHOLD;
    BYTE ltLog = ltDown ? 1 : 0, rtLog = rtDown ? 1 : 0;
    if (g->wButtons != lastLoggedButtons || ltLog != lastLoggedLeftTrigger || rtLog != lastLoggedRightTrigger) {
        logmsg("XInput buttons=0x%04x LT=%u RT=%u\n", (unsigned)g->wButtons, (unsigned)ltDown, (unsigned)rtDown);
        lastLoggedButtons = g->wButtons;
        lastLoggedLeftTrigger = ltLog;
        lastLoggedRightTrigger = rtLog;
    }

    /* Left stick -> FusionFall's keyboard movement defaults. */
    send_key('A', g->sThumbLX < -OF_LEFT_DEADZONE);
    send_key('D', g->sThumbLX > OF_LEFT_DEADZONE);
    send_key('W', g->sThumbLY > OF_LEFT_DEADZONE);
    send_key('S', g->sThumbLY < -OF_LEFT_DEADZONE);

    /* Right stick -> relative mouse camera, retaining analog turn speed. */
    int dx = axis_delta(g->sThumbRX, OF_RIGHT_DEADZONE);
    int dy = -axis_delta(g->sThumbRY, OF_RIGHT_DEADZONE);
    if (dx || dy) {
        INPUT input = {0};
        input.type = INPUT_MOUSE;
        input.mi.dx = dx; input.mi.dy = dy;
        input.mi.dwFlags = MOUSEEVENTF_MOVE;
        SendInput(1, &input, sizeof(input));
    }

    /*
     * Android compatibility layout. The pad is treated only as a source of
     * ordinary keyboard/mouse input so FusionFall keeps using its known-good
     * keyboard bindings instead of its legacy joystick axis map.
     *
     * A              -> Jump (Space)
     * X / Y / B      -> Nanos 1 / 2 / 3
     * LB             -> Nano power (X)
     * RB             -> Switch weapon (Tab)
     * RT / LT        -> Left click / right click
     * Back / Start   -> Map (M) / Menu/Pause (Enter/Return)
     * D-pad U/D/L/R  -> Weapon boost / Nano boost / Inventory / Vehicle
     */
    send_key(VK_SPACE, (g->wButtons & XBTN_A) != 0);
    send_key('1', (g->wButtons & XBTN_X) != 0);
    send_key('2', (g->wButtons & XBTN_Y) != 0);
    send_key('3', (g->wButtons & XBTN_B) != 0);

    send_key('X', (g->wButtons & XBTN_LEFT_SHOULDER) != 0);
    send_key(VK_TAB, (g->wButtons & XBTN_RIGHT_SHOULDER) != 0);
    send_mouse_button(false, rtDown);
    send_mouse_button(true, ltDown);

    send_key('M', (g->wButtons & XBTN_BACK) != 0);
    send_return_scancode((g->wButtons & XBTN_START) != 0);
    send_key('R', (g->wButtons & XBTN_DPAD_UP) != 0);
    send_key('C', (g->wButtons & XBTN_DPAD_DOWN) != 0);
    send_key('I', (g->wButtons & XBTN_DPAD_LEFT) != 0);
    send_key('V', (g->wButtons & XBTN_DPAD_RIGHT) != 0);

    /* Preserve the useful right-stick-click Attack/Use shortcut. */
    send_key('Z', (g->wButtons & XBTN_RIGHT_THUMB) != 0);

}

void controller_shutdown(void)
{
    if (controllerWindow) KillTimer(controllerWindow, CONTROLLER_TIMER_ID);
    release_all(); connected = false; controllerWindow = NULL; xinputGetState = NULL;
    if (xinputModule) FreeLibrary(xinputModule);
    xinputModule = NULL;
}
