# FFRunner 1.1.1-wine-windowfix — x86 test build

A targeted revision of the supplied FFRunner source, for testing in Wine/Proton containers on Android. This is a 32-bit Windows executable, not an ARM64EC executable. The Unity Web Player loader, player, and Mono DLLs are all PE32/i386 and must load inside a 32-bit process. Your Proton/Wine build must therefore provide working x86/WoW64 support.

## Install the standalone runner

1. Close the game and its container.
2. Keep a backup of your existing `ffrunner.exe`.
3. Extract this package's `ffrunner-android.exe` into the same game folder, next to the existing `loader`, `player`, `mono`, and `assets` folders. Keep all those files and your graphics DLLs.
4. Prepare a fresh launch in the updated Android launcher, or change your existing script to invoke `ffrunner-android.exe` with the same server/account arguments. The default is now windowed.
5. Set the container display resolution to the resolution you want to render. Start with your existing working container profile. Borderless does not change the container resolution or repair a GPU driver.

The new Android 0.3.1 APK can instead install this binary automatically beside the original, as `ffrunner-android.exe`, every time you prepare a launch. Its **Setup → Game settings → Borderless window (experimental)** option selects `--borderless-window` or `--windowed`, keeping the chosen resolution. The option resets to off once on upgrading.

## Window options

- `--borderless`: native borderless fullscreen on the current container display (experimental, manual opt-in). No title bar, no exclusive display-mode switch, and no permanent topmost setting. Container resolution takes precedence over `--width`/`--height`.
- `--borderless-window --width 960 --height 540`: borderless window with the requested client size, centered in the display work area.
- `--windowed --width 960 --height 540`: normal framed window with an actual 960×540 client area (default mode).
- Use the runtime's exit/back controls or Alt+F4 to close a borderless game.

Append the desired options to your existing game command, which still needs its usual server/content/sign-in arguments. These flags are for this custom build; upstream FFRunner does not implement them.

## Changes in 1.1.1

- Windowed is the default. Android's borderless checkbox keeps the requested size instead of changing to the full container resolution.
- Restore upstream's show-before-plugin-attachment order and request initial foreground focus. No repeated focus stealing or permanent topmost flag.
- Defer size notifications to the message loop; suppress unchanged/reentrant SetWindow calls and ignore zero-sized render surfaces. Keep valid client dimensions while minimized.
- Add startup visibility/foreground diagnostics. `ffrunner.log` identifies this build.

These are targeted fixes for plausible contributors, not proof that the reported missing sound or server disconnect is resolved. No device logs from 1.1.0 were supplied with this report. The APK separately improves Android audio-focus handoff. Test windowed first, then borderless at the same size, using your previously working container/driver.

## Compatibility changes

- Window messages no longer call the Unity plugin before its instance and NPWindow are ready. Minimize, resize and detach synchronize the plugin rectangle; detach happens once.
- Borderless geometry uses the monitor rectangle; framed mode accounts for title-bar/border dimensions. Signed centering avoids underflow when a requested window exceeds the desktop.
- DPI awareness falls back when the newer API exists but cannot be used. Window titles convert from UTF-8 with a code-page fallback.
- DXGI is no longer a mandatory import. The runner skips the startup GPU-memory query by default and supplies `UNITY_FF_VRAM_MB=512`, preserving an existing environment setting. This is an engine memory hint, not a GPU-memory reservation. `--vram-mb 768` explicitly overrides it; `--query-vram` opts into the original style of DXGI probing and clamps its result to 128–2048 MB.
- DXGI memory addition uses 64-bit arithmetic to avoid 32-bit overflow.
- Download workers yield for 2 ms when Unity reports no stream progress, instead of repeatedly waking the UI thread without progress. Failed message dispatch no longer waits forever for an event that cannot arrive.
- Argument values and dimensions are checked before startup. Truncated log lines use the actual buffer length.
- The release is built for generic i686 with optimization, without fast-math or extra SIMD requirements. It retains large-address awareness and does not require a separate libgcc/winpthread DLL.

These changes do not port or rewrite the proprietary Unity player, Mono runtime, Direct3D wrapper, graphics drivers, or the game's code. They do not establish that Adreno/Turnip crashes are fixed. An ARM64EC-native game host would require compatible engine dependencies, or a much larger process-bridging design that still executes the old player in a 32-bit process.

Architecture references:
- https://learn.microsoft.com/en-us/windows/arm/arm64ec
- https://learn.microsoft.com/en-us/windows/win32/winprog64/process-interoperability

## Build and verification

The supplied source uses the MIT license in `LICENSE.md`. This is an unofficial test build.

Install MinGW-w64's i686 GCC/binutils, then run `make`. To build from a relocatable toolchain, override `CC` and `WINDRES` on the make command line. GCC 13-win32 / MinGW-w64 11 was used for the delivered binary. CMake requires a 32-bit Windows cross toolchain; 64-bit targets intentionally fail because the supplied engine is 32-bit.

Run `python3 tests/test_portable.py` with a native `gcc` to test the actual argument parser and guarded plugin window lifecycle. It uses a stub for the client-rectangle API; it does not run Windows or Unity. Compilation, PE architecture/import inspection, and these host-side tests passed. Full game rendering and ARM64EC Proton behavior must still be tested on the target Android device.

The runner writes `ffrunner.log`. Its first line includes the custom build name; the window and VRAM choices are also logged. Existing upstream verbose logs can contain account information, so review them before sharing.
