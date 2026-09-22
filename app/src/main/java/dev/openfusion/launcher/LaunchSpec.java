package dev.openfusion.launcher;

import java.net.*;
import java.util.*;
import org.json.*;

/** Protocol/quoting shared by the Android backend and JVM regression tests. */
public final class LaunchSpec {
    public static final String RUNNER = "ffrunner-android.exe";
    private LaunchSpec() {}
    public static boolean migrateWindowSettings(JSONObject settings) throws JSONException {
        if (settings.optInt("windowSettingsVersion", 0) >= 1) return false;
        if(!settings.has("borderless"))settings.put("borderless", false);
        settings.put("windowSettingsVersion", 1);
        return true;
    }
    public static String endpoint(String input) throws Exception {
        String s = input.trim();
        if (!s.contains("://")) s = "https://" + s;
        URI u = new URI(s);
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
            throw new Exception("Enter an HTTPS API host, optionally with a path such as /academy.");
        if (s.matches(".*[\\s\\\"<>\\\\].*")) throw new Exception("Invalid API address.");
        while (s.endsWith("/")) s = s.substring(0, s.length()-1);
        return s;
    }
    public static String webUrl(String input) throws Exception {
        URI u = new URI(input);
        if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null)
            throw new Exception("Game assets need an HTTP or HTTPS URL.");
        quote(input);
        return input;
    }
    public static String quote(String value) throws Exception {
        // cmd.exe expands % even within quotes. Delayed expansion is disabled in the script.
        // Reject quotes, control characters and trailing backslashes (CRT quote escaping).
        if (value == null || value.matches("(?s).*[\\x00-\\x1f\\x7f\\\"].*") || value.endsWith("\\"))
            throw new Exception("A launch value contains an unsupported quote, control character or trailing slash.");
        return "\"" + value.replace("%", "%%") + "\"";
    }
    public static String resolveAddress(String address) throws Exception {
        int split = address.lastIndexOf(':');
        if (split <= 0) throw new Exception("Server address must include a port, for example host:23000.");
        String host = address.substring(0, split);
        int port;
        try { port = Integer.parseInt(address.substring(split+1)); }
        catch (Exception e) { throw new Exception("Invalid server port."); }
        if (port < 1 || port > 65535) throw new Exception("Invalid server port.");
        for (InetAddress ip : InetAddress.getAllByName(host))
            if (ip instanceof Inet4Address) return ip.getHostAddress() + ":" + port;
        throw new Exception("FFRunner needs an IPv4 server address.");
    }
    public static int bounded(JSONObject o, String key, int fallback, int low, int high) throws Exception {
        int n = o.optInt(key, fallback);
        if (n < low || n > high) throw new Exception(key + " must be between " + low + " and " + high + ".");
        return n;
    }
    public static String windowsFolder(String s) throws Exception {
        s = s.trim().replace('/', '\\');
        while (s.endsWith("\\")) s = s.substring(0, s.length()-1);
        if (!s.matches("[A-Za-z]:\\\\[A-Za-z0-9 _\\\\.()-]+") || Arrays.asList(s.split("\\\\")).contains(".."))
            throw new Exception("Use the Windows path shown inside Winlator, for example D:\\OpenFusion.");
        return s;
    }
    /** Stable namespace excludes login cookies, DNS results and proxy URLs. */
    public static String profileKey(String server, String version) throws Exception {
        UUID.fromString(version);
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest((server+"\n"+version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key=new StringBuilder();for(byte b:bytes)key.append(String.format(Locale.ROOT,"%02x",b&255));return key.toString();
    }
    public static String script(JSONObject spec, JSONObject settings) throws Exception {
        List<String> args = new ArrayList<>();
        Collections.addAll(args, "-m", webUrl(spec.getString("main")), "-a", spec.getString("address"), "--asseturl", webUrl(spec.getString("assets")), "-n", spec.optString("name", "FusionFall"), "-l", "ffrunner.log",
            "--width", ""+bounded(settings,"width",960,640,3840), "--height", ""+bounded(settings,"height",540,360,2160));
        if (spec.has("endpoint")) Collections.addAll(args,"-e",endpoint(spec.getString("endpoint")).substring(8));
        if (spec.has("authUrl")) Collections.addAll(args,"--auth-url",webUrl(spec.getString("authUrl")));
        else if (spec.has("cookie")) Collections.addAll(args,"-u",spec.getString("username"),"-t",spec.getString("cookie"));
        if (spec.optBoolean("loader")) args.add("--loader-images");
        if (settings.optBoolean("controllerBridge", true)) args.add("--xinput-bridge");
        if ("opengl".equals(settings.optString("graphics"))) args.add("--force-opengl");
        if ("vulkan".equals(settings.optString("graphics"))) args.add("--force-vulkan");
        args.add(settings.optBoolean("borderless", false) ? "--borderless-window" : "--windowed");
        String id = spec.getString("version");
        UUID.fromString(id);
        StringBuilder b = new StringBuilder("@echo off\r\nsetlocal DisableDelayedExpansion\r\nchcp 65001 >nul\r\ncd /d \"%~dp0\"\r\n");
        b.append("rem OpenFusion Android 0.4.11 stable auth log diagnostic - runner 1.9.0\r\n");
        b.append("if not exist \"").append(RUNNER).append("\" (echo Missing ").append(RUNNER).append(". Prepare launch again in the Android app. & pause & exit /b 1)\r\n");
        b.append("set \"UNITY_FF_CACHE_DIR=%CD%\\ffcache\\").append(id).append("\"\r\n");
        b.append("if not exist \"%UNITY_FF_CACHE_DIR%\" mkdir \"%UNITY_FF_CACHE_DIR%\"\r\n");
        b.append("set \"UNITY_FF_FPS_CAP=").append(bounded(settings,"fps",30,15,120)).append("\"\r\n");
        // Use a launcher-owned DXVK config rather than modifying the user's
        // existing dxvk.conf. Exclusive D3D9 mode switching remains disabled;
        // FFRunner handles Unity's window-mode transitions itself.
        b.append("set \"DXVK_CONFIG_FILE=%CD%\\openfusion-dxvk.conf\"\r\n");
        b.append("set \"DXVK_FORCE_WINDOWED=1\"\r\n");
        b.append("rem Reusable launch file. Android bridge supplies a fresh game login at runtime.\r\n");
        b.append('"').append(RUNNER).append('"');
        for (String arg : args) b.append(' ').append(quote(arg));
        b.append("\r\n");
        return b.toString();
    }
    public static String desktop(String folder) throws Exception {
        String command = windowsFolder(folder) + "\\Launch_OpenFusion.cmd";
        return "[Desktop Entry]\nName=OpenFusion Android\nType=Application\nExec=wine C:\\\\windows\\\\system32\\\\cmd.exe\n[Extra Data]\nexecArgs=/d /c \"" + command + "\"\n";
    }
}
