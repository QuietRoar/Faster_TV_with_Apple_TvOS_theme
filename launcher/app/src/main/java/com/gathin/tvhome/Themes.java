package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * tvos_style's themes. TvOS is the theme folder's landscape slideshow; ten more are painted here, on the TV, so a
 * theme costs no download: a mesh gradient (colour blobs blended in linear light), a pattern of its own drawn over
 * it, a fade to near black where the top row sits, and a grain under one 8-bit step against banding. Pictures of
 * your own become themes too. Each theme's accent, pattern strength and brightness can be changed. The glass, the
 * header and the dock follow whatever is behind them, so they adapt by themselves.
 */
final class Themes {
    static final String TVOS = "tvos", IMAGE = "img:", SLIDES = "slides";
    /** The slideshow of your photos: how long each one stays, in minutes. */
    static final int[] SLIDE_MINUTES = {1, 5, 15, 30, 60};
    /** The symbols' colour over a photo: a pale tint of the photo's own colour, or plain white. */
    static final int ICONS_AUTO = 0, ICONS_WHITE = 1;
    static final int[] ICON_NAMES = {R.string.theme_icons_auto, R.string.theme_icons_white};
    /** Pictures you add from a USB drive are copied here, so the theme stays when the drive is out. */
    static final String MY_THEMES = "mythemes";
    /** The folder looked for on USB drives and the TV's own storage. */
    static final String USB_FOLDER = "TV Themes";

    enum Pattern { NONE, AURORA, MOUNTAINS, WAVES, CONTOURS, HEXAGONS, FOREST, BOKEH, PLANET, DUNES, SYNTH }

    /** Accent choices: 0 is the theme's own. */
    static final int[] ACCENTS = {0, 0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFFD60A, 0xFFFFFFFF};
    static final int[] ACCENT_NAMES = {R.string.theme_accent_own, R.string.vol_colour_blue, R.string.vol_colour_green,
            R.string.vol_colour_orange, R.string.vol_colour_pink, R.string.vol_colour_purple, R.string.theme_accent_teal,
            R.string.theme_accent_yellow, R.string.vol_colour_white};
    static final int[] PATTERN_NAMES = {R.string.theme_pattern_off, R.string.theme_pattern_soft,
            R.string.theme_pattern_normal, R.string.theme_pattern_bold};
    static final int[] BRIGHTNESS_NAMES = {R.string.theme_darker, R.string.theme_normal, R.string.theme_brighter};
    /**
     * How a photo of your own meets the screen. Auto fills it when little would be cut off and shows the whole photo
     * otherwise (a portrait photo, say), on a blurred, dimmed copy of itself so no bars show; Fill and Whole force one.
     * Position picks the part kept when filling, or where the whole photo sits; Zoom enlarges it from there.
     */
    static final int FIT_AUTO = 0, FIT_FILL = 1, FIT_WHOLE = 2;
    static final int[] FIT_NAMES = {R.string.photo_fit_auto, R.string.photo_fit_fill, R.string.photo_fit_whole};
    static final int POS_CENTRE = 0, POS_TOP = 1, POS_BOTTOM = 2, POS_LEFT = 3, POS_RIGHT = 4;
    static final int[] POSITION_NAMES = {R.string.photo_pos_centre, R.string.photo_pos_top, R.string.photo_pos_bottom,
            R.string.photo_pos_left, R.string.photo_pos_right};
    private static final float[] ZOOMS = {1f, 1.15f, 1.3f, 1.5f};
    static final int[] ZOOM_NAMES = {R.string.photo_zoom_off, R.string.photo_zoom_115, R.string.photo_zoom_130,
            R.string.photo_zoom_150};
    /** Auto fills only when at least this much of the photo stays on screen. */
    private static final float AUTO_FILL_KEEPS = 0.7f;

    /** The app tiles' corners: soft (tvOS), round, or sharp; the dock band and the glass follow. */
    static final int TILES_SOFT = 0, TILES_ROUND = 1, TILES_SHARP = 2;
    static final int[] TILE_NAMES = {R.string.theme_tiles_soft, R.string.theme_tiles_round, R.string.theme_tiles_sharp};
    private static final float[] TILE_RADIUS_DP = {16f, 26f, 6f};

    static final class Theme {
        final String id;
        final int name;
        final String label;   // a picture's own name; null for the built-in ones
        final int base;
        final float[][] blobs;
        final int[] blobColours;
        final int accent;
        final Pattern pattern;
        final File image;
        /** The theme's own clock (Clock's style and place) and tiles; -1 keeps what the owner chose before. */
        final int clockStyle, clockPlace, tiles;

        Theme(String id, int name, int base, int accent, Pattern pattern, float[][] blobs, int... blobColours) {
            this.id = id;
            this.name = name;
            this.label = null;
            this.base = base;
            this.accent = accent;
            this.pattern = pattern;
            this.blobs = blobs;
            this.blobColours = blobColours;
            this.image = null;
            this.clockStyle = -1;
            this.clockPlace = -1;
            this.tiles = -1;
        }

        private Theme(Theme t, int clockStyle, int clockPlace, int tiles) {
            this.id = t.id;
            this.name = t.name;
            this.label = t.label;
            this.base = t.base;
            this.accent = t.accent;
            this.pattern = t.pattern;
            this.blobs = t.blobs;
            this.blobColours = t.blobColours;
            this.image = t.image;
            this.clockStyle = clockStyle;
            this.clockPlace = clockPlace;
            this.tiles = tiles;
        }

        /** The same theme with a clock and tiles of its own. */
        Theme look(int clockStyle, int clockPlace, int tiles) {
            return new Theme(this, clockStyle, clockPlace, tiles);
        }

