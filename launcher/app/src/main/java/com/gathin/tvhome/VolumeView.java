package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;

/**
 * tvos_style's own volume display, in one of six styles (Settings, Volume): drawn here so it looks the same over
 * any app (VolumeService shows it) and in the Settings preview. Each style draws itself, its frosted panel included;
 * muted, the speaker is struck through and the level greys out.
 */
public class VolumeView extends View {
    static final int GLASS_PILL = 0, TVOS_BAR = 1, SIDE_SLIDER = 2, RING = 3, CLASSIC = 4, MINIMAL = 5;
    static final int[] STYLE_NAMES = {R.string.vol_style_pill, R.string.vol_style_tvos, R.string.vol_style_side,
            R.string.vol_style_ring, R.string.vol_style_classic, R.string.vol_style_minimal};
    /** Colours: 0 follows the theme's accent, then white and a fixed palette. */
    static final int[] COLOURS = {0, 0xFFFFFFFF, 0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2};
    static final int[] COLOUR_NAMES = {R.string.vol_colour_theme, R.string.vol_colour_white, R.string.vol_colour_blue,
            R.string.vol_colour_green, R.string.vol_colour_orange, R.string.vol_colour_pink, R.string.vol_colour_purple};
    static final float[] SIZES = {0.8f, 1f, 1.25f};
    static final int[] SIZE_NAMES = {R.string.vol_size_small, R.string.vol_size_medium, R.string.vol_size_large};
    static final int[] SECONDS_MS = {1500, 2500, 4000};
    /** The Side slider covers System UI's own bar, so it stays at least as long as that bar does. */
    static int showMs(Look look) {
        return look.style == SIDE_SLIDER ? Math.max(3500, SECONDS_MS[look.seconds]) : SECONDS_MS[look.seconds];
    }

    /** The choices, kept with the other tvos_style ones. */
    static final class Look {
        boolean enabled;
        int style, colour, size, seconds;
        boolean number, stickyMute;

        static Look read(Context ctx) {
            SharedPreferences p = ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE);
            Look l = new Look();
            l.enabled = p.getBoolean("vol_on", false);
            l.style = clamp(p.getInt("vol_style", SIDE_SLIDER), STYLE_NAMES.length);
            l.colour = clamp(p.getInt("vol_colour", 0), COLOURS.length);
            l.size = clamp(p.getInt("vol_size", 1), SIZES.length);
            l.seconds = clamp(p.getInt("vol_seconds", 1), SECONDS_MS.length);
            l.number = p.getBoolean("vol_number", true);
            l.stickyMute = p.getBoolean("vol_sticky_mute", true);
            return l;
        }

