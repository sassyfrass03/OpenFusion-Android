# OpenFusion Android

An Android-native launcher and compatibility bridge for running **FusionFall through OpenFusion** inside Android Windows compatibility runtimes such as Winlator and GameNative.

The desktop OpenFusion launcher is built around a Windows/WebView2 workflow that is not a good fit for Android Wine/Proton environments. OpenFusion Android handles the Android-side work — setup, authentication, downloads, launch preparation and compatibility helpers — then hands the legacy Windows client to the runtime you already use.

> **Project status:** Stable baseline / public testing  
> **Current build:** `0.4.11-stable-runtime-detection`  
> **Minimum Android version:** Android 8.0 / API 26

## Discord for debugging and development
https://discord.gg/bHQAd6KACr
## What it does

- Provides a native Android setup and sign-in flow for OpenFusion servers.
- Creates a reusable `Launch_OpenFusion.cmd` for the selected Windows runtime.
- Runs a local Android bridge so the legacy Unity Web Player can fetch modern HTTPS content and request fresh game credentials.
- Installs the required OpenFusion runtime files from the official OpenFusion portable release; the Windows desktop launcher itself is not used.
- Supports resolution, FPS-cap, renderer and borderless-window options.
- Includes an optional XInput-to-keyboard/mouse compatibility layer for FusionFall controls.
- Keeps Unity/FFRunner diagnostic logs available for troubleshooting.
- Detects several Winlator-style Android runtimes from the Setup screen.

## Runtime detection

The launcher can recognize installed apps whose launcher identity matches:

- Winlator / Ludashi
- GameNative
- WinNative
- GameHub / GameHub Lite
- Bannerlator
- BannerHub

Detection does **not** guarantee that every version or fork will run FusionFall correctly. Direct launch is only attempted when the selected runtime exposes a compatible activity; manual launch remains the fallback.

## Requirements

You will need:

- Android 8.0 or newer.
- Android System WebView or Chrome enabled for the launcher UI.
- A compatible Android Windows runtime with support for **32-bit Windows/x86 applications**.
- Internet access for OpenFusion authentication and game assets.
- A supported OpenFusion/FusionFall server account.

The APK does **not** bundle Winlator, Wine, Proton, FusionFall game content or a complete Windows environment.

## Quick start

1. Install OpenFusion Android.
2. Open **Setup** and choose a folder such as `Download/OpenFusion`.
3. Use **Install runner** to install/check the required OpenFusion runtime files.
4. Select your installed Windows runtime and make sure its Windows path points to the same folder, commonly `D:\OpenFusion`.
5. Create or use a container capable of running 32-bit Windows applications.
6. In the launcher, choose your server, sign in and press **Play**.
7. Open the selected Windows runtime and run `Launch_OpenFusion.cmd` from the OpenFusion folder.

The launch file is reusable. Pressing **Play** again refreshes its settings and restarts the Android bridge when needed.

### Optional runtime shortcut

A Winlator-style shortcut can launch:

```text
C:\windows\system32\cmd.exe
```

with arguments:

```text
/d /c "D:\OpenFusion\Launch_OpenFusion.cmd"
```

Change the path if your container maps the Android folder differently.

## How the bridge works

OpenFusion Android does not try to run the original desktop launcher inside Wine. Instead it separates the job into three parts:

```text
Android launcher  ->  local auth/asset bridge  ->  FFRunner + legacy Unity client
```

The Android app talks to the OpenFusion API, prepares the client configuration and keeps a loopback-only bridge available while the game starts. FFRunner hosts the legacy 32-bit Unity Web Player inside the Windows runtime.

When remembered sign-in is enabled, the reusable CMD does not contain your password or long-lived refresh token. FFRunner requests a fresh short-lived game credential from the Android bridge when Unity authenticates.

## Controller compatibility

The optional controller compatibility mode reads an XInput-style controller and sends FusionFall's normal keyboard/mouse controls. The current default mapping is:

