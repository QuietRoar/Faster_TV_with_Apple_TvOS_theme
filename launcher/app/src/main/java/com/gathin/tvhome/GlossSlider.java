package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The Control Center's Gloss slider track (tvos_style). It takes no focus itself: its pill does, handles Left and
 * Right, and shares its focused state (duplicateParentState), so the track turns dark on the pill's white focus.
 */
public class GlossSlider extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    /** 0 to 100. */
    private int value;

    public GlossSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    void setValue(int value) {
        this.value = Math.max(0, Math.min(100, value));
        invalidate();
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        boolean focused = false;
        for (int s : getDrawableState()) if (s == android.R.attr.state_focused) focused = true;
        int ink = focused ? 0x111111 : 0xFFFFFF;
        track.setColor(0x40000000 | ink);
        fill.setColor(0xE6000000 | ink);
        knob.setColor(focused ? 0xFF111111 : 0xFFFFFFFF);

        float h = 4 * density, r = 8 * density;
        float cy = getHeight() / 2f;
        float left = r, right = getWidth() - r;
        float x = left + (right - left) * value / 100f;
        canvas.drawRoundRect(left, cy - h / 2, right, cy + h / 2, h / 2, h / 2, track);
        canvas.drawRoundRect(left, cy - h / 2, x, cy + h / 2, h / 2, h / 2, fill);
        canvas.drawCircle(x, cy, r, knob);
    }
}
