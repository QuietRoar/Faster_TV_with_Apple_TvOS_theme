package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvos_style's top shelf: while a top-row app is focused, its own picture (the theme's shelf/<package>.jpg) fills
 * the screen over the wallpaper, and the glass frosts it. An app without one fades back to the wallpaper.
 * Pictures decode on a background thread once focus has rested for a moment, so passing along the row costs
 * nothing, and the last two stay decoded for going back and forth. They decode in full 32-bit colour (about
 * 4 MB each at 720p): the theme's pictures are smooth gradients, which 16-bit colour would show as bands.
 */
final class ShelfArt {
    /** Focus has to rest this long before a picture is decoded, so moving along the row stays smooth. */
    private static final long SETTLE_MS = 220;
    private static final String[] TYPES = {".jpg", ".jpeg", ".png", ".webp"};

    private final ImageView view;
    private final File dir;
    private final int width, height;
    private final boolean reducedMotion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** Package to {screen picture, glass backdrop}. */
    private final LruCache<String, Bitmap[]> cache = new LruCache<>(2);
    /** The package whose picture should be on screen, or null for the wallpaper. */
    private String wanted;
    private String shown;
    private final Runnable load = this::load;

    ShelfArt(Context context, ImageView view, File themeDir, boolean reducedMotion) {
        this.view = view;
        this.dir = themeDir == null ? null : new File(themeDir, ThemeFiles.SHELF);
        this.reducedMotion = reducedMotion;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setAlpha(0f);
    }

    /** True when the theme brought any shelf pictures at all; without them nothing is added to Home. */
    static boolean any(File themeDir) {
        File[] files = themeDir == null ? null : new File(themeDir, ThemeFiles.SHELF).listFiles(
                (d, name) -> ThemeFiles.collectionImage(name));
        return files != null && files.length > 0;
    }

    /** A top-row app took focus: its picture, or the wallpaper when it has none. */
    void focus(String pkg) {
        if (pkg != null && file(pkg) == null) pkg = null;
        wanted = pkg;
        handler.removeCallbacks(load);
        if (pkg == null) {
            show(null, null);
            return;
        }
        if (pkg.equals(shown)) return;
        Bitmap[] cached = cache.get(pkg);
        if (cached != null) show(pkg, cached);
        else handler.postDelayed(load, SETTLE_MS);
    }

    private void load() {
        String pkg = wanted;
        File file = pkg == null ? null : file(pkg);
        if (file == null) return;
        decoder.execute(() -> {
            Bitmap[] pair = decode(file);
            handler.post(() -> {
                if (pair == null) return;
                cache.put(pkg, pair);
                if (pkg.equals(wanted)) show(pkg, pair);
            });
        });
    }

    private void show(String pkg, Bitmap[] pair) {
        long ms = reducedMotion ? Motion.REDUCED_MS : 450;
        if (pkg == null) {
            if (shown == null) return;
            shown = null;
            LiquidGlass.setOverlay(null);
            view.animate().alpha(0f).setDuration(ms).start();
            return;
        }
        shown = pkg;
        boolean swap = view.getAlpha() > 0f;
        Runnable in = () -> {
            view.setImageBitmap(pair[0]);
            LiquidGlass.setOverlay(pair[1]);
            view.animate().alpha(1f).setDuration(ms).start();
        };
        // From one app's picture to another's: dip briefly toward the wallpaper rather than cut.
        if (swap) view.animate().alpha(0.35f).setDuration(ms / 3).withEndAction(in).start();
        else in.run();
    }

    private File file(String pkg) {
        if (dir == null) return null;
        for (String type : TYPES) {
            File f = new File(dir, pkg + type);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Screen-sized, 32-bit, on the GPU, plus the glass backdrop made from the software copy first. */
    private Bitmap[] decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = WallpaperSize.sampleSize(options.outWidth, options.outHeight, width, height);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            Bitmap small = LiquidGlass.smallCopy(decoded);
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) decoded.recycle();
            else gpu = decoded;
            return new Bitmap[]{gpu, small};
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
