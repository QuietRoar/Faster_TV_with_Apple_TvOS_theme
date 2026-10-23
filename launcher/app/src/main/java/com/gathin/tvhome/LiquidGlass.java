package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * tvos_style's Liquid Glass. Android 9 has no live blur, so each wallpaper leaves a small copy of itself
 * (128x72, a little more saturated, box-blurred so the stretch shows no blocks) and every glass surface draws
 * the slice of it that lies behind it, stretched with bilinear filtering: that reads as heavy frosted glass for
 * some 37 KB and one bitmap draw. Over it go a tint, a sheen across the top and a rim that is bright at the top
 * edge and fainter below, as tvOS 26 draws it.
 */
final class LiquidGlass {
    private LiquidGlass() { }

    /** tvos_style is on: the wallpaper player prepares backdrops and the dock and panels draw as glass. */
    static boolean enabled;
    /**
     * The Control Center's Gloss slider, 0 to 1: the alpha of every rim, sheen and band of light, on the glass and
     * the icons (IconGloss). The gradients are drawn strong enough for 1; 0.6 is the look the defaults were tuned to.
     */
    static float gloss = 0.6f;

    private static final int SMALL_W = 128, SMALL_H = 72;
    /** Box blur radius in small pixels, and passes: three box passes come close to a gaussian. */
    private static final int BLUR_RADIUS = 3, BLUR_PASSES = 3;
    private static Bitmap backdrop;
    /** The wallpaper's backdrop, and the focused app's top-shelf picture's while one is shown. */
    private static Bitmap base, overlay;
    /** Small copies made on the decoder thread, waiting for their picture to reach the screen. */
    private static final Map<Bitmap, Bitmap> pending = Collections.synchronizedMap(new IdentityHashMap<>());
    /** Views drawing glass, redrawn when the backdrop changes. */
    private static final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix matrix = new Matrix();
    // Unit gradients from 0 to 1, moved onto each surface with a matrix instead of being remade every frame.
    private static final LinearGradient sheenShader = new LinearGradient(0, 0, 0, 1,
            new int[]{0x4AFFFFFF, 0x0DFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    private static final LinearGradient rimShader = new LinearGradient(0, 0, 0, 1,
            new int[]{0xFFFFFFFF, 0x2AFFFFFF, 0x20FFFFFF, 0x7BFFFFFF}, new float[]{0f, 0.35f, 0.7f, 1f},
            Shader.TileMode.CLAMP);
    private static final Matrix unit = new Matrix();

    static {
        rim.setStyle(Paint.Style.STROKE);
        sheen.setShader(sheenShader);
        rim.setShader(rimShader);
    }

    /**
     * Called on the decoder thread with the full picture: keeps its small, blurred-by-scaling copy until
     * shown(key), key being the bitmap that goes on screen (the GPU copy of picture).
     */
    static void prepare(Bitmap picture, Bitmap key) {
        if (!enabled || picture == null || key == null) return;
        Bitmap small = smallCopy(picture);
        if (small != null) pending.put(key, small);
    }

    /** The backdrop for a picture: halved down, saturated a little and box-blurred; null when it cannot be made. */
    static Bitmap smallCopy(Bitmap picture) {
        try {
            Bitmap step = picture;
            // Halve repeatedly: one jump from 1920 to 128 would sample a few pixels and shimmer between pictures.
            while (step.getWidth() / 2 >= SMALL_W * 2) {
                Bitmap half = Bitmap.createScaledBitmap(step, step.getWidth() / 2, step.getHeight() / 2, true);
                if (step != picture) step.recycle();
                step = half;
            }
            Bitmap small = Bitmap.createBitmap(SMALL_W, SMALL_H, Bitmap.Config.ARGB_8888);
            Paint vivid = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.35f);
            vivid.setColorFilter(new ColorMatrixColorFilter(saturate));
            new Canvas(small).drawBitmap(step, null, new Rect(0, 0, SMALL_W, SMALL_H), vivid);
            if (step != picture) step.recycle();
            boxBlur(small);
            return small;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;   // no glass backdrop for this picture: surfaces fall back to their tint
        }
    }

    /** Blurs the small copy in place: horizontal then vertical running sums, BLUR_PASSES times. */
    private static void boxBlur(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        bitmap.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < BLUR_PASSES; pass++) {
            blurLine(a, b, w, h, 1, w);   // rows
            blurLine(b, a, h, w, w, 1);   // columns
        }
        bitmap.setPixels(a, 0, w, 0, 0, w, h);
    }

