package com.winlator.cmod;

import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.winlator.cmod.box64.Box64Preset;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OpenGLDriverDefaults;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.fexcore.FEXCorePreset;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Provisions the Ludashi runtime for OpenFusion and launches ffrunner directly.
 * The Windows Tauri launcher is intentionally never executed, avoiding WebView2.
 */
public final class OpenFusionBootstrap {
    private static final String TAG = "OpenFusionBootstrap";
    private static final String CONTAINER_NAME = "OpenFusion";
    private static final String RUNTIME_ASSET = "openfusion-portable.zip";
    private static final String INSTALL_DIR = ".wine/drive_c/OpenFusion";
    private static final String SHORTCUT_FILE = "OpenFusion-FusionFall.desktop";

    public interface ReadyCallback { void onReady(Container container); }

    public static final class LaunchSpec {
        public String serverName;
        public String endpoint;
        public String gameAddress;
        public String username;
        public String cookie;
        public String assetUrl;
        public String mainUrl;
        public String versionId;
        public boolean customLoadingScreen;
        public int width = 1280;
        public int height = 720;
    }

    private OpenFusionBootstrap() {}

    /** Create the dedicated Wine/FEX container and install the Windows runtime, but do not launch. */
    public static void ensureInstalled(MainActivity activity, ReadyCallback callback) {
        try {
            ContentsManager contentsManager = new ContentsManager(activity);
            contentsManager.syncContents();
            ContainerManager manager = new ContainerManager(activity);

            Container existing = findOpenFusionContainer(manager);
            if (existing != null) {
                preparePayload(activity, existing, callback);
                return;
            }

            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", "1280x720");
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
            data.put("graphicsDriverConfig", Container.DEFAULT_GRAPHICSDRIVERCONFIG);
            data.put("rendererNative", false);
            data.put("rendererPresentMode", "fifo");
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("dxwrapperConfig", Container.DEFAULT_DXWRAPPERCONFIG);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("emulator", "FEXCore");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES);
            data.put("box64Version", DefaultVersion.WOWBOX64);
            data.put("box64Preset", Box64Preset.COMPATIBILITY);
            data.put("fexcoreVersion", DefaultVersion.FEXCORE);
            data.put("fexcorePreset", FEXCorePreset.INTERMEDIATE);
            data.put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
            OpenGLDriverDefaults.initialize(activity, data);

            Toast.makeText(activity, "Preparing OpenFusion runtime…", Toast.LENGTH_SHORT).show();
            manager.createContainerAsync(data, contentsManager, created -> {
                if (created == null) {
                    Toast.makeText(activity, "Unable to create the OpenFusion container.", Toast.LENGTH_LONG).show();
                    if (callback != null) callback.onReady(null);
                    return;
                }
                preparePayload(activity, created, callback);
            });
        } catch (Exception error) {
            Log.e(TAG, "Unable to bootstrap OpenFusion", error);
            Toast.makeText(activity, "Unable to prepare OpenFusion: " + error.getMessage(), Toast.LENGTH_LONG).show();
            if (callback != null) callback.onReady(null);
        }
    }

    private static Container findOpenFusionContainer(ContainerManager manager) {
        for (Container container : manager.getContainers()) {
            if (CONTAINER_NAME.equals(container.getName())) return container;
        }
        return null;
    }

    private static void preparePayload(MainActivity activity, Container container, ReadyCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File installDir = new File(container.getRootDir(), INSTALL_DIR);
                File marker = new File(installDir, ".openfusion-android-native-ready");
                File ffrunner = new File(installDir, "ffrunner.exe");
                File unity = new File(installDir, "loader/npUnity3D32.dll");
                File webPlayer = new File(installDir, "player/fusion-2.x.x/webplayer_win.dll");

                if (!marker.isFile() || !ffrunner.isFile() || !unity.isFile() || !webPlayer.isFile()) {
                    FileUtils.delete(installDir);
                    if (!installDir.mkdirs() && !installDir.isDirectory()) {
                        throw new IllegalStateException("Could not create " + installDir);
                    }
                    extractAssetZip(activity, RUNTIME_ASSET, installDir);
                    if (!ffrunner.isFile() || !unity.isFile() || !webPlayer.isFile()) {
                        throw new IllegalStateException("OpenFusion runtime extraction was incomplete");
                    }
                    FileUtils.writeString(marker, "OpenFusion Android native launcher runtime v3\n");
                }

                File cacheDir = new File(installDir, "cache");
                if (!cacheDir.isDirectory()) cacheDir.mkdirs();
                activity.runOnUiThread(() -> { if (callback != null) callback.onReady(container); });
            } catch (Exception error) {
                Log.e(TAG, "Unable to install OpenFusion payload", error);
                activity.runOnUiThread(() -> {
                    Toast.makeText(activity, "OpenFusion setup failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    if (callback != null) callback.onReady(null);
                });
            }
        });
    }

    /** Writes a one-shot Ludashi shortcut for ffrunner.exe and launches it. */
    public static void launchFfrunner(MainActivity activity, Container container, LaunchSpec spec) {
        if (container == null) {
            Toast.makeText(activity, "Compatibility runtime is not ready.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File desktopDir = container.getDesktopDir();
            if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) {
                throw new IllegalStateException("Could not create desktop directory");
            }
            File shortcutFile = new File(desktopDir, SHORTCUT_FILE);
            StringBuilder args = new StringBuilder();
            appendArg(args, "-m", spec.mainUrl);
            appendArg(args, "-a", spec.gameAddress);
            appendArg(args, "--asseturl", spec.assetUrl);
            appendArg(args, "-l", "C:\\OpenFusion\\ffrunner.log");
            appendArg(args, "-n", spec.serverName);
            appendArg(args, "-u", spec.username);
            appendArg(args, "-t", spec.cookie);
            appendArg(args, "-e", spec.endpoint);
            if (spec.customLoadingScreen) args.append(" --loader-images");
            args.append(" --width ").append(spec.width);
            args.append(" --height ").append(spec.height);

            String cachePath = "C:\\OpenFusion\\cache\\" + safeFilePart(spec.versionId);
            String shortcut = "[Desktop Entry]\n"
                    + "Name=FusionFall\n"
                    + "Exec=wine C:\\\\OpenFusion\\\\ffrunner.exe\n"
                    + "Type=Application\n"
                    + "StartupNotify=false\n"
                    + "\n[Extra Data]\n"
                    + "execArgs=" + args.toString().trim() + "\n"
                    + "envVars=UNITY_FF_CACHE_DIR=" + cachePath + "\n"
                    + "fexcorePreset=" + FEXCorePreset.INTERMEDIATE + "\n"
                    + "screenSize=" + spec.width + "x" + spec.height + "\n"
                    + "fullscreenStretched=1\n"
                    + "disableXinput=0\n";
            FileUtils.writeString(shortcutFile, shortcut);
            launchShortcut(activity, container, shortcutFile);
        } catch (Exception error) {
            Log.e(TAG, "Unable to launch ffrunner", error);
            Toast.makeText(activity, "Could not launch FusionFall: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void appendArg(StringBuilder args, String name, String value) {
        if (value == null || value.isEmpty()) return;
        args.append(' ').append(name).append(' ').append(quoteWindowsArg(value));
    }

    // Enough for the URLs/tokens/server names used here. Escape embedded quotes and trailing slashes.
    private static String quoteWindowsArg(String value) {
        String escaped = value.replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String safeFilePart(String value) {
        if (value == null || value.isEmpty()) return "default";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void extractAssetZip(MainActivity activity, String assetName, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (InputStream raw = activity.getAssets().open(assetName);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) {
                    throw new SecurityException("Unsafe path in OpenFusion runtime: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IllegalStateException("Could not create " + out);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IllegalStateException("Could not create " + parent);
                    }
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void launchShortcut(MainActivity activity, Container container, File shortcutFile) {
        Shortcut shortcut = new Shortcut(container, shortcutFile);
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcutFile.getPath());
        intent.putExtra("shortcut_name", shortcut.name);
        intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
        intent.putExtra("native_rendering", shortcut.getRendererNative());
        activity.startActivity(intent);
    }
}
