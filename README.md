# OpenFusion Android 0.4.11 display/input test launcher

Android System WebView launcher for FusionFall. The bundled Windows FFRunner 1.9.0 runs inside your separately installed Wine/Proton container. Android 8.0/API 26 minimum; target API 35; version code 16. This source build is debug-signed and uses the same test signing certificate as 0.4.10.

## What's new

- Portrait retains its existing artwork and form layout. Landscape uses the supplied reference for its decorative left artwork, fixed navigation and a separately scrolling right form. Wider landscape screens add a third column for first-time setup. All controls remain live HTML; rotating retains the same form nodes, values and selected tab.
- A short CRT power-on animation runs at WebView startup, with an expanding horizontal light, scanlines and a phosphor glow. Tap to skip. Reduced-motion preferences skip it. It does not replay merely on rotation or returning from the game.
- `Launch_OpenFusion.cmd` remains after launching. Play and Prepare overwrite the same file with current settings and credentials. No self-deletion command is generated. Signing out still clears the prepared launch.
- A separate dot and player count appear beside server reachability. OFAPI `/status` and its numeric `player_count` field follow the original desktop launcher's protocol. The count refreshes on startup, manual server checks, server changes, foreground return and every 30 seconds while visible. Background polling stops. A separate worker prevents slow status requests blocking launch preparation.
- A real zero is shown as zero; missing, invalid or unavailable data is shown as unavailable. Direct servers have no OFAPI count. Responses for a previously selected server are discarded. Public status GETs support same-origin HTTPS redirects and bounded response sizes; authentication behavior is unchanged.
- Existing borderless preferences are preserved. The user confirmed the prior audio and borderless symptoms were container issues. This release does not change the bundled FFRunner binary or graphics/audio drivers.

## Install and test

1. Install the APK over the previous launcher.
2. Keep your selected game folder, account and working container settings.
3. Tap Play once to replace the old self-deleting CMD and refresh launch details.
4. Reopen that same `Launch_OpenFusion.cmd` inside the container for subsequent launches.
5. Rotate the phone on Play, Setup and Help; the forms scroll independently in landscape.

The CMD invokes `ffrunner-android.exe`. The APK copies that executable into the selected game folder during preparation. Keep the existing Unity loader, player, Mono and game files. A shortcut to `C:\windows\system32\cmd.exe` can use `/d /c "D:\OpenFusion\Launch_OpenFusion.cmd"`, substituting your configured path.

The saved CMD no longer embeds a disposable game-login cookie. It contains a capability-protected loopback auth URL instead; FFRunner asks the Android bridge for a fresh game credential when Unity authenticates. Passwords and long-lived refresh tokens are not placed in the script. The Android bridge must remain running while FusionFall launches and game content is fetched through it.

## Build

Use JDK 17+ (or compatible javac), Python 3, Android SDK platform 35 and build-tools 35.0.1. ECJ is optional:

```sh
python3 build.py --android-jar /path/to/android.jar --build-tools /path/to/build-tools --keystore /path/to/existing/debug.keystore
```

Output: `build/OpenFusionAndroid-test-0.4.11.apk`. The script compiles, dexes, aligns, signs and verifies the APK. MP3 assets remain uncompressed for native Android audio. The debug key expects alias/password `androiddebugkey` / `android`. Private keys and toolchains are excluded from this source package.

Runner source is included in `runner-source/`; it remains x86 because the Unity engine DLLs are 32-bit. The 1.2.0 build adds runtime auth bridging and an optional XInput compatibility bridge for the legacy FusionFall controller path.

## Checks

- `tests/LaunchSpecRegression.java`: persistent CMD generation, quoting, default/explicit window modes, preference preservation and player-count validation. Compile together with LaunchSpec, ServerStatus, HttpApi and an org.json JAR.
- `tests/ui-dom.cjs`: simulated Android bridge; account/navigation/audio settings, population/zero/unavailable states, stale responses, startup dismissal and unique control IDs. Requires jsdom.
- `tests/test_audio.py --ecj /path/to/ecj.jar`: existing audio handoff regression test with Android stubs.
- `VALIDATION.json`: build and verification details, including limits of device/live-server testing.

The user's supplied landscape reference is included unchanged as `landscape-art.png`; CSS displays only its decorative sections. Launcher branding/artwork remains a community fan presentation. No Wine/Proton runtime, proprietary Unity engine or game content is bundled in the APK.

## 0.4.3 artwork update
The supplied blue logo is used unchanged for the CRT splash and Android adaptive icon. Landscape artwork now joins the heroes, logo and city into a continuous full-height rail. Portrait controls and launch behavior are unchanged.

Transparency fix: splash and adaptive-icon foreground use a real RGBA PNG, with no CSS blend dependency. Background extraction used the built-in image editor with the prompt: remove only the solid black exterior background; preserve blue logo, navy interiors and cyan glow; output true alpha transparency.

