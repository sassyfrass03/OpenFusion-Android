# OpenFusion Android — native launcher + Ludashi 4.1 runtime

This revision intentionally **does not start `openfusionlauncher.exe`**. The Windows OpenFusion launcher is Tauri/WebView2 based and is not part of the runtime path.

## Runtime flow

1. Android displays a native login/server screen.
2. The app calls the OpenFusion HTTPS API directly:
   - `POST /auth` -> refresh token
   - `POST /auth/session` -> short-lived session token
   - `GET /` -> server name, login address, supported game version(s)
   - `GET /versions/<uuid>` -> `asset_url` and `main_file_url`
   - `POST /cookie` -> FusionFall username/cookie
3. Android resolves the game login host to IPv4 using the desktop launcher's default port behavior (23000 when no port is supplied).
4. The app creates a one-shot Ludashi shortcut targeting `C:\OpenFusion\ffrunner.exe`.
5. Ludashi starts the ARM64EC/FEX compatibility environment and runs `ffrunner.exe` directly.
6. `ffrunner.exe` loads `loader\npUnity3D32.dll` / the Unity Web Player runtime and connects to OpenFusion.

WebView2 is therefore not required anywhere in the launch chain.

## Native launcher files

- `app/src/main/java/com/winlator/cmod/OpenFusionNativeLauncher.java`
- `app/src/main/java/com/winlator/cmod/OpenFusionApiClient.java`
- `app/src/main/java/com/winlator/cmod/OpenFusionBootstrap.java`
- `app/src/main/res/layout/openfusion_launcher_activity.xml`

The supplied Windows Portable payload remains at:

`app/src/main/assets/openfusion-portable.zip`

`openfusionlauncher.exe` may still exist inside that ZIP, but v0.3 never executes it.

## Servers currently preconfigured

- OpenFusion Public - Original: `api.dexlabs.systems`
- OpenFusion Public - Academy: `api.dexlabs.systems/academy`

These are taken from the default server list in the OpenFusion launcher source supplied for this port.

## Cache

Each selected OpenFusion game version gets its own Unity cache location under:

`C:\OpenFusion\cache\<version UUID>`

The path is supplied to the Windows runtime through `UNITY_FF_CACHE_DIR`.

## Building

The included workflow `.github/workflows/build-openfusion-apk.yml` builds the project on a full Android runner.

Expected debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

The workflow publishes it as:

`OpenFusion-Android-Ludashi-4.1-debug.apk`

### Why no APK is included in this archive

The current ChatGPT execution container has Java but no Android SDK/NDK and cannot reach Gradle's distribution/Maven hosts from its shell. A local Gradle invocation therefore stops before compilation while trying to fetch Gradle itself. The source is set up so a normal Android/CI environment can perform the actual build.

## First device test priorities

1. ImageFS/Proton/FEX extraction completes.
2. The native Android login succeeds.
3. A session and FusionFall cookie are returned.
4. Ludashi reaches `ffrunner.exe` without the Windows launcher ever appearing.
5. Unity Web Player creates its window and downloads `main.unity3d`.
6. Input, audio and D3D9 rendering work on the device.

The highest-risk part is now the old 32-bit Unity Web Player under the Ludashi runtime, not WebView2.
