package com.winlator.cmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Native Android implementation of the small subset of the OpenFusion launcher API
 * needed to log in and construct an ffrunner command. No WebView/WebView2 is used. */
public final class OpenFusionApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build();

    public static final class ServerChoice {
        public final String name;
        public final String endpoint;
        public ServerChoice(String name, String endpoint) {
            this.name = name;
            this.endpoint = endpoint;
        }
        @Override public String toString() { return name; }
    }

    public static final class ServerInfo {
        public String serverName;
        public String loginAddress;
        public boolean customLoadingScreen;
        public final List<String> versions = new ArrayList<>();
    }

    public static final class Session {
        public String username;
        public String sessionToken;
    }

    public static final class GameCookie {
        public String username;
        public String cookie;
        public long expires;
    }

    public static final class VersionInfo {
        public String uuid;
        public String name;
        public String assetUrl;
        public String mainFileUrl;
    }

    public ServerInfo getInfo(String endpoint) throws Exception {
        JsonObject root = getJson("https://" + endpoint);
        ServerInfo info = new ServerInfo();
        info.serverName = getString(root, "server_name", "OpenFusion");
        info.loginAddress = getString(root, "login_address", null);
        info.customLoadingScreen = root.has("custom_loading_screen")
                && !root.get("custom_loading_screen").isJsonNull()
                && root.get("custom_loading_screen").getAsBoolean();
        if (root.has("game_versions") && root.get("game_versions").isJsonArray()) {
            JsonArray versions = root.getAsJsonArray("game_versions");
            for (JsonElement version : versions) info.versions.add(version.getAsString());
        } else if (root.has("game_version") && !root.get("game_version").isJsonNull()) {
            info.versions.add(root.get("game_version").getAsString());
        }
        if (info.loginAddress == null || info.loginAddress.isEmpty()) {
            throw new IOException("Server did not provide a game login address");
        }
        if (info.versions.isEmpty()) throw new IOException("Server did not provide a game version");
        return info;
    }

    public String login(String endpoint, String username, String password) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        Request request = new Request.Builder()
                .url("https://" + endpoint + "/auth")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (response.code() == 401) throw new IOException("Incorrect username or password");
            if (!response.isSuccessful()) throw apiError(response, text);
            return stripJsonString(text.trim());
        }
    }

    public Session getSession(String endpoint, String refreshToken) throws Exception {
        Request request = new Request.Builder()
                .url("https://" + endpoint + "/auth/session")
                .header("Authorization", "Bearer " + refreshToken)
                .post(RequestBody.create(new byte[0], (MediaType) null))
                .build();
        JsonObject root = executeJson(request);
        Session session = new Session();
        session.username = getString(root, "username", "");
        session.sessionToken = getString(root, "session_token", null);
        if (session.sessionToken == null || session.sessionToken.isEmpty()) {
            throw new IOException("Server did not return a session token");
        }
        return session;
    }

    public GameCookie getCookie(String endpoint, String sessionToken) throws Exception {
        Request request = new Request.Builder()
                .url("https://" + endpoint + "/cookie")
                .header("Authorization", "Bearer " + sessionToken)
                .post(RequestBody.create(new byte[0], (MediaType) null))
                .build();
        JsonObject root = executeJson(request);
        GameCookie cookie = new GameCookie();
        cookie.username = getString(root, "username", "");
        cookie.cookie = getString(root, "cookie", null);
        cookie.expires = root.has("expires") ? root.get("expires").getAsLong() : 0;
        if (cookie.cookie == null || cookie.cookie.isEmpty()) {
            throw new IOException("Server did not return a game cookie");
        }
        long now = System.currentTimeMillis() / 1000L;
        if (cookie.expires > 0 && cookie.expires < now) {
            throw new IOException("Game cookie was already expired; check the phone's date/time");
        }
        return cookie;
    }

    public VersionInfo getVersion(String endpoint, String uuid) throws Exception {
        IOException firstFailure = null;
        for (String suffix : new String[]{uuid, uuid + ".json"}) {
            try {
                JsonObject root = getJson("https://" + endpoint + "/versions/" + suffix);
                VersionInfo info = new VersionInfo();
                info.uuid = getString(root, "uuid", uuid);
                info.name = getString(root, "name", uuid);
                info.assetUrl = getString(root, "asset_url", null);
                info.mainFileUrl = getString(root, "main_file_url", null);
                if (info.assetUrl == null || info.assetUrl.isEmpty()) {
                    throw new IOException("Version did not contain asset_url");
                }
                if (info.mainFileUrl == null || info.mainFileUrl.isEmpty()) {
                    info.mainFileUrl = trimSlash(info.assetUrl) + "/main.unity3d";
                }
                return info;
            } catch (IOException ex) {
                if (firstFailure == null) firstFailure = ex;
            }
        }
        throw firstFailure != null ? firstFailure : new IOException("Unable to load game version");
    }

    /** Mirrors the desktop launcher's IPv4 + default port 23000 behavior. */
    public String resolveGameAddress(String loginAddress) throws Exception {
        String host = loginAddress;
        int port = 23000;
        int colon = loginAddress.lastIndexOf(':');
        if (colon > 0 && colon < loginAddress.length() - 1 && loginAddress.indexOf(':') == colon) {
            host = loginAddress.substring(0, colon);
            port = Integer.parseInt(loginAddress.substring(colon + 1));
        }
        try {
            InetAddress literal = InetAddress.getByName(host);
            if (literal instanceof Inet4Address && isNumericAddress(host)) {
                return literal.getHostAddress() + ":" + port;
            }
        } catch (Exception ignored) {}
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address instanceof Inet4Address) return address.getHostAddress() + ":" + port;
        }
        throw new IOException("No IPv4 address found for " + host);
    }

    private JsonObject getJson(String url) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        return executeJson(request);
    }

    private JsonObject executeJson(Request request) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw apiError(response, text);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IOException("Unexpected response from " + request.url());
            return parsed.getAsJsonObject();
        }
    }

    private static IOException apiError(Response response, String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() > 240) body = body.substring(0, 240) + "…";
        return new IOException("API error " + response.code() + (body.isEmpty() ? "" : ": " + body));
    }

    private static String getString(JsonObject root, String key, String fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
        return root.get(key).getAsString();
    }

    private static String stripJsonString(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            try { return JsonParser.parseString(text).getAsString(); }
            catch (Exception ignored) {}
        }
        return text;
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean isNumericAddress(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c == '.' || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
}
