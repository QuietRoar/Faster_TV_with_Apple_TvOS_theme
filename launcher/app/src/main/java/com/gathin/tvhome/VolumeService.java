package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * tvos_style's volume display. On Xiaomi TVs the remote's volume keys never reach apps: the window manager turns
 * them into volume changes itself and System UI draws its own bar on the right edge. So this listens for the
 * changes instead and shows VolumeView in the chosen style, in an accessibility overlay, beside that bar; the Side
 * slider sits exactly over it, opaque, which hides it (Android draws the overlay above System UI's volume window).
 * It takes no keys and reads nothing on screen.
 */
public class VolumeService extends AccessibilityService {
    /** The music stream, as the change broadcasts name it. */
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private AudioManager audio;
    private VolumeView view, badge;
    private final Runnable hide = this::hideDisplay;
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_STREAM, AudioManager.STREAM_MUSIC) != AudioManager.STREAM_MUSIC) return;
            VolumeView.Look look = VolumeView.Look.read(VolumeService.this);
            // The new level comes with the change; reading it back now can still give the old one.
            int level = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
            if (look.enabled) show(look, level);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        filter.addAction("android.media.STREAM_MUTE_CHANGED_ACTION");
        registerReceiver(changes, filter);
    }

    private WindowManager.LayoutParams params(VolumeView v) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = v.gravity();
        lp.x = v.margin();
        lp.y = v.marginTop();
        return lp;
    }

    /** Shows the slider at the current level over System UI's bar; it hides itself after the chosen time. */
    void show(VolumeView.Look look, int newLevel) {
        int level = newLevel >= 0 ? newLevel : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        if (view == null) {
            view = new VolumeView(this);
            view.setLook(look, look.accent(this));
            windows.addView(view, params(view));
        } else {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setLook(look, look.accent(this));
        }
        view.setLevel(level, max, muted);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, VolumeView.showMs(look));
        updateBadge(look, muted || level == 0);
    }

    /** With "stays while muted", a small struck-through speaker keeps to the corner until the sound is back. */
    private void updateBadge(VolumeView.Look look, boolean muted) {
        boolean want = look.stickyMute && muted;
        if (want && badge == null) {
            badge = new VolumeView(this);
            badge.setLook(look, look.accent(this));
            badge.setBadge(true);
            badge.setLevel(0, 1, true);
            windows.addView(badge, params(badge));
        } else if (!want && badge != null) {
            windows.removeView(badge);
            badge = null;
        }
    }

    private void hideDisplay() {
        if (view == null) return;
        VolumeView gone = view;
        view = null;
        // System UI's bar has gone by now (it stays a shorter time), so the fade cannot uncover it.
        gone.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            try {
                windows.removeView(gone);
            } catch (IllegalArgumentException ignored) {
                // Already removed.
            }
        }).start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(changes);
        } catch (IllegalArgumentException ignored) {
            // Never registered.
        }
        if (view != null) windows.removeView(view);
        if (badge != null) windows.removeView(badge);
        super.onDestroy();
    }
}
