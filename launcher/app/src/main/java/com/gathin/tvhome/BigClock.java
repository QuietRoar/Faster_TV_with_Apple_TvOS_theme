package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * tvos_style's big clock, for the open sky above the apps (Clock.TOP_LEFT and Clock.CENTRE): the header clock's
 * styles drawn large, plus Stacked (hours over minutes) and Day (weekday, time and date). Drawn on the canvas once
 * a minute (MainActivity's time tick), never animated, so it costs one small view.
 */
public class BigClock extends View {
    /** One line of the clock: its text, how it is drawn, and the height it takes (digits have no descender). */
    private static final class Line {
        final String text;
        final Paint paint;
        final float height;

        Line(String text, Paint paint, float height) {
            this.text = text;
            this.paint = paint;
            this.height = height;
        }
    }

    /** How much of a font size the digits stand above the baseline, and capitals. */
    private static final float DIGIT_HEIGHT = 0.74f, CAPS_HEIGHT = 0.75f;

    private final float density, scaled;
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caps = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int style = Clock.LIGHT;
    private boolean centred;
    private int accent = 0xFF0A84FF;
    private boolean dark;

    public BigClock(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        scaled = context.getResources().getDisplayMetrics().scaledDensity;
        thin.setTypeface(UiFont.get(context, R.string.font_extralight));
        light.setTypeface(UiFont.get(context, R.string.font_light));
        Typeface medium = UiFont.get(context, R.string.font_medium);
        bold.setTypeface(Typeface.create(medium, Typeface.BOLD));
        small.setTypeface(UiFont.get(context, R.string.font_regular));
        caps.setTypeface(medium);
        caps.setLetterSpacing(0.14f);
        for (Paint p : new Paint[]{thin, light, bold}) p.setFontFeatureSettings("tnum");
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The style (Clock's), and whether its lines are centred (Clock.CENTRE) or start at the left. */
    void setStyle(int style, boolean centred) {
        this.style = style;
        this.centred = centred;
        requestLayout();
        invalidate();
    }

    /** The accent (Day's weekday, the widget's ring); dark ink over a bright sky, like the header. */
    void setColours(int accent, boolean dark) {
        if (accent == this.accent && dark == this.dark) return;
        this.accent = accent;
        this.dark = dark;
        invalidate();
    }

    /** Called on the minute: the text may change width (12:59 to 1:00), so it is measured again. */
    void tick() {
        requestLayout();
        invalidate();
    }

    private float sp(float v) {
        return v * scaled;
    }

    private String time(Date d) {
        return DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", d).toString();
    }

    private static String upper(CharSequence text) {
        return text.toString().toUpperCase(Locale.getDefault());
    }

    private Line digits(String text, Paint paint, float size) {
        paint.setTextSize(sp(size));
        return new Line(text, paint, sp(size) * DIGIT_HEIGHT);
    }

    private Line words(String text, Paint paint, float size) {
        paint.setTextSize(sp(size));
        return new Line(text, paint, sp(size) * CAPS_HEIGHT);
    }

    private List<Line> lines() {
        Date d = Calendar.getInstance().getTime();
        List<Line> out = new ArrayList<>();
        switch (style) {
            case Clock.BOLD:
                out.add(digits(time(d), bold, 104));
                break;
            case Clock.WITH_DATE:
                out.add(words(upper(DateFormat.format("EEEE d MMMM", d)), caps, 19));
                out.add(digits(time(d), light, 96));
                break;
            case Clock.GLASS:
                out.add(digits(time(d), light, 76));
                break;
            case Clock.LARGE:
                out.add(digits(time(d), thin, 150));
                break;
            case Clock.WIDGET:
                out.add(digits(time(d), bold, 52));
                out.add(words(DateFormat.format("EEEE, d MMMM", d).toString(), small, 19));
                break;
            case Clock.STACKED:
                boolean h24 = DateFormat.is24HourFormat(getContext());
                out.add(digits(DateFormat.format(h24 ? "HH" : "hh", d).toString(), light, 118));
                out.add(digits(DateFormat.format("mm", d).toString(), light, 118));
                break;
            case Clock.DAY:
                out.add(words(upper(DateFormat.format("EEEE", d)), caps, 21));
                out.add(digits(time(d), thin, 112));
                out.add(words(DateFormat.format("d MMMM", d).toString(), small, 22));
                break;
            default:
                out.add(digits(time(d), thin, 112));
        }
        return out;
    }

    /** The gap between lines: tight for the stacked digits, roomier under a caption. */
    private float gap() {
        return (style == Clock.STACKED ? 14 : 18) * density;
    }

    /** Glass and the widget sit in a glass card (set by MainActivity); the others float on the picture. */
    boolean carded() {
        return style == Clock.GLASS || style == Clock.WIDGET;
    }

    private float padX() {
        return carded() ? 30 * density : 4 * density;
    }

    private float padY() {
        return carded() ? 24 * density : 4 * density;
    }

    private float ringSpace() {
        return style == Clock.WIDGET ? 88 * density : 0f;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        List<Line> lines = lines();
        float w = 0, h = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            w = Math.max(w, l.paint.measureText(l.text));
            h += l.height + (i > 0 ? gap() : 0);
        }
        setMeasuredDimension(Math.round(w + ringSpace() + padX() * 2), Math.round(h + padY() * 2));
    }

    @Override
    protected void onDraw(Canvas c) {
        List<Line> lines = lines();
        int ink = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        boolean shadow = !dark && !carded();
        float left = padX() + ringSpace(), width = getWidth() - left - padX();
        if (style == Clock.WIDGET) drawRing(c);
        float y = padY();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (i > 0) y += gap();
            y += l.height;
            l.paint.setColor(colour(i, l, ink));
            if (shadow) l.paint.setShadowLayer(14 * density, 0, 2 * density, 0x66000000);
            else l.paint.clearShadowLayer();
            float x = centred ? left + (width - l.paint.measureText(l.text)) / 2f : left;
            c.drawText(l.text, x, y, l.paint);
        }
    }

    /** Day's weekday in the accent, captions a little dimmer, and Stacked's minutes softer than its hours. */
    private int colour(int index, Line line, int ink) {
        if (style == Clock.DAY && index == 0) return accent;
        if (line.paint == small || line.paint == caps) return (ink & 0x00FFFFFF) | 0xC0000000;
        if (style == Clock.STACKED && index == 1) return (ink & 0x00FFFFFF) | 0x9E000000;
        return ink;
    }

    /** The widget's ring: it fills as the day goes by, in the accent. */
    private void drawRing(Canvas c) {
        Calendar now = Calendar.getInstance();
        float day = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)) / 1440f;
        float r = 30 * density, cx = padX() + r, cy = getHeight() / 2f;
        arc.set(cx - r, cy - r, cx + r, cy + r);
        ring.setStrokeWidth(6 * density);
        ring.setColor(dark ? 0x331C1C1E : 0x33FFFFFF);
        c.drawOval(arc, ring);
        ring.setColor(accent);
        c.drawArc(arc, -90, 360 * day, false, ring);
    }
}