        void save(Context ctx) {
            ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE).edit().putBoolean("vol_on", enabled)
                    .putInt("vol_style", style).putInt("vol_colour", colour).putInt("vol_size", size)
                    .putInt("vol_seconds", seconds).putBoolean("vol_number", number)
                    .putBoolean("vol_sticky_mute", stickyMute).apply();
        }

        int accent(Context ctx) {
            return COLOURS[colour] != 0 ? COLOURS[colour] : Themes.accent(ctx);
        }

        private static int clamp(int v, int n) {
            return v < 0 || v >= n ? 0 : v;
        }
    }

    private final float density;
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private int style, accent;
    private float scale = 1f;
    private boolean number;
    private int level, max = 1;
    private boolean muted;
    /** Only the struck-through speaker: the mute badge that stays while muted. */
    private boolean badge;

    public VolumeView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        panel.setColor(0xD91C1C1E);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        icon.setColor(0xFFFFFFFF);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(UiFont.get(context, R.string.font_medium) != null ? UiFont.get(context, R.string.font_medium) : Typeface.DEFAULT_BOLD);
        text.setFontFeatureSettings("tnum");   // the level's digits keep their width as it counts
    }

    void setLook(Look look, int accent) {
        style = look.style;
        // The Side slider sits over System UI's own volume bar, which must not show through it.
        panel.setColor(style == SIDE_SLIDER ? 0xFF1C1C20 : 0xD91C1C1E);
        scale = SIZES[look.size];
        number = look.number;
        this.accent = accent;
        requestLayout();
        invalidate();
    }

    void setLevel(int level, int max, boolean muted) {
        this.level = level;
        this.max = Math.max(1, max);
        this.muted = muted || level == 0;
        invalidate();
    }

    void setBadge(boolean badge) {
        this.badge = badge;
        requestLayout();
        invalidate();
    }

    /** Where the style sits on screen, as Gravity. */
    int gravity() {
        if (badge) return Gravity.TOP | Gravity.END;
        switch (style) {
            case TVOS_BAR: case MINIMAL: return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case SIDE_SLIDER: return Gravity.CENTER_VERTICAL | Gravity.END;
            case RING: case CLASSIC: return Gravity.CENTER;
            default: return Gravity.TOP | Gravity.END;
        }
    }

    /**
     * Its margin from the top edge, in px: the Glass pill hangs just under Home's header (the clock and the buttons),
     * above the TV's own volume bar on the right.
     */
    int marginTop() {
        return style == GLASS_PILL && !badge ? (int) (84 * density) : margin();
    }

    /** Its margin from the screen's edge, in px. */
    int margin() {
        if (style == SIDE_SLIDER && !badge) return (int) (14 * density);
        return style == MINIMAL && !badge ? 0 : (int) (28 * density);
    }

    private float dp(float v) {
        return v * density * scale;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w, h;
        if (badge) {
            w = h = (int) dp(56);
        } else {
            switch (style) {
                case TVOS_BAR: w = (int) dp(300); h = (int) dp(44); break;
                // Sized to cover System UI's bar (about 40 x 267 dp at the right edge), its rounded ends past that bar's.
                case SIDE_SLIDER: w = (int) (density * 64 * Math.max(1f, scale)); h = (int) (density * 330 * Math.max(1f, scale)); break;
                case RING: w = h = (int) dp(170); break;
                case CLASSIC: w = h = (int) dp(210); break;
                case MINIMAL: w = getResources().getDisplayMetrics().widthPixels; h = (int) dp(30); break;
                default: w = (int) dp(330); h = (int) dp(64); break;
            }
        }
        setMeasuredDimension(w, h);
    }

    private float fraction() {
        return muted ? 0f : level / (float) max;
    }

    private int ink() {
        return muted ? 0x66FFFFFF : accent;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        rim.setShader(new LinearGradient(0, 0, 0, h, 0x66FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        if (badge) {
            r.set(0, 0, w, h);
            c.drawOval(r, panel);
            c.drawOval(r, rim);
            speaker(c, w / 2f, h / 2f, dp(22), true);
            return;
        }
        switch (style) {
            case TVOS_BAR: tvosBar(c, w, h); break;
            case SIDE_SLIDER: side(c, w, h); break;
            case RING: ring(c, w, h); break;
            case CLASSIC: classic(c, w, h); break;
            case MINIMAL: minimal(c, w, h); break;
            default: pill(c, w, h); break;
        }
    }

    private void glass(Canvas c, float w, float h, float radius) {
        r.set(0, 0, w, h);
        c.drawRoundRect(r, radius, radius, panel);
        r.inset(density / 2, density / 2);
        c.drawRoundRect(r, radius, radius, rim);
    }

    private void bar(Canvas c, float left, float top, float right, float bottom) {
        float radius = (bottom - top) / 2f;
        track.setColor(0x33FFFFFF);
        r.set(left, top, right, bottom);
        c.drawRoundRect(r, radius, radius, track);
        float f = fraction();
        if (f <= 0) return;
        fill.setColor(ink());
        r.set(left, top, left + Math.max(bottom - top, (right - left) * f), bottom);
        c.drawRoundRect(r, radius, radius, fill);
    }

    private void count(Canvas c, float x, float y, float size, Paint.Align align) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(muted ? 0x99FFFFFF : 0xFFFFFFFF);
        c.drawText(String.valueOf(level), x, y, text);
    }

    private void pill(Canvas c, float w, float h) {
        glass(c, w, h, h / 2f);
        speaker(c, dp(36), h / 2f, dp(22), muted);
        float right = number ? w - dp(62) : w - dp(26);
        bar(c, dp(64), h / 2f - dp(5), right, h / 2f + dp(5));
        if (number) count(c, w - dp(26), h / 2f + dp(7), dp(19), Paint.Align.RIGHT);
    }

    private void tvosBar(Canvas c, float w, float h) {
        glass(c, w, h, h / 2f);
        speaker(c, dp(26), h / 2f, dp(18), muted);
        bar(c, dp(48), h / 2f - dp(3), w - dp(20), h / 2f + dp(3));
    }

    private void side(Canvas c, float w, float h) {
        glass(c, w, h, w / 2f);
        float left = w / 2f - dp(6), right = w / 2f + dp(6);
        float top = number ? dp(44) : dp(22), bottom = h - dp(56);
        track.setColor(0x33FFFFFF);
        r.set(left, top, right, bottom);
        c.drawRoundRect(r, dp(6), dp(6), track);
        float f = fraction();
        if (f > 0) {
            fill.setColor(ink());
            r.set(left, bottom - Math.max(dp(12), (bottom - top) * f), right, bottom);
            c.drawRoundRect(r, dp(6), dp(6), fill);
        }
        if (number) count(c, w / 2f, dp(32), dp(17), Paint.Align.CENTER);
        speaker(c, w / 2f, h - dp(30), dp(20), muted);
    }

    private void ring(Canvas c, float w, float h) {
        r.set(0, 0, w, h);
        c.drawOval(r, panel);
        c.drawOval(r, rim);
        float stroke = dp(10);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(0x26FFFFFF);
        r.set(stroke * 1.6f, stroke * 1.6f, w - stroke * 1.6f, h - stroke * 1.6f);
        c.drawArc(r, 135, 270, false, track);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(stroke);
        fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(ink());
        float f = fraction();
        if (f > 0) c.drawArc(r, 135, 270 * f, false, fill);
        fill.setStyle(Paint.Style.FILL);
        track.setStyle(Paint.Style.FILL);
        if (number && !muted) {
            count(c, w / 2f, h / 2f + dp(12), dp(40), Paint.Align.CENTER);
            speaker(c, w / 2f, h - dp(34), dp(16), false);
        } else {
            speaker(c, w / 2f, h / 2f, dp(40), muted);
        }
    }

    private void classic(Canvas c, float w, float h) {
        glass(c, w, h, dp(30));
        speaker(c, w / 2f, h * 0.4f, dp(70), muted);
        int segments = 16;
        float gap = dp(3), left = dp(24), right = w - dp(24);
        float seg = (right - left - gap * (segments - 1)) / segments;
        int lit = Math.round(fraction() * segments);
        float top = h - dp(38), bottom = h - dp(28);
        for (int i = 0; i < segments; i++) {
            Paint p = i < lit ? fill : track;
            if (i < lit) fill.setColor(ink());
            else track.setColor(0x33FFFFFF);
            float x = left + i * (seg + gap);
            r.set(x, top, x + seg, bottom);
            c.drawRoundRect(r, dp(1.5f), dp(1.5f), p);
        }
    }

    private void minimal(Canvas c, float w, float h) {
        fill.setColor(ink());
        float f = fraction();
        track.setColor(0x26FFFFFF);
        c.drawRect(0, 0, w, dp(4), track);
        if (f > 0) c.drawRect(0, 0, w * f, dp(4), fill);
        if (number) {
            text.setTextSize(dp(15));
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(0xE6FFFFFF);
            text.setShadowLayer(dp(4), 0, 0, 0xAA000000);
            c.drawText((muted ? "∅ " : "") + level, w - dp(24), dp(24), text);
            text.clearShadowLayer();
        }
    }

    /** A speaker with sound waves, or struck through when muted; size is its height. */
    private void speaker(Canvas c, float cx, float cy, float size, boolean struck) {
        float s = size / 24f;
        icon.setStyle(Paint.Style.FILL);
        icon.setColor(struck ? 0xCCFFFFFF : 0xFFFFFFFF);
        path.reset();
        path.moveTo(cx - 9 * s, cy - 4 * s);
        path.lineTo(cx - 5 * s, cy - 4 * s);
        path.lineTo(cx, cy - 9 * s);
        path.lineTo(cx, cy + 9 * s);
        path.lineTo(cx - 5 * s, cy + 4 * s);
        path.lineTo(cx - 9 * s, cy + 4 * s);
        path.close();
        c.drawPath(path, icon);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(2.2f * s);
        if (struck) {
            c.drawLine(cx + 4 * s, cy - 4 * s, cx + 11 * s, cy + 4 * s, icon);
            c.drawLine(cx + 11 * s, cy - 4 * s, cx + 4 * s, cy + 4 * s, icon);
            return;
        }
        float f = fraction();
        r.set(cx - 1 * s, cy - 5 * s, cx + 7 * s, cy + 5 * s);
        c.drawArc(r, -50, 100, false, icon);
        if (f > 0.4f) {
            r.set(cx - 3 * s, cy - 9 * s, cx + 11 * s, cy + 9 * s);
            c.drawArc(r, -50, 100, false, icon);
        }
    }
}