## 0.4.3 saved game settings
Unity Web Player stores preferences per game URL (https://docs.unity3d.com/460/Documentation/ScriptReference/PlayerPrefs.html). The old Android proxy generated a random port and path on every start. The proxy now persists a random capability token and loopback port per server/build in private Android preferences, keeping the complete game URL stable across launcher restarts. Tokens remain unguessable and the server remains loopback-only. A busy saved port fails with an actionable message instead of silently changing the preferences namespace.

After upgrading, press Play to regenerate the command, select the desired settings in the game and choose **Save and Exit** in the settings menu. Quit the game normally and relaunch in the same Wine container. Old preferences from previous random URLs are not automatically migrated. Different containers, cleared Android app data, or a different server/build can have separate preferences. Direct-download mode is unchanged. This fixes URL instability; actual in-game persistence still requires device verification.

Icon: navy gradient adaptive background and a smaller, centered foreground with even padding. The transparent splash artwork is unchanged.

## 0.4.5 runtime auth and exclusive controller compatibility
The reusable CMD now points FFRunner at a private loopback `__auth` endpoint. Each request mints a fresh short-lived game credential instead of reusing the cookie that was present when Play was pressed. The stable per-profile loopback URL from 0.4.3 remains unchanged so Unity Web Player preferences keep the same namespace.

FFRunner 1.3.0 also supports `--xinput-bridge`. The launcher enables it by default and exposes a setting to disable it. It reads XInput-style GameNative/Ludashi input directly and installs app-local XInput blocker DLLs so Unity's legacy joystick layer cannot simultaneously misinterpret the same stick/trigger data. FFRunner loads the real XInput backend explicitly from System32 and translates movement/camera/buttons to FusionFall's keyboard/mouse defaults. Physical behavior still requires device testing.

### 0.4.5 controller isolation test

When the controller bridge is enabled, the launcher installs small app-local XInput blocker DLLs in the OpenFusion runtime search paths. These blockers report no controller to Unity's legacy input layer. FFRunner 1.3.0 loads the real Wine/Ludashi XInput DLL explicitly from the Windows System32 directory and translates the controller to FusionFall keyboard/mouse defaults. This is intended to prevent malformed native mappings such as right-stick motion triggering fire. Disabling the bridge removes only blocker files previously installed by this launcher.


## 0.4.7 keyboard/mouse controller mode + DXVK display guard

0.4.7 removes the experimental XInput blocker and `dinput8.dll` proxy from 0.4.5/0.4.6. When a launch is prepared, the Android launcher cleans up those older files if it previously installed them. FFRunner 1.5.0 now treats the controller as an input source only: it reads XInput and emits FusionFall's normal keyboard/mouse controls. Before Unity loads, FFRunner enumerates attached legacy DirectInput game controllers and writes Wine's per-app `disabled` entries for those exact device names under `ffrunner-android.exe`, so Unity should not receive the same pad a second time.

The uploaded DXVK log showed the game requesting `Windowed: false` when its resolution option is changed. 0.4.7 writes a launcher-owned `openfusion-dxvk.conf` containing `d3d9.enableDialogMode = True` and points DXVK at that file. DXVK 1.x documents this option as disabling exclusive fullscreen, allowing the game to reset its D3D9 swap chain without asking Wine/Android to perform an exclusive display-mode switch. The existing FFRunner resize debounce remains in place as a secondary guard.


## 0.4.11 recursive top-level browser renderer test

0.4.11 keeps the working controller-to-keyboard/mouse architecture and requested FusionFall button layout. Start now emits the main Enter/Return scan code because FusionFall uses Enter for its chat/menu/pause interface.

The 0.4.10 Wine log showed that Unity nests the browser renderer under an intermediate child (`host -> UnityIntermediate -> UnityWindow`). The 0.4.10 one-level search therefore never found or promoted the actual renderer. 0.4.11 recursively enumerates descendants and logs the discovered renderer/parent before promotion.

A focused Wine/X11/Vulkan log captured the remaining display failure. Unity uses two different Win32 windows: `UnityWindowFS` is the smooth top-level fullscreen path, while browser mode renders through `UnityWindow`, a child of FFRunner's NPAPI host. At the black-screen transition Wine successfully destroyed the fullscreen surface, created a new Vulkan surface/swap chain on `UnityWindow`, and continued presenting frames; the game did not crash. This points to the embedded child-window presentation path rather than a D3D device loss.

### 0.4.11 test changes

- Promotes Unity's existing `UnityWindow` browser renderer from `WS_CHILD` to an owned borderless top-level window before browser-mode Vulkan presentation settles. The exact Unity HWND is preserved; only its parent/style/presentation path changes.
- Leaves Unity's `UnityWindowFS` fullscreen window untouched. A low-rate timer watches the two Unity windows and only maintains the browser renderer while the fullscreen window is hidden.
- Removes the 0.4.9 forced browser `NPP_SetWindow` reattach on focus return; the Wine log showed that the swap chain was already being rebuilt successfully, so repeated reattachment was targeting the wrong layer.
- Removes the experimental `d3d9.deferSurfaceCreation` and `d3d9.maxFrameLatency` overrides. The launcher-owned DXVK config now keeps only `d3d9.enableDialogMode = True`, while `DXVK_FORCE_WINDOWED=1` remains set before Unity loads.
- Start/Pause uses the main Enter/Return scan code (`0x1c`).
- Controller mapping otherwise remains: A jump; X/Y/B Nanos 1/2/3; LB Nano Power; RB Switch Weapon; RT/LT left/right mouse; Back Map; D-pad Up/Down/Left/Right Weapon Boost/Nano Boost/Inventory/Vehicle.

This build is intentionally a device test of the window architecture. The expected result is smoother browser-mode presentation and a reliable fullscreen-to-browser return without a black image. If the experiment causes new window/focus behavior, the 0.4.9 controller path remains the known-good input baseline.


## Stable runtime-picker restoration

This build keeps the proven 0.4.11 stable-auth-logdiag game/runtime path unchanged and restores only the later Android runtime picker detection. Setup now recognizes Winlator/Ludashi, GameNative, WinNative, GameHub/GameHub Lite, Bannerlator and BannerHub from launcher labels, with known ordinary package IDs as fallbacks. Performance-spoof package IDs are not accepted solely by ID, preventing unrelated apps from appearing. FFRunner, Unity layout, renderer behavior, authentication, asset loading and diagnostic Unity logging are unchanged.
