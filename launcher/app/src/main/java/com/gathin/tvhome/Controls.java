package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * tvos_style's controls and where each one shows: in Control Center (in the owner's order), as a round pill
 * beside the clock on Home, both or neither. Wi-Fi, Bluetooth and the sleep timer are status controls: on Home
 * they show only while on. The choices are kept with the rest of the tvos_style ones.
 */
final class Controls {
    static final String POWER = "power", WIFI = "wifi", INPUTS = "inputs", BLUETOOTH = "bluetooth", SLEEP = "sleep",
            SOUND = "sound", APPS = "apps", ABOUT = "about", SETTINGS = "settings", ASSISTANT = "assistant",
            HOME_SETTINGS = "home_settings", REORDER = "reorder", GLOSS = "gloss", BOOST = "boost", THEMES = "themes",
            WELCOME = "welcome", NIGHT = "night";
    /** Every control, in the order "More controls" lists them. */
    static final List<String> ALL = Arrays.asList(POWER, WIFI, INPUTS, BLUETOOTH, SLEEP, NIGHT, BOOST, SOUND, APPS, ABOUT,
            THEMES, WELCOME, SETTINGS, ASSISTANT, HOME_SETTINGS, REORDER, GLOSS);
    private static final String DEFAULT_CC = "power,wifi,inputs,bluetooth,sleep,night,boost,home_settings,settings,assistant,gloss";
    private static final String DEFAULT_HOME = "wifi,bluetooth,sleep,night";
    private static final String PREFS = "tvos", CC = "cc_items", HOME = "home_items";

    /**
     * A control's size in Control Center, the owner's choice: half a row (two side by side), the whole row, or a
     * round button (four to a row, on the same columns). The Gloss slider always takes the whole row.
     */
    static final int WIDE = 0, FULL = 1, ROUND = 2;
    static final int[] SIZE_NAMES = {R.string.cc_size_wide, R.string.cc_size_full, R.string.cc_size_round};

    private Controls() { }

    static int size(Context ctx, String key) {
        if (GLOSS.equals(key)) return FULL;
        int v = prefs(ctx).getInt("cc_size_" + key, defaultSize(key));
        return v < 0 || v >= SIZE_NAMES.length ? defaultSize(key) : v;
    }

    static void setSize(Context ctx, String key, int size) {
        prefs(ctx).edit().putInt("cc_size_" + key, size).apply();
    }

    /** Shortcuts are round buttons; controls with a state to show (a network, a device, the time left) are pills. */
    private static int defaultSize(String key) {
        switch (key) {
            case POWER: case SETTINGS: case ASSISTANT: case HOME_SETTINGS: case REORDER: case THEMES: case WELCOME:
                return ROUND;
            default:
                return WIDE;
        }
    }

    /** On Home only while it is on (connected, playing to a speaker, counting down). */
    static boolean status(String key) {
        return WIFI.equals(key) || BLUETOOTH.equals(key) || SLEEP.equals(key) || NIGHT.equals(key);
    }

    /** The Gloss slider lives in Control Center only: a pill has nowhere to slide. */
    static boolean canBeOnHome(String key) {
        return !GLOSS.equals(key);
    }

    static int icon(String key) {
        switch (key) {
            case POWER: return R.drawable.ic_power;
            case WIFI: return R.drawable.ic_wifi;
            case INPUTS: return R.drawable.ic_input;
            case BLUETOOTH: return R.drawable.ic_bluetooth;
            case SLEEP: return R.drawable.ic_sleep;
            case SOUND: return R.drawable.ic_volume;
            case APPS: return R.drawable.ic_apps;
            case ABOUT: return R.drawable.ic_info;
            case SETTINGS: return R.drawable.ic_settings;
            case ASSISTANT: return R.drawable.ic_mic;
            case HOME_SETTINGS: return R.drawable.ic_tune;
            case REORDER: return R.drawable.ic_swap_vert;
            case NIGHT: return R.drawable.ic_night;
            case BOOST: return R.drawable.ic_boost;
            case THEMES: return R.drawable.ic_themes;
            case WELCOME: return R.drawable.ic_wave;
            default: return R.drawable.ic_gloss;
        }
    }

