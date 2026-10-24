package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * tvos_style's own Settings: a glass sheet from the right edge with Wi-Fi, Bluetooth, Inputs, Picture & sound,
 * Apps, About & storage and Home settings, so everyday changes never open Android's settings. One row at the
 * bottom still does, for everything else. Back goes up a page and closes the sheet from the first one.
 */
final class SettingsSheet {
    static final String HUB = "hub", WIFI = "wifi", SOUND = "sound", APPS = "apps", ABOUT = "about",
            CONTROLS = "controls", ACCOUNT = "account", MEMORY = "memory", THEMES = "themes", WELCOME = "welcome",
            VOLUME = "volume", CLOCK = "clock", SOUNDS = "sounds", INPUTS = "inputs", BLUETOOTH = "bluetooth",
            UPLOAD = "upload", FOUND = "found", DISPLAY = "display", DATETIME = "datetime", KEYBOARD = "keyboard",
            PRIVACY = "privacy", NAME = "name", ANDROID = "android";
    private static final String THEME = "theme:", BANNER = "banner:", PICK = "pick:";
    private static final String CONTROL = "control:", BT_DEVICE = "btdevice:", RENAME = "rename:";
    private static final String PASSWORD = "password:", NETWORK = "network:", APP = "app:";
    /** Screensaver start (Settings.System.SCREEN_OFF_TIMEOUT) and sleep (Settings.Secure.SLEEP_TIMEOUT), in ms. */
    private static final int[] SCREENSAVER_MS = {60_000, 300_000, 900_000, 1_800_000, 3_600_000, Integer.MAX_VALUE};
    private static final int[] SLEEP_MS = {1_800_000, 3_600_000, 10_800_000, 21_600_000, 43_200_000, -1};

    private final MainActivity home;
    private final float density;
    private final WifiManager wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> stack = new ArrayDeque<>();
    /**
     * The sheet, a view over Home in Home's own window: a PopupWindow keeps Back for itself, and Back here has to
     * go up a page. Pages' rows are rebuilt in place; their tags say which row to focus again.
     */
    private FrameLayout sheet;
    /** What had focus on Home before the sheet opened, to give it back on closing. */
    private View focusBefore;
    /** Shown as a compact card rather than the full-height sheet. */
    private boolean compact;
    private LinearLayout rows;
    private TextView title;
    private ScrollView scroll;
    private BroadcastReceiver wifiReceiver;
    /** The network a password is being typed for: SSID and its security, from the scan. */
    private final Map<String, String> security = new HashMap<>();
    /** App sizes worked out in the background for the Apps page, by package. */
    private final Map<String, Long> appSizes = new HashMap<>();

