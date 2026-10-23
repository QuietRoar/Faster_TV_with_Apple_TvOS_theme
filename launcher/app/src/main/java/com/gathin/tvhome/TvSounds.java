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
 * tvos_style's own interface sounds in place of Android's click: one as focus moves, one on OK and one on Back,
 * from the chosen pack (res/raw/snd_<pack>_*, synthesised for this launcher by make_sounds.py) at the chosen
 * volume. They follow the TV's "Sound effects" setting, read when Home starts, as Android's own clicks do.
 */
final class TvSounds {
    static final String[] PACKS = {"glass", "pop", "wood", "chime", "crystal", "click"};
    static final int[] PACK_NAMES = {R.string.snd_glass, R.string.snd_pop, R.string.snd_wood, R.string.snd_chime,
            R.string.snd_crystal, R.string.snd_click};
    private static final int[][] RES = {
            {R.raw.snd_glass_move, R.raw.snd_glass_select, R.raw.snd_glass_back},
            {R.raw.snd_pop_move, R.raw.snd_pop_select, R.raw.snd_pop_back},
            {R.raw.snd_wood_move, R.raw.snd_wood_select, R.raw.snd_wood_back},
            {R.raw.snd_chime_move, R.raw.snd_chime_select, R.raw.snd_chime_back},
            {R.raw.snd_crystal_move, R.raw.snd_crystal_select, R.raw.snd_crystal_back},
            {R.raw.snd_click_move, R.raw.snd_click_select, R.raw.snd_click_back}};
    static final float[] VOLUMES = {0.25f, 0.55f, 1f};
    static final int[] VOLUME_NAMES = {R.string.snd_soft, R.string.snd_normal, R.string.snd_loud};

    private static SoundPool pool;
    /** Loaded sound ids, [pack][move, select, back]. */
    private static final int[][] ids = new int[PACKS.length][3];
    /** The welcome and goodbye chimes, apart from the packs. */
    private static int welcomeId, goodbyeId;
    /** They play at this share of the chosen volume: very soft. */
    private static final float GREETING_LEVEL = 0.45f;
    private static boolean on, back = true;
    private static int pack, volume = 1;

    private TvSounds() { }

    static int pack(Context ctx) {
        int p = ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getInt("sound_pack", 0);
        return p < 0 || p >= PACKS.length ? 0 : p;
    }

    static int volume(Context ctx) {
        int v = ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getInt("sound_volume", 1);
        return v < 0 || v >= VOLUMES.length ? 1 : v;
    }

    static boolean backSound(Context ctx) {
        return ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getBoolean("sound_back", true);
    }

    /** The Sounds page's choices, saved and used at once. */
    static void choose(Context ctx, int newPack, int newVolume, boolean newBack) {
        ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).edit().putInt("sound_pack", newPack)
                .putInt("sound_volume", newVolume).putBoolean("sound_back", newBack).apply();
        pack = newPack;
        volume = newVolume;
        back = newBack;
    }

    /** Loads every pack once (some 190 KB of short sounds), so changing pack or previewing costs nothing. */
    private static void load(Context context) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        for (int p = 0; p < PACKS.length; p++) {
            for (int k = 0; k < 3; k++) ids[p][k] = pool.load(context.getApplicationContext(), RES[p][k], 1);
        }
        welcomeId = pool.load(context.getApplicationContext(), R.raw.snd_welcome, 1);
        goodbyeId = pool.load(context.getApplicationContext(), R.raw.snd_goodbye, 1);
    }

    static void init(Context context) {
        on = Settings.System.getInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
        pack = pack(context);
        volume = volume(context);
        back = backSound(context);
        if (on) load(context);
    }

    /** The Sound effects switch in the launcher's Settings: on loads the sounds, off silences them at once. */
    static void setEnabled(Context context, boolean enabled) {
        on = enabled;
        if (enabled) init(context);
    }

    static boolean active() {
        return on && pool != null;
    }

    private static void play(int kind, float level) {
        float v = level * VOLUMES[volume];
        pool.play(ids[pack][kind], v, v, 1, 0, 1f);
    }

    static void move() {
        if (active()) play(0, 0.8f);
    }

    static void select() {
        if (active()) play(1, 1f);
    }

    static void back() {
        if (active() && back) play(2, 0.9f);
    }

    static boolean welcomeOn(Context ctx) {
        return ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getBoolean("sound_welcome", true);
    }

    static boolean goodbyeOn(Context ctx) {
        return ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getBoolean("sound_goodbye", true);
    }

    static void setGreetings(Context ctx, boolean welcome, boolean goodbye) {
        ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).edit().putBoolean("sound_welcome", welcome)
                .putBoolean("sound_goodbye", goodbye).apply();
    }

    /** The soft rising chime, with the welcome banner. force: the Sounds page's preview, even with sounds off. */
    static void welcome(Context ctx, boolean force) {
        if (!force && (!active() || !welcomeOn(ctx))) return;
        load(ctx);
        float v = GREETING_LEVEL * VOLUMES[volume];
        pool.play(welcomeId, v, v, 2, 0, 1f);
    }

    /** The soft falling chime, before Power off puts the TV to sleep. */
    static void goodbye(Context ctx, boolean force) {
        if (!force && (!active() || !goodbyeOn(ctx))) return;
        load(ctx);
        float v = GREETING_LEVEL * VOLUMES[volume];
        pool.play(goodbyeId, v, v, 2, 0, 1f);
    }

    /** Plays a pack's OK sound at a volume, for choosing on the Sounds page, even with sounds off. */
    static void preview(Context context, int previewPack, int previewVolume) {
        load(context);
        float v = VOLUMES[previewVolume];
        pool.play(ids[previewPack][1], v, v, 1, 0, 1f);
    }

    /** OK pressed: the tock on the first press, not on the key's auto-repeat. */
    static void onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            select();
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            back();
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