    static int label(String key) {
        switch (key) {
            case POWER: return R.string.cc_power;
            case WIFI: return R.string.wifi;
            case INPUTS: return R.string.inputs;
            case BLUETOOTH: return R.string.bluetooth;
            case SLEEP: return R.string.cc_sleep_timer;
            case SOUND: return R.string.set_sound;
            case APPS: return R.string.set_apps;
            case ABOUT: return R.string.set_about;
            case SETTINGS: return R.string.settings;
            case ASSISTANT: return R.string.cc_assistant;
            case HOME_SETTINGS: return R.string.cc_home_settings;
            case REORDER: return R.string.cc_reorder;
            case NIGHT: return R.string.night_mode;
            case BOOST: return R.string.boost;
            case THEMES: return R.string.themes;
            case WELCOME: return R.string.welcome;
            default: return R.string.cc_gloss;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> read(Context ctx, String name, String fallback) {
        List<String> keys = new ArrayList<>();
        for (String key : TextUtils.split(prefs(ctx).getString(name, fallback), ",")) {
            if (ALL.contains(key) && !keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    /** Control Center's controls, in order. A control added in an update joins once, after the sleep timer. */
    static List<String> controlCenter(Context ctx) {
        offerNight(ctx);
        return read(ctx, CC, DEFAULT_CC);
    }

    /** Night mode came in an update: it joins saved lists once, after the sleep timer, in each that exists. */
    private static void offerNight(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getBoolean("offered_night2", false)) return;
        p.edit().putBoolean("offered_night2", true).apply();
        if (p.contains(CC)) {
            List<String> keys = read(ctx, CC, DEFAULT_CC);
            if (!keys.contains(NIGHT)) {
                int at = keys.indexOf(SLEEP);
                keys.add(at < 0 ? keys.size() : at + 1, NIGHT);
                save(ctx, CC, keys);
            }
        }
        if (p.contains(HOME)) {
            List<String> home = read(ctx, HOME, DEFAULT_HOME);
            if (!home.contains(NIGHT)) {
                int at = home.indexOf(SLEEP);
                home.add(at < 0 ? 0 : at + 1, NIGHT);
                save(ctx, HOME, home);
            }
        }
    }

    /** The controls with a pill on Home, in order. Earlier builds kept hidden status pills in "status_hidden". */
    static List<String> home(Context ctx) {
        offerNight(ctx);
        SharedPreferences p = prefs(ctx);
        if (!p.contains(HOME) && p.contains("status_hidden")) {
            List<String> keys = read(ctx, HOME, DEFAULT_HOME);
            keys.removeAll(p.getStringSet("status_hidden", java.util.Collections.emptySet()));
            save(ctx, HOME, keys);
        }
        return read(ctx, HOME, DEFAULT_HOME);
    }

    private static void save(Context ctx, String name, List<String> keys) {
        prefs(ctx).edit().putString(name, TextUtils.join(",", keys)).apply();
    }

    static void setInControlCenter(Context ctx, String key, boolean in) {
        List<String> keys = controlCenter(ctx);
        keys.remove(key);
        if (in) keys.add(key);
        save(ctx, CC, keys);
    }

    static void setOnHome(Context ctx, String key, boolean on) {
        List<String> keys = home(ctx);
        keys.remove(key);
        if (on && canBeOnHome(key)) keys.add(key);
        save(ctx, HOME, keys);
    }

    /** Moves a control one place up (-1) or down (+1) in Control Center's order. */
    static void move(Context ctx, String key, int delta) {
        List<String> keys = controlCenter(ctx);
        int from = keys.indexOf(key);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= keys.size()) return;
        keys.remove(from);
        keys.add(to, key);
        save(ctx, CC, keys);
    }
}
