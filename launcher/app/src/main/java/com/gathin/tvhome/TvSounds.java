package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * tvos_style's own interface sounds in place of Android's click: a light tick as focus moves and a rounder tock
 * on OK (res/raw, synthesised for this launcher). They follow the TV's "Sound effects" setting, read when Home
 * starts, as Android's own clicks do.
 */
final class TvSounds {
    private static SoundPool pool;
    private static int move, select;
    private static boolean on;

    private TvSounds() { }

    static void init(Context context) {
        on = Settings.System.getInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
        if (pool != null || !on) return;
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        move = pool.load(context, R.raw.tvos_move, 1);
        select = pool.load(context, R.raw.tvos_select, 1);
    }

    /** The Sound effects switch in the launcher's Settings: on loads the sounds, off silences them at once. */
    static void setEnabled(Context context, boolean enabled) {
        on = enabled;
        if (enabled && pool == null) init(context);
    }

    static boolean active() {
        return on && pool != null;
    }

    static void move() {
        if (active()) pool.play(move, 0.45f, 0.45f, 1, 0, 1f);
    }

    static void select() {
        if (active()) pool.play(select, 0.6f, 0.6f, 1, 0, 1f);
    }

    /** OK pressed: the tock on the first press, not on the key's auto-repeat. */
    static void onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            select();
        }
    }

    /** Switches Android's click off for a view and everything inside it, so only these sounds play. */
    static void mute(View view) {
        if (!active()) return;
        view.setSoundEffectsEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) mute(group.getChildAt(i));
        }
    }

    /**
     * A popup's content wrapped so it sounds like Home: a popup is a window of its own, so neither Home's key
     * handling nor its focus listener hear what happens in it.
     */
    static View wrap(View content) {
        if (!active()) return content;
        FrameLayout frame = new FrameLayout(content.getContext()) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                onKey(event);
                return super.dispatchKeyEvent(event);
            }
        };
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (oldFocus != null && newFocus != null) move();
        });
        // Rows and buttons are added after the popup is made, so mute them once they are all in.
        frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                mute(v);
            }

            @Override public void onViewDetachedFromWindow(View v) { }
        });
        return frame;
    }
}
