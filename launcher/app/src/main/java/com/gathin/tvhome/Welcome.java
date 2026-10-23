package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * tvos_style's welcome banners, one for when the TV starts and one for when it wakes from standby or the
 * screensaver, each with its own switch, emoji, greeting, message and time on screen. The emoji are Microsoft's
 * Fluent 3D emoji (MIT licence, res/drawable-nodpi/emoji_*.png), glossy and lit like Apple's.
 */
final class Welcome {
    static final String START = "start", WAKE = "wake";
    static final String[] EMOJI = {"wave", "smile", "starstruck", "sparkles", "heart", "party", "sun", "sunrise",
            "moon", "house", "popcorn", "tv", "coffee"};
    static final int[] EMOJI_RES = {R.drawable.emoji_wave, R.drawable.emoji_smile, R.drawable.emoji_starstruck,
            R.drawable.emoji_sparkles, R.drawable.emoji_heart, R.drawable.emoji_party,
            R.drawable.emoji_sun, R.drawable.emoji_sunrise, R.drawable.emoji_moon, R.drawable.emoji_house,
            R.drawable.emoji_popcorn, R.drawable.emoji_tv, R.drawable.emoji_coffee};
    static final int[] EMOJI_NAMES = {R.string.emoji_wave, R.string.emoji_smile, R.string.emoji_starstruck,
            R.string.emoji_sparkles, R.string.emoji_heart, R.string.emoji_party, R.string.emoji_sun,
            R.string.emoji_sunrise, R.string.emoji_moon, R.string.emoji_house, R.string.emoji_popcorn, R.string.emoji_tv,
            R.string.emoji_coffee};
    static final int[] SECONDS = {3, 5, 8, 10, 15, 20, 30};

    private Welcome() { }

    private static SharedPreferences prefs(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE);
        // The single banner of an earlier build becomes the start banner.
        if (p.contains("welcome_on") && !p.contains("welcome_start_on")) {
            p.edit().putBoolean("welcome_start_on", p.getBoolean("welcome_on", true))
                    .putString("welcome_start_title", p.getString("welcome_title", ctx.getString(R.string.welcome_default_title)))
                    .putString("welcome_start_message", p.getString("welcome_message", ctx.getString(R.string.welcome_default_message)))
                    .remove("welcome_on").remove("welcome_title").remove("welcome_message").apply();
        }
        return p;
    }

    static boolean on(Context ctx, String when) {
        return prefs(ctx).getBoolean("welcome_" + when + "_on", true);
    }

    static String title(Context ctx, String when) {
        return prefs(ctx).getString("welcome_" + when + "_title",
                ctx.getString(START.equals(when) ? R.string.welcome_default_title : R.string.welcome_default_wake_title));
    }

    static String message(Context ctx, String when) {
        return prefs(ctx).getString("welcome_" + when + "_message",
                ctx.getString(START.equals(when) ? R.string.welcome_default_message : R.string.welcome_default_wake_message));
    }

    /** Index into EMOJI. */
    static int emoji(Context ctx, String when) {
        String key = prefs(ctx).getString("welcome_" + when + "_emoji", START.equals(when) ? "wave" : "sparkles");
        for (int i = 0; i < EMOJI.length; i++) if (EMOJI[i].equals(key)) return i;
        return 0;
    }

    /** Index into SECONDS. */
    static int seconds(Context ctx, String when) {
        int i = prefs(ctx).getInt("welcome_" + when + "_seconds", START.equals(when) ? 2 : 1);
        return i < 0 || i >= SECONDS.length ? 1 : i;
    }

    static void setOn(Context ctx, String when, boolean on) {
        prefs(ctx).edit().putBoolean("welcome_" + when + "_on", on).apply();
    }

    static void setTitle(Context ctx, String when, String text) {
        prefs(ctx).edit().putString("welcome_" + when + "_title", text).apply();
    }

    static void setMessage(Context ctx, String when, String text) {
        prefs(ctx).edit().putString("welcome_" + when + "_message", text).apply();
    }

    static void setEmoji(Context ctx, String when, int index) {
        prefs(ctx).edit().putString("welcome_" + when + "_emoji", EMOJI[index]).apply();
    }

    static void setSeconds(Context ctx, String when, int index) {
        prefs(ctx).edit().putInt("welcome_" + when + "_seconds", index).apply();
    }
}
