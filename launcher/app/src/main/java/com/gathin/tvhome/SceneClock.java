package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * couch-tv's Night home, drawn natively (scene_clock): a scene fills the screen, a huge thin clock sits in its sky,
 * filled with a frosted copy of the scene and edged in light, and the scenery cut out of the picture is drawn over
 * the digits, so the peaks and hills rise in front of the time. Each scene has its own clock: place, size and tint
 * follow couch-tv's design (tv-home/web/app.css, .s-night and the rest). The scenes take turns every few seconds
 * with a slow crossfade. Only the scene showing and the one coming are in memory.
 *
 * The pictures come from the theme folder's scenes/ (pushed with the theme): night.jpg, night-fg.webp (the cut-out,
 * optional) and night-glass.jpg (the clock's fill, optional), and the same for golden, water, fog and dawn.
 */
final class SceneClock extends View {
    private static final String TAG = "SceneClock";
    /** couch-tv's design size: positions below are in pixels of a 1920x1080 screen. */
    private static final float DESIGN_W = 1920f;
    private static final int FADE_MS = 1600;
    private static final long MINUTE_MS = 60_000L;
    /** CSS line-height of couch-tv's clock: the digits are centred in a box this many ems tall. */
    private static final float LINE_HEIGHT = 0.82f;

    /** One scene's clock and date, as couch-tv places them. A left below 0 centres them. */
    static final class Layout {
        final String id;
        final float clockTop, clockSize, clockLeft, dateTop, dateLeft;
        final boolean darkFill;
        final int rimColour, dateColour;

        Layout(String id, float clockTop, float clockSize, float clockLeft, float dateTop, float dateLeft,
               boolean darkFill, int rimColour, int dateColour) {
            this.id = id;
            this.clockTop = clockTop;
            this.clockSize = clockSize;
            this.clockLeft = clockLeft;
            this.dateTop = dateTop;
            this.dateLeft = dateLeft;
            this.darkFill = darkFill;
            this.rimColour = rimColour;
            this.dateColour = dateColour;
        }
    }

    /** couch-tv's five scenes, in its order. */
    static final Layout[] LAYOUTS = {
            new Layout("night", 150, 400, -1, 96, -1, false, 0x80FFD7E1, 0xEBFFE8EE),
            new Layout("golden", 152, 390, -1, 106, -1, false, 0x8CFFFFFF, 0xFFFFFFFF),
            new Layout("water", 268, 420, -1, 216, -1, false, 0x8CFFFFFF, 0xFFFFFFFF),
            new Layout("fog", 172, 470, 96, 116, 104, true, 0x99FFFFFF, 0xFF16201D),
            new Layout("dawn", 230, 400, -1, 176, -1, false, 0x8CFFFFFF, 0xFFFFFFFF),
    };

    /** A scene in memory: its picture, cut-out and frosted fill. */
    private static final class Loaded {
        final Layout layout;
        final Bitmap photo, cutout, glass;

        Loaded(Layout layout, Bitmap photo, Bitmap cutout, Bitmap glass) {
            this.layout = layout;
            this.photo = photo;
            this.cutout = cutout;
            this.glass = glass;
        }

        void recycle() {
            photo.recycle();
            if (cutout != null) cutout.recycle();
            if (glass != null) glass.recycle();
        }
    }

    private interface Ready {
        void done(Loaded loaded);
    }

    private final File dir;
    private final List<Layout> scenes = new ArrayList<>();
    private final long sceneMs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final RectF dst = new RectF();
    private final Matrix matrix = new Matrix();
    private final ColorMatrixColorFilter bright = filter(1.15f, 1.2f), dark = filter(0.46f, 0.8f);
    private Loaded current, coming;
    private float fade = 1f;
    private int index;
    private boolean running;
    private ValueAnimator fader;

    private SceneClock(Context ctx, File dir, List<Layout> scenes, long sceneMs) {
        super(ctx);
        this.dir = dir;
        this.scenes.addAll(scenes);
        this.sceneMs = sceneMs;
        Typeface antonio = getResources().getFont(R.font.antonio_light);
        for (Paint p : new Paint[]{fill, rim, sheen}) p.setTypeface(antonio);
        rim.setStyle(Paint.Style.STROKE);
        date.setTypeface(UiFont.get(ctx, R.string.font_medium));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The scene clock for the scenes the theme brought, or null when it brought none. */
    static SceneClock create(Context ctx, File themeDir, long sceneMs) {
        if (themeDir == null) return null;
        File dir = new File(themeDir, "scenes");
        List<Layout> found = new ArrayList<>();
        for (Layout l : LAYOUTS) if (new File(dir, l.id + ".jpg").isFile()) found.add(l);
        return found.isEmpty() ? null : new SceneClock(ctx, dir, found, sceneMs);
    }

    /** couch-tv's CSS brightness() then saturate() on the clock's fill. */
    private static ColorMatrixColorFilter filter(float brightness, float saturation) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(saturation);
        ColorMatrix b = new ColorMatrix();
        b.setScale(brightness, brightness, brightness, 1f);
        m.postConcat(b);
        return new ColorMatrixColorFilter(m);
    }

    /** Home is on screen: show the scene and start the turns and the minute tick. */
    void start() {
        if (running) return;
        running = true;
        if (current == null) {
            load(index, loaded -> {
                if (current != null) current.recycle();
                current = loaded;
                fade = 1f;
                invalidate();
            });
        }
        main.postDelayed(next, sceneMs);
        tickAtNextMinute();
    }

    /** Home went away: stop the turns and keep only the scene showing. */
    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (fader != null) fader.end();
    }

    /** Lets go of the pictures (memory is short); they are read again at the next start. */
    void release() {
        stop();
        if (current != null) current.recycle();
        if (coming != null) coming.recycle();
        current = null;
        coming = null;
    }

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            if (!running || scenes.size() < 2) return;
            int to = (index + 1) % scenes.size();
            load(to, loaded -> {
                if (!running) {
                    loaded.recycle();
                    return;
                }
                index = to;
                coming = loaded;
                crossfade();
            });
        }
    };

    private void crossfade() {
        fader = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        fader.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            invalidate();
        });
        fader.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (coming != null) {
                    if (current != null) current.recycle();
                    current = coming;
                    coming = null;
                }
                fade = 1f;
                invalidate();
                if (running) main.postDelayed(next, sceneMs);
            }
        });
        fade = 0f;
        fader.start();
    }

    private void tickAtNextMinute() {
        long now = System.currentTimeMillis();
        main.postDelayed(() -> {
            invalidate();
            if (running) tickAtNextMinute();
        }, MINUTE_MS - now % MINUTE_MS + 50);
    }

    /** Decodes a scene off the main thread, at the screen's size. */
    private void load(int at, Ready then) {
        Layout layout = scenes.get(at);
        int width = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            Bitmap photo = decode(new File(dir, layout.id + ".jpg"), width, Bitmap.Config.RGB_565);
            if (photo == null) return;
            Bitmap cutout = decode(new File(dir, layout.id + "-fg.webp"), width, Bitmap.Config.ARGB_8888);
            Bitmap glass = decode(new File(dir, layout.id + "-glass.jpg"), width / 2, Bitmap.Config.RGB_565);
            Loaded loaded = new Loaded(layout, photo, cutout, glass);
            main.post(() -> then.done(loaded));
        }, "scene").start();
    }

    private static Bitmap decode(File file, int width, Bitmap.Config config) {
        if (!file.isFile()) return null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), probe);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, probe.outWidth / Math.max(1, width))));
            opts.inPreferredConfig = config;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, "could not read " + file + ": " + e);
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (current == null) return;
        int alpha = Math.round(255 * fade);
        drawScene(c, current, coming != null ? 255 - alpha : 255);
        if (coming != null) drawScene(c, coming, alpha);
    }

    private void drawScene(Canvas c, Loaded s, int alpha) {
        if (alpha <= 0) return;
        float scale = getWidth() / DESIGN_W;
        dst.set(0, 0, getWidth(), getHeight());
        bitmapPaint.setAlpha(alpha);
        c.drawBitmap(s.photo, null, dst, bitmapPaint);
        drawDate(c, s.layout, scale, alpha);
        drawClock(c, s, scale, alpha);
        if (s.cutout != null) c.drawBitmap(s.cutout, null, dst, bitmapPaint);
    }

    private static int withAlpha(int colour, int alpha) {
        return ((Math.round((colour >>> 24) * alpha / 255f)) << 24) | (colour & 0xFFFFFF);
    }

    private void drawDate(Canvas c, Layout l, float scale, int alpha) {
        String text = DateFormat.format("EEEE d MMMM", Calendar.getInstance()).toString();
        date.setTextSize(34 * scale);
        date.setColor(withAlpha(l.dateColour, alpha));
        if (l.darkFill) date.clearShadowLayer();
        else date.setShadowLayer(18 * scale, 0, 2 * scale, 0x40000000);
        float x = l.dateLeft < 0 ? (getWidth() - date.measureText(text)) / 2 : l.dateLeft * scale;
        c.drawText(text, x, (l.dateTop + 30) * scale, date);
    }

    private void drawClock(Canvas c, Loaded s, float scale, int alpha) {
        Layout l = s.layout;
        String time = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm",
                Calendar.getInstance()).toString();
        float size = l.clockSize * scale;
        for (Paint p : new Paint[]{fill, rim, sheen}) p.setTextSize(size);
        fill.getTextBounds(time, 0, time.length(), bounds);
        float width = fill.measureText(time);
        float x = l.clockLeft < 0 ? (getWidth() - width) / 2 : l.clockLeft * scale;
        float boxTop = l.clockTop * scale, boxH = LINE_HEIGHT * size;
        float baseline = boxTop + (boxH + bounds.height()) / 2f;

        // The fill: the frosted copy of the scene, seen through the digits (couch-tv's background-clip: text).
        if (s.glass != null) {
            BitmapShader shader = new BitmapShader(s.glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            matrix.setScale(getWidth() / (float) s.glass.getWidth(), getHeight() / (float) s.glass.getHeight());
            shader.setLocalMatrix(matrix);
            fill.setShader(shader);
            fill.setColorFilter(l.darkFill ? dark : bright);
        } else {
            fill.setShader(null);
            fill.setColorFilter(null);
            fill.setColor(l.darkFill ? 0xFF2A3430 : 0xFFFFFFFF);
        }
        fill.setAlpha(Math.round((l.darkFill ? 0.8f : 0.9f) * alpha));
        c.drawText(time, x, baseline, fill);

        // The sheen: light from the top left, fading through the digits.
        if (!l.darkFill) {
            sheen.setShader(new LinearGradient(x, boxTop, x + width * 0.4f, boxTop + boxH,
                    new int[]{0x8CFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x38FFFFFF}, new float[]{0f, 0.38f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
            sheen.setAlpha(alpha);
            c.drawText(time, x, baseline, sheen);
        }

        // The rim: a thin line of light round each digit.
        rim.setStrokeWidth(Math.max(1f, 1.5f * scale));
        rim.setColor(withAlpha(l.rimColour, alpha));
        c.drawText(time, x, baseline, rim);
    }
}
