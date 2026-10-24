package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * tvos_style's comfort choices: the night clock
 * and its hours, one-tap Night mode, and clearing background apps when the TV goes to standby.
 */
final class Comfort {
    /** Night mode: the volume is lowered to this share of what it was, and a sleep timer is set for this long. */
    static final float NIGHT_VOLUME = 0.5f;
    static final int NIGHT_TIMER_MINUTES = 60;

    private Comfort() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE);
    }

    static boolean nightClock(Context ctx) {
        return prefs(ctx).getBoolean("night_clock", false);
    }

    static int nightFrom(Context ctx) {
        return prefs(ctx).getInt("night_from", 23);
    }

    static int nightUntil(Context ctx) {
        return prefs(ctx).getInt("night_until", 6);
    }

    static void setNightClock(Context ctx, boolean on, int from, int until) {
        prefs(ctx).edit().putBoolean("night_clock", on).putInt("night_from", from).putInt("night_until", until).apply();
    }

    /** Within the night clock's hours now (from 23 until 6 runs over midnight). */
    static boolean isNightHours(Context ctx) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int from = nightFrom(ctx), until = nightUntil(ctx);
        return from == until || (from < until ? hour >= from && hour < until : hour >= from || hour < until);
    }

    static boolean nightMode(Context ctx) {
        return prefs(ctx).getBoolean("night_mode", false);
    }

    /** Night mode on (keeping the volume to go back to) or off. */
    static void setNightMode(Context ctx, boolean on, int previousVolume) {
        SharedPreferences.Editor e = prefs(ctx).edit().putBoolean("night_mode", on);
        if (on) e.putInt("night_prev_volume", previousVolume);
        e.apply();
    }

    static int nightPreviousVolume(Context ctx) {
        return prefs(ctx).getInt("night_prev_volume", -1);
    }

    static boolean clearOnStandby(Context ctx) {
        return prefs(ctx).getBoolean("clear_on_standby", false);
    }

    static void setClearOnStandby(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("clear_on_standby", on).apply();
    }
}
