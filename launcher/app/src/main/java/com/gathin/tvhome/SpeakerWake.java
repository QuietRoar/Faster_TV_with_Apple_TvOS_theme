package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * Wakes a soundbar on HDMI ARC that has put itself to sleep (speaker_wake). Soundbars such as JBL's switch off
 * after a while with no audio; the TV then plays through its own speakers. Turning the TV's HDMI-CEC off and on again
 * makes it announce itself and ask the soundbar to take the audio, which wakes it (tried on a JBL Cinema SB271 and a
 * OnePlus TV, Android 11). It needs WRITE_SECURE_SETTINGS, which the install script grants.
 *
 * It wakes the soundbar when the volume buttons change the TV's own volume, when Mute is pressed, or when an app starts
 * playing, each only while the soundbar is asleep (no HDMI ARC output), and only on a TV where one has been seen on ARC.
 */
final class SpeakerWake {
    private static final String TAG = "SpeakerWake";
    private static final String ACTION_VOLUME = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_MUTE = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";
    private static final String CEC = "hdmi_control_enabled";
    private static final String PREFS = "tvos", SEEN = "speaker_arc_seen";
    /** AudioDeviceInfo.TYPE_HDMI_EARC, added in Android 12. */
    private static final int TYPE_HDMI_EARC = 29;
    /** CEC stays off this long before it comes back on; long enough for the TV to drop and rebuild the link. */
    private static final long CEC_OFF_MS = 700;
    /** A wake takes a few seconds: further presses in this time are left alone. */
    private static final long COOLDOWN_MS = 10_000;
    /** After a wake from Mute, the sound is unmuted once the soundbar has taken it. */
    private static final long UNMUTE_AFTER_MS = 4_000;

    private final Context ctx;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastWake = -COOLDOWN_MS;
    private BroadcastReceiver keys;
    private AudioManager.AudioPlaybackCallback playback;
    private AudioDeviceCallback devices;
    /** Told when the soundbar comes on or goes to sleep (its ARC output appears or goes), for the Home pill. */
    private Runnable onChange;

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** The Soundbar switch in Settings: the owner's choice, else the theme's speaker_wake. */
    static boolean enabled(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(SWITCH) ? p.getBoolean(SWITCH, false)
                : Config.themeSwitch(ctx, R.bool.speaker_wake, "speaker_wake");
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SWITCH, on).apply();
    }

    private static final String SWITCH = "soundbar_on";

    SpeakerWake(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.audio = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    /** A soundbar is on HDMI ARC now. Remembers that one has been seen, so the TV is known to have one. */
    boolean soundbarOn() {
        if (audio == null) return false;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_HDMI_ARC || (Build.VERSION.SDK_INT >= 31 && type == TYPE_HDMI_EARC)) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SEEN, true).apply();
                return true;
            }
        }
        return false;
    }

    /** This TV has had a soundbar on ARC (now or before): the only TVs where waking means anything. */
    boolean hasSoundbar() {
        return soundbarOn() || ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SEEN, false);
    }

    /** Wakes the soundbar if it is asleep. Returns false when there is nothing to do or the TV refused. */
    boolean wake() {
        if (soundbarOn() || !hasSoundbar()) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWake < COOLDOWN_MS) return true;
        lastWake = now;
        try {
            if (Settings.Global.getInt(ctx.getContentResolver(), CEC, 1) == 0) return false;   // the owner turned CEC off
            Settings.Global.putInt(ctx.getContentResolver(), CEC, 0);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot wake the soundbar, WRITE_SECURE_SETTINGS missing: " + e.getMessage());
            return false;
        }
        main.postDelayed(() -> {
            try {
                Settings.Global.putInt(ctx.getContentResolver(), CEC, 1);
            } catch (SecurityException e) {
                Log.w(TAG, "could not turn HDMI-CEC back on: " + e.getMessage());
            }
        }, CEC_OFF_MS);
        Log.i(TAG, "waking the soundbar");
        return true;
    }

    /** Starts listening for the volume buttons, Mute and playback. Safe to call again. */
    void start() {
        if (keys != null || audio == null) return;
        keys = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
                boolean muted = ACTION_MUTE.equals(intent.getAction()) && intent.getBooleanExtra(EXTRA_MUTED, false);
                if (!wake() || !muted) return;
                // Mute pressed to wake it: the sound comes back on once the soundbar has it.
                main.postDelayed(() -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, 0), UNMUTE_AFTER_MS);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_VOLUME);
        filter.addAction(ACTION_MUTE);
        ctx.registerReceiver(keys, filter);
        playback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                if (!configs.isEmpty()) wake();   // something is playing; wake() does nothing when the soundbar is on
            }
        };
        audio.registerAudioPlaybackCallback(playback, main);
        devices = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                changed();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                changed();
            }
        };
        audio.registerAudioDeviceCallback(devices, main);
        soundbarOn();   // remember a soundbar that is on now
    }

    void stop() {
        if (keys != null) {
            ctx.unregisterReceiver(keys);
            keys = null;
        }
        if (playback != null && audio != null) {
            audio.unregisterAudioPlaybackCallback(playback);
            playback = null;
        }
        if (devices != null && audio != null) {
            audio.unregisterAudioDeviceCallback(devices);
            devices = null;
        }
    }

    private void changed() {
        soundbarOn();   // remembers one that has just come on
        if (onChange != null) onChange.run();
    }
}
