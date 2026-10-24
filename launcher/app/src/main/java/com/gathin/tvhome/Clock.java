package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * tvos_style's clock: its style, and its place, small in the header (beside the profile picture, or left of the
 * symbols) or big in the open sky above the apps (top left, or centred), or hidden. Each theme keeps its own:
 * a theme may come with a clock of its own, and a change made while it is in use stays with it. A theme without
 * one uses the choice of earlier builds, which had a single clock for every theme.
 */
final class Clock {
    static final int LIGHT = 0, BOLD = 1, WITH_DATE = 2, GLASS = 3, LARGE = 4, WIDGET = 5, STACKED = 6, DAY = 7;
    static final int[] STYLE_NAMES = {R.string.clock_light, R.string.clock_bold, R.string.clock_date,
            R.string.clock_glass, R.string.clock_large, R.string.clock_widget, R.string.clock_stacked, R.string.clock_day};
    static final int RIGHT = 0, LEFT = 1, TOP_LEFT = 2, CENTRE = 3, HIDDEN = 4;
    static final int[] PLACE_NAMES = {R.string.clock_right, R.string.clock_left, R.string.clock_top_left,
            R.string.clock_centre, R.string.clock_hidden};

    private Clock() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE);
    }

    private static int read(Context ctx, Themes.Theme theme, String what, int themeOwn, int count, int fallback) {
        SharedPreferences p = prefs(ctx);
        int v = p.contains("theme_" + what + "_" + theme.id) ? p.getInt("theme_" + what + "_" + theme.id, fallback)
                : themeOwn >= 0 ? themeOwn : p.getInt(what, fallback);
        return v < 0 || v >= count ? fallback : v;
    }

    static int style(Context ctx) {
        return style(ctx, Themes.current(ctx));
    }

    static int place(Context ctx) {
        return place(ctx, Themes.current(ctx));
    }

    static int style(Context ctx, Themes.Theme theme) {
        return read(ctx, theme, "clock_style", theme.clockStyle, STYLE_NAMES.length, LIGHT);
    }

    static int place(Context ctx, Themes.Theme theme) {
        return read(ctx, theme, "clock_place", theme.clockPlace, PLACE_NAMES.length, RIGHT);
    }

    /** A big clock in the sky, rather than the small one in the header. */
    static boolean big(int place) {
        return place == TOP_LEFT || place == CENTRE;
    }

    /** The header has one line of room: Stacked shows as Light there, and Day as the time and date. */
    static int inHeader(int style) {
        return style == STACKED ? LIGHT : style == DAY ? WITH_DATE : style;
    }

    static void setStyle(Context ctx, int style) {
        setStyle(ctx, Themes.current(ctx), style);
    }

    static void setPlace(Context ctx, int place) {
        setPlace(ctx, Themes.current(ctx), place);
    }

    static void setStyle(Context ctx, Themes.Theme theme, int style) {
        prefs(ctx).edit().putInt("theme_clock_style_" + theme.id, style).apply();
    }

    static void setPlace(Context ctx, Themes.Theme theme, int place) {
        prefs(ctx).edit().putInt("theme_clock_place_" + theme.id, place).apply();
    }
}
