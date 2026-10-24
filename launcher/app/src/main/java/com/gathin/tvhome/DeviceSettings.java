package com.gathin.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.LocationManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * tvos_style's own pages for Android's device settings: which screensaver, text size, animation speed, date and
 * time, keyboard, location and the TV's name. The launcher writes them itself with the WRITE_SETTINGS and
 * WRITE_SECURE_SETTINGS that build-and-install.sh grants; each write says whether Android took it, so a page can
 * offer Android's own screen instead. The settings no app can change (language, picture, reset) are Android's own
 * pages, opened directly from ANDROID_PAGES. Screensaver and sleep times stay in SettingsSheet's choice rows.
 */
final class DeviceSettings {
    private DeviceSettings() { }

    static final float[] FONT_SCALES = {0.85f, 1f, 1.15f, 1.3f};
    static final int[] FONT_NAMES = {R.string.dev_text_small, R.string.dev_text_default, R.string.dev_text_large,
            R.string.dev_text_largest};
    /** Animation speed: Android's three animation scales together. Off makes every app open at once. */
    static final float[] ANIMATION_SCALES = {0f, 0.5f, 1f, 1.5f};
    static final int[] ANIMATION_NAMES = {R.string.dev_anim_off, R.string.dev_anim_fast, R.string.dev_anim_normal,
            R.string.dev_anim_slow};
    private static final String[] ANIMATION_KEYS = {Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE};
    /** 0 follows the language, 1 is 12-hour, 2 is 24-hour. */
    static final int[] HOUR_NAMES = {R.string.dev_hours_auto, R.string.dev_hours_12, R.string.dev_hours_24};
    private static final String SCREENSAVER_ON = "screensaver_enabled", SCREENSAVER = "screensaver_components",
            DEVICE_NAME = "device_name";

    /** Index of the choice nearest to a value, so a value set elsewhere still shows as the closest choice. */
    static int nearest(float[] choices, float value) {
        int best = 0;
        for (int i = 1; i < choices.length; i++) {
            if (Math.abs(choices[i] - value) < Math.abs(choices[best] - value)) best = i;
        }
        return best;
    }

    // ---- screensaver --------------------------------------------------------------------------------------

    /** An installed screensaver; a null component is "None". */
    static final class Dream {
        final String label;
        final ComponentName component;

        Dream(String label, ComponentName component) {
            this.label = label;
            this.component = component;
        }
    }