        /** A picture of your own. */
        Theme(File image) {
            this.id = IMAGE + image.getAbsolutePath();
            this.name = 0;
            String n = image.getName();
            int dot = n.lastIndexOf('.');
            this.label = (dot > 0 ? n.substring(0, dot) : n).replace('_', ' ').replace('-', ' ');
            this.base = 0xFF000000;
            this.accent = 0xFF0A84FF;
            this.pattern = Pattern.NONE;
            this.blobs = new float[0][];
            this.blobColours = new int[0];
            this.image = image;
            this.clockStyle = -1;
            this.clockPlace = -1;
            this.tiles = -1;
        }

        boolean photos() {
            return TVOS.equals(id);
        }

        /** The slideshow of your own photos. */
        boolean slides() {
            return SLIDES.equals(id);
        }

        /** Painted from your photos: a photo of yours, or their slideshow. */
        boolean mine() {
            return image != null || slides();
        }

        /** The name you gave it, else a photo's file name or the theme's own. */
        String title(Context ctx) {
            String given = prefs(ctx).getString("theme_title_" + id, null);
            if (given != null && !given.trim().isEmpty()) return given.trim();
            return label != null ? label : ctx.getString(name);
        }
    }

    private static float[][] b(float... v) {
        float[][] out = new float[v.length / 5][];
        for (int i = 0; i < out.length; i++) out[i] = Arrays.copyOfRange(v, i * 5, i * 5 + 5);
        return out;
    }

    static final List<Theme> BUILT_IN = Arrays.asList(
            new Theme(TVOS, R.string.theme_tvos, 0xFF0B1220, 0xFF0A84FF, Pattern.NONE,
                    b(.3f, .1f, .55f, .4f, .8f, .8f, .2f, .4f, .35f, .6f), 0xFF4A7FB5, 0xFF7DA86B),
            new Theme("aurora", R.string.theme_aurora, 0xFF020812, 0xFF30D158, Pattern.AURORA,
                    b(.5f, .0f, .8f, .35f, .55f, .2f, .25f, .4f, .3f, .35f), 0xFF0B3A4A, 0xFF1B1550),
            new Theme("sunset", R.string.theme_sunset, 0xFF1A0612, 0xFFFF9F0A, Pattern.MOUNTAINS,
                    b(.6f, .3f, .7f, .35f, .95f, .2f, .05f, .5f, .3f, .7f, .9f, .1f, .4f, .3f, .6f),
                    0xFFFF8A3D, 0xFF8E2A6B, 0xFFFF4D6D),
            new Theme("ocean", R.string.theme_ocean, 0xFF01101F, 0xFF64D2FF, Pattern.WAVES,
                    b(.5f, .1f, .7f, .4f, .9f, .9f, .2f, .35f, .3f, .5f), 0xFF0E6BA8, 0xFF22C1E0),
            new Theme("midnight", R.string.theme_midnight, 0xFF020205, 0xFF0A84FF, Pattern.CONTOURS,
                    b(.75f, .15f, .5f, .35f, .8f, .2f, .3f, .4f, .3f, .6f), 0xFF1A2440, 0xFF151525),
            new Theme("graphite", R.string.theme_graphite, 0xFF17181B, 0xFF0A84FF, Pattern.HEXAGONS,
                    b(.7f, .12f, .55f, .45f, .7f, .2f, .25f, .45f, .35f, .6f), 0xFF5A606B, 0xFF33363C),
            new Theme("forest", R.string.theme_forest, 0xFF04120A, 0xFF34C759, Pattern.FOREST,
                    b(.5f, .05f, .8f, .4f, .9f, .85f, .25f, .35f, .3f, .6f), 0xFF7FB89A, 0xFFF2C46B),
            new Theme("rose", R.string.theme_rose, 0xFF14030C, 0xFFFF375F, Pattern.BOKEH,
                    b(.25f, .12f, .5f, .4f, .8f, .75f, .15f, .45f, .35f, .7f, .5f, .45f, .45f, .2f, .7f),
                    0xFFFF5C8A, 0xFFC03AA8, 0xFF3A0B2A),
            new Theme("nebula", R.string.theme_nebula, 0xFF04020D, 0xFFBF5AF2, Pattern.PLANET,
                    b(.3f, .15f, .45f, .35f, .8f, .65f, .1f, .4f, .3f, .6f, .5f, .35f, .35f, .25f, .45f),
                    0xFF5B2BD9, 0xFFD13AB8, 0xFF1D4ED8),
            new Theme("dune", R.string.theme_dune, 0xFF2A1A0C, 0xFFFF9F0A, Pattern.DUNES,
                    b(.6f, .05f, .7f, .35f, .9f, .2f, .2f, .4f, .3f, .5f), 0xFFF3C27E, 0xFFE58F5A),
            new Theme("neon", R.string.theme_neon, 0xFF07020F, 0xFF00E5FF, Pattern.SYNTH,
                    b(.5f, .3f, .7f, .3f, .7f, .2f, .1f, .4f, .3f, .5f), 0xFF5A0F8C, 0xFF0F2A6E),
            // Themes with a look of their own: each brings its clock and its tiles.
            new Theme("studio", R.string.theme_studio, 0xFF0C0C0E, 0xFFFFFFFF, Pattern.NONE,
                    b(.15f, .1f, .5f, .4f, .55f, .85f, .2f, .35f, .3f, .3f), 0xFF3A3F4A, 0xFF23262D)
                    .look(Clock.LARGE, Clock.TOP_LEFT, TILES_SHARP),
            new Theme("editorial", R.string.theme_editorial, 0xFF14060A, 0xFFFF9F0A, Pattern.BOKEH,
                    b(.2f, .15f, .5f, .4f, .75f, .8f, .2f, .4f, .35f, .55f), 0xFF7A1E2C, 0xFF3D0F24)
                    .look(Clock.DAY, Clock.TOP_LEFT, TILES_SOFT),
            new Theme("retro", R.string.theme_retro, 0xFF0D0716, 0xFFFF375F, Pattern.SYNTH,
                    b(.5f, .25f, .7f, .3f, .8f, .2f, .1f, .35f, .3f, .55f), 0xFFE0661B, 0xFF6A1B9A)
                    .look(Clock.STACKED, Clock.CENTRE, TILES_ROUND),
            new Theme("glacier", R.string.theme_glacier, 0xFF06121C, 0xFF64D2FF, Pattern.MOUNTAINS,
                    b(.4f, .1f, .7f, .35f, .85f, .85f, .15f, .35f, .3f, .6f), 0xFF8EC9E8, 0xFF3B6E9E)
                    .look(Clock.GLASS, Clock.TOP_LEFT, TILES_ROUND),
            new Theme("moss", R.string.theme_moss, 0xFF07110A, 0xFF30D158, Pattern.CONTOURS,
                    b(.3f, .1f, .6f, .4f, .8f, .8f, .2f, .35f, .3f, .55f), 0xFF2F5D3A, 0xFF5E7F2A)
                    .look(Clock.WIDGET, Clock.TOP_LEFT, TILES_SOFT));