    /** One box pass along lines of length len, stepping step within a line and next between lines; edges clamp. */
    private static void blurLine(int[] in, int[] out, int len, int lines, int step, int next) {
        int r = BLUR_RADIUS, div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * next;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[base + Math.max(0, Math.min(len - 1, i)) * step];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[base + Math.min(len - 1, i + r + 1) * step];
                int sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    /** Called on the main thread as the picture goes on screen. */
    static void shown(Bitmap picture) {
        Bitmap small = pending.remove(picture);
        if (small == null) return;
        base = small;
        if (overlay == null) use(small);
    }

    /**
     * The focused app's top-shelf picture covers the wallpaper (ShelfArt): the glass frosts that instead, and
     * null goes back to the wallpaper's backdrop.
     */
    static void setOverlay(Bitmap small) {
        overlay = small;
        Bitmap now = small != null ? small : base;
        if (now != null && now != backdrop) use(now);
    }

    private static void use(Bitmap small) {
        backdrop = small;
        glass.setShader(new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        for (View host : new ArrayList<>(hosts)) host.invalidate();
    }

    static void register(View host) {
        hosts.add(host);
    }

    /** Sets the Gloss slider's strength and redraws the glass; the caller redraws the icons. */
    static void setGloss(float strength) {
        gloss = Math.max(0f, Math.min(1f, strength));
        for (View host : new ArrayList<>(hosts)) host.invalidate();
    }

    /**
     * Draws a glass surface over rect, in canvas coordinates whose origin lies at (originX, originY) on screen.
     * @param tintArgb lifts the glass (white) or darkens it for text on top (near-black).
     */
    static void draw(Canvas canvas, RectF rect, float radius, int originX, int originY, DisplayMetrics metrics,
                     int tintArgb) {
        if (backdrop != null) {
            matrix.setScale(metrics.widthPixels / (float) SMALL_W, metrics.heightPixels / (float) SMALL_H);
            matrix.postTranslate(-originX, -originY);
            glass.getShader().setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, radius, radius, glass);
            tint.setColor(tintArgb);
        } else {
            tint.setColor(0xD91C1C1E);   // no wallpaper yet: plain smoky glass
        }
        canvas.drawRoundRect(rect, radius, radius, tint);

        int alpha = Math.round(gloss * 255);
        if (alpha == 0) return;
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
        unit.setScale(1f, rect.height());
        unit.postTranslate(0f, rect.top);
        sheenShader.setLocalMatrix(unit);
        canvas.drawRoundRect(rect, radius, radius, sheen);

        float stroke = metrics.density * 1.2f;
        rim.setStrokeWidth(stroke);
        rimShader.setLocalMatrix(unit);
        float h = stroke / 2f;
        canvas.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius - h, radius - h, rim);
    }

    /**
     * tvos_style's frosted grid: the whole screen behind the app grid as glass, faded in once focus leaves the
     * top row, as tvOS blurs and dims its background there. The root sits at the screen's origin.
     */
    static final class Frost extends View {
        private final RectF rect = new RectF();

        Frost(android.content.Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setAlpha(0f);
            register(this);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (backdrop == null) return;
            rect.set(0, 0, getWidth(), getHeight());
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            matrix.setScale(metrics.widthPixels / (float) SMALL_W, metrics.heightPixels / (float) SMALL_H);
            glass.getShader().setLocalMatrix(matrix);
            canvas.drawRect(rect, glass);
            tint.setColor(0x73000000);
            canvas.drawRect(rect, tint);
        }
    }

    /**
     * A view background made of glass. With focusWhite the focused state is solid white instead, the tvOS
     * focus; a negative radius makes a pill (half the height).
     */
    static final class Background extends Drawable {
        private final View host;
        private final float radius;
        private final int tintArgb;
        private final boolean focusWhite;
        private final int[] at = new int[2];
        private final RectF rect = new RectF();
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean focused;

        Background(View host, float radius, int tintArgb, boolean focusWhite) {
            this.host = host;
            this.radius = radius;
            this.tintArgb = tintArgb;
            this.focusWhite = focusWhite;
            white.setColor(0xF5FFFFFF);
            register(host);
        }

        private float radius() {
            Rect b = getBounds();
            return radius < 0 ? Math.min(b.width(), b.height()) / 2f : radius;
        }

        @Override public void draw(@NonNull Canvas canvas) {
            rect.set(getBounds());
            float r = radius();
            if (focusWhite && focused) {
                canvas.drawRoundRect(rect, r, r, white);
                return;
            }
            host.getLocationOnScreen(at);
            LiquidGlass.draw(canvas, rect, r, at[0], at[1], host.getResources().getDisplayMetrics(), tintArgb);
        }

        @Override public boolean isStateful() {
            return focusWhite;
        }

        @Override protected boolean onStateChange(int[] state) {
            boolean now = false;
            for (int s : state) if (s == android.R.attr.state_focused) now = true;
            if (now == focused) return false;
            focused = now;
            invalidateSelf();
            return true;
        }

        @Override public void getOutline(@NonNull Outline outline) {
            outline.setRoundRect(getBounds(), radius());
        }

        @Override public void setAlpha(int alpha) { }

        @Override public void setColorFilter(ColorFilter colorFilter) { }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