    /** "None", then every screensaver installed. */
    static List<Dream> dreams(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Dream> out = new ArrayList<>();
        out.add(new Dream(ctx.getString(R.string.dev_screensaver_none), null));
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.service.dreams.DreamService"), 0)) {
            out.add(new Dream(String.valueOf(r.loadLabel(pm)),
                    new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name)));
        }
        return out;
    }

    /** Index into dreams(): the one in use, 0 (None) when the screensaver is off. */
    static int currentDream(Context ctx, List<Dream> dreams) {
        if (Settings.Secure.getInt(ctx.getContentResolver(), SCREENSAVER_ON, 1) == 0) return 0;
        String now = Settings.Secure.getString(ctx.getContentResolver(), SCREENSAVER);
        ComponentName first = now == null ? null : ComponentName.unflattenFromString(now.split(",")[0]);
        for (int i = 1; i < dreams.size(); i++) if (dreams.get(i).component.equals(first)) return i;
        return 0;
    }

    static boolean setDream(Context ctx, Dream dream) {
        if (dream.component == null) return putSecure(ctx, SCREENSAVER_ON, "0");
        return putSecure(ctx, SCREENSAVER, dream.component.flattenToShortString()) && putSecure(ctx, SCREENSAVER_ON, "1");
    }

    // ---- text and animations ----------------------------------------------------------------------------

    static float fontScale(Context ctx) {
        return Settings.System.getFloat(ctx.getContentResolver(), Settings.System.FONT_SCALE, 1f);
    }

    static boolean setFontScale(Context ctx, float scale) {
        try {
            return Settings.System.putFloat(ctx.getContentResolver(), Settings.System.FONT_SCALE, scale);
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static float animationScale(Context ctx) {
        return Settings.Global.getFloat(ctx.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    static boolean setAnimationScale(Context ctx, float scale) {
        boolean all = true;
        for (String key : ANIMATION_KEYS) all &= putGlobal(ctx, key, String.valueOf(scale));
        return all;
    }

    // ---- date and time --------------------------------------------------------------------------------------

    static boolean autoTime(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AUTO_TIME, 1) == 1;
    }

    static boolean setAutoTime(Context ctx, boolean on) {
        return putGlobal(ctx, Settings.Global.AUTO_TIME, on ? "1" : "0");
    }

    static boolean autoZone(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 1) == 1;
    }

    static boolean setAutoZone(Context ctx, boolean on) {
        return putGlobal(ctx, Settings.Global.AUTO_TIME_ZONE, on ? "1" : "0");
    }

    static int hours(Context ctx) {
        String v = Settings.System.getString(ctx.getContentResolver(), Settings.System.TIME_12_24);
        return "12".equals(v) ? 1 : "24".equals(v) ? 2 : 0;
    }

    static boolean setHours(Context ctx, int choice) {
        try {
            return Settings.System.putString(ctx.getContentResolver(), Settings.System.TIME_12_24,
                    choice == 1 ? "12" : choice == 2 ? "24" : null);
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static String timeZone() {
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        return zone.getDisplayName(zone.inDaylightTime(new java.util.Date()), java.util.TimeZone.LONG);
    }

    // ---- keyboard ---------------------------------------------------------------------------------------------

    /** The keyboards installed, Google's remote-control bridge left out: it is no keyboard to type on. */
    static List<InputMethodInfo> keyboards(Context ctx) {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> out = new ArrayList<>();
        if (imm == null) return out;
        for (InputMethodInfo ime : imm.getInputMethodList()) {
            if (!ime.getPackageName().equals("com.google.android.tv.remote.service")) out.add(ime);
        }
        return out;
    }

    static String currentKeyboard(Context ctx) {
        return Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    /** Makes a keyboard the one that comes up, turning it on first if it was off. */
    static boolean setKeyboard(Context ctx, InputMethodInfo ime) {
        String id = ime.getId();
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        List<String> ids = new ArrayList<>(Arrays.asList(TextUtils.isEmpty(enabled) ? new String[0] : enabled.split(":")));
        boolean ok = true;
        if (!ids.contains(id)) {
            ids.add(id);
            ok = putSecure(ctx, Settings.Secure.ENABLED_INPUT_METHODS, TextUtils.join(":", ids));
        }
        return ok && putSecure(ctx, Settings.Secure.DEFAULT_INPUT_METHOD, id);
    }

    // ---- location and name ---------------------------------------------------------------------------------

    static boolean location(Context ctx) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    /** LocationManager.setLocationEnabledForUser is a system API; WRITE_SECURE_SETTINGS is what it checks. */
    static boolean setLocation(Context ctx, boolean on) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            LocationManager.class.getMethod("setLocationEnabledForUser", boolean.class, UserHandle.class)
                    .invoke(lm, on, Process.myUserHandle());
            return location(ctx) == on;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static String deviceName(Context ctx) {
        String name = Settings.Global.getString(ctx.getContentResolver(), DEVICE_NAME);
        return name != null ? name : android.os.Build.MODEL;
    }

    /** The name phones see when casting and in their Bluetooth lists. */
    static boolean setDeviceName(Context ctx, String name) {
        String clean = name.trim();
        if (clean.isEmpty()) return false;
        boolean ok = putGlobal(ctx, DEVICE_NAME, clean);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            if (adapter != null) adapter.setName(clean);
        } catch (SecurityException ignored) {
            // The Bluetooth name stays as it was.
        }
        return ok;
    }

    // ---- Android's own pages ------------------------------------------------------------------------------

    /** An Android settings page: its name, symbol, and where it is (a component this TV has, or a standard action). */
    static final class Page {
        final int name, icon;
        final String component, action;

        Page(int name, int icon, String component, String action) {
            this.name = name;
            this.icon = icon;
            this.component = component;
            this.action = action;
        }

        /** The intent, or null when this TV has no such page. */
        Intent intent(Context ctx) {
            PackageManager pm = ctx.getPackageManager();
            if (component != null) {
                Intent i = new Intent().setComponent(ComponentName.unflattenFromString(component));
                if (pm.resolveActivity(i, 0) != null) return i;
            }
            if (action != null) {
                Intent i = new Intent(action);
                if (pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) return i;
            }
            return null;
        }
    }

    static final Page PICTURE = new Page(R.string.and_picture, R.drawable.ic_picture,
            "com.droidlogic.tv.settings/.display.DisplayActivity", Settings.ACTION_DISPLAY_SETTINGS);

    /** Every Android page the launcher does not do itself, grouped as Android TV lists them. */
    static final List<Page> ANDROID_PAGES = Arrays.asList(
            PICTURE,
            new Page(R.string.and_sound, R.drawable.ic_volume,
                    "com.android.tv.settings/com.xiaomi.mitv.settings.sound.SoundActivity", Settings.ACTION_SOUND_SETTINGS),
            new Page(R.string.and_sound_mode, R.drawable.ic_volume,
                    "com.droidlogic.tv.soundeffectsettings/.SoundModeActivity", null),
            new Page(R.string.and_network, R.drawable.ic_wifi, "com.android.tv.settings/.connectivity.NetworkActivity",
                    Settings.ACTION_WIFI_SETTINGS),
            new Page(R.string.and_accounts, R.drawable.ic_person, null, Settings.ACTION_SYNC_SETTINGS),
            new Page(R.string.and_language, R.drawable.ic_info, "com.android.tv.settings/.system.LanguageActivity",
                    Settings.ACTION_LOCALE_SETTINGS),
            new Page(R.string.and_region, R.drawable.ic_info,
                    "com.android.tv.settings/com.xiaomi.mitv.settings.patchwall.RegionSettingsActivity", null),
            new Page(R.string.and_font, R.drawable.ic_tune,
                    "com.android.tv.settings/com.xiaomi.mitv.settings.font.FontSwitchActivity", null),
            new Page(R.string.and_keyboard, R.drawable.ic_tune, "com.android.tv.settings/.inputmethod.KeyboardActivity",
                    Settings.ACTION_INPUT_METHOD_SETTINGS),
            new Page(R.string.and_date, R.drawable.ic_clock, "com.android.tv.settings/.system.DateTimeActivity",
                    Settings.ACTION_DATE_SETTINGS),
            new Page(R.string.and_captions, R.drawable.ic_info, "com.android.tv.settings/.system.CaptionSetupActivity",
                    Settings.ACTION_CAPTIONING_SETTINGS),
            new Page(R.string.and_accessibility, R.drawable.ic_info, null, Settings.ACTION_ACCESSIBILITY_SETTINGS),
            new Page(R.string.and_security, R.drawable.ic_info, "com.android.tv.settings/.system.SecurityActivity",
                    Settings.ACTION_SECURITY_SETTINGS),
            new Page(R.string.and_unknown_sources, R.drawable.ic_apps,
                    "com.android.tv.settings/.device.apps.specialaccess.ExternalSourcesActivity",
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            new Page(R.string.and_privacy, R.drawable.ic_info, "com.android.tv.settings/.device.privacy.PrivacyActivity",
                    Settings.ACTION_PRIVACY_SETTINGS),
            new Page(R.string.and_location, R.drawable.ic_info, "com.android.tv.settings/.system.LocationActivity",
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            new Page(R.string.and_all_apps, R.drawable.ic_apps, "com.android.tv.settings/.device.apps.AllAppsActivity",
                    Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
            new Page(R.string.and_storage, R.drawable.ic_info, null, Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            new Page(R.string.and_status, R.drawable.ic_info, "com.android.tv.settings/.about.StatusActivity", null),
            new Page(R.string.and_about, R.drawable.ic_info, "com.android.tv.settings/.about.AboutActivity",
                    Settings.ACTION_DEVICE_INFO_SETTINGS),
            new Page(R.string.and_legal, R.drawable.ic_info, "com.android.tv.settings/.about.LicenseActivity", null),
            new Page(R.string.and_developer, R.drawable.ic_tune,
                    "com.android.tv.settings/.system.development.DevelopmentActivity",
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            new Page(R.string.and_reset, R.drawable.ic_info, "com.android.tv.settings/.device.StorageResetActivity", null),
            new Page(R.string.and_everything, R.drawable.ic_settings, null, Settings.ACTION_SETTINGS));

    /** One of ANDROID_PAGES by its name. */
    static Page page(int name) {
        for (Page p : ANDROID_PAGES) if (p.name == name) return p;
        throw new IllegalArgumentException("No such page");
    }

    // ---- writing ----------------------------------------------------------------------------------------------

    private static boolean putSecure(Context ctx, String key, String value) {
        try {
            return Settings.Secure.putString(ctx.getContentResolver(), key, value);
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean putGlobal(Context ctx, String key, String value) {
        try {
            return Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