    private Themes() { }

    // ---- which themes there are, and the chosen one --------------------------------------------------------

    /** The built-in themes, then your pictures. */
    /** The built-in themes, then the slideshow of your photos (once there is one), then each photo. */
    static List<Theme> all(Context ctx) {
        List<Theme> out = new ArrayList<>(BUILT_IN);
        List<File> mine = myImages(ctx);
        if (!mine.isEmpty()) out.add(SLIDESHOW);
        for (File f : mine) out.add(new Theme(f));
        return out;
    }

    static final Theme SLIDESHOW = new Theme(SLIDES, R.string.theme_slides, 0xFF000000, 0xFF0A84FF, Pattern.NONE,
            new float[0][]);

    static void rename(Context ctx, Theme t, String title) {
        prefs(ctx).edit().putString("theme_title_" + t.id, title.trim()).apply();
    }

    // ---- the slideshow ------------------------------------------------------------------------------------

    static int slideMinutesChoice(Context ctx) {
        return clamp(prefs(ctx).getInt("slides_minutes", 1), SLIDE_MINUTES.length);
    }

    static void setSlideMinutes(Context ctx, int choice) {
        prefs(ctx).edit().putInt("slides_minutes", choice).apply();
    }

    /** The photo the slideshow is on now, as its own theme (so its fit, position and zoom apply); null with none. */
    static Theme currentSlide(Context ctx) {
        List<File> mine = myImages(ctx);
        if (mine.isEmpty()) return null;
        int i = Math.floorMod(prefs(ctx).getInt("slides_at", 0), mine.size());
        return new Theme(mine.get(i));
    }

    /** On to the next photo. */
    static void nextSlide(Context ctx) {
        prefs(ctx).edit().putInt("slides_at", prefs(ctx).getInt("slides_at", 0) + 1).apply();
    }

    /** Pictures that are themes: pushed with the theme folder (mythemes/) or copied from a USB drive. */
    static List<File> myImages(Context ctx) {
        List<File> files = new ArrayList<>();
        File pushed = Config.dir(ctx);
        addImages(files, pushed == null ? null : new File(pushed, MY_THEMES));
        addImages(files, new File(ctx.getFilesDir(), MY_THEMES));
        return files;
    }

    /** Photos found on the TV or a USB drive at most; the newest are kept. */
    static final int MAX_FOUND = 40;
    /** Smaller files are thumbnails and icons, not photos for a whole screen. */
    private static final long MIN_PHOTO_BYTES = 60 * 1024;
    /** The folders photos land in on the TV and on a drive: a "TV Themes" folder, the camera's, downloads,
     *  pictures, and what arrives over Bluetooth. */
    private static final String[] PHOTO_FOLDERS = {USB_FOLDER, "Pictures/" + USB_FOLDER, "DCIM", "DCIM/Camera",
            "Pictures", "Download", "bluetooth", "Bluetooth", ""};

