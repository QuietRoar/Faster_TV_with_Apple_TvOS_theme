package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The AirPods connect card, drawn the way an iPhone shows it when AirPods are opened next to it: a dark rounded card
 * rising from the bottom of the screen, a close button top right, the model's name, a picture of the buds and their
 * case, each battery with its level, and one wide Connect button. The picture is drawn here in code (no Apple art).
 */
final class AirPodsCard extends FrameLayout {
    interface Listener {
        void onConnect();

        /** Close button, Back, or Not now: the owner said no. */
        void onClose();
    }

    private static final float WIDTH_DP = 440, RADIUS_DP = 40, BOTTOM_DP = 36;
    private static final int RISE_MS = 320, FALL_MS = 220;
    private static final int CARD = 0xF21C1C1E, BUTTON = 0x33FFFFFF, BUTTON_FOCUS = 0xFFFFFFFF;

    private final float density;
    private final Listener listener;
    private final TextView title;
    private final BatteryView left, right, box, both;
    private final TextView status;
    private final TextView connect;
    /** Connected mode: the AirPods are on, the card says so and its button is Done. */
    private boolean connected;
    private final TextView close;
    private boolean leaving;

    AirPodsCard(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(28), dp(26), dp(28), dp(26));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(RADIUS_DP * density);
        column.setBackground(bg);
        column.setElevation(24 * density);
        column.setClipChildren(false);   // the focused Connect button grows past its edges
        column.setClipToPadding(false);
        setClipChildren(false);

        title = text("AirPods", 24, 0xFFFFFFFF, true);
        title.setPadding(dp(36), 0, dp(36), 0);
        column.addView(title, wrap());
        status = text(ctx.getString(R.string.airpods_connected), 15, 0xFF8E8E93, false);
        status.setVisibility(GONE);
        column.addView(status, wrap());