| Controller | FusionFall action |
| --- | --- |
| Left stick | Movement |
| Right stick | Camera/mouse |
| A | Jump |
| X / Y / B | Nano 1 / 2 / 3 |
| LB | Nano Power |
| RB | Switch Weapon |
| RT / LT | Left / Right mouse click |
| Back | Map |
| Start | Enter / Pause |
| D-pad Up | Weapon Boost |
| D-pad Down | Nano Boost |
| D-pad Left | Inventory |
| D-pad Right | Vehicle |

If your runtime exposes the same controller through both XInput and legacy DirectInput, use the runtime's XInput-only option when possible.

## Recommended baseline settings

For first-time testing, start with:

```text
Renderer: Direct3D 9
Resolution: 960 x 540
FPS cap: 30
Asset proxy: Enabled
Controller compatibility: Enabled if using a controller
```

Once the game is confirmed working, change one setting at a time. Renderer, driver and presentation behavior can vary significantly between Android Windows runtimes and devices.

## Logs and bug reports

The most useful files for troubleshooting are:

```text
ffrunner.log
unity-webplayer.log
```

They are written to the OpenFusion folder. Logs may contain account-related or server information, so review them before posting them publicly.

When reporting an issue, include your Android device/chipset, Android version, Windows runtime and version, graphics driver/renderer, whether the issue happens before or after character selection, and the two logs above when available.

## Known limitations

- The Unity Web Player and NPAPI client are legacy software and can behave differently across Wine/Proton forks.
- Some runtimes require launching `Launch_OpenFusion.cmd` manually.
- The Android bridge must remain active while the client authenticates and while proxied game assets are being fetched.
- Runtime detection only identifies likely compatible apps; it is not a compatibility certification.
- Fullscreen/browser presentation and frame pacing depend heavily on the selected runtime, GPU driver and Wine graphics stack.
- This project is currently focused on preserving a stable working baseline rather than aggressively modifying the original Unity client.

## Building from source

### Android launcher

Requirements:

- JDK 17+
- Python 3
- Android SDK Platform 35
- Android Build Tools 35.0.1
- Optional: Eclipse ECJ (otherwise `javac` is used)

Build with:

```bash
python3 build.py \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.1"
```

To use an existing signing key:

```bash
python3 build.py \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.1" \
  --keystore /path/to/debug.keystore
```

If no keystore is supplied, the development build script creates a local debug keystore under `build/`. **Do not commit signing keys.**

### FFRunner

FFRunner is a 32-bit Windows application because the Unity Web Player used by FusionFall is 32-bit. A MinGW toolchain is the recommended build path:

```bash
cd runner-source
make
```

After rebuilding, stage the resulting runner where the Android build expects `app/src/main/assets/runner/ffrunner-android.exe`.

## Repository layout

```text
app/                    Android launcher source and assets
runner-source/          Custom FFRunner source
 tests/                 Launcher regression tests
validation/             Historical validation/test material
build.py                Standalone Android APK build script
VALIDATION.json         Baseline build/test information
```

## Security notes

- Passwords are used for sign-in but are not saved by the launcher.
- Remembered sign-in tokens are stored using Android Keystore-backed protection.
- Long-lived credentials are not written into `Launch_OpenFusion.cmd`.
- The game bridge binds to loopback and uses a per-profile capability value.
- Signing keys, private logs and personal account data should never be committed to the repository.

## Credits and disclaimer

This is a community compatibility project built around the OpenFusion ecosystem and FFRunner. OpenFusion/FFRunner components included in this repository retain their respective license notices; the included FFRunner code is MIT-licensed.

FusionFall and related names, characters and artwork are property of their respective owners. This project is not affiliated with or endorsed by Cartoon Network, Warner Bros. Discovery, Unity Technologies, the Winlator project, or the developers of third-party Android Windows runtimes.

No FusionFall game content is distributed by this repository.
