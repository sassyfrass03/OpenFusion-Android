package com.winlator.cmod;

import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.container.Container;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Binds the native Android launcher UI to OpenFusion's HTTP API and Ludashi runtime. */
public final class OpenFusionNativeLauncher {
    private static final String PREF_REFRESH_ORIGINAL = "openfusion_refresh_original";
    private static final String PREF_REFRESH_ACADEMY = "openfusion_refresh_academy";
    private static final String PREF_USERNAME_ORIGINAL = "openfusion_username_original";
    private static final String PREF_USERNAME_ACADEMY = "openfusion_username_academy";

    private static final OpenFusionApiClient.ServerChoice[] SERVERS = new OpenFusionApiClient.ServerChoice[]{
            new OpenFusionApiClient.ServerChoice("OpenFusion Public - Original", "api.dexlabs.systems"),
            new OpenFusionApiClient.ServerChoice("OpenFusion Public - Academy", "api.dexlabs.systems/academy")
    };

    private final MainActivity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OpenFusionApiClient api = new OpenFusionApiClient();
    private final SharedPreferences prefs;

    private Spinner serverSpinner;
    private EditText username;
    private EditText password;
    private CheckBox remember;
    private Button play;
    private ProgressBar progress;
    private TextView status;
    private Container runtimeContainer;

    public OpenFusionNativeLauncher(MainActivity activity) {
        this.activity = activity;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    public void bind() {
        serverSpinner = activity.findViewById(R.id.OFServerSpinner);
        username = activity.findViewById(R.id.OFUsername);
        password = activity.findViewById(R.id.OFPassword);
        remember = activity.findViewById(R.id.OFRemember);
        play = activity.findViewById(R.id.OFPlay);
        progress = activity.findViewById(R.id.OFProgress);
        status = activity.findViewById(R.id.OFStatus);

        ArrayAdapter<OpenFusionApiClient.ServerChoice> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, SERVERS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        serverSpinner.setAdapter(adapter);
        serverSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                username.setText(prefs.getString(usernamePref(SERVERS[position]), ""));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        play.setEnabled(false);
        play.setOnClickListener(v -> play());
        setBusy(true, "Preparing compatibility runtime…");
        OpenFusionBootstrap.ensureInstalled(activity, container -> {
            runtimeContainer = container;
            setBusy(false, container != null ? "Ready" : "Runtime setup failed");
            play.setEnabled(container != null);
        });
    }

    private void play() {
        OpenFusionApiClient.ServerChoice server = (OpenFusionApiClient.ServerChoice) serverSpinner.getSelectedItem();
        String user = username.getText().toString().trim();
        String pass = password.getText().toString();
        if (user.isEmpty()) {
            username.setError("Username required");
            return;
        }

        String savedRefresh = prefs.getString(refreshPref(server), "");
        boolean canReuse = !savedRefresh.isEmpty() && pass.isEmpty();
        if (!canReuse && pass.isEmpty()) {
            password.setError("Password required");
            return;
        }

        setBusy(true, canReuse ? "Signing in…" : "Logging in…");
        executor.execute(() -> {
            try {
                String refresh = savedRefresh;
                if (!canReuse) {
                    refresh = api.login(server.endpoint, user, pass);
                    if (remember.isChecked()) {
                        prefs.edit()
                                .putString(refreshPref(server), refresh)
                                .putString(usernamePref(server), user)
                                .apply();
                    }
                }

                OpenFusionApiClient.Session session;
                try {
                    session = api.getSession(server.endpoint, refresh);
                } catch (Exception sessionFailure) {
                    // A stored refresh token can expire. Ask for the password rather than looping.
                    if (canReuse) {
                        prefs.edit().remove(refreshPref(server)).apply();
                        throw new Exception("Saved login expired. Enter your password again.", sessionFailure);
                    }
                    throw sessionFailure;
                }

                updateStatus("Reading server configuration…");
                OpenFusionApiClient.ServerInfo serverInfo = api.getInfo(server.endpoint);
                String versionUuid = serverInfo.versions.get(0);
                OpenFusionApiClient.VersionInfo version = api.getVersion(server.endpoint, versionUuid);
                OpenFusionApiClient.GameCookie cookie = api.getCookie(server.endpoint, session.sessionToken);
                String gameAddress = api.resolveGameAddress(serverInfo.loginAddress);

                OpenFusionBootstrap.LaunchSpec spec = new OpenFusionBootstrap.LaunchSpec();
                spec.serverName = serverInfo.serverName;
                spec.endpoint = server.endpoint;
                spec.gameAddress = gameAddress;
                spec.username = cookie.username.isEmpty() ? session.username : cookie.username;
                spec.cookie = cookie.cookie;
                spec.assetUrl = ensureTrailingSlash(version.assetUrl);
                spec.mainUrl = version.mainFileUrl;
                spec.versionId = version.uuid;
                spec.customLoadingScreen = serverInfo.customLoadingScreen;
                spec.width = 1280;
                spec.height = 720;

                activity.runOnUiThread(() -> {
                    setBusy(false, "Launching " + spec.serverName + "…");
                    OpenFusionBootstrap.launchFfrunner(activity, runtimeContainer, spec);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    setBusy(false, "Login/launch failed");
                    Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setBusy(boolean busy, String text) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        serverSpinner.setEnabled(!busy);
        username.setEnabled(!busy);
        password.setEnabled(!busy);
        remember.setEnabled(!busy);
        play.setEnabled(!busy && runtimeContainer != null);
        status.setText(text);
    }

    private void updateStatus(String text) {
        activity.runOnUiThread(() -> status.setText(text));
    }

    private static String refreshPref(OpenFusionApiClient.ServerChoice server) {
        return server.endpoint.endsWith("/academy") ? PREF_REFRESH_ACADEMY : PREF_REFRESH_ORIGINAL;
    }

    private static String usernamePref(OpenFusionApiClient.ServerChoice server) {
        return server.endpoint.endsWith("/academy") ? PREF_USERNAME_ACADEMY : PREF_USERNAME_ORIGINAL;
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}
