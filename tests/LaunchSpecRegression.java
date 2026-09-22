import dev.openfusion.launcher.LaunchSpec;
import org.json.JSONObject;

public final class LaunchSpecRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,"17",-1,1.5,Double.MAX_VALUE})
            check(dev.openfusion.launcher.ServerStatus.playerCount(new JSONObject().put("player_count",value))==null,"reject invalid population");
        check(dev.openfusion.launcher.ServerStatus.playerCount(new JSONObject())==null,"missing is unknown, not zero");
        check(dev.openfusion.launcher.ServerStatus.playerCount(new JSONObject().put("player_count",0))==0,"zero players is valid");
        check(dev.openfusion.launcher.ServerStatus.playerCount(new JSONObject().put("player_count",42))==42,"active population");

        JSONObject spec = new JSONObject().put("main", "https://example.com/main.unity3d")
            .put("assets", "https://example.com/assets/").put("address", "127.0.0.1:23000")
            .put("name", "Test & (100%)").put("version", "12345678-1234-1234-1234-123456789abc")
            .put("authUrl", "http://127.0.0.1:43123/00000000-0000-0000-0000-000000000001/__auth")
            // A bridge URL must take precedence over any stale legacy credential fields.
            .put("username", "SHOULD-NOT-LEAK").put("cookie", "SHOULD-NOT-LEAK");
        JSONObject settings = new JSONObject().put("width", 960).put("height", 540).put("fps", 30);
        String defaults = LaunchSpec.script(spec, settings);
        check(defaults.contains("\"--windowed\""), "safe default windowed");
        check(!defaults.contains("\"ffrunner.exe\""), "no obsolete executable in script");
        check(defaults.contains("\"--auth-url\""), "runtime auth bridge included");
        check(defaults.contains("/__auth\""), "auth capability URL included");
        check(!defaults.contains("SHOULD-NOT-LEAK"), "legacy username/cookie excluded when bridge is present");
        check(defaults.contains("\"--xinput-bridge\""), "controller bridge enabled by default");
        check(defaults.contains("DXVK_CONFIG_FILE=%CD%\\openfusion-dxvk.conf"), "OpenFusion DXVK config selected by absolute working path");
        check(defaults.contains("DXVK_FORCE_WINDOWED=1"), "DXVK D3D9 fullscreen requests forced windowed");
        check(!defaults.contains("WINEDLLOVERRIDES="), "no injected controller DLL overrides");

        settings.put("borderless", true);
        check(LaunchSpec.migrateWindowSettings(settings) && settings.getBoolean("borderless"), "existing borderless preference retained");
        settings.put("borderless", true);
        check(!LaunchSpec.migrateWindowSettings(settings) && settings.getBoolean("borderless"), "new explicit preference preserved");
        String borderless = LaunchSpec.script(spec, settings);
        check(borderless.contains("\"ffrunner-android.exe\" \"-m\""), "bundled runner selected");
        check(borderless.contains("\"--borderless-window\"") && !borderless.contains("\"--borderless\""), "borderless keeps requested resolution");
        check(!borderless.contains("explorer.exe"), "native mode, no virtual desktop");
        check(borderless.contains("Test & (100%%)"), "command quoting preserved");
        check(!borderless.contains("del /q") && !borderless.contains("%~f0"), "launch file remains reusable");

        settings.put("borderless", false).put("graphics", "opengl").put("controllerBridge", false);
        String windowed = LaunchSpec.script(spec, settings);
        check(windowed.contains("\"--windowed\"") && !windowed.contains("\"--borderless\""), "explicit windowed mode");
        check(windowed.contains("\"--width\" \"960\" \"--height\" \"540\""), "window dimensions retained");
        check(windowed.contains("\"--force-opengl\""), "renderer retained");
        check(!windowed.contains("\"--xinput-bridge\""), "controller bridge can be disabled");
        check(windowed.contains("DXVK_CONFIG_FILE=%CD%\\openfusion-dxvk.conf"), "DXVK guard remains active with controller adapter off");
        check(windowed.contains("DXVK_FORCE_WINDOWED=1"), "force-windowed guard remains active with controller adapter off");
        check(!windowed.contains("WINEDLLOVERRIDES="), "no controller DLL overrides when adapter disabled");
        check(LaunchSpec.desktop("D:\\OpenFusion").contains("Launch_OpenFusion.cmd"), "shortcut opens reusable CMD");
        settings.put("width", 0);
        try { LaunchSpec.script(spec, settings); throw new AssertionError("invalid size accepted"); }
        catch (Exception expected) { check(expected.getMessage().contains("width"), "dimension validation"); }
        System.out.println("PASS: reusable runtime-auth CMD, fullscreen-safe keyboard/mouse controller mode, DXVK force-windowed display guard, window modes, dimensions and quoting.");
    }
}