    /** Photos on the TV's own storage or a USB drive, newest first, not yet added. */
    static List<File> onDrives(Context ctx) {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File v : volumes) {
                if (!v.getName().equals("emulated") && !v.getName().equals("self")) roots.add(v);
            }
        }
        List<File> found = new ArrayList<>();
        for (File root : roots) {
            for (String folder : PHOTO_FOLDERS) {
                // The storage's top level only on a drive: the TV's own holds nothing but folders there.
                if (folder.isEmpty() && root.equals(Environment.getExternalStorageDirectory())) continue;
                addImages(found, folder.isEmpty() ? root : new File(root, folder));
            }
        }
        List<String> have = new ArrayList<>();
        for (File f : myImages(ctx)) have.add(f.getName());
        List<String> seen = new ArrayList<>();
        List<File> out = new ArrayList<>();
        for (File f : found) {
            String key = f.getAbsolutePath();
            if (have.contains(f.getName()) || seen.contains(key) || f.length() < MIN_PHOTO_BYTES) continue;
            seen.add(key);
            out.add(f);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out.size() > MAX_FOUND ? new ArrayList<>(out.subList(0, MAX_FOUND)) : out;
    }

    /** A small copy of a photo for a list row: decoded at a fraction of its size, turned upright. */
    static Bitmap thumbnail(File file, int width, int height) {
        return picture(file, width, height, FIT_FILL, POS_CENTRE, 1f);
    }

    private static void addImages(List<File> into, File dir) {
        File[] files = dir == null ? null : dir.listFiles((d, n) -> {
            String l = n.toLowerCase(Locale.ROOT);
            return !n.startsWith(".") && (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp"));
        });
        if (files == null) return;
        Arrays.sort(files);
        Collections.addAll(into, files);
    }

    /** Copies a picture from a drive into the launcher's own storage; the new theme, or null if it failed. */
    static Theme importImage(Context ctx, File source) {
        File dir = new File(ctx.getFilesDir(), MY_THEMES);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, source.getName().replaceAll("[^A-Za-z0-9 ._-]", "_"));
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        } catch (IOException e) {
            return null;
        }
        return new Theme(target);
    }

    /** Removes one of your pictures (only copies made from a drive; pushed ones go with the theme folder). */
    static boolean removeImage(Context ctx, Theme t) {
        return t.image != null && t.image.getParentFile() != null
                && t.image.getParentFile().equals(new File(ctx.getFilesDir(), MY_THEMES)) && t.image.delete();
    }

    static Theme find(Context ctx, String id) {
        if (SLIDES.equals(id) && !myImages(ctx).isEmpty()) return SLIDESHOW;
        if (id != null && id.startsWith(IMAGE)) {
            File f = new File(id.substring(IMAGE.length()));
            if (f.isFile()) return new Theme(f);
        }
        for (Theme t : BUILT_IN) if (t.id.equals(id)) return t;
        return BUILT_IN.get(0);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("tvos", Context.MODE_PRIVATE);
    }

    static Theme current(Context ctx) {
        return find(ctx, prefs(ctx).getString("theme", TVOS));
    }

    static void select(Context ctx, String id) {
        prefs(ctx).edit().putString("theme", id).apply();
    }

    // ---- per-theme choices --------------------------------------------------------------------------------

    static int accentChoice(Context ctx, Theme t) {
        return prefs(ctx).getInt("theme_accent_" + t.id, 0);
    }

    static int patternChoice(Context ctx, Theme t) {
        return prefs(ctx).getInt("theme_pattern_" + t.id, 2);
    }

    static int brightnessChoice(Context ctx, Theme t) {
        return prefs(ctx).getInt("theme_bright_" + t.id, 1);
    }

    static int fitChoice(Context ctx, Theme t) {
        return clamp(prefs(ctx).getInt("theme_fit_" + t.id, FIT_AUTO), FIT_NAMES.length);
    }

    static int positionChoice(Context ctx, Theme t) {
        return clamp(prefs(ctx).getInt("theme_pos_" + t.id, POS_CENTRE), POSITION_NAMES.length);
    }

    static int zoomChoice(Context ctx, Theme t) {
        return clamp(prefs(ctx).getInt("theme_zoom_" + t.id, 0), ZOOM_NAMES.length);
    }

    private static int clamp(int v, int count) {
        return v < 0 || v >= count ? 0 : v;
    }

    /** A key for a theme's look as painted, so a cached preview is redrawn after any change to it. */
    static String paintKey(Context ctx, Theme t) {
        if (t.slides()) {
            Theme slide = currentSlide(ctx);
            return SLIDES + "|" + (slide == null ? "" : paintKey(ctx, slide));
        }
        return t.id + "|" + patternChoice(ctx, t) + brightnessChoice(ctx, t) + fitChoice(ctx, t) + positionChoice(ctx, t)
                + zoomChoice(ctx, t);
    }

    static void setChoice(Context ctx, Theme t, String what, int value) {
        prefs(ctx).edit().putInt("theme_" + what + "_" + t.id, value).apply();
    }

    /** The accent for controls that are on: your choice, else the theme's own, else a picture's own colour. */
    static int accent(Context ctx) {
        Theme t = current(ctx);
        int choice = accentChoice(ctx, t);
        if (choice > 0 && choice < ACCENTS.length) return ACCENTS[choice];
        if (t.slides()) {
            Theme slide = currentSlide(ctx);
            if (slide != null) t = slide;
        }
        if (t.image != null) return prefs(ctx).getInt("theme_sampled_" + t.id, t.accent);
        return t.accent;
    }

    static int iconsChoice(Context ctx, Theme t) {
        return clamp(prefs(ctx).getInt("theme_icons_" + t.id, ICONS_AUTO), ICON_NAMES.length);
    }

    /**
     * The header symbols' and clock's colour at rest: over your photos, by default, a tint of the photo's own colour,
     * pale over a dark picture and deep over a bright one, so they look part of it and stay easy to read; plain
     * white or near black otherwise.
     */
    static int ink(Context ctx, boolean overBright) {
        Theme t = current(ctx);
        int plain = overBright ? 0xFF1C1C1E : 0xFFFFFFFF;
        if (!t.mine() || iconsChoice(ctx, t) != ICONS_AUTO) return plain;
        float[] hsv = new float[3];
        Color.colorToHSV(accent(ctx), hsv);
        if (overBright) {
            hsv[1] = Math.min(1f, Math.max(0.55f, hsv[1]));
            hsv[2] = 0.28f;   // deep enough for text over a bright sky
        } else {
            hsv[1] = Math.min(0.3f, hsv[1] * 0.45f);
            hsv[2] = 1f;   // pale: white with the photo's colour in it
        }
        return Color.HSVToColor(hsv);
    }

    /** The current theme's tile corners: your choice for it, else its own, else soft. */
    static int tilesChoice(Context ctx, Theme t) {
        int own = t.tiles >= 0 ? t.tiles : TILES_SOFT;
        int v = prefs(ctx).getInt("theme_tiles_" + t.id, own);
        return v < 0 || v >= TILE_NAMES.length ? TILES_SOFT : v;
    }

    static float tileRadiusDp(Context ctx) {
        return TILE_RADIUS_DP[tilesChoice(ctx, current(ctx))];
    }

    // ---- painting ------------------------------------------------------------------------------------------

    private static float lin(int c) {
        float v = c / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }

    private static float srgb(float v) {
        v = Math.max(0f, Math.min(1f, v));
        return 255f * (v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1 / 2.4f) - 0.055f);
    }

    /** Paints a theme at width x height with its current choices; for previews too. */
    static Bitmap render(Context ctx, Theme t, int width, int height) {
        if (t.slides()) {
            Theme slide = currentSlide(ctx);
            if (slide != null) t = slide;
        }
        int pattern = patternChoice(ctx, t), bright = brightnessChoice(ctx, t);
        Bitmap bitmap;
        if (t.image != null) {
            bitmap = picture(t.image, width, height, fitChoice(ctx, t), positionChoice(ctx, t), ZOOMS[zoomChoice(ctx, t)]);
            if (bitmap == null) bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            else if (width >= 300) prefs(ctx).edit().putInt("theme_sampled_" + t.id, sample(bitmap)).apply();
        } else {
            bitmap = gradient(t, width, height);
            if (pattern > 0) drawPattern(new Canvas(bitmap), t, width, height, new float[]{0f, 0.5f, 1f, 1.5f}[pattern]);
        }
        Canvas c = new Canvas(bitmap);
        shade(c, width, height, bright, t.image != null);
        grain(bitmap);
        return bitmap;
    }

    /** The mesh gradient, worked out at a quarter of the pixels and stretched: it has no detail to lose. */
    private static Bitmap gradient(Theme t, int width, int height) {
        int lw = Math.max(2, width / 2), lh = Math.max(2, height / 2);
        int[] small = new int[lw * lh];
        float br = lin((t.base >> 16) & 0xFF), bg = lin((t.base >> 8) & 0xFF), bb = lin(t.base & 0xFF);
        for (int y = 0; y < lh; y++) {
            float fy = y / (float) (lh - 1);
            for (int x = 0; x < lw; x++) {
                float fx = x / (float) (lw - 1);
                float cr = br, cg = bg, cb = bb;
                for (int i = 0; i < t.blobs.length; i++) {
                    float[] o = t.blobs[i];
                    float dx = (fx - o[0]) / o[2], dy = (fy - o[1]) / o[3];
                    float a = o[4] * (float) Math.exp(-(dx * dx + dy * dy));
                    int col = t.blobColours[i];
                    cr = cr * (1 - a) + lin((col >> 16) & 0xFF) * a;
                    cg = cg * (1 - a) + lin((col >> 8) & 0xFF) * a;
                    cb = cb * (1 - a) + lin(col & 0xFF) * a;
                }
                small[y * lw + x] = 0xFF000000 | Math.round(srgb(cr)) << 16 | Math.round(srgb(cg)) << 8 | Math.round(srgb(cb));
            }
        }
        Bitmap low = Bitmap.createBitmap(small, lw, lh, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createScaledBitmap(low, width, height, true).copy(Bitmap.Config.ARGB_8888, true);
        low.recycle();
        return out;
    }

    /** Down towards near black where the top row and the grid sit, a soft vignette, and the brightness choice. */
    private static void shade(Canvas c, int w, int h, int bright, boolean picture) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float start = picture ? 0.5f : 0.42f;
        int bottom = picture ? 0xD9000000 : 0xF0000000;
        p.setShader(new LinearGradient(0, h * start, 0, h, 0x00000000, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, h * start, w, h, p);
        p.setShader(new RadialGradient(w * 0.5f, h * 0.35f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x73000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        if (bright == 0) {
            p.setColor(0x59000000);
            c.drawRect(0, 0, w, h, p);
        } else if (bright == 2) {
            p.setColor(0x1FFFFFFF);
            c.drawRect(0, 0, w, h * 0.55f, p);
        }
    }

    /** A grain of under one 8-bit step, the same on all three channels, so gradients show no bands. */
    private static void grain(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        int seed = 0x2F6E2B1;
        for (int i = 0; i < px.length; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 17;
            seed ^= seed << 5;
            int n = ((seed & 0x3) == 0) ? -1 : ((seed & 0x3) == 3) ? 1 : 0;
            if (n == 0) continue;
            int c = px[i];
            int r = Math.max(0, Math.min(255, ((c >> 16) & 0xFF) + n));
            int g = Math.max(0, Math.min(255, ((c >> 8) & 0xFF) + n));
            int bl = Math.max(0, Math.min(255, (c & 0xFF) + n));
            px[i] = 0xFF000000 | r << 16 | g << 8 | bl;
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** A photo at width x height: filled or whole (see FIT_AUTO), at its position and zoom, turned upright. */
    private static Bitmap picture(File file, int width, int height, int fit, int position, float zoom) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width && o.outHeight / (o.inSampleSize * 2) >= height) o.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        int turn = rotation(file);
        if (turn != 0) {
            // A phone's portrait photo is stored sideways with a note to turn it: turned upright here.
            Matrix r = new Matrix();
            r.setRotate(turn);
            Bitmap upright = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), r, true);
            if (upright != src) src.recycle();
            src = upright;
        }
        float sw = src.getWidth(), sh = src.getHeight();
        float fill = Math.max(width / sw, height / sh), whole = Math.min(width / sw, height / sh);
        boolean filled = fit == FIT_FILL || fit == FIT_AUTO && (whole / fill) * (whole / fill) >= AUTO_FILL_KEEPS;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (!filled) blurredBackdrop(c, src, width, height, fill, smooth);
        float s = (filled ? fill : whole) * zoom;
        float dx = width - sw * s, dy = height - sh * s;
        float x = position == POS_LEFT ? 0 : position == POS_RIGHT ? dx : dx / 2f;
        float y = position == POS_TOP ? 0 : position == POS_BOTTOM ? dy : dy / 2f;
        if (filled || zoom > 1f) {
            // Never past an edge: a filled or zoomed photo always covers the screen where it can.
            x = dx < 0 ? Math.max(dx, Math.min(0, x)) : x;
            y = dy < 0 ? Math.max(dy, Math.min(0, y)) : y;
        }
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(x, y);
        c.drawBitmap(src, m, smooth);
        src.recycle();
        return out;
    }

    /** Behind a whole photo: the photo itself filling the screen, blurred by shrinking it a lot, and dimmed. */
    private static void blurredBackdrop(Canvas c, Bitmap src, int width, int height, float fill, Paint smooth) {
        int bw = Math.max(1, width / 32), bh = Math.max(1, height / 32);
        Bitmap tiny = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        float s = fill * bw / width;
        m.setScale(s, s);
        m.postTranslate((bw - src.getWidth() * s) / 2f, (bh - src.getHeight() * s) / 2f);
        new Canvas(tiny).drawBitmap(src, m, smooth);
        // Up again in two steps, which smooths the blocks into a soft blur.
        Bitmap mid = Bitmap.createScaledBitmap(tiny, bw * 4, bh * 4, true);
        tiny.recycle();
        c.drawBitmap(mid, null, new RectF(0, 0, width, height), smooth);
        mid.recycle();
        c.drawColor(0x66000000);
    }

    /** How far a photo's EXIF says to turn it, in degrees; 0 for a PNG, a WebP or a photo without the note. */
    private static int rotation(File file) {
        try {
            int o = new android.media.ExifInterface(file.getPath()).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            return o == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** A picture's own accent: the most saturated of its brighter colours, lifted so it reads on dark glass. */
    private static int sample(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 32, 18, true);
        float bestScore = -1;
        float[] best = {210, 0.7f, 0.9f};
        float[] hsv = new float[3];
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                Color.colorToHSV(small.getPixel(x, y), hsv);
                float score = hsv[1] * (0.4f + hsv[2]);
                if (hsv[2] > 0.25f && score > bestScore) {
                    bestScore = score;
                    best = hsv.clone();
                }
            }
        }
        small.recycle();
        best[1] = Math.max(0.55f, best[1]);
        best[2] = Math.max(0.85f, best[2]);
        return Color.HSVToColor(best);
    }

    // ---- patterns ------------------------------------------------------------------------------------------

    private static int alpha(int colour, float a) {
        return (Math.max(0, Math.min(255, Math.round(a * 255))) << 24) | (colour & 0xFFFFFF);
    }

    private static void drawPattern(Canvas c, Theme t, int w, int h, float k) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random random = new Random(t.id.hashCode());
        float u = Math.min(w, h) / 720f;   // one unit at 720p
        switch (t.pattern) {
            case AURORA: aurora(c, p, w, h, k, random); break;
            case MOUNTAINS: mountains(c, p, w, h, k); break;
            case WAVES: waves(c, p, w, h, k); break;
            case CONTOURS: contours(c, p, w, h, k, u); break;
            case HEXAGONS: hexagons(c, p, w, h, k, u); break;
            case FOREST: forest(c, p, w, h, k, random); break;
            case BOKEH: bokeh(c, p, w, h, k, u, random); break;
            case PLANET: stars(c, p, w, h, k, random); planet(c, p, w, h, k); break;
            case DUNES: dunes(c, p, w, h, k); break;
            case SYNTH: synth(c, p, w, h, k, u); break;
            default: break;
        }
    }

    private static void stars(Canvas c, Paint p, int w, int h, float k, Random random) {
        p.setShader(null);
        int count = w * h / 2200;
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w, y = random.nextFloat() * random.nextFloat() * h * 0.8f;
            float r = Math.max(0.4f, random.nextFloat() * random.nextFloat() * 1.8f * w / 1280f);
            p.setColor(alpha(0xFFFFFF, Math.min(1f, (0.25f + random.nextFloat() * 0.75f) * k)));
            c.drawCircle(x, y, r, p);
        }
    }

    private static void aurora(Canvas c, Paint p, int w, int h, float k, Random random) {
        stars(c, p, w, h, k * 0.8f, random);
        int[] colours = {0xFF2BFFB0, 0xFF27C7FF, 0xFFA45CFF};
        Path path = new Path();
        for (int band = 0; band < 3; band++) {
            float base = h * (0.18f + band * 0.07f), amp = h * (0.05f + band * 0.02f), phase = band * 1.7f;
            float thick = h * (0.28f - band * 0.05f);
            path.reset();
            path.moveTo(0, base + thick);
            for (int x = 0; x <= w; x += Math.max(2, w / 160)) {
                float fx = x / (float) w;
                path.lineTo(x, base + (float) (Math.sin(fx * 5.2 + phase) * amp + Math.sin(fx * 11 + phase * 2) * amp * 0.3));
            }
            path.lineTo(w, base + thick);
            path.close();
            p.setShader(new LinearGradient(0, base - amp, 0, base + thick, alpha(colours[band], 0.55f * k),
                    alpha(colours[band], 0f), Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            // Fine vertical rays along each curtain.
            p.setShader(null);
            p.setStrokeWidth(Math.max(1f, w / 900f));
            for (int x = 0; x < w; x += Math.max(3, w / 180)) {
                float fx = x / (float) w;
                float top = base + (float) (Math.sin(fx * 5.2 + phase) * amp + Math.sin(fx * 11 + phase * 2) * amp * 0.3);
                p.setShader(new LinearGradient(0, top, 0, top + thick * 0.6f, alpha(colours[band], 0.22f * k * random.nextFloat()),
                        alpha(colours[band], 0f), Shader.TileMode.CLAMP));
                c.drawLine(x, top, x, top + thick * 0.6f, p);
            }
            p.setShader(null);
        }
    }

    private static float ridge(float fx, float seed, float rough) {
        return (float) (Math.sin(fx * 3.1 + seed) * 0.5 + Math.sin(fx * 7.3 + seed * 2.1) * 0.25 * rough
                + Math.sin(fx * 17.9 + seed * 3.7) * 0.12 * rough + Math.sin(fx * 41 + seed) * 0.04 * rough);
    }

    private static void mountains(Canvas c, Paint p, int w, int h, float k) {
        // The sun, low over the ridges, with a wide glow.
        float sx = w * 0.62f, sy = h * 0.4f, sr = h * 0.09f;
        p.setShader(new RadialGradient(sx, sy, sr * 6, new int[]{alpha(0xFFE3A0, 0.55f * k), alpha(0xFF8A50, 0.18f * k), 0},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, sr * 6, p);
        p.setShader(new LinearGradient(0, sy - sr, 0, sy + sr, alpha(0xFFF4C8, Math.min(1f, 0.95f * k)),
                alpha(0xFFB060, Math.min(1f, 0.9f * k)), Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, sr, p);
        int[] shades = {0xFF6B2A5A, 0xFF4A1C46, 0xFF2C0F2E, 0xFF160716};
        Path path = new Path();
        for (int i = 0; i < shades.length; i++) {
            float base = h * (0.42f + i * 0.07f), amp = h * (0.09f - i * 0.012f);
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x <= w; x += Math.max(2, w / 240)) path.lineTo(x, base - ridge(x / (float) w, i * 2.3f, 1f) * amp);
            path.lineTo(w, h);
            path.close();
            p.setShader(new LinearGradient(0, base - amp, 0, h, alpha(shades[i], Math.min(1f, 0.9f * k)), alpha(shades[i], Math.min(1f, k)),
                    Shader.TileMode.CLAMP));
            c.drawPath(path, p);
        }
        p.setShader(null);
    }

    private static void waves(Canvas c, Paint p, int w, int h, float k) {
        Path path = new Path();
        for (int i = 0; i < 6; i++) {
            float base = h * (0.32f + i * 0.075f), amp = h * (0.03f + i * 0.006f), phase = i * 0.9f;
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x <= w; x += Math.max(2, w / 200)) {
                float fx = x / (float) w;
                path.lineTo(x, base + (float) (Math.sin(fx * 6.2 + phase) * amp + Math.sin(fx * 13.7 + phase * 1.6) * amp * 0.35));
            }
            path.lineTo(w, h);
            path.close();
            int col = i % 2 == 0 ? 0x2FB7E8 : 0x0A5A9C;
            p.setShader(new LinearGradient(0, base - amp, 0, base + h * 0.25f, alpha(col, 0.26f * k), alpha(0x02101F, 0.5f * k),
                    Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            // A bright crest line on each wave.
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, h / 500f));
            p.setColor(alpha(0xBFF3FF, 0.22f * k));
            Path crest = new Path();
            for (int x = 0; x <= w; x += Math.max(2, w / 200)) {
                float fx = x / (float) w;
                float y = base + (float) (Math.sin(fx * 6.2 + phase) * amp + Math.sin(fx * 13.7 + phase * 1.6) * amp * 0.35);
                if (x == 0) crest.moveTo(x, y);
                else crest.lineTo(x, y);
            }
            c.drawPath(crest, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private static void contours(Canvas c, Paint p, int w, int h, float k, float u) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.1f * u));
        float[][] centres = {{0.72f, 0.28f}, {0.18f, 0.62f}};
        Path path = new Path();
        for (float[] cen : centres) {
            for (int ring = 1; ring < 26; ring++) {
                float r0 = ring * 22 * u;
                path.reset();
                for (int a = 0; a <= 120; a++) {
                    double ang = a / 120.0 * Math.PI * 2;
                    float r = r0 * (1 + 0.16f * (float) Math.sin(ang * 3 + ring * 0.37) + 0.08f * (float) Math.sin(ang * 5 + ring * 0.9));
                    float x = cen[0] * w + (float) Math.cos(ang) * r * 1.25f, y = cen[1] * h + (float) Math.sin(ang) * r;
                    if (a == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                p.setColor(alpha(ring % 5 == 0 ? 0x6E8BFF : 0x8FA4D8, (ring % 5 == 0 ? 0.2f : 0.09f) * k));
                c.drawPath(path, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void hexagons(Canvas c, Paint p, int w, int h, float k, float u) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.2f * u));
        float r = 34 * u, dx = r * 1.732f, dy = r * 1.5f;
        Path hex = new Path();
        for (int row = -1; row * dy < h + r; row++) {
            for (int col = -1; col * dx < w + r; col++) {
                float cx = col * dx + (row % 2 == 0 ? 0 : dx / 2), cy = row * dy;
                float dist = (float) Math.hypot((cx / w - 0.7f) * 1.4f, cy / h - 0.15f);
                float a = Math.max(0f, 0.22f - dist * 0.25f) * k;
                if (a <= 0.01f) continue;
                hex.reset();
                for (int i = 0; i < 6; i++) {
                    double ang = Math.PI / 3 * i + Math.PI / 6;
                    float x = cx + (float) Math.cos(ang) * r, y = cy + (float) Math.sin(ang) * r;
                    if (i == 0) hex.moveTo(x, y);
                    else hex.lineTo(x, y);
                }
                hex.close();
                p.setColor(alpha(0xDDE6F5, a));
                c.drawPath(hex, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(w * 0.7f, h * 0.15f, w * 0.35f, alpha(0xFFFFFF, 0.1f * k), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    private static void forest(Canvas c, Paint p, int w, int h, float k, Random random) {
        int[] shades = {0xFF3E6B57, 0xFF2A4E3D, 0xFF173426, 0xFF0A1C13};
        Path path = new Path();
        for (int i = 0; i < shades.length; i++) {
            float base = h * (0.4f + i * 0.08f), amp = h * (0.05f - i * 0.006f);
            path.reset();
            path.moveTo(0, h);
            float tree = w / (60f - i * 8);
            for (float x = 0; x <= w + tree; x += tree) {
                float fx = x / w;
                float ground = base - ridge(fx, i * 1.9f, 0.6f) * amp;
                float height = (0.6f + random.nextFloat() * 0.8f) * h * (0.045f + i * 0.012f);
                path.lineTo(x, ground);
                path.lineTo(x + tree * 0.5f, ground - height);
                path.lineTo(x + tree, ground);
            }
            path.lineTo(w, h);
            path.close();
            p.setColor(alpha(shades[i], Math.min(1f, (0.55f + i * 0.15f) * k)));
            c.drawPath(path, p);
            // Mist between the rows of hills.
            p.setShader(new LinearGradient(0, base - h * 0.06f, 0, base + h * 0.04f, 0, alpha(0xCFE6DA, 0.12f * k),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, base - h * 0.06f, w, base + h * 0.04f, p);
            p.setShader(null);
        }
    }

    private static void bokeh(Canvas c, Paint p, int w, int h, float k, float u, Random random) {
        int[] colours = {0xFF9EC3, 0xFFC7A0, 0xE59BFF, 0xFFFFFF};
        for (int i = 0; i < 46; i++) {
            float x = random.nextFloat() * w, y = random.nextFloat() * h * 0.75f;
            float r = (14 + random.nextFloat() * random.nextFloat() * 90) * u;
            int col = colours[random.nextInt(colours.length)];
            float a = (0.06f + random.nextFloat() * 0.16f) * k;
            p.setShader(new RadialGradient(x, y, r, new int[]{alpha(col, a), alpha(col, a * 0.85f), alpha(col, 0)},
                    new float[]{0f, 0.82f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p);
        }
        p.setShader(null);
    }

    private static void planet(Canvas c, Paint p, int w, int h, float k) {
        float cx = w * 0.78f, cy = h * 0.42f, r = h * 0.22f;
        p.setShader(new RadialGradient(cx, cy, r * 1.6f, new int[]{alpha(0xB48CFF, 0.35f * k), 0}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.6f, p);
        p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, alpha(0xE7C6FF, Math.min(1f, 0.95f * k)),
                alpha(0x2A0F5C, Math.min(1f, k)), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        // Bands across the planet.
        p.setShader(null);
        c.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(clip);
        for (int i = -4; i <= 4; i++) {
            p.setColor(alpha(i % 2 == 0 ? 0xFFFFFF : 0x2A0F5C, 0.07f * k));
            c.drawRect(cx - r, cy + i * r * 0.2f - r * 0.05f, cx + r, cy + i * r * 0.2f + r * 0.05f, p);
        }
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.06f);
        RectF ring = new RectF(cx - r * 1.9f, cy - r * 0.42f, cx + r * 1.9f, cy + r * 0.42f);
        c.save();
        c.rotate(-14, cx, cy);
        p.setShader(new LinearGradient(ring.left, 0, ring.right, 0, alpha(0xF5D8FF, 0.15f * k), alpha(0xF5D8FF, 0.65f * k),
                Shader.TileMode.MIRROR));
        c.drawArc(ring, 0, 180, false, p);   // the front half of the ring, over the planet
        c.restore();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }

    private static void dunes(Canvas c, Paint p, int w, int h, float k) {
        int[] lights = {0xFFF7D49A, 0xFFE9B074, 0xFFC98451, 0xFF8E5530};
        Path path = new Path();
        for (int i = 0; i < lights.length; i++) {
            float base = h * (0.38f + i * 0.09f);
            path.reset();
            path.moveTo(0, h);
            path.lineTo(0, base + h * 0.04f);
            path.cubicTo(w * (0.2f + i * 0.05f), base - h * 0.09f, w * (0.45f - i * 0.04f), base + h * 0.08f, w * 0.65f, base - h * 0.02f);
            path.cubicTo(w * 0.8f, base - h * 0.07f, w * 0.9f, base + h * 0.03f, w, base - h * 0.03f);
            path.lineTo(w, h);
            path.close();
            p.setShader(new LinearGradient(0, base - h * 0.08f, w * 0.3f, base + h * 0.2f, alpha(lights[i], Math.min(1f, 0.85f * k)),
                    alpha(lights[Math.min(lights.length - 1, i + 1)] & 0xFF7F7F7F, Math.min(1f, k)), Shader.TileMode.CLAMP));
            c.drawPath(path, p);
        }
        p.setShader(null);
    }

    private static void synth(Canvas c, Paint p, int w, int h, float k, float u) {
        float horizon = h * 0.5f;
        // The sun, striped, sitting on the horizon.
        float sx = w * 0.5f, sr = h * 0.17f, sy = horizon - sr * 0.35f;
        p.setShader(new RadialGradient(sx, sy, sr * 3.2f, new int[]{alpha(0xFF3DBE, 0.4f * k), 0}, null, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, sr * 3.2f, p);
        c.save();
        c.clipRect(0, 0, w, horizon);
        p.setShader(new LinearGradient(0, sy - sr, 0, sy + sr, alpha(0xFFE45E, Math.min(1f, k)), alpha(0xFF2BD6, Math.min(1f, k)),
                Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, sr, p);
        p.setShader(null);
        p.setColor(0xFF07020F);
        // Stripes across the half of the sun above the horizon, thicker towards it.
        for (int i = 0; i < 6; i++) {
            float y = sy - sr * 0.3f + i * (horizon - (sy - sr * 0.3f)) / 6f;
            c.drawRect(sx - sr, y, sx + sr, y + sr * (0.025f + i * 0.016f), p);
        }
        c.restore();
        // The grid, running to the horizon.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.3f * u));
        for (int i = -18; i <= 18; i++) {
            p.setColor(alpha(0x00E5FF, 0.4f * k));
            c.drawLine(sx + i * w * 0.012f, horizon, sx + i * w * 0.11f, h, p);
        }
        for (int i = 1; i < 14; i++) {
            float f = (float) Math.pow(i / 14f, 2.1);
            float y = horizon + (h - horizon) * f;
            p.setColor(alpha(0xFF2BD6, 0.45f * k * (0.3f + f)));
            c.drawLine(0, y, w, y, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(alpha(0xFF6BE8, 0.6f * k));
        c.drawRect(0, horizon - u, w, horizon + u, p);
    }
}
