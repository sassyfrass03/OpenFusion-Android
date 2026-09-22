package dev.openfusion.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Activity-owned audio. All entry points and callbacks run on the main thread. */
public final class LauncherAudio {
    private final SharedPreferences preferences;
    private final AudioManager manager;
    private final AudioFocusRequest focusRequest;
    private MediaPlayer music;
    private SoundPool effects;
    private int tapId;
    private boolean prepared, tapReady, foreground, focused, released, gameSuspended;
    private long lastTap;

    public LauncherAudio(Context context, SharedPreferences preferences) {
        this.preferences = preferences;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes musicAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(musicAttributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(change -> {
                if (released) return;
                // A queued focus-gain callback can arrive after handoff/pause.
                // Do not treat that stale callback as ownership of audio focus.
                if (!foreground || gameSuspended || musicMuted()) { updateMusic(); return; }
                focused = change == AudioManager.AUDIOFOCUS_GAIN;
                if (focused && foreground && !gameSuspended && !musicMuted()) startPrepared();
                else pauseMusic();
            }, new Handler(Looper.getMainLooper())).build();
        try {
            music = new MediaPlayer();
            music.setAudioAttributes(musicAttributes);
            try (AssetFileDescriptor file = context.getAssets().openFd("audio/background.mp3")) {
                music.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            }
            music.setLooping(true);
            music.setVolume(0.45f, 0.45f);
            music.setOnPreparedListener(player -> { prepared = true; updateMusic(); });
            music.setOnErrorListener((player, what, extra) -> {
                prepared = false;
                if (focused) manager.abandonAudioFocusRequest(focusRequest);
                focused = false;
                return true;
            });
            music.prepareAsync();
        } catch (Exception e) {
            if (music != null) music.release();
            music = null;
        }
        try {
            effects = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            effects.setOnLoadCompleteListener((pool, sample, status) -> { if (!released) tapReady = status == 0; });
            try (AssetFileDescriptor file = context.getAssets().openFd("audio/tap.mp3")) {
                tapId = effects.load(file, 1);
            }
        } catch (Exception e) {
            if (effects != null) effects.release();
            effects = null;
        }
    }
    private boolean musicMuted() { return preferences.getBoolean("muteMusic", false); }
    private void startPrepared() {
        if (music != null && prepared && !released) try { music.start(); } catch (IllegalStateException ignored) {}
    }
    private void pauseMusic() {
        if (music != null && prepared) try { if (music.isPlaying()) music.pause(); } catch (IllegalStateException ignored) {}
    }
    private void updateMusic() {
        if (released) return;
        if (!foreground || gameSuspended || musicMuted()) {
            pauseMusic();
            manager.abandonAudioFocusRequest(focusRequest);
            focused = false;
        } else if (music != null && prepared) {
            if (!focused) focused = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (focused) startPrepared();
        }
    }
    public void resume() { foreground = true; gameSuspended = false; updateMusic(); }
    public void suspendForGame() {
        gameSuspended = true;
        updateMusic();
        if (effects != null) effects.autoPause();
    }
    public void pause() {
        foreground = false; updateMusic();
        if (effects != null) effects.autoPause();
    }
    public void setMuted(boolean musicMuted, boolean uiMuted) {
        preferences.edit().putBoolean("muteMusic", musicMuted).putBoolean("muteUiSounds", uiMuted).apply();
        if (uiMuted && effects != null) effects.autoPause();
        updateMusic();
    }
    public void tap() {
        if (released || !foreground || gameSuspended || !tapReady || effects == null || preferences.getBoolean("muteUiSounds", false)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTap < 65) return;
        lastTap = now;
        effects.play(tapId, 0.7f, 0.7f, 1, 0, 1f);
    }
    public void release() {
        if (released) return;
        pause(); released = true;
        if (music != null) { music.release(); music = null; }
        if (effects != null) { effects.release(); effects = null; }
    }
}
