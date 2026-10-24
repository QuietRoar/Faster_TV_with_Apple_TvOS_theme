package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

/**
 * Draws TvKeyboard in the Apple TV way, on one canvas: a dark glass band across the bottom of the screen, what
 * has been typed and the tabs on top, and below them the long line of keys. The key the remote is on is a white
 * rounded square, a little larger, with dark ink; the rest are white letters on the glass.
 */
public class TvKeyboardView extends View {
    private final float density, scaled;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint key = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint typed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private KeyboardLayout layout;
    private CharSequence text = "";
    private CharSequence hint = "";
    private int accent = 0xFF0A84FF;
    private float[] tabRects = new float[0], keyRects = new float[0];

    public TvKeyboardView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        scaled = context.getResources().getDisplayMetrics().scaledDensity;
        key.setTypeface(UiFont.get(context, R.string.font_regular));
        key.setTextAlign(Paint.Align.CENTER);
        tab.setTypeface(UiFont.get(context, R.string.font_medium));
        tab.setTextAlign(Paint.Align.CENTER);
        typed.setTypeface(UiFont.get(context, R.string.font_regular));
        rim.setColor(0x24FFFFFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void bind(KeyboardLayout layout) {
        this.layout = layout;
        invalidate();
    }

    /** What the field holds (shown above the keys, as tvOS shows it), and its hint while it is empty. */
    void setText(CharSequence text, CharSequence hint) {
        this.text = text == null ? "" : text;
        this.hint = hint == null ? "" : hint;
        invalidate();
    }

    void setAccent(int accent) {
        this.accent = accent;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(168 * density));
    }

    private float margin() {
        return 48 * density;
    }

    private float tabTop() {
        return 22 * density;
    }

    private float keyTop() {
        return 84 * density;
    }

    /** Where each key of a row is across the screen, as pairs of left and right edges. */
    private float[] rects(int row) {
        List<String> keys = layout.row(row);
        float left = margin(), right = getWidth() - margin();
        float[] out = new float[keys.size() * 2];
        if (row == KeyboardLayout.TABS) {
            // The tabs keep to the right half, each wide enough for its word; the typed text has the left.
            float w = 74 * density, gap = 6 * density;
            float x = right - keys.size() * w - (keys.size() - 1) * gap;
            for (int i = 0; i < keys.size(); i++, x += w + gap) {
                out[i * 2] = x;
                out[i * 2 + 1] = x + w;
            }
            return out;
        }
        // Character keys share the width equally; ".com", Space and Delete are wider.
        float units = 0;
        for (String k : keys) units += weight(k);
        float unit = (right - left) / units;
        float x = left;
        for (int i = 0; i < keys.size(); i++) {
            float w = unit * weight(keys.get(i));
            out[i * 2] = x;
            out[i * 2 + 1] = x + w;
            x += w;
        }
        return out;
    }

    private static float weight(String k) {
        return KeyboardLayout.SPACE.equals(k) ? 3f : KeyboardLayout.DELETE.equals(k) ? 2f : k.length() > 1 ? 2f : 1f;
    }

    /** The centres of a row's keys as fractions of the width, for moving between the rows. */
    float[] centres(int row) {
        float[] edges = rects(row);
        float[] out = new float[edges.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (edges[i * 2] + edges[i * 2 + 1]) / 2f / Math.max(1, getWidth());
        return out;
    }

    float selectedFraction() {
        float[] c = centres(layout.row());
        return c.length == 0 ? 0f : c[Math.min(layout.column(), c.length - 1)];
    }

    @Override
    protected void onDraw(Canvas c) {
        if (layout == null) return;
        float w = getWidth(), h = getHeight();
        // The glass band: darker at the bottom, a hairline along its top.
        band.setShader(new LinearGradient(0, 0, 0, h, 0xF51E1E22, 0xFC0E0E10, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, band);
        c.drawRect(0, 0, w, density, rim);
        tabRects = rects(KeyboardLayout.TABS);
        keyRects = rects(KeyboardLayout.KEYS);
        drawTyped(c);
        drawTabs(c);
        drawKeys(c);
    }

    /** The field's text, the end kept in view, with a caret; the hint while it is empty. */
    private void drawTyped(Canvas c) {
        float x = margin(), base = tabTop() + 24 * density;
        float room = tabRects[0] - x - 24 * density;
        typed.setTextSize(22 * scaled);
        boolean empty = text.length() == 0;
        typed.setColor(empty ? 0x80FFFFFF : 0xFFFFFFFF);
        String shown = empty ? hint.toString() : text.toString();
        while (shown.length() > 1 && typed.measureText(shown) > room) shown = shown.substring(1);
        if (!empty && shown.length() < text.length()) shown = "…" + shown.substring(Math.min(1, shown.length()));
        c.drawText(TextUtils.isEmpty(shown) ? "" : shown, x, base, typed);
        if (!empty || hint.length() == 0) {
            float caretX = empty ? x : x + typed.measureText(shown) + 3 * density;
            fill.setColor(accent);
            c.drawRect(caretX, base - 22 * density, caretX + 2.5f * density, base + 5 * density, fill);
        }
    }

    private void drawTabs(Canvas c) {
        List<String> tabs = layout.row(KeyboardLayout.TABS);
        float top = tabTop(), bottom = top + 36 * density;
        tab.setTextSize(15 * scaled);
        for (int i = 0; i < tabs.size(); i++) {
            r.set(tabRects[i * 2], top, tabRects[i * 2 + 1], bottom);
            boolean on = layout.row() == KeyboardLayout.TABS && layout.column() == i;
            boolean chosen = i == layout.mode();
            String label = KeyboardLayout.DONE.equals(tabs.get(i)) ? getContext().getString(R.string.kb_done) : tabs.get(i);
            if (on) {
                fill.setColor(0xF5FFFFFF);
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
            } else if (chosen) {
                fill.setColor(0x2EFFFFFF);
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
            }
            tab.setColor(on ? 0xFF111111 : chosen ? 0xFFFFFFFF : 0xA6FFFFFF);
            c.drawText(label, r.centerX(), r.centerY() + 5.5f * scaled, tab);
        }
    }

    private void drawKeys(Canvas c) {
        List<String> keys = layout.row(KeyboardLayout.KEYS);
        float top = keyTop(), bottom = top + 54 * density;
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            boolean on = layout.row() == KeyboardLayout.KEYS && layout.column() == i;
            float l = keyRects[i * 2], rt = keyRects[i * 2 + 1];
            if (on) {
                // A white rounded key, grown a little past its slot, as the focused key is on Apple TV.
                float grow = 6 * density;
                r.set(l - grow / 2f, top - grow, rt + grow / 2f, bottom + grow);
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, 12 * density, 12 * density, fill);
            }
            boolean word = k.length() > 1;
            key.setTextSize((word ? 16 : on ? 30 : 26) * scaled);
            key.setTypeface(UiFont.get(getContext(), word ? R.string.font_medium : R.string.font_regular));
            key.setColor(on ? 0xFF111111 : word ? 0xB3FFFFFF : 0xEBFFFFFF);
            String label = KeyboardLayout.SPACE.equals(k) ? getContext().getString(R.string.kb_space)
                    : KeyboardLayout.DELETE.equals(k) ? "⌫" : k;
            if (KeyboardLayout.DELETE.equals(k)) key.setTextSize((on ? 30 : 26) * scaled);
            c.drawText(label, (l + rt) / 2f, (top + bottom) / 2f + (word && !KeyboardLayout.DELETE.equals(k) ? 6 : 10) * scaled, key);
        }
    }
}
