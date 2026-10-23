package com.gathin.tvhome;

import android.content.Context;

/** tvos_style's header clock: its style, and its place (beside the profile picture, or left of the pills). */
final class Clock {
    static final int LIGHT = 0, BOLD = 1, WITH_DATE = 2, GLASS = 3, LARGE = 4, WIDGET = 5;
    static final int[] STYLE_NAMES = {R.string.clock_light, R.string.clock_bold, R.string.clock_date,
            R.string.clock_glass, R.string.clock_large, R.string.clock_widget};
    static final int RIGHT = 0, LEFT = 1;
    static final int[] PLACE_NAMES = {R.string.clock_right, R.string.clock_left};

    private Clock() { }

    static int style(Context ctx) {
        int s = ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getInt("clock_style", LIGHT);
        return s < 0 || s >= STYLE_NAMES.length ? LIGHT : s;
    }

    static int place(Context ctx) {
        int p = ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).getInt("clock_place", RIGHT);
        return p < 0 || p >= PLACE_NAMES.length ? RIGHT : p;
    }

    static void setStyle(Context ctx, int style) {
        ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).edit().putInt("clock_style", style).apply();
    }

    static void setPlace(Context ctx, int place) {
        ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).edit().putInt("clock_place", place).apply();
    }
}
