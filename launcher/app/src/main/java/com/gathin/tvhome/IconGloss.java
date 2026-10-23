package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * tvos_style: the glass on every app icon, drawn as the tile's foreground over its banner. A rim that is bright
 * along the top edge and faint below, a soft sheen across the top, and on focus a slanted band of light that
 * crosses the icon once, as tvOS 26 icons catch the light. All of it follows the Gloss slider (LiquidGlass.gloss).
 */
final class IconGloss extends Drawable {
    private final float density;
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix unit = new Matrix();
    private final Matrix bandMatrix = new Matrix();
    private final RectF rect = new RectF();
    // Unit gradients (0 to 1) placed on the tile with a matrix, so a bind or a sweep frame allocates nothing.
    private final LinearGradient sheenShader = new LinearGradient(0, 0, 0, 1,
            new int[]{0x4AFFFFFF, 0x10FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
    private final LinearGradient rimShader = new LinearGradient(0, 0, 0, 1,
            new int[]{0xFFFFFFFF, 0x3DFFFFFF, 0x18FFFFFF, 0x66FFFFFF}, new float[]{0f, 0.3f, 0.75f, 1f},
            Shader.TileMode.CLAMP);
    private final LinearGradient bandShader = new LinearGradient(0, 0, 1, 0,
            new int[]{0x00FFFFFF, 0xA3FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
    private ValueAnimator sweepAnimator;
    /** Where the band of light is, 0 to 1 across the icon; negative while it rests. */
    private float sweep = -1f;

    IconGloss(float density) {
        this.density = density;
        sheen.setShader(sheenShader);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * density);
        rim.setShader(rimShader);
        band.setShader(bandShader);
    }

    /** Sends the band of light across once; reduced motion skips it. */
    void sweep() {
        if (sweepAnimator != null) sweepAnimator.cancel();
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(700);
        sweepAnimator.setStartDelay(140);
        sweepAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        sweepAnimator.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        sweepAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                sweep = -1f;
                invalidateSelf();
            }
        });
        sweepAnimator.start();
    }

    @Override public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        rect.set(b);
        int alpha = Math.round(LiquidGlass.gloss * 255);
        if (alpha == 0) return;
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
        band.setAlpha(alpha);
        float radius = AppAdapter.cornerRadius(b.width(), density);
        unit.setScale(1f, rect.height());
        unit.postTranslate(0f, rect.top);
        sheenShader.setLocalMatrix(unit);
        canvas.drawRoundRect(rect, radius, radius, sheen);
        if (sweep >= 0f) {
            // A band 45% of the icon wide, slanted, travelling from off the left edge to off the right.
            float w = rect.width() * 0.45f;
            bandMatrix.setScale(w, 1f);
            bandMatrix.postSkew(-0.4f, 0f);
            bandMatrix.postTranslate(rect.left - w * 1.5f + (rect.width() + w * 2f) * sweep, 0f);
            bandShader.setLocalMatrix(bandMatrix);
            canvas.drawRoundRect(rect, radius, radius, band);
        }
        rimShader.setLocalMatrix(unit);
        float h = rim.getStrokeWidth() / 2f;
        canvas.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius - h, radius - h, rim);
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter colorFilter) { }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