    SettingsSheet(MainActivity home) {
        this.home = home;
        this.density = home.getResources().getDisplayMetrics().density;
        this.wifi = (WifiManager) home.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** Opens the full sheet on a page (HUB, WIFI and so on); Back from it returns to the hub. */
    void show(String page) {
        show(page, false);
    }

    /**
     * compact: a card the size of Control Center hanging under the header, with only this page, for a pill or a
     * Control Center control; Back closes it. Otherwise the full-height sheet with the hub under the page.
     */
    void show(String page, boolean compact) {
        if (isShowing() && this.compact != compact) dismiss();
        this.compact = compact;
        if (!isShowing()) open();
        stack.clear();
        if (!compact) stack.push(HUB);
        if (page != null && !(HUB.equals(page) && !compact)) stack.push(page);
        render(null);
    }

    // ---- the sheet ----------------------------------------------------------------------------------------

    private void open() {
        Context ctx = home;
        int margin = (int) (24 * density);
        // A compact card grows with its rows but stops at the bottom of the screen; the list scrolls instead.
        int top = compact ? home.headerBottom() + (int) (8 * density) : margin;
        int maxHeight = home.homeRoot().getHeight() - top - margin;
        LinearLayout panel = new LinearLayout(ctx) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, compact ? MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) : heightSpec);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * density);
        if (compact) pad = (int) (12 * density);
        panel.setPadding(pad, (int) ((compact ? 16 : 26) * density), pad, pad);
        title = new TextView(ctx);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 20 : 26);
        title.setTypeface(UiFont.get(ctx, R.string.font_medium));
        title.setPadding((int) (6 * density), 0, 0, (int) (14 * density));
        panel.addView(title);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, compact
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Back goes up a page and closes the sheet from the first; arrows never leave the sheet for Home behind it.
        sheet = new FrameLayout(ctx) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        if (stack.size() > 1) back();
                        else dismiss();
                    }
                    return true;
                }
                // A text field keeps Up and Down for its cursor, which a one-line field has no use for: move on instead.
                View current = findFocus();
                boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
                if (!(vertical && current instanceof EditText) && super.dispatchKeyEvent(event)) return true;
                int direction = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN
                        ? View.FOCUS_DOWN : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                        : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
                if (direction == 0) return false;
                View focused = findFocus();
                if (event.getAction() == KeyEvent.ACTION_DOWN && focused != null) {
                    View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
                    if (next != null) next.requestFocus(direction);
                }
                return true;
            }
        };
        sheet.setClickable(true);   // touches on it never reach Home
        // A compact card has Control Center's size and edges (its 340 dp less its 6 dp padding each side), so every
        // control's card lines up with it, whatever page it shows.
        int width = (int) ((compact ? 328 : 440) * density);
        int height = compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.END | Gravity.TOP);
        lp.setMargins(margin, top, compact ? home.headerRightInset() - (int) (4 * density) : margin, margin);
        sheet.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        // Concentric with the rows: their 14 dp corners plus the panel's side padding.
        panel.setBackground(new LiquidGlass.Background(panel, (compact ? 26 : 36) * density, 0x99141416, false));
        panel.setElevation(16 * density);
        focusBefore = home.getCurrentFocus();
        home.homeRoot().addView(sheet, lp);
        panel.setAlpha(0f);
        panel.setTranslationX(width / 4f);
        panel.animate().alpha(1f).translationX(0f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }

    boolean isShowing() {
        return sheet != null && sheet.getParent() != null;
    }

    /** Up a page, with focus on the row that led to the one being left. */
    private void back() {
        String from = stack.pop();
        if (from.startsWith(PICK)) {
            pickers.remove(from.substring(PICK.length()));
            render(from.substring(PICK.length()));
            return;
        }
        render(from.startsWith(PASSWORD) ? "net:" + from.substring(PASSWORD.length()) : from);
    }

    private void push(String page) {
        forgetArmed = false;
        stack.push(page);
        render(null);
    }

    /** Closes the sheet and gives focus back to where it was on Home. */
    void dismiss() {
        if (!isShowing()) return;
        stopWifi();
        stopBluetooth();
        stopUpload();
        // Out quicker and quieter than it came in: 150 ms, a short slide back and a fade. Focus is back on Home at
        // once, and the sheet is let go, so showing it again builds a new one while this one fades.
        FrameLayout gone = sheet;
        sheet = null;
        gone.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        View card = gone.getChildAt(0);
        card.animate().cancel();
        card.animate().alpha(0f).translationX(card.getWidth() / 10f).setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> home.homeRoot().removeView(gone)).start();
        if (focusBefore != null && focusBefore.isAttachedToWindow()) focusBefore.requestFocus();
        focusBefore = null;
    }

    /** Rebuilds the top page, keeping focus on the row with focusTag (or the one focused now). */
    private void render(Object focusTag) {
        // Rebuilding moves focus off the old rows and onto the new ones; that is no step of the owner's, so it is
        // silent (a Bluetooth search rebuilds its page each time a device turns up).
        TvSounds.hush(true);
        try {
            build(focusTag);
        } finally {
            TvSounds.hush(false);
        }
    }

    private void build(Object focusTag) {
        if (focusTag == null && rows.findFocus() != null) focusTag = rows.findFocus().getTag();
        rows.removeAllViews();
        String page = stack.peek();
        if (WIFI.equals(page)) wifiPage();
        else if (SOUND.equals(page)) soundPage();
        else if (APPS.equals(page)) appsPage();
        else if (ABOUT.equals(page)) aboutPage();
        else if (CONTROLS.equals(page)) controlsPage();
        else if (ACCOUNT.equals(page)) accountPage();
        else if (MEMORY.equals(page)) memoryPage();
        else if (THEMES.equals(page)) themesPage();
        else if (WELCOME.equals(page)) welcomePage();
        else if (VOLUME.equals(page)) volumePage();
        else if (CLOCK.equals(page)) clockPage();
        else if (SOUNDS.equals(page)) soundsPage();
        else if (INPUTS.equals(page)) inputsPage();
        else if (BLUETOOTH.equals(page)) bluetoothPage();
        else if (UPLOAD.equals(page)) uploadPage();
        else if (FOUND.equals(page)) foundPage();
        else if (DISPLAY.equals(page)) displayPage();
        else if (DATETIME.equals(page)) dateTimePage();
        else if (KEYBOARD.equals(page)) keyboardPage();
        else if (PRIVACY.equals(page)) privacyPage();
        else if (NAME.equals(page)) namePage();
        else if (ANDROID.equals(page)) androidPage();
        else if (page.startsWith(THEME)) themePage(page.substring(THEME.length()));
        else if (page.startsWith(PICK)) pickPage(page.substring(PICK.length()));
        else if (page.startsWith(BANNER)) bannerPage(page.substring(BANNER.length()));
        else if (page.startsWith(CONTROL)) controlPage(page.substring(CONTROL.length()));
        else if (page.startsWith(BT_DEVICE)) btDevicePage(page.substring(BT_DEVICE.length()));
        else if (page.startsWith(RENAME)) renamePage(page.substring(RENAME.length()));
        else if (page.startsWith(PASSWORD)) passwordPage(page.substring(PASSWORD.length()));
        else if (page.startsWith(NETWORK)) networkPage(page.substring(NETWORK.length()));
        else if (page.startsWith(APP)) appPage(page.substring(APP.length()));
        else hubPage();
        if (WIFI.equals(page) || page.startsWith(NETWORK)) startWifi();
        else stopWifi();
        if (!BLUETOOTH.equals(page) && !page.startsWith(BT_DEVICE)) stopBluetooth();
        if (!UPLOAD.equals(page)) stopUpload();
        TvSounds.mute(rows);
        View target = null;
        if (focusTag != null) {
            for (int i = 0; i < rows.getChildCount() && target == null; i++) {
                if (focusTag.equals(rows.getChildAt(i).getTag())) target = rows.getChildAt(i);
            }
        }
        if (target == null) {
            for (int i = 0; i < rows.getChildCount() && target == null; i++) {
                if (rows.getChildAt(i).isFocusable()) target = rows.getChildAt(i);
            }
        }
        if (target != null) target.requestFocus();
        else scroll.requestFocus();
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    /** A row; action null makes it information only. Chevron: it leads to a page; external: it leaves the launcher. */
    private View row(int icon, CharSequence name, CharSequence value, String tag, boolean chevron, Runnable action) {
        View row = home.getLayoutInflater().inflate(R.layout.item_setting_row, rows, false);
        ImageView iconView = row.findViewById(R.id.icon);
        if (icon != 0) iconView.setImageResource(icon);
        else iconView.setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(name);
        TextView valueView = row.findViewById(R.id.value);
        if (TextUtils.isEmpty(value)) valueView.setVisibility(View.GONE);
        else valueView.setText(value);
        row.findViewById(R.id.chevron).setVisibility(chevron ? View.VISIBLE : View.GONE);
        row.setTag(tag != null ? tag : name.toString());
        if (action != null) row.setOnClickListener(v -> action.run());
        rows.addView(row);
        return row;
    }

    /** A row that leaves for Android's own settings: an arrow out instead of the chevron. */
    private void externalRow(int icon, CharSequence name, Intent intent) {
        View row = row(icon, name, null, null, true, () -> {
            dismiss();
            home.open(intent);
        });
        ((ImageView) row.findViewById(R.id.chevron)).setImageResource(R.drawable.ic_external);
    }

    private void header(String text) {
        TextView header = new TextView(home);
        header.setText(text.toUpperCase(Locale.getDefault()));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.setLetterSpacing(0.12f);
        header.setTextColor(0x8CFFFFFF);
        int h = (int) (16 * density);
        header.setPadding(h, (int) (14 * density), h, (int) (6 * density));
        rows.addView(header);
    }

    /** Left and Right step through choices on the focused row, as OK does forwards. */
    private void stepper(View row, Runnable previous, Runnable next) {
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) previous.run();
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) next.run();
            else return false;
            return true;
        });
    }

    // ---- hub ----------------------------------------------------------------------------------------------

    /** The hub, grouped as tvOS groups its Settings: what connects, how Home looks, how it sounds, the system. */
    private void hubPage() {
        title.setText(R.string.settings);
        header(home.getString(R.string.set_group_connect));
        row(R.drawable.ic_wifi, home.getString(R.string.wifi), wifiSummary(), WIFI, true, () -> push(WIFI));
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            row(R.drawable.ic_bluetooth, home.getString(R.string.bluetooth), home.bluetoothSummary(adapter), BLUETOOTH, true,
                    () -> push(BLUETOOTH));
        }
        row(R.drawable.ic_input, home.getString(R.string.inputs), null, INPUTS, true, () -> {
            if (!home.inputsArePanel()) {
                push(INPUTS);
                return;
            }
            dismiss();
            home.homeRoot().post(home::showInputs);   // the TV's own source panel
        });
        header(home.getString(R.string.set_group_look));
        row(R.drawable.ic_themes, home.getString(R.string.themes), Themes.current(home).title(home), THEMES, true,
                () -> push(THEMES));
        row(R.drawable.ic_clock, home.getString(R.string.clock), home.getString(Clock.STYLE_NAMES[Clock.style(home)]), CLOCK, true,
                () -> push(CLOCK));
        row(R.drawable.ic_wave, home.getString(R.string.welcome), null, WELCOME, true, () -> push(WELCOME));
        row(R.drawable.ic_volume, home.getString(R.string.volume),
                home.getString(VolumeView.Look.read(home).enabled ? VolumeView.STYLE_NAMES[VolumeView.Look.read(home).style] : R.string.bt_off),
                VOLUME, true, () -> push(VOLUME));
        View night = row(R.drawable.ic_night, home.getString(R.string.night_mode),
                home.getString(Comfort.nightMode(home) ? R.string.bt_on : R.string.bt_off), "night", false, () -> {
                    home.toggleNightMode();
                    render("night");
                });
        stepper(night, night::performClick, night::performClick);
        header(home.getString(R.string.set_group_sound));
        row(R.drawable.ic_volume, home.getString(R.string.set_sound), null, SOUND, true, () -> push(SOUND));
        row(R.drawable.ic_volume, home.getString(R.string.snd_title), home.getString(TvSounds.PACK_NAMES[TvSounds.pack(home)]),
                SOUNDS, true, () -> push(SOUNDS));
        header(home.getString(R.string.set_group_system));
        row(R.drawable.ic_apps, home.getString(R.string.set_apps), null, APPS, true, () -> push(APPS));
        row(R.drawable.ic_control_center, home.getString(R.string.set_controls), null, CONTROLS, true, () -> push(CONTROLS));
        row(R.drawable.ic_tune, home.getString(R.string.cc_home_settings), null, "home", true, () -> {
            dismiss();
            home.openLauncherSettings();
        });
        row(R.drawable.ic_boost, home.getString(R.string.boost), null, MEMORY, true, () -> {
            boostRequested = true;
            push(MEMORY);
        });
        header(home.getString(R.string.set_group_device));
        row(R.drawable.ic_picture, home.getString(R.string.dev_display), null, DISPLAY, true, () -> push(DISPLAY));
        row(R.drawable.ic_clock, home.getString(R.string.dev_datetime), DeviceSettings.timeZone(), DATETIME, true,
                () -> push(DATETIME));
        row(R.drawable.ic_tune, home.getString(R.string.dev_keyboard), keyboardName(), KEYBOARD, true, () -> push(KEYBOARD));
        row(R.drawable.ic_lock, home.getString(R.string.dev_privacy), null, PRIVACY, true, () -> push(PRIVACY));
        row(R.drawable.ic_livetv, home.getString(R.string.dev_name), DeviceSettings.deviceName(home), NAME, true,
                () -> push(NAME));
        row(R.drawable.ic_info, home.getString(R.string.set_about), Build.MODEL, ABOUT, true, () -> push(ABOUT));
        row(R.drawable.ic_settings, home.getString(R.string.dev_android), null, ANDROID, true, () -> push(ANDROID));
    }

    // ---- Device: Android's settings, in the launcher -----------------------------------------------------------

    /** A row for an Android setting the launcher just tried to change: said plainly when Android refused it. */
    private void refused(boolean ok) {
        if (!ok) Toast.makeText(home, R.string.dev_refused, Toast.LENGTH_LONG).show();
    }

    /** An on/off row for an Android setting; OK, Left and Right flip it. */
    private void switchRow(int icon, int name, boolean on, String tag, java.util.function.Predicate<Boolean> set) {
        View row = row(icon, home.getString(name), home.getString(on ? R.string.bt_on : R.string.bt_off), tag, false, () -> {
            refused(set.test(!on));
            render(tag);
        });
        stepper(row, row::performClick, row::performClick);
    }

    /** Opens one of Android's own pages from the list; left out when this TV has no such page. */
    private void androidRow(DeviceSettings.Page page) {
        android.content.Intent intent = page.intent(home);
        if (intent != null) externalRow(page.icon, home.getString(page.name), intent);
    }

    /** Which screensaver and when, when the TV sleeps, the text size and how fast windows animate. */
    private void displayPage() {
        title.setText(R.string.dev_display);
        header(home.getString(R.string.dev_screensaver));
        List<DeviceSettings.Dream> dreams = DeviceSettings.dreams(home);
        String[] names = new String[dreams.size()];
        for (int i = 0; i < names.length; i++) names[i] = dreams.get(i).label;
        listRow(R.drawable.ic_picture, home.getString(R.string.dev_screensaver), names, null,
                DeviceSettings.currentDream(home, dreams), "dream", i -> refused(DeviceSettings.setDream(home, dreams.get(i))), null);
        boolean canWrite = Settings.System.canWrite(home);
        choiceRow(R.drawable.ic_sleep, R.string.set_screensaver, "screensaver", SCREENSAVER_MS,
                Settings.System.getInt(home.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 300_000),
                ms -> canWrite && Settings.System.putInt(home.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, ms));
        choiceRow(R.drawable.ic_power, R.string.set_sleep, "sleep", SLEEP_MS,
                Settings.Secure.getInt(home.getContentResolver(), "sleep_timeout", -1),
                ms -> {
                    try {
                        return Settings.Secure.putInt(home.getContentResolver(), "sleep_timeout", ms);
                    } catch (SecurityException e) {
                        return false;
                    }
                });
        header(home.getString(R.string.dev_screen));
        cycleRow(R.drawable.ic_tune, R.string.dev_text, DeviceSettings.FONT_NAMES,
                DeviceSettings.nearest(DeviceSettings.FONT_SCALES, DeviceSettings.fontScale(home)), "font",
                i -> refused(DeviceSettings.setFontScale(home, DeviceSettings.FONT_SCALES[i])));
        cycleRow(R.drawable.ic_gloss, R.string.dev_anim, DeviceSettings.ANIMATION_NAMES,
                DeviceSettings.nearest(DeviceSettings.ANIMATION_SCALES, DeviceSettings.animationScale(home)), "anim",
                i -> refused(DeviceSettings.setAnimationScale(home, DeviceSettings.ANIMATION_SCALES[i])));
        header(home.getString(R.string.set_more));
        androidRow(DeviceSettings.PICTURE);
    }

    /** Network time and time zone, and 12 or 24 hours; the zone itself is picked on Android's page. */
    private void dateTimePage() {
        title.setText(R.string.dev_datetime);
        switchRow(R.drawable.ic_clock, R.string.dev_auto_time, DeviceSettings.autoTime(home), "autotime",
                on -> DeviceSettings.setAutoTime(home, on));
        switchRow(R.drawable.ic_wifi, R.string.dev_auto_zone, DeviceSettings.autoZone(home), "autozone",
                on -> DeviceSettings.setAutoZone(home, on));
        cycleRow(R.drawable.ic_clock, R.string.dev_hours, DeviceSettings.HOUR_NAMES, DeviceSettings.hours(home), "hours", i -> {
            refused(DeviceSettings.setHours(home, i));
            home.applyClock();   // the header and big clocks in the new format at once
        });
        row(R.drawable.ic_info, home.getString(R.string.dev_zone), DeviceSettings.timeZone(), "zone", false, null);
        header(home.getString(R.string.set_more));
        androidRow(DeviceSettings.page(R.string.and_date));   // setting the zone or the time by hand
    }

    private String keyboardName() {
        String id = DeviceSettings.currentKeyboard(home);
        for (android.view.inputmethod.InputMethodInfo ime : DeviceSettings.keyboards(home)) {
            if (ime.getId().equals(id)) return String.valueOf(ime.loadLabel(home.getPackageManager()));
        }
        return null;
    }

    /** Every keyboard installed; OK makes one the keyboard that comes up. */
    private void keyboardPage() {
        title.setText(R.string.dev_keyboard);
        String current = DeviceSettings.currentKeyboard(home);
        for (android.view.inputmethod.InputMethodInfo ime : DeviceSettings.keyboards(home)) {
            boolean on = ime.getId().equals(current);
            row(on ? R.drawable.ic_check : R.drawable.ic_blank, ime.loadLabel(home.getPackageManager()),
                    on ? home.getString(R.string.dev_in_use) : null, "ime:" + ime.getId(), false, () -> {
                        refused(DeviceSettings.setKeyboard(home, ime));
                        render("ime:" + ime.getId());
                    });
        }
        header(home.getString(R.string.set_more));
        androidRow(DeviceSettings.page(R.string.and_keyboard));   // each keyboard's own languages and options
    }

    /** Location for apps that use it, and Android's pages for unknown sources, security and diagnostics. */
    private void privacyPage() {
        title.setText(R.string.dev_privacy);
        switchRow(R.drawable.ic_info, R.string.dev_location, DeviceSettings.location(home), "location",
                on -> DeviceSettings.setLocation(home, on));
        header(home.getString(R.string.set_more));
        for (int name : new int[]{R.string.and_unknown_sources, R.string.and_security, R.string.and_privacy,
                R.string.and_location}) {
            androidRow(DeviceSettings.page(name));
        }
    }

    /** The name phones see when casting and in Bluetooth lists. */
    private void namePage() {
        title.setText(R.string.dev_name);
        String[] typed = {DeviceSettings.deviceName(home)};
        textField(typed[0], R.string.dev_name, "namefield", text -> typed[0] = text);
        row(R.drawable.ic_check, home.getString(R.string.dev_name_save), null, "save", false, () -> {
            boolean ok = DeviceSettings.setDeviceName(home, typed[0]);
            refused(ok);
            if (ok) Toast.makeText(home, home.getString(R.string.dev_name_saved, typed[0].trim()), Toast.LENGTH_SHORT).show();
        });
        row(R.drawable.ic_info, home.getString(R.string.dev_name_how), null, "how", false, null);
    }

    /** Every other page of Android's Settings that this TV has, each opened directly. */
    private void androidPage() {
        title.setText(R.string.dev_android);
        for (DeviceSettings.Page page : DeviceSettings.ANDROID_PAGES) androidRow(page);
    }

    // ---- Inputs and Bluetooth -------------------------------------------------------------------------------

    /** The inputs to switch to, as rows of this sheet: picking one switches and closes it. */
    private void inputsPage() {
        title.setText(R.string.inputs);
        List<MainActivity.ListRow> list = home.inputRows();
        if (list.isEmpty()) row(R.drawable.ic_info, home.getString(R.string.no_inputs), null, "none", false, null);
        listRows(list);
    }

    /** True while this sheet scans for and connects Bluetooth devices (only while the Bluetooth page is open). */
    private boolean bluetoothOn;

    /** A theme's name: typed with the keyboard, saved with Save. Empty goes back to its own name. */
    private void renamePage(String id) {
        Themes.Theme t = Themes.find(home, id);
        title.setText(R.string.theme_name);
        String[] typed = {t.title(home)};
        textField(typed[0], R.string.theme_name, "namefield", text -> typed[0] = text);
        row(R.drawable.ic_check, home.getString(R.string.theme_name_save), null, "save", false, () -> {
            Themes.rename(home, t, typed[0]);
            previews.clear();
            Toast.makeText(home, home.getString(R.string.theme_renamed, Themes.find(home, id).title(home)), Toast.LENGTH_SHORT).show();
            back();
        });
    }

    /** Paired and nearby devices, kept current while the page is open, as rows of this sheet. */
    private void bluetoothPage() {
        title.setText(R.string.bluetooth);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            row(R.drawable.ic_bluetooth, home.getString(R.string.not_available), null, "none", false, null);
            return;
        }
        Runnable refresh = bluetoothRefresh();
        try {
            startBluetoothSession(adapter, refresh, true);
            listRows(home.bluetoothRows(adapter, refresh, this::dismiss, d -> push(BT_DEVICE + d.getAddress())));
        } catch (SecurityException e) {
            // Without the nearby-devices permission the system page can still do all of it.
            dismiss();
            home.startActivity(new android.content.Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Redraws the Bluetooth page or a device's page as devices are found, pair, connect and go. */
    private Runnable bluetoothRefresh() {
        return () -> {
            if (isShowing() && (BLUETOOTH.equals(stack.peek()) || stack.peek().startsWith(BT_DEVICE))) render(null);
        };
    }

    private void startBluetoothSession(BluetoothAdapter adapter, Runnable refresh, boolean search) {
        if (bluetoothOn) return;
        bluetoothOn = true;
        home.startBluetooth(adapter, refresh);
        if (search && adapter.isEnabled()) adapter.startDiscovery();
    }

    /** True once Forget was pressed once on the device page; a second press forgets. */
    private boolean forgetArmed;

    /** One paired device: whether it is connected, connect or disconnect it, or forget it. All in the launcher. */
    private void btDevicePage(String address) {
        android.bluetooth.BluetoothDevice device = home.pairedDevice(address);
        if (device == null) {   // forgotten meanwhile
            back();
            return;
        }
        title.setText(MainActivity.nameOf(device));
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        startBluetoothSession(adapter, bluetoothRefresh(), false);
        boolean connected = MainActivity.isConnected(device);
        row(R.drawable.ic_bluetooth, home.getString(R.string.bt_status),
                home.getString(connected ? R.string.connected : R.string.bt_not_connected), "status", false, null);
        row(connected ? R.drawable.ic_hide : R.drawable.ic_check, home.getString(connected ? R.string.bt_disconnect
                : R.string.bt_connect), null, "connect", false, () -> {
            boolean ok = connected ? home.disconnectBluetooth(device) : home.connectPaired(device);
            if (!ok) Toast.makeText(home, R.string.not_available, Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> {
                if (isShowing() && (BT_DEVICE + address).equals(stack.peek())) render("connect");
            }, 2500);
        });
        row(R.drawable.ic_hide, home.getString(forgetArmed ? R.string.bt_forget_sure : R.string.bt_forget), null, "forget",
                false, () -> {
                    if (!forgetArmed) {
                        forgetArmed = true;
                        render("forget");
                        return;
                    }
                    forgetArmed = false;
                    if (MainActivity.forgetBluetooth(device)) {
                        Toast.makeText(home, home.getString(R.string.bt_forgotten, MainActivity.nameOf(device)),
                                Toast.LENGTH_SHORT).show();
                        handler.postDelayed(this::back, 600);
                    } else {
                        Toast.makeText(home, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
        header(home.getString(R.string.bt_details));
        row(R.drawable.ic_info, home.getString(R.string.bt_address), address, "address", false, null);
    }

    private void stopBluetooth() {
        if (!bluetoothOn) return;
        bluetoothOn = false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) home.closeBluetooth(adapter);
    }

    /** MainActivity's list rows as this sheet's: headers, a little space for a separator, and the rows. */
    private void listRows(List<MainActivity.ListRow> list) {
        for (MainActivity.ListRow item : list) {
            if (item == MainActivity.ListRow.SEPARATOR) {
                rows.addView(new View(home), new LinearLayout.LayoutParams(1, (int) (10 * density)));
            } else if (item.action == null) {
                header(item.title);
            } else {
                row(item.icon, item.title, item.status, item.key, false, () -> {
                    if (item.closes) dismiss();
                    item.action.run();
                });
            }
        }
    }

    // ---- Account ------------------------------------------------------------------------------------------

    /** The profile button's card: the picture and account, the theme and welcome, and Google's own page. */
    private void accountPage() {
        title.setText(R.string.cc_profile);
        String account = home.accountName();
        View me = row(R.drawable.ic_person, account != null ? account : home.getString(R.string.acct_none), null, "me", false, null);
        android.graphics.Bitmap photo = home.profileBitmap();
        if (photo != null) {
            ImageView icon = me.findViewById(R.id.icon);
            icon.setImageBitmap(photo);
            icon.setImageTintList((ColorStateList) null);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            icon.setClipToOutline(true);
        }
        row(R.drawable.ic_themes, home.getString(R.string.themes), Themes.current(home).title(home), THEMES, true,
                () -> push(THEMES));
        row(R.drawable.ic_wave, home.getString(R.string.welcome), null, WELCOME, true, () -> push(WELCOME));
        header(home.getString(R.string.set_more));
        externalRow(R.drawable.ic_person, home.getString(R.string.acct_google), home.accountIntent());
    }

    // ---- Clear background apps ----------------------------------------------------------------------------

    /** Set when the page is opened to clear: it clears once, in the background, and then shows the result. */
    boolean boostRequested;
    private Boost.Result boost;
    private boolean boosting;

    private void memoryPage() {
        title.setText(R.string.boost);
        if (boostRequested && !boosting) {
            boostRequested = false;
            boosting = true;
            Executors.newSingleThreadExecutor().execute(() -> {
                Boost.Result r = Boost.clear(home);
                handler.post(() -> {
                    boost = r;
                    boosting = false;
                    if (isShowing() && MEMORY.equals(stack.peek())) render("again");
                });
            });
        }
        if (boosting || boost == null) {
            row(R.drawable.ic_boost, home.getString(R.string.boost_working), null, "working", false, null);
            return;
        }
        row(R.drawable.ic_boost, home.getString(R.string.boost_cleared), home.getResources().getQuantityString(
                R.plurals.boost_apps, boost.apps, boost.apps), "cleared", false, null);
        row(0, home.getString(R.string.boost_freed), Formatter.formatShortFileSize(home, Math.max(0, boost.after - boost.before)),
                "freed", false, null);
        row(0, home.getString(R.string.boost_free_now), home.getString(R.string.set_free_of,
                Formatter.formatShortFileSize(home, boost.after), Formatter.formatShortFileSize(home, boost.total)), "now", false, null);
        row(0, home.getString(R.string.boost_again), null, "again", false, () -> {
            boostRequested = true;
            render("again");
        });
        View standby = row(R.drawable.ic_night, home.getString(R.string.boost_standby),
                home.getString(Comfort.clearOnStandby(home) ? R.string.bt_on : R.string.bt_off), "standby", false, () -> {
                    Comfort.setClearOnStandby(home, !Comfort.clearOnStandby(home));
                    render("standby");
                });
        stepper(standby, standby::performClick, standby::performClick);
    }

    // ---- Themes -------------------------------------------------------------------------------------------

    private static final Map<String, android.graphics.Bitmap> previews = new HashMap<>();

    /**
     * Every theme with a preview: OK switches to it at once, Right or Menu opens its choices. Then your pictures,
     * and the pictures found in a "TV Themes" folder on a USB drive, which OK adds and switches to.
     */
    private void themesPage() {
        title.setText(R.string.themes);
        Themes.Theme now = Themes.current(home);
        row(R.drawable.ic_tune, home.getString(R.string.theme_customise, now.title(home)), null, "customise", true,
                () -> push(THEME + now.id));
        header(home.getString(R.string.themes));
        boolean mineHeader = false;
        for (Themes.Theme t : Themes.all(home)) {
            if (t.mine() && !mineHeader) {
                header(home.getString(R.string.theme_yours));
                mineHeader = true;
            }
            themeRow(t, t.id.equals(now.id));
        }
        header(home.getString(R.string.theme_add));
        row(R.drawable.ic_wifi, home.getString(R.string.theme_from_phone), null, UPLOAD, true, () -> push(UPLOAD));
        row(R.drawable.ic_picture, home.getString(R.string.theme_on_tv), null, FOUND, true, () -> push(FOUND));
    }

    // ---- photos of your own ---------------------------------------------------------------------------------

    /** Decodes photo thumbnails off the main thread, one at a time. */
    private final java.util.concurrent.ExecutorService thumbs = Executors.newSingleThreadExecutor();

    /**
     * Shows a photo's thumbnail in a row's icon, 16:9 with rounded corners like the theme previews; decoded in the
     * background and kept, so scrolling the list back does not decode it again.
     */
    private void photoIcon(View row, File photo, Themes.Theme theme) {
        ImageView icon = row.findViewById(R.id.icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageTintList((ColorStateList) null);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = (int) (44 * density);
        lp.height = (int) (25 * density);
        icon.setLayoutParams(lp);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 6 * density);
            }
        });
        icon.setClipToOutline(true);
        String key = "thumb:" + photo.getAbsolutePath() + (theme == null ? "" : Themes.paintKey(home, theme));
        android.graphics.Bitmap cached = previews.get(key);
        if (cached != null) {
            icon.setImageBitmap(cached);
            return;
        }
        icon.setImageDrawable(null);
        icon.setBackgroundColor(0x33FFFFFF);
        thumbs.execute(() -> {
            android.graphics.Bitmap b = theme != null ? Themes.render(home, theme, 88, 50) : Themes.thumbnail(photo, 88, 50);
            if (b == null) return;
            handler.post(() -> {
                previews.put(key, b);
                if (!icon.isAttachedToWindow()) return;
                icon.setBackground(null);
                icon.setImageBitmap(b);
            });
        });
    }

    /** A photo becomes a theme: it is copied in, put on, and its page opens to choose its clock and tiles. */
    private void addPhoto(File photo) {
        Toast.makeText(home, R.string.theme_adding, Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            Themes.Theme added = Themes.importImage(home, photo);
            handler.post(() -> {
                if (added == null) {
                    Toast.makeText(home, R.string.not_available, Toast.LENGTH_SHORT).show();
                    return;
                }
                openPhotoTheme(added);
            });
        });
    }

    private void openPhotoTheme(Themes.Theme theme) {
        home.applyTheme(theme.id);
        if (!isShowing()) return;
        stack.push(THEME + theme.id);
        render(null);
    }

    /** Photos found on the TV and on USB drives, newest first; OK on one makes it a theme. */
    private void foundPage() {
        title.setText(R.string.theme_on_tv);
        List<File> found = Themes.onDrives(home);
        if (found.isEmpty()) row(R.drawable.ic_info, home.getString(R.string.theme_none_found), null, "none", false, null);
        for (File f : found) {
            String folder = f.getParentFile() == null ? null : f.getParentFile().getName();
            View row = row(R.drawable.ic_picture, f.getName(), folder, "add:" + f.getAbsolutePath(), false, () -> addPhoto(f));
            photoIcon(row, f, null);
        }
        header("");
        row(R.drawable.ic_info, home.getString(R.string.theme_how), null, "how", false, null);
    }

    /** The phone upload, while its page is open; null otherwise. */
    private PhotoUpload upload;
    /** Photos the phone sent while this sheet was around, newest first. */
    private final List<File> received = new ArrayList<>();

    /** The address and its QR code for the phone, and each photo as it arrives, ready to be put on. */
    private void uploadPage() {
        title.setText(R.string.theme_from_phone);
        String address = PhotoUpload.address();
        if (address == null) {
            row(R.drawable.ic_wifi, home.getString(R.string.upload_offline), null, "offline", false, null);
            return;
        }
        if (upload == null) {
            upload = new PhotoUpload(new File(home.getFilesDir(), Themes.MY_THEMES), photo -> {
                received.add(0, photo);
                Toast.makeText(home, home.getString(R.string.upload_added, photo.getName()), Toast.LENGTH_SHORT).show();
                if (isShowing() && UPLOAD.equals(stack.peek())) render(null);
            });
            if (!upload.start()) {
                upload = null;
                row(R.drawable.ic_info, home.getString(R.string.not_available), null, "failed", false, null);
                return;
            }
        }
        LinearLayout card = new LinearLayout(home);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (12 * density);
        card.setPadding(pad, pad, pad, pad);
        QrCode qr = QrCode.encode(address);
        if (qr != null) {
            ImageView code = new ImageView(home);
            code.setImageBitmap(qr.bitmap(4));
            code.setScaleType(ImageView.ScaleType.FIT_CENTER);
            android.graphics.drawable.GradientDrawable white = new android.graphics.drawable.GradientDrawable();
            white.setColor(0xFFFFFFFF);
            white.setCornerRadius(12 * density);
            code.setBackground(white);
            int p = (int) (6 * density);
            code.setPadding(p, p, p, p);
            int side = (int) (128 * density);
            card.addView(code, new LinearLayout.LayoutParams(side, side));
        }
        LinearLayout words = new LinearLayout(home);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView link = new TextView(home);
        link.setText(address.replace("http://", ""));
        link.setTextColor(0xFFFFFFFF);
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        link.setTypeface(UiFont.get(home, R.string.font_medium));
        TextView how = new TextView(home);
        how.setText(R.string.upload_how);
        how.setTextColor(0xB3FFFFFF);
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        how.setPadding(0, (int) (6 * density), 0, 0);
        words.addView(link);
        words.addView(how);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wl.setMarginStart((int) (16 * density));
        card.addView(words, wl);
        rows.addView(card);
        header(home.getString(R.string.upload_received));
        received.removeIf(f -> !f.isFile());   // a photo removed from its theme page leaves the list too
        if (received.isEmpty()) {
            row(R.drawable.ic_picture, home.getString(R.string.upload_waiting), null, "waiting", false, null);
        }
        for (File f : received) {
            Themes.Theme t = Themes.find(home, Themes.IMAGE + f.getAbsolutePath());
            View row = row(R.drawable.ic_picture, t.title(home), null, "got:" + f.getName(), true, () -> openPhotoTheme(t));
            photoIcon(row, f, t);
        }
    }

    private void stopUpload() {
        if (upload == null) return;
        upload.stop();
        upload = null;
    }

    private void themeRow(Themes.Theme t, boolean current) {
        View row = row(0, t.title(home), current ? home.getString(R.string.theme_current) : null, "theme:" + t.id, true, () -> {
            home.applyTheme(t.id);
            render("theme:" + t.id);
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT && keyCode != KeyEvent.KEYCODE_MENU) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) push(THEME + t.id);
            return true;
        });
        if (t.image != null) {
            photoIcon(row, t.image, t);   // a photo takes a moment to decode: in the background
            return;
        }
        if (t.slides()) {
            Themes.Theme slide = Themes.currentSlide(home);
            if (slide != null) photoIcon(row, slide.image, slide);
            return;
        }
        android.graphics.Bitmap preview = previews.get(t.id + Themes.patternChoice(home, t) + Themes.brightnessChoice(home, t));
        if (preview == null) {
            preview = Themes.render(home, t, 80, 45);
            previews.put(t.id + Themes.patternChoice(home, t) + Themes.brightnessChoice(home, t), preview);
        }
        ImageView icon = row.findViewById(R.id.icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageBitmap(preview);
        icon.setImageTintList((ColorStateList) null);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ViewGroup.LayoutParams lp = icon.getLayoutParams();
        lp.width = (int) (44 * density);
        lp.height = (int) (25 * density);
        icon.setLayoutParams(lp);
        icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 6 * density);
            }
        });
        icon.setClipToOutline(true);
    }

    /** One theme's choices: use it, its accent, pattern strength and brightness; remove a picture of yours. */
    private void themePage(String id) {
        Themes.Theme t = Themes.find(home, id);
        title.setText(t.title(home));
        boolean current = t.id.equals(Themes.current(home).id);
        row(R.drawable.ic_themes, home.getString(current ? R.string.theme_in_use : R.string.theme_use), null, "use",
                false, current ? null : () -> {
                    home.applyTheme(t.id);
                    render("use");
                });
        Runnable redraw = () -> {
            previews.clear();
            if (t.id.equals(Themes.current(home).id)) home.applyTheme(t.id);
        };
        row(R.drawable.ic_tune, home.getString(R.string.theme_name), t.title(home), "name", true, () -> push(RENAME + t.id));
        if (t.slides()) {
            String[] every = new String[Themes.SLIDE_MINUTES.length];
            for (int i = 0; i < every.length; i++) {
                int m = Themes.SLIDE_MINUTES[i];
                every[i] = m >= 60 ? home.getString(R.string.theme_slides_hour) : home.getString(R.string.theme_slides_min, m);
            }
            listRow(R.drawable.ic_picture, home.getString(R.string.theme_slides_every), every, null,
                    Themes.slideMinutesChoice(home), "every", i -> {
                        Themes.setSlideMinutes(home, i);
                        if (t.id.equals(Themes.current(home).id)) home.scheduleSlide(t);
                    }, null);
            row(R.drawable.ic_swap_vert, home.getString(R.string.theme_slides_next), null, "next", false, () -> {
                Themes.nextSlide(home);
                if (t.id.equals(Themes.current(home).id)) home.applyTheme(t.id);
                previews.clear();
            });
        }
        cycleRow(R.drawable.ic_gloss, R.string.theme_accent, Themes.ACCENT_NAMES, Themes.accentChoice(home, t), "accent", i -> {
            Themes.setChoice(home, t, "accent", i);
            home.refreshAccent();
        });
        // Its look: each theme keeps its own clock and tiles, and they change with it.
        Runnable relook = () -> {
            if (t.id.equals(Themes.current(home).id)) {
                home.applyClock();
                home.applyTiles();
            }
        };
        cycleRow(R.drawable.ic_clock, R.string.theme_clock_style, Clock.STYLE_NAMES, Clock.style(home, t), "clockstyle", i -> {
            Clock.setStyle(home, t, i);
            relook.run();
        });
        cycleRow(R.drawable.ic_swap_vert, R.string.theme_clock_place, Clock.PLACE_NAMES, Clock.place(home, t), "clockplace", i -> {
            Clock.setPlace(home, t, i);
            relook.run();
        });
        cycleRow(R.drawable.ic_apps, R.string.theme_tiles, Themes.TILE_NAMES, Themes.tilesChoice(home, t), "tiles", i -> {
            Themes.setChoice(home, t, "tiles", i);
            relook.run();
        });
        if (t.mine()) {
            cycleRow(R.drawable.ic_gloss, R.string.theme_icons, Themes.ICON_NAMES, Themes.iconsChoice(home, t), "icons", i -> {
                Themes.setChoice(home, t, "icons", i);
                home.refreshAccent();
            });
        }
        if (t.image != null) {
            // How the photo meets the screen: the whole photo or filling it, which part, and how close.
            header(home.getString(R.string.photo_fit_title));
            cycleRow(R.drawable.ic_picture, R.string.photo_fit, Themes.FIT_NAMES, Themes.fitChoice(home, t), "fit", i -> {
                Themes.setChoice(home, t, "fit", i);
                redraw.run();
            });
            cycleRow(R.drawable.ic_swap_vert, R.string.photo_pos, Themes.POSITION_NAMES, Themes.positionChoice(home, t), "pos", i -> {
                Themes.setChoice(home, t, "pos", i);
                redraw.run();
            });
            cycleRow(R.drawable.ic_tune, R.string.photo_zoom, Themes.ZOOM_NAMES, Themes.zoomChoice(home, t), "zoom", i -> {
                Themes.setChoice(home, t, "zoom", i);
                redraw.run();
            });
        }
        if (t.image == null && !t.photos()) {
            cycleRow(R.drawable.ic_themes, R.string.theme_pattern, Themes.PATTERN_NAMES, Themes.patternChoice(home, t), "pattern", i -> {
                Themes.setChoice(home, t, "pattern", i);
                redraw.run();
            });
        }
        if (t.slides()) {
            row(R.drawable.ic_info, home.getString(R.string.theme_slides_how), null, "how", false, null);
        }
        if (!t.photos() && !t.slides()) {
            cycleRow(R.drawable.ic_info, R.string.theme_brightness, Themes.BRIGHTNESS_NAMES, Themes.brightnessChoice(home, t), "bright", i -> {
                Themes.setChoice(home, t, "bright", i);
                redraw.run();
            });
        }
        if (t.image != null && t.image.getParentFile() != null
                && t.image.getParentFile().equals(new File(home.getFilesDir(), Themes.MY_THEMES))) {
            header(home.getString(R.string.set_actions));
            row(0, home.getString(R.string.theme_remove), null, "remove", false, () -> {
                if (current) home.applyTheme(Themes.TVOS);
                Themes.removeImage(home, t);
                previews.clear();
                // The slideshow may be on this photo: on to one that is still there, or back to TvOS without any.
                if (Themes.current(home).slides()) home.applyTheme(Themes.myImages(home).isEmpty() ? Themes.TVOS : Themes.SLIDES);
                back();
            });
        }
    }

    interface Choose {
        void chose(int index);
    }

    /** A row with named choices: OK lists them all to pick from; Left and Right step through them in place. */
    private View cycleRow(int icon, int name, int[] names, int current, String tag, Choose choose) {
        String[] labels = new String[names.length];
        for (int i = 0; i < names.length; i++) labels[i] = home.getString(names[i]);
        return listRow(icon, home.getString(name), labels, null, current, tag, choose, null);
    }

    /** A choice being picked from its list page: title, labels, optional icons, current, and what to do. */
    private static final class Picker {
        String title;
        String[] labels;
        int[] icons;
        int current;
        Choose choose, preview;
    }

    private final Map<String, Picker> pickers = new HashMap<>();

    /**
     * The row for a setting with several choices. OK opens a page listing every choice, the current one ticked;
     * OK on one picks it and comes back. Left and Right still step through them on the row. preview, when given,
     * runs as each choice in the list takes focus (a sound pack plays its sound).
     */
    private View listRow(int icon, String name, String[] labels, int[] icons, int current, String tag, Choose choose,
                         Choose preview) {
        int[] index = {Math.max(0, Math.min(labels.length - 1, current))};
        View row = row(icon, name, labels[index[0]], tag, true, null);
        row.setOnClickListener(v -> {
            Picker p = new Picker();
            p.title = name;
            p.labels = labels;
            p.icons = icons;
            p.current = index[0];
            p.choose = choose;
            p.preview = preview;
            pickers.put(tag, p);
            stack.push(PICK + tag);
            render("opt:" + index[0]);
        });
        Runnable[] step = new Runnable[2];
        for (int d = 0; d < 2; d++) {
            int delta = d == 0 ? -1 : 1;
            step[d] = () -> {
                index[0] = (index[0] + delta + labels.length) % labels.length;
                ((TextView) row.findViewById(R.id.value)).setText(labels[index[0]]);
                choose.chose(index[0]);
            };
        }
        stepper(row, step[0], step[1]);
        return row;
    }

    /** Every choice of one setting; the current one is ticked. */
    private void pickPage(String tag) {
        Picker p = pickers.get(tag);
        if (p == null) {   // the sheet was rebuilt: nothing to pick from any more
            stack.pop();
            render(tag);
            return;
        }
        title.setText(p.title);
        for (int i = 0; i < p.labels.length; i++) {
            int choice = i;
            View row = row(p.icons != null ? p.icons[i] : (i == p.current ? R.drawable.ic_check : R.drawable.ic_blank),
                    p.labels[i], i == p.current && p.icons != null ? home.getString(R.string.theme_current) : null,
                    "opt:" + i, false, () -> {
                        p.choose.chose(choice);
                        back();
                    });
            if (p.icons != null) ((ImageView) row.findViewById(R.id.icon)).setImageTintList((ColorStateList) null);
            if (p.preview != null) {
                row.setOnFocusChangeListener((v, focused) -> {
                    if (focused) p.preview.chose(choice);
                });
            }
        }
    }

    // ---- Welcome ------------------------------------------------------------------------------------------

    /** The two banners: when the TV starts, and when it wakes. */
    private void welcomePage() {
        title.setText(R.string.welcome);
        for (String when : new String[]{Welcome.START, Welcome.WAKE}) {
            int name = Welcome.START.equals(when) ? R.string.welcome_start : R.string.welcome_wake;
            View row = row(0, home.getString(name), Welcome.on(home, when)
                    ? home.getString(R.string.vol_seconds_value, String.valueOf(Welcome.SECONDS[Welcome.seconds(home, when)]))
                    : home.getString(R.string.bt_off), when, true, () -> push(BANNER + when));
            ImageView icon = row.findViewById(R.id.icon);
            icon.setVisibility(View.VISIBLE);
            icon.setImageResource(Welcome.EMOJI_RES[Welcome.emoji(home, when)]);
            icon.setImageTintList((ColorStateList) null);
        }
    }

    /** One banner: on or off, its emoji, greeting and message, how long it stays, and a preview on Home. */
    private void bannerPage(String when) {
        title.setText(Welcome.START.equals(when) ? R.string.welcome_start : R.string.welcome_wake);
        View toggle = row(R.drawable.ic_wave, home.getString(R.string.welcome_show),
                home.getString(Welcome.on(home, when) ? R.string.bt_on : R.string.bt_off), "on", false, () -> {
                    Welcome.setOn(home, when, !Welcome.on(home, when));
                    render("on");
                });
        stepper(toggle, toggle::performClick, toggle::performClick);
        String[] emojiNames = new String[Welcome.EMOJI_NAMES.length];
        for (int i = 0; i < emojiNames.length; i++) emojiNames[i] = home.getString(Welcome.EMOJI_NAMES[i]);
        View emoji = listRow(0, home.getString(R.string.welcome_emoji), emojiNames, Welcome.EMOJI_RES,
                Welcome.emoji(home, when), "emoji", i -> {
                    Welcome.setEmoji(home, when, i);
                    View r = rows.findViewWithTag("emoji");
                    if (r != null) ((ImageView) r.findViewById(R.id.icon)).setImageResource(Welcome.EMOJI_RES[i]);
                }, null);
        ImageView icon = emoji.findViewById(R.id.icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageResource(Welcome.EMOJI_RES[Welcome.emoji(home, when)]);
        icon.setImageTintList((ColorStateList) null);
        String[] secondLabels = new String[Welcome.SECONDS.length];
        for (int i = 0; i < secondLabels.length; i++) {
            secondLabels[i] = home.getString(R.string.vol_seconds_value, String.valueOf(Welcome.SECONDS[i]));
        }
        listRow(R.drawable.ic_clock, home.getString(R.string.welcome_seconds), secondLabels, null,
                Welcome.seconds(home, when), "seconds", i -> Welcome.setSeconds(home, when, i), null);
        header(home.getString(R.string.welcome_title));
        textField(Welcome.title(home, when), R.string.welcome_title, "title", text -> Welcome.setTitle(home, when, text));
        header(home.getString(R.string.welcome_message));
        textField(Welcome.message(home, when), R.string.welcome_message, "message", text -> Welcome.setMessage(home, when, text));
        row(0, home.getString(R.string.welcome_preview), null, "preview", false, () -> {
            dismiss();
            home.showWelcome(when, true);
        });
    }

    // ---- Volume display -----------------------------------------------------------------------------------

    /** The volume display's choices; each change shows the display on Home as a preview. */
    private void volumePage() {
        title.setText(R.string.volume);
        VolumeView.Look look = VolumeView.Look.read(home);
        View on = row(R.drawable.ic_volume, home.getString(R.string.vol_on), home.getString(look.enabled ? R.string.bt_on : R.string.bt_off),
                "on", false, () -> {
                    VolumeView.Look l = VolumeView.Look.read(home);
                    l.enabled = !l.enabled;
                    l.save(home);
                    home.setVolumeService(l.enabled);
                    if (l.enabled) home.previewVolume();
                    render("on");
                });
        stepper(on, on::performClick, on::performClick);
        if (!look.enabled) {
            row(R.drawable.ic_info, home.getString(R.string.vol_off_hint), null, "hint", false, null);
            return;
        }
        cycleRow(R.drawable.ic_tune, R.string.vol_style, VolumeView.STYLE_NAMES, look.style, "style", i -> {
            VolumeView.Look l = VolumeView.Look.read(home);
            l.style = i;
            l.save(home);
            home.previewVolume();
        });
        cycleRow(R.drawable.ic_gloss, R.string.vol_colour, VolumeView.COLOUR_NAMES, look.colour, "colour", i -> {
            VolumeView.Look l = VolumeView.Look.read(home);
            l.colour = i;
            l.save(home);
            home.previewVolume();
        });
        cycleRow(R.drawable.ic_picture, R.string.vol_size, VolumeView.SIZE_NAMES, look.size, "size", i -> {
            VolumeView.Look l = VolumeView.Look.read(home);
            l.size = i;
            l.save(home);
            home.previewVolume();
        });
        View number = row(R.drawable.ic_info, home.getString(R.string.vol_number), home.getString(look.number ? R.string.bt_on : R.string.bt_off),
                "number", false, () -> {
                    VolumeView.Look l = VolumeView.Look.read(home);
                    l.number = !l.number;
                    l.save(home);
                    home.previewVolume();
                    render("number");
                });
        stepper(number, number::performClick, number::performClick);
        View sticky = row(R.drawable.ic_volume, home.getString(R.string.vol_sticky), home.getString(look.stickyMute ? R.string.bt_on : R.string.bt_off),
                "sticky", false, () -> {
                    VolumeView.Look l = VolumeView.Look.read(home);
                    l.stickyMute = !l.stickyMute;
                    l.save(home);
                    render("sticky");
                });
        stepper(sticky, sticky::performClick, sticky::performClick);
        int[] secondNames = {R.string.vol_secs_short, R.string.vol_secs_medium, R.string.vol_secs_long};
        cycleRow(R.drawable.ic_clock, R.string.vol_seconds, secondNames, look.seconds, "seconds", i -> {
            VolumeView.Look l = VolumeView.Look.read(home);
            l.seconds = i;
            l.save(home);
        });
        row(R.drawable.ic_info, home.getString(R.string.vol_hint), null, "hint", false, null);
    }

    // ---- Sounds -------------------------------------------------------------------------------------------

    /** The interface sounds: the pack (each plays as you move through the list), volume, and the Back sound. */
    private void soundsPage() {
        title.setText(R.string.snd_title);
        View onOff = row(R.drawable.ic_volume, home.getString(R.string.snd_on),
                home.getString(TvSounds.enabled(home) ? R.string.bt_on : R.string.bt_off), "on", false, () -> {
                    TvSounds.setSwitch(home, !TvSounds.enabled(home));
                    render("on");
                });
        stepper(onOff, onOff::performClick, onOff::performClick);
        String[] packs = new String[TvSounds.PACKS.length];
        for (int i = 0; i < packs.length; i++) packs[i] = home.getString(TvSounds.PACK_NAMES[i]);
        listRow(R.drawable.ic_volume, home.getString(R.string.snd_pack), packs, null, TvSounds.pack(home), "pack",
                i -> TvSounds.choose(home, i, TvSounds.volume(home), TvSounds.backSound(home)),
                i -> home.homeRoot().postDelayed(() -> TvSounds.preview(home, i, TvSounds.volume(home)), 120));
        String[] volumes = new String[TvSounds.VOLUMES.length];
        for (int i = 0; i < volumes.length; i++) volumes[i] = home.getString(TvSounds.VOLUME_NAMES[i]);
        listRow(R.drawable.ic_gloss, home.getString(R.string.snd_volume), volumes, null, TvSounds.volume(home), "volume",
                i -> TvSounds.choose(home, TvSounds.pack(home), i, TvSounds.backSound(home)),
                i -> home.homeRoot().postDelayed(() -> TvSounds.preview(home, TvSounds.pack(home), i), 120));
        View back = row(R.drawable.ic_swap_vert, home.getString(R.string.snd_back),
                home.getString(TvSounds.backSound(home) ? R.string.bt_on : R.string.bt_off), "back", false, () -> {
                    TvSounds.choose(home, TvSounds.pack(home), TvSounds.volume(home), !TvSounds.backSound(home));
                    render("back");
                });
        stepper(back, back::performClick, back::performClick);
        header(home.getString(R.string.snd_greetings));
        View welcome = row(R.drawable.ic_wave, home.getString(R.string.snd_welcome),
                home.getString(TvSounds.welcomeOn(home) ? R.string.bt_on : R.string.bt_off), "welcome", false, () -> {
                    boolean on = !TvSounds.welcomeOn(home);
                    TvSounds.setGreetings(home, on, TvSounds.goodbyeOn(home));
                    if (on) TvSounds.welcome(home, true);
                    render("welcome");
                });
        stepper(welcome, welcome::performClick, welcome::performClick);
        View goodbye = row(R.drawable.ic_power, home.getString(R.string.snd_goodbye),
                home.getString(TvSounds.goodbyeOn(home) ? R.string.bt_on : R.string.bt_off), "goodbye", false, () -> {
                    boolean on = !TvSounds.goodbyeOn(home);
                    TvSounds.setGreetings(home, TvSounds.welcomeOn(home), on);
                    if (on) TvSounds.goodbye(home, true);
                    render("goodbye");
                });
        stepper(goodbye, goodbye::performClick, goodbye::performClick);
        row(R.drawable.ic_info, home.getString(R.string.snd_goodbye_hint), null, "goodbyehint", false, null);
        if (TvSounds.enabled(home) && Comfort.nightMode(home)) {
            row(R.drawable.ic_info, home.getString(R.string.snd_off_hint), null, "hint", false, null);
        }
    }

    // ---- Clock --------------------------------------------------------------------------------------------

    private void clockPage() {
        title.setText(R.string.clock);
        cycleRow(R.drawable.ic_clock, R.string.clock_style, Clock.STYLE_NAMES, Clock.style(home), "style", i -> {
            Clock.setStyle(home, i);
            home.applyClock();
        });
        cycleRow(R.drawable.ic_swap_vert, R.string.clock_place, Clock.PLACE_NAMES, Clock.place(home), "place", i -> {
            Clock.setPlace(home, i);
            home.applyClock();
        });
        header(home.getString(R.string.night_clock));
        View on = row(R.drawable.ic_night, home.getString(R.string.night_clock),
                home.getString(Comfort.nightClock(home) ? R.string.bt_on : R.string.bt_off), "nightclock", false, () -> {
                    Comfort.setNightClock(home, !Comfort.nightClock(home), Comfort.nightFrom(home), Comfort.nightUntil(home));
                    home.updateNight();
                    render("nightclock");
                });
        stepper(on, on::performClick, on::performClick);
        hourRow(R.string.night_from, "from", Comfort.nightFrom(home), h -> {
            Comfort.setNightClock(home, Comfort.nightClock(home), h, Comfort.nightUntil(home));
            home.updateNight();
        });
        hourRow(R.string.night_until, "until", Comfort.nightUntil(home), h -> {
            Comfort.setNightClock(home, Comfort.nightClock(home), Comfort.nightFrom(home), h);
            home.updateNight();
        });
    }

    /** A row that steps through the hours of the day, shown as times (23:00 or 11 pm). */
    private void hourRow(int name, String tag, int hour, Choose choose) {
        String[] hours = new String[24];
        for (int h = 0; h < 24; h++) hours[h] = hourText(h);
        listRow(R.drawable.ic_clock, home.getString(name), hours, null, hour, tag, choose, null);
    }

    private String hourText(int hour) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, 0);
        return android.text.format.DateFormat.getTimeFormat(home).format(c.getTime());
    }

    interface Edit {
        void changed(String text);
    }

    /** A text field in the sheet's glass style; Gboard comes up on OK. Saved as you type. */
    private void textField(String text, int hint, String tag, Edit onChange) {
        EditText field = new EditText(home);
        field.setText(text);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x80FFFFFF);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        field.setBackgroundResource(R.drawable.cc_tile_bg);
        int p = (int) (16 * density);
        field.setPadding(p, 0, p, 0);
        field.setTag(tag);
        field.setOnFocusChangeListener((v, focused) -> field.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(android.text.Editable s) {
                onChange.changed(s.toString());
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (54 * density));
        lp.bottomMargin = (int) (4 * density);
        rows.addView(field, lp);
    }

    // ---- Control Center & Home ----------------------------------------------------------------------------

    /** Every control: Control Center's first, in its order, then the rest. OK on one opens its choices. */
    private void controlsPage() {
        title.setText(R.string.set_controls);
        List<String> cc = Controls.controlCenter(home);
        List<String> onHome = Controls.home(home);
        header(home.getString(R.string.set_in_cc));
        for (String key : cc) controlRow(key, onHome);
        header(home.getString(R.string.set_more_controls));
        for (String key : Controls.ALL) if (!cc.contains(key)) controlRow(key, onHome);
    }

    private void controlRow(String key, List<String> onHome) {
        boolean inCc = Controls.controlCenter(home).contains(key);
        String value = inCc && !Controls.GLOSS.equals(key) ? home.getString(Controls.SIZE_NAMES[Controls.size(home, key)])
                : onHome.contains(key) ? home.getString(R.string.set_on_home) : null;
        row(Controls.icon(key), home.getString(Controls.label(key)), value, CONTROL + key, true, () -> push(CONTROL + key));
    }

    /** One control: in Control Center or not, on Home or not, and its place in Control Center. */
    private void controlPage(String key) {
        title.setText(Controls.label(key));
        boolean inCc = Controls.controlCenter(home).contains(key);
        View cc = row(R.drawable.ic_control_center, home.getString(R.string.set_show_cc),
                home.getString(inCc ? R.string.bt_on : R.string.bt_off), "cc", false, () -> {
                    Controls.setInControlCenter(home, key, !Controls.controlCenter(home).contains(key));
                    render("cc");
                });
        stepper(cc, cc::performClick, cc::performClick);
        if (Controls.canBeOnHome(key)) {
            boolean onHome = Controls.home(home).contains(key);
            String value = home.getString(onHome ? R.string.bt_on : R.string.bt_off);
            if (onHome && Controls.status(key)) value = home.getString(R.string.set_while_on);
            View pill = row(R.drawable.ic_home_settings_pill, home.getString(R.string.set_show_home), value, "home", false, () -> {
                Controls.setOnHome(home, key, !Controls.home(home).contains(key));
                home.refreshHomePills();
                render("home");
            });
            stepper(pill, pill::performClick, pill::performClick);
        }
        if (inCc && !Controls.GLOSS.equals(key)) {
            cycleRow(R.drawable.ic_apps, R.string.cc_size, Controls.SIZE_NAMES, Controls.size(home, key), "size",
                    i -> Controls.setSize(home, key, i));
        }
        if (inCc) {
            header(home.getString(R.string.set_position));
            List<String> order = Controls.controlCenter(home);
            int at = order.indexOf(key);
            row(0, home.getString(R.string.set_move_up), (at + 1) + " / " + order.size(), "up", false, () -> {
                Controls.move(home, key, -1);
                render("up");
            });
            row(0, home.getString(R.string.set_move_down), null, "down", false, () -> {
                Controls.move(home, key, 1);
                render("down");
            });
        }
    }

    // ---- Wi-Fi --------------------------------------------------------------------------------------------

    private String wifiSummary() {
        if (wifi == null) return null;
        if (!wifi.isWifiEnabled()) return home.getString(R.string.bt_off);
        String ssid = currentSsid();
        return ssid != null ? ssid : home.getString(R.string.cc_wifi_off);
    }

    /** The connected network's name, without Android's quotes; null when not connected or unknown. */
    private String currentSsid() {
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) return null;
        String ssid = info.getSSID();
        if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.equals("<unknown ssid>")) return null;
        return unquote(ssid);
    }

    private static String unquote(String ssid) {
        return ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
    }

    private String signal(int rssi) {
        int level = WifiManager.calculateSignalLevel(rssi, 4);
        return home.getString(level >= 3 ? R.string.set_signal_excellent : level == 2 ? R.string.set_signal_good
                : level == 1 ? R.string.set_signal_fair : R.string.set_signal_weak);
    }

    private void wifiPage() {
        title.setText(R.string.wifi);
        if (wifi == null) {
            row(R.drawable.ic_wifi, home.getString(R.string.not_available), null, null, false, null);
            return;
        }
        boolean on = wifi.isWifiEnabled();
        View toggle = row(R.drawable.ic_wifi, home.getString(R.string.wifi), home.getString(on ? R.string.bt_on : R.string.bt_off),
                "toggle", false, () -> {
                    wifi.setWifiEnabled(!wifi.isWifiEnabled());
                    handler.postDelayed(() -> render("toggle"), 600);
                });
        stepper(toggle, () -> toggle.performClick(), () -> toggle.performClick());
        if (!on) return;
        String current = currentSsid();
        WifiInfo info = wifi.getConnectionInfo();
        if (current != null) {
            header(home.getString(R.string.cc_connected));
            row(R.drawable.ic_wifi, current, signal(info.getRssi()), NETWORK + current, true, () -> push(NETWORK + current));
        }
        header(home.getString(R.string.set_networks));
        Set<String> saved = new HashSet<>();
        try {
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs != null) for (WifiConfiguration c : configs) if (c.SSID != null) saved.add(unquote(c.SSID));
        } catch (SecurityException ignored) {
            // Without location access: no "Saved" labels.
        }
        Map<String, ScanResult> best = new HashMap<>();
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            results = Collections.emptyList();
        }
        for (ScanResult r : results) {
            if (TextUtils.isEmpty(r.SSID) || r.SSID.equals(current)) continue;
            ScanResult known = best.get(r.SSID);
            if (known == null || r.level > known.level) best.put(r.SSID, r);
        }
        List<ScanResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> Integer.compare(b.level, a.level));
        if (list.isEmpty()) {
            row(0, home.getString(R.string.bt_searching), null, "searching", false, null);
        }
        for (int i = 0; i < Math.min(15, list.size()); i++) {
            ScanResult r = list.get(i);
            boolean secured = secured(r.capabilities);
            boolean isSaved = saved.contains(r.SSID);
            String value = (isSaved ? home.getString(R.string.set_saved) + " · " : "") + signal(r.level);
            View row = row(secured ? R.drawable.ic_lock_small : R.drawable.ic_wifi, r.SSID, value, "net:" + r.SSID, !isSaved && secured, () -> {
                if (isSaved) connectSaved(r.SSID);
                else if (!secured) connect(r.SSID, null, r.capabilities);
                else {
                    security.put(r.SSID, r.capabilities);
                    push(PASSWORD + r.SSID);
                }
            });
            row.setContentDescription(r.SSID);
        }
        View rescan = row(0, home.getString(R.string.bt_search_again), null, "rescan", false, () -> {
            wifi.startScan();
            Toast.makeText(home, R.string.bt_searching, Toast.LENGTH_SHORT).show();
        });
        rescan.setAlpha(0.8f);
    }

    private static boolean secured(String capabilities) {
        return capabilities != null && (capabilities.contains("WPA") || capabilities.contains("WEP")
                || capabilities.contains("PSK") || capabilities.contains("SAE") || capabilities.contains("EAP"));
    }

    /** The connected network: its details, and disconnect or forget. */
    private void networkPage(String ssid) {
        title.setText(ssid);
        WifiInfo info = wifi.getConnectionInfo();
        boolean connected = ssid.equals(currentSsid());
        if (connected) {
            row(0, home.getString(R.string.set_signal), signal(info.getRssi()), null, false, null);
            row(0, home.getString(R.string.set_ip), ipAddress(), null, false, null);
            row(0, home.getString(R.string.set_frequency), (info.getFrequency() >= 4900 ? "5 GHz" : "2.4 GHz"), null, false, null);
            row(0, home.getString(R.string.set_speed), info.getLinkSpeed() + " Mbps", null, false, null);
        }
        header(home.getString(R.string.set_actions));
        if (connected) {
            row(0, home.getString(R.string.set_disconnect), null, "disconnect", false, () -> {
                wifi.disconnect();
                back();
            });
        }
        row(0, home.getString(R.string.set_forget), null, "forget", false, () -> {
            int id = networkId(ssid);
            if (id != -1 && wifi.removeNetwork(id)) {
                wifi.saveConfiguration();
                Toast.makeText(home, home.getString(R.string.set_forgotten, ssid), Toast.LENGTH_SHORT).show();
                back();
            } else {
                // Android lets an app forget only the networks it added itself.
                Toast.makeText(home, R.string.set_forget_android, Toast.LENGTH_LONG).show();
                dismiss();
                home.open(home.systemIntent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
    }

    private void passwordPage(String ssid) {
        title.setText(ssid);
        EditText field = new EditText(home);
        field.setHint(R.string.set_password);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x80FFFFFF);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        field.setBackgroundResource(R.drawable.cc_tile_bg);
        int p = (int) (16 * density);
        field.setPadding(p, 0, p, 0);
        field.setTag("field");
        field.setOnFocusChangeListener((v, focused) -> field.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF));
        rows.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (54 * density)));
        Runnable go = () -> {
            String password = field.getText().toString();
            if (password.length() < 8 && !security.getOrDefault(ssid, "").contains("WEP")) {
                Toast.makeText(home, R.string.set_password_short, Toast.LENGTH_SHORT).show();
                return;
            }
            connect(ssid, password, security.get(ssid));
            back();
        };
        field.setOnEditorActionListener((v, actionId, event) -> {
            go.run();
            return true;
        });
        View show = row(0, home.getString(R.string.set_show_password), home.getString(R.string.bt_off), "show", false, null);
        show.setOnClickListener(v -> {
            boolean visible = (field.getInputType() & InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0;
            field.setInputType(InputType.TYPE_CLASS_TEXT | (visible ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
            field.setSelection(field.getText().length());
            ((TextView) show.findViewById(R.id.value)).setText(visible ? R.string.bt_off : R.string.bt_on);
        });
        row(R.drawable.ic_wifi, home.getString(R.string.set_connect), null, "connect", false, go);
    }

    private int networkId(String ssid) {
        try {
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs != null) for (WifiConfiguration c : configs) if (ssid.equals(unquote(String.valueOf(c.SSID)))) return c.networkId;
        } catch (SecurityException ignored) {
            // Unknown.
        }
        return -1;
    }

    private void connectSaved(String ssid) {
        int id = networkId(ssid);
        if (id == -1) {
            Toast.makeText(home, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        wifi.disconnect();
        wifi.enableNetwork(id, true);
        wifi.reconnect();
        Toast.makeText(home, home.getString(R.string.set_connecting, ssid), Toast.LENGTH_SHORT).show();
    }

    /** Adds the network (WPA/WPA2 with a password, WEP, or open) and switches to it. */
    private void connect(String ssid, String password, String capabilities) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        if (password == null) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else if (capabilities != null && capabilities.contains("WEP")) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            config.wepKeys[0] = "\"" + password + "\"";
            config.wepTxKeyIndex = 0;
        } else {
            config.preSharedKey = "\"" + password + "\"";
        }
        int id = wifi.addNetwork(config);
        if (id == -1) id = networkId(ssid);
        if (id == -1) {
            Toast.makeText(home, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        wifi.disconnect();
        wifi.enableNetwork(id, true);
        wifi.reconnect();
        Toast.makeText(home, home.getString(R.string.set_connecting, ssid), Toast.LENGTH_SHORT).show();
    }

    /** While a Wi-Fi page is open: rescan, and redraw as results, the connection or Wi-Fi itself change. */
    private void startWifi() {
        if (wifiReceiver != null || wifi == null) return;
        wifiReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(intent.getAction())
                        && intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING) {
                    Toast.makeText(home, R.string.set_wrong_password, Toast.LENGTH_LONG).show();
                }
                String page = stack.peek();
                if (isShowing() && page != null && (WIFI.equals(page) || page.startsWith(NETWORK))) {
                    render(null);
                }
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        home.registerReceiver(wifiReceiver, filter);
        wifi.startScan();
    }

    private void stopWifi() {
        if (wifiReceiver == null) return;
        try {
            home.unregisterReceiver(wifiReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already gone.
        }
        wifiReceiver = null;
    }

    // ---- Picture & sound ----------------------------------------------------------------------------------

    private void soundPage() {
        title.setText(R.string.set_sound);
        AudioManager audio = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        View volume = row(R.drawable.ic_volume, home.getString(R.string.set_volume),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " / " + max, "volume", false, null);
        Runnable[] step = new Runnable[2];
        for (int d = 0; d < 2; d++) {
            int delta = d == 0 ? -1 : 1;
            step[d] = () -> {
                int v = Math.max(0, Math.min(max, audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta));
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_PLAY_SOUND);
                ((TextView) volume.findViewById(R.id.value)).setText(v + " / " + max);
            };
        }
        stepper(volume, step[0], step[1]);
        volume.setOnClickListener(v -> step[1].run());

        // The launcher's own sounds, apart from Android's "Sound effects" (which Xiaomi firmware turns off by itself).
        View sounds = row(R.drawable.ic_gloss, home.getString(R.string.set_sound_effects),
                home.getString(TvSounds.enabled(home) ? R.string.bt_on : R.string.bt_off), "effects", false, () -> {
                    TvSounds.setSwitch(home, !TvSounds.enabled(home));
                    render("effects");
                });
        stepper(sounds, sounds::performClick, sounds::performClick);
        header(home.getString(R.string.set_more));
        // Picture modes and brightness belong to the TV maker's own settings (Xiaomi's is droidlogic's); no app can reach them.
        androidRow(DeviceSettings.PICTURE);
    }

    interface Apply {
        boolean set(int ms);
    }

    /** A row that steps through times: OK or Right for the next, Left for the previous. */
    private void choiceRow(int icon, int name, String tag, int[] choices, int current, Apply apply) {
        int at = 0;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = duration(choices[i]);
            if (choices[i] == current) at = i;
        }
        listRow(icon, home.getString(name), labels, null, at, tag, i -> {
            if (!apply.set(choices[i])) Toast.makeText(home, R.string.set_no_permission, Toast.LENGTH_LONG).show();
        }, null);
    }

    private String duration(int ms) {
        if (ms < 0 || ms == Integer.MAX_VALUE) return home.getString(R.string.set_never);
        int minutes = ms / 60_000;
        return minutes >= 60 ? home.getString(R.string.set_hours, minutes / 60) : home.getString(R.string.set_minutes, minutes);
    }

    // ---- Apps ---------------------------------------------------------------------------------------------

    private List<ResolveInfo> launchable() {
        PackageManager pm = home.getPackageManager();
        List<ResolveInfo> all = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        Set<String> seen = new HashSet<>();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo r : all) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(home.getPackageName()) || !seen.add(pkg)) continue;
            apps.add(r);
        }
        Collections.sort(apps, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        return apps;
    }

    private void appsPage() {
        title.setText(R.string.set_apps);
        PackageManager pm = home.getPackageManager();
        List<ResolveInfo> apps = launchable();
        List<String> missing = new ArrayList<>();
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            Long size = appSizes.get(pkg);
            if (size == null) missing.add(pkg);
            View row = row(0, r.loadLabel(pm), size == null ? null : Formatter.formatShortFileSize(home, size),
                    APP + pkg, true, () -> push(APP + pkg));
            ImageView icon = row.findViewById(R.id.icon);
            Drawable drawable = r.loadIcon(pm);
            icon.setImageDrawable(drawable);
            icon.setImageTintList((ColorStateList) null);
            icon.setVisibility(View.VISIBLE);
        }
        if (!missing.isEmpty()) {
            // Sizes need usage access (granted by the install script); work them out off the main thread.
            Executors.newSingleThreadExecutor().execute(() -> {
                for (String pkg : missing) appSizes.put(pkg, appSize(pkg));
                handler.post(() -> {
                    if (isShowing() && APPS.equals(stack.peek())) render(null);
                });
            });
        }
    }

    /** App, data and cache together, in bytes; 0 without usage access. */
    private long appSize(String pkg) {
        try {
            StorageStatsManager stats = (StorageStatsManager) home.getSystemService(Context.STORAGE_STATS_SERVICE);
            StorageStats s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
            return s.getAppBytes() + s.getDataBytes() + s.getCacheBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    private void appPage(String pkg) {
        PackageManager pm = home.getPackageManager();
        ApplicationInfo app;
        PackageInfo info;
        try {
            app = pm.getApplicationInfo(pkg, 0);
            info = pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            back();
            return;
        }
        title.setText(pm.getApplicationLabel(app));
        row(0, home.getString(R.string.set_version), info.versionName, null, false, null);
        Long size = appSizes.get(pkg);
        if (size != null && size > 0) row(0, home.getString(R.string.set_size), Formatter.formatShortFileSize(home, size), null, false, null);
        header(home.getString(R.string.set_actions));
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        Intent start = launch;
        if (start != null) {
            row(0, home.getString(R.string.set_open), null, "open", false, () -> {
                dismiss();
                home.open(start);
            });
        }
        row(0, home.getString(R.string.set_stop), null, "stop", false, () -> {
            // A launcher cannot force-stop another app; this ends its background processes, as Android allows.
            ((ActivityManager) home.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
            Toast.makeText(home, home.getString(R.string.set_stopped, pm.getApplicationLabel(app)), Toast.LENGTH_SHORT).show();
        });
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            row(0, home.getString(R.string.set_uninstall), null, "uninstall", false, () -> {
                dismiss();
                home.open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
            });
        }
        header(home.getString(R.string.set_more));
        externalRow(R.drawable.ic_settings, home.getString(R.string.set_app_info),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    // ---- About & storage ----------------------------------------------------------------------------------

    private void aboutPage() {
        title.setText(R.string.set_about);
        String maker = Build.MANUFACTURER.isEmpty() ? "" : Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1) + " ";
        row(0, home.getString(R.string.set_model), maker + Build.MODEL, null, false, null);
        row(0, home.getString(R.string.set_android_version), Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", null, false, null);
        row(0, home.getString(R.string.set_build), Build.DISPLAY, null, false, null);
        header(home.getString(R.string.set_network));
        String ssid = wifi == null ? null : currentSsid();
        row(0, home.getString(R.string.set_network), ssid != null ? ssid : home.networkConnected()
                ? home.getString(R.string.cc_connected) : home.getString(R.string.cc_wifi_off), null, false, null);
        row(0, home.getString(R.string.set_ip), ipAddress(), null, false, null);
        header(home.getString(R.string.set_storage));
        StatFs data = new StatFs(Environment.getDataDirectory().getPath());
        row(0, home.getString(R.string.set_storage), home.getString(R.string.set_free_of,
                Formatter.formatShortFileSize(home, data.getAvailableBytes()),
                Formatter.formatShortFileSize(home, data.getTotalBytes())), null, false, null);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) home.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        row(0, home.getString(R.string.set_memory), home.getString(R.string.set_free_of,
                Formatter.formatShortFileSize(home, memory.availMem),
                Formatter.formatShortFileSize(home, memory.totalMem)), null, false, null);
        try {
            String version = home.getPackageManager().getPackageInfo(home.getPackageName(), 0).versionName;
            row(0, home.getString(R.string.set_launcher_version), version, null, false, null);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Always found: it is this app.
        }
        // The information rows can take focus too, so the page scrolls with the remote.
        for (int i = 0; i < rows.getChildCount(); i++) rows.getChildAt(i).setFocusable(rows.getChildAt(i) instanceof LinearLayout);
    }

    /** The TV's IPv4 address on its active network, or a dash. */
    private String ipAddress() {
        ConnectivityManager cm = (ConnectivityManager) home.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm == null ? null : cm.getActiveNetwork();
        LinkProperties link = network == null ? null : cm.getLinkProperties(network);
        if (link != null) {
            for (LinkAddress address : link.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
            }
        }
        return "–";
    }

    /** Ethernet or not, for the hub's summary of the network. */
    static boolean ethernet(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }
}
