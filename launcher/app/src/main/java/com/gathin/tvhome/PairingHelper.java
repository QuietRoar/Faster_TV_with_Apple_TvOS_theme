package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Bluetooth pairing that stays in the launcher. Only a system app may answer a pairing request
 * (BLUETOOTH_PRIVILEGED), so Android always opens its own "Pair with this device?" window. When the launcher has
 * just asked to pair a device, this covers that window with the launcher's own card ("Pairing with …"), presses
 * the window's Pair button at once (a headset gives up after about five seconds), and shows how it went.
 *
 * It acts only on Android's TV Settings (packageNames in pairing_service.xml), only within 30 seconds of a pairing
 * the owner started from the launcher, and only when the window names that device. A device that wants a PIN
 * typed gets Android's window uncovered, as no button press can answer it.
 */
public class PairingHelper extends AccessibilityService {
    private static final long EXPECT_MS = 30_000;
    /** The card leaves Android's window uncovered when no Pair button turned up in this time (a PIN to type). */
    private static final long NO_BUTTON_MS = 4_000;
    private static final long RESULT_MS = 2_200;

    private static PairingHelper running;
    private static String expectedName;
    private static long expectedUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private View cover;
    private TextView title, detail;
    private boolean pressed, seen;
    private final Runnable uncover = this::removeCover;

    /** Whether Android has this service running, so pairing can be kept in the launcher. */
    static boolean available() {
        return running != null;
    }

    /** The launcher is about to pair this device: cover Android's window when it comes and answer it. */
    static void begin(Context ctx, String deviceName) {
        expectedName = deviceName;
        expectedUntil = SystemClock.uptimeMillis() + EXPECT_MS;
        if (running == null) return;
        running.showCover(ctx.getString(R.string.pair_pairing, deviceName), ctx.getString(R.string.pair_wait));
        running.handler.postDelayed(running.uncover, EXPECT_MS);   // never left up, whatever happens
    }

    /** A step along the way (paired, now connecting): the card says it and stays. */
    static void update(Context ctx, String head, String body) {
        expectedName = null;
        if (running == null) return;
        running.showCover(head, body);
        running.handler.postDelayed(running.uncover, EXPECT_MS);
    }

    /** The pairing ended: the card says how, then goes. */
    static void finish(Context ctx, String head, String body) {
        expectedName = null;
        if (running == null) return;
        running.showCover(head, body);
        running.handler.postDelayed(running.uncover, RESULT_MS);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        windows = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (expectedName == null || SystemClock.uptimeMillis() > expectedUntil || pressed) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        // Android's window for this device, not any other screen of TV Settings.
        if (root.findAccessibilityNodeInfosByText(expectedName).isEmpty()) return;
        if (!seen) {
            seen = true;
            // A PIN to type: no Pair button turns up, and Android's window is needed after all.
            handler.postDelayed(() -> {
                if (!pressed) removeCover();
            }, NO_BUTTON_MS);
        }
        AccessibilityNodeInfo pair = findPairButton(root);
        if (pair == null) return;
        pressed = true;
        click(pair);
    }

    /** The window's Pair action: by the TV Settings' own word for it, else the English one. */
    private AccessibilityNodeInfo findPairButton(AccessibilityNodeInfo root) {
        String word = settingsString("bluetooth_pair", "Pair");
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(word);
        for (AccessibilityNodeInfo n : found) {
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().equalsIgnoreCase(word)) return n;
        }
        return null;
    }

    private String settingsString(String name, String fallback) {
        try {
            android.content.res.Resources res = getPackageManager().getResourcesForApplication("com.android.tv.settings");
            int id = res.getIdentifier(name, "string", "com.android.tv.settings");
            return id != 0 ? res.getString(id) : fallback;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            return fallback;
        }
    }

    /** Clicks the node, or the nearest clickable view around it (the action row holds the text). */
    private static void click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    // ---- the launcher's card over Android's window ------------------------------------------------------

    private void showCover(String head, String body) {
        if (windows == null) return;
        pressed = false;
        seen = false;
        if (cover == null) {
            cover = buildCover();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            try {
                windows.addView(cover, lp);
            } catch (RuntimeException e) {
                cover = null;
                return;
            }
        }
        title.setText(head);
        detail.setText(body);
        handler.removeCallbacksAndMessages(null);
    }

    private View buildCover() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(0xF20A0A0D);   // Android's window stays hidden underneath
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (36 * d);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xE61C1C20);
        glass.setCornerRadius(32 * d);
        glass.setStroke((int) d, 0x26FFFFFF);
        card.setBackground(glass);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_bluetooth);
        icon.setColorFilter(0xFFFFFFFF);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Themes.accent(this));
        icon.setBackground(disc);
        int ip = (int) (16 * d);
        icon.setPadding(ip, ip, ip, ip);
        card.addView(icon, new LinearLayout.LayoutParams((int) (72 * d), (int) (72 * d)));
        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        Typeface medium = UiFont.get(this, R.string.font_medium);
        if (medium != null) title.setTypeface(medium);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.topMargin = (int) (20 * d);
        card.addView(title, tl);
        detail = new TextView(this);
        detail.setTextColor(0xB3FFFFFF);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (8 * d);
        card.addView(detail, dl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams((int) (460 * d), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        screen.addView(card, cl);
        return screen;
    }

    private void removeCover() {
        handler.removeCallbacksAndMessages(null);
        if (cover != null) {
            try {
                windows.removeView(cover);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
        }
        cover = null;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        removeCover();
        if (running == this) running = null;
        super.onDestroy();
    }
}