        View art = new AirPodsArt(ctx);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(260), dp(150));
        artLp.topMargin = dp(14);
        column.addView(art, artLp);

        LinearLayout batteries = new LinearLayout(ctx);
        batteries.setOrientation(LinearLayout.HORIZONTAL);
        batteries.setGravity(Gravity.CENTER);
        left = new BatteryView(ctx, "L");
        right = new BatteryView(ctx, "R");
        box = new BatteryView(ctx, ctx.getString(R.string.airpods_case));
        both = new BatteryView(ctx, "AirPods");
        both.setVisibility(GONE);
        batteries.addView(both, gap(0));
        batteries.addView(left, gap(0));
        batteries.addView(right, gap(26));
        batteries.addView(box, gap(26));
        LinearLayout.LayoutParams batLp = wrap();
        batLp.topMargin = dp(12);
        column.addView(batteries, batLp);

        connect = text(ctx.getString(R.string.airpods_connect), 17, 0xFFFFFFFF, true);
        connect.setGravity(Gravity.CENTER);
        connect.setFocusable(true);
        connect.setBackground(pill(BUTTON));
        connect.setOnFocusChangeListener((v, f) -> {
            v.setBackground(pill(f ? BUTTON_FOCUS : BUTTON));
            ((TextView) v).setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(120).start();
        });
        connect.setOnClickListener(v -> listener.onConnect());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(22);
        column.addView(connect, btnLp);

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(column, columnLp);

        // The round close button in the card's top right corner, as on iOS.
        close = text("✕", 14, 0xFFAEAEB2, true);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setBackground(circle(0x2EFFFFFF));
        close.setElevation(25 * density);
        close.setOnFocusChangeListener((v, f) -> {
            v.setBackground(circle(f ? 0xFFFFFFFF : 0x2EFFFFFF));
            ((TextView) v).setTextColor(f ? 0xFF000000 : 0xFFAEAEB2);
        });
        close.setOnClickListener(v -> listener.onClose());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        closeLp.topMargin = dp(18);
        closeLp.rightMargin = dp(18);
        addView(close, closeLp);

        // Focus stays on the card: Up and Right reach the close button, Down and Left come back to Connect.
        OnKeyListener keys = (v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_BACK;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    listener.onClose();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    close.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    connect.requestFocus();
                    return true;
                default:
                    return false;
            }
        };
        connect.setOnKeyListener(keys);
        close.setOnKeyListener(keys);
    }

    /** Where the card sits in Home's root: centred at the bottom. */
    FrameLayout.LayoutParams placement() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(BOTTOM_DP);
        return lp;
    }

    /** The model and batteries from the latest message. */
    void update(String model, AirPodsWatch.Info info) {
        if (!connected) title.setText(model);
        boolean any = info.left != AirPodsWatch.UNKNOWN || info.right != AirPodsWatch.UNKNOWN
                || info.box != AirPodsWatch.UNKNOWN;
        if (connected && !any) return;   // keep what the Connected card already shows
        both.setVisibility(GONE);
        left.set(info.left, info.leftCharging);
        right.set(info.right, info.rightCharging);
        box.set(info.box, info.boxCharging);
    }

    /**
     * The AirPods connected (they do by themselves when the case opens next to the TV they were last used with):
     * "Connected" under the name, the one battery level Android reports for them (UNKNOWN hides it), and Done.
     */
    void setConnected(String model, int level) {
        connected = true;
        title.setText(model);
        status.setVisibility(VISIBLE);
        if (level != AirPodsWatch.UNKNOWN || (left.getVisibility() != VISIBLE && right.getVisibility() != VISIBLE)) {
            left.setVisibility(GONE);
            right.setVisibility(GONE);
            box.setVisibility(GONE);
            both.set(level, false);
        }
        status.setText(R.string.airpods_connected);
        connect.setVisibility(VISIBLE);
        connect.setText(R.string.airpods_done);
    }

    /** The case was closed: "Disconnected", no button; the host takes the card away a moment later. */
    void setDisconnected() {
        status.setText(R.string.airpods_disconnected);
        status.setVisibility(VISIBLE);
        connect.setVisibility(GONE);
        close.requestFocus();
    }

    boolean isConnected() {
        return connected;
    }

    /** Rises from below the screen and takes focus. */
    void rise() {
        setAlpha(0f);
        setTranslationY(260 * density);
        animate().alpha(1f).translationY(0f).setDuration(RISE_MS).setInterpolator(new DecelerateInterpolator(2f)).start();
        connect.requestFocus();
    }

    /** Sinks out of view, then runs done (which removes it). */
    void fall(Runnable done) {
        if (leaving) return;
        leaving = true;
        animate().alpha(0f).translationY(260 * density).setDuration(FALL_MS).withEndAction(done).start();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams gap(float leftDp) {
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(leftDp);
        return lp;
    }

    private TextView text(String value, float sp, int colour, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(UiFont.get(getContext(), R.string.font_medium), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable pill(int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(26 * density);
        return g;
    }

    private static GradientDrawable circle(int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        return g;
    }

    /** One battery: a small battery outline filled to its level, green while charging, and the percent and label. */
    private static final class BatteryView extends LinearLayout {
        private final Gauge gauge;
        private final TextView level;

        BatteryView(Context ctx, String label) {
            super(ctx);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            gauge = new Gauge(ctx);
            row.addView(gauge, new LayoutParams(Math.round(26 * d), Math.round(13 * d)));
            level = new TextView(ctx);
            level.setTextColor(0xFFFFFFFF);
            level.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            level.setPadding(Math.round(6 * d), 0, 0, 0);
            row.addView(level);
            addView(row);
            TextView name = new TextView(ctx);
            name.setText(label);
            name.setTextColor(0xFF8E8E93);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setGravity(Gravity.CENTER);
            addView(name);
        }

        void set(int percent, boolean charging) {
            setVisibility(percent == AirPodsWatch.UNKNOWN ? GONE : VISIBLE);
            level.setText(percent + "%");
            gauge.set(percent, charging);
        }
    }

    private static final class Gauge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int percent;
        private boolean charging;

        Gauge(Context ctx) {
            super(ctx);
        }

        void set(int percent, boolean charging) {
            this.percent = percent;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight();
            float tip = 2.5f * d, stroke = 1.2f * d, rad = 3.5f * d;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0x80FFFFFF);
            r.set(stroke, stroke, w - tip - stroke, h - stroke);
            c.drawRoundRect(r, rad, rad, paint);
            paint.setStyle(Paint.Style.FILL);
            r.set(w - tip, h * 0.32f, w, h * 0.68f);
            c.drawRoundRect(r, d, d, paint);
            boolean low = percent <= 20 && !charging;
            paint.setColor(charging ? 0xFF30D158 : low ? 0xFFFF453A : 0xFFFFFFFF);
            float inset = stroke + 1.5f * d, full = w - tip - 2 * inset;
            r.set(inset, inset, inset + full * Math.max(0.08f, percent / 100f), h - inset);
            c.drawRoundRect(r, 2 * d, 2 * d, paint);
        }
    }

    /** Two white buds and their open case, drawn with soft gradients on the dark card. */
    private static final class AirPodsArt extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        AirPodsArt(Context ctx) {
            super(ctx);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            // The case: a rounded white box, its lid open behind it, and the hinge line.
            float cw = w * 0.42f, ch = h * 0.56f, cx = w * 0.5f, top = h * 0.40f;
            shade(cx, top - ch * 0.52f, top);
            r.set(cx - cw / 2, top - ch * 0.52f, cx + cw / 2, top + ch * 0.06f);
            c.drawRoundRect(r, cw * 0.28f, cw * 0.28f, paint);
            shade(cx, top, top + ch);
            r.set(cx - cw / 2, top, cx + cw / 2, top + ch);
            c.drawRoundRect(r, cw * 0.30f, cw * 0.30f, paint);
            paint.setShader(null);
            paint.setColor(0x33000000);
            paint.setStrokeWidth(Math.max(1f, h * 0.008f));
            c.drawLine(cx - cw / 2 + cw * 0.06f, top + ch * 0.12f, cx + cw / 2 - cw * 0.06f, top + ch * 0.12f, paint);
            // The buds, one each side of the case, stems down.
            bud(c, w * 0.15f, h * 0.30f, h, false);
            bud(c, w * 0.85f, h * 0.30f, h, true);
        }

        private void bud(Canvas c, float x, float y, float h, boolean mirror) {
            float head = h * 0.16f, stemW = h * 0.07f, stemH = h * 0.40f, s = mirror ? -1f : 1f;
            shade(x, y - head, y + stemH + head);
            c.drawCircle(x, y, head, paint);
            r.set(x + s * head * 0.15f - stemW / 2, y + head * 0.2f, x + s * head * 0.15f + stemW / 2, y + head * 0.2f + stemH);
            c.drawRoundRect(r, stemW / 2, stemW / 2, paint);
            paint.setShader(null);
            paint.setColor(0xFF1C1C1E);
            c.drawCircle(x - s * head * 0.35f, y - head * 0.1f, head * 0.28f, paint);
        }

        private void shade(float x, float from, float to) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);   // full strength: a shader takes its alpha from the paint's colour
            paint.setShader(new LinearGradient(x, from, x, to, 0xFFFFFFFF, 0xFFC7C7CC, Shader.TileMode.CLAMP));
        }
    }
}
