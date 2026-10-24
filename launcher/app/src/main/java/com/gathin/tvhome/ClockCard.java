package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Calendar;
import java.util.Date;

/**
 * tvos_style's widget clock: a glass card with a ring that fills as the day goes by, the time and the date, like a
 * tvOS widget. Drawn once a minute (MainActivity's time tick), never animated.
 */
public class ClockCard extends View {
    private final float density;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private int accent = 0xFF0A84FF;
    private boolean dark;

    public ClockCard(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        time.setTypeface(UiFont.get(context, R.string.font_medium));
        date.setTypeface(UiFont.get(context, R.string.font_regular));
        time.setFontFeatureSettings("tnum");
    }

    /** The ring in the theme's accent; dark ink over a bright sky, as the rest of the header. */
    void setColours(int accent, boolean dark) {
        this.accent = accent;
        this.dark = dark;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension((int) (168 * density), (int) (54 * density));
    }

    @Override
    protected void onDraw(Canvas c) {
        Calendar now = Calendar.getInstance();
        float day = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)) / 1440f;
        float h = getHeight(), stroke = 3.5f * density, r = h / 2f - 9 * density;
        float cx = 10 * density + r, cy = h / 2f;
        arc.set(cx - r, cy - r, cx + r, cy + r);
        ring.setStrokeWidth(stroke);
        ring.setColor(dark ? 0x331C1C1E : 0x33FFFFFF);
        c.drawOval(arc, ring);
        ring.setColor(accent);
        c.drawArc(arc, -90, 360 * day, false, ring);
        int ink = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        float x = cx + r + 10 * density;
        time.setColor(ink);
        time.setTextSize(19 * density);
        Date d = now.getTime();
        String t = DateFormat.is24HourFormat(getContext()) ? DateFormat.format("HH:mm", d).toString()
                : DateFormat.format("h:mm", d).toString();
        c.drawText(t, x, cy - 1 * density, time);
        date.setColor(dark ? 0xB31C1C1E : 0xB3FFFFFF);
        date.setTextSize(11.5f * density);
        c.drawText(DateFormat.format("EEE d MMM", d).toString(), x, cy + 14 * density, date);
    }
}
