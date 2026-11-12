package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keep watching (watch_next_row): the shows other apps put on Android's Continue watching list, as glass cards
 * above the dock, the way couch-tv's Night home shows them. Each card has the show's picture, the app, the title,
 * the episode and a progress line; OK opens the show in its app. The list is read off the main thread each time
 * Home comes back. A poster on the web is downloaded once into Home's cache folder and kept (40 at most).
 */
final class WatchNextRow extends LinearLayout {
    private static final String TAG = "WatchNextRow";
    /** Cards shown at most: the newest first. */
    static final int MAX_CARDS = 8;
    /** Shows kept from the list, so the row can show the focused app's even when others are newer. */
    static final int MAX_KEPT = 40;
    private static final float CARD_W_DP = 260, CARD_H_DP = 72, THUMB_W_DP = 96, RADIUS_DP = 14, GAP_DP = 16;
    private static final float FOCUS_SCALE = 1.06f;
    private static final int FOCUS_MS = 140, PROGRESS_STEPS = 1000;

    /** Down from a card goes to the dock. */
    interface Host {
        void onWatchNextDown();
    }

    /** One row of the Watch Next table, as a card shows it. */
    static final class Item {
        final String pkg, title, subtitle, intentUri, posterUri;
        final float progress;
        final long engaged;

        Item(String pkg, String title, String subtitle, String intentUri, String posterUri, float progress, long engaged) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.intentUri = intentUri;
            this.posterUri = posterUri;
            this.progress = progress;
            this.engaged = engaged;
        }
    }

    private final float density;
    private final Host host;
    private final LinearLayout cards;
    private final HorizontalScrollView scroller;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Bumped by every refresh and clear, so a slow read never draws over a newer one. */
    private int generation;
    private final TextView heading;
    /** Every show kept from the list, newest first, and the posters had so far, by their address. */
    private List<Item> all = new ArrayList<>();
    private final java.util.Map<String, Bitmap> posters = new java.util.HashMap<>();
    /** The apps' own recommendations (readPicks), shown for the focused app when it has nothing to keep watching. */
    private List<Item> picks = new ArrayList<>();
    /** The focused app (showFor), or null: then every app's shows. */
    private String filter;
    /** The shows on screen now, so a move to another tile with the same shows redraws nothing. */
    private List<Item> shown = new ArrayList<>();

    WatchNextRow(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        density = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        heading = new TextView(ctx);
        heading.setText(R.string.watch_next_title);
        heading.setTextColor(0xD9FFFFFF);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        heading.setTypeface(UiFont.get(ctx, R.string.font_medium));
        heading.setShadowLayer(8 * density, 0, density, 0x66000000);
        heading.setPadding(dp(4), 0, 0, dp(8));
        addView(heading);
        scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        scroller.setPadding(dp(4), dp(6), dp(40), dp(6));
        cards = new LinearLayout(ctx);
        cards.setOrientation(HORIZONTAL);
        cards.setClipChildren(false);
        scroller.addView(cards);
        addView(scroller);
        setVisibility(GONE);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Runs after every redraw of the cards, so the host can put the row back where the page has scrolled to. */
    private Runnable onShown;

    void setOnShown(Runnable onShown) {
        this.onShown = onShown;
    }

    /** True when the row is on screen with at least one card. */
    boolean hasCards() {
        return getVisibility() == VISIBLE && cards.getChildCount() > 0;
    }

    /** Puts focus on the first card. */
    boolean focusFirst() {
        if (!hasCards()) return false;
        scroller.scrollTo(0, 0);
        return cards.getChildAt(0).requestFocus();
    }

    /** Reads the list again (off the main thread) and redraws the cards; the row hides when there is nothing. */
    void refresh() {
        final int ticket = ++generation;
        final ContentResolver resolver = getContext().getApplicationContext().getContentResolver();
        new Thread(() -> {
            List<Item> items = read(resolver);
            List<Item> appPicks = readPicks(resolver);
            List<Item> both = new ArrayList<>(items);
            both.addAll(appPicks);
            // First what is already on the TV, so the row comes up at once; then the posters still to download,
            // each dropped into its card as it arrives.
            java.util.Map<String, Bitmap> found = new java.util.HashMap<>();
            for (Item item : both) {
                Bitmap b = item.posterUri == null ? null : poster(resolver, item.posterUri, false);
                if (b != null) found.put(item.posterUri, b);
            }
            main.post(() -> {
                if (ticket != generation) return;
                all = items;
                picks = appPicks;
                posters.clear();
                posters.putAll(found);
                shown = new ArrayList<>();
                render();
            });
            for (Item item : both) {
                if (item.posterUri == null || found.containsKey(item.posterUri) || ticket != generation) continue;
                Bitmap late = poster(resolver, item.posterUri, true);
                if (late == null) continue;
                found.put(item.posterUri, late);
                main.post(() -> {
                    if (ticket == generation) setPoster(item.posterUri, late);
                });
            }
        }, "watch-next").start();
    }

    /**
     * The focused app changed (a tile on Home): the row shows that app's shows, as the Apple TV's top shelf does.
     * An app with none leaves every app's shows in the row, so it does not come and go while moving along the dock.
     */
    void showFor(String pkg) {
        if (pkg == null ? filter == null : pkg.equals(filter)) return;
        filter = pkg;
        render();
    }

    /** Puts a downloaded poster into every card showing it. */
    private void setPoster(String uri, Bitmap poster) {
        posters.put(uri, poster);
        for (int i = 0; i < cards.getChildCount(); i++) {
            View card = cards.getChildAt(i);
            if (!(card instanceof ViewGroup) || !(((ViewGroup) card).getChildAt(0) instanceof ImageView)) continue;
            ImageView thumb = (ImageView) ((ViewGroup) card).getChildAt(0);
            if (uri.equals(thumb.getTag())) thumb.setImageBitmap(poster);
        }
    }

    /** Lets go of the cards and their pictures (Home is in the background and memory is short). */
    void clear() {
        generation++;
        cards.removeAllViews();
        all = new ArrayList<>();
        picks = new ArrayList<>();
        shown = new ArrayList<>();
        posters.clear();
        setVisibility(GONE);
    }

    /**
     * The focused app's shows to keep watching; else its own picks; else every app's shows to keep watching.
     * At most MAX_CARDS.
     */
    static List<Item> pick(List<Item> all, List<Item> picks, String pkg) {
        List<Item> from = all;
        if (pkg != null) {
            List<Item> mine = ofApp(all, pkg);
            if (mine.isEmpty()) mine = ofApp(picks, pkg);
            if (!mine.isEmpty()) from = mine;
        }
        return new ArrayList<>(from.subList(0, Math.min(MAX_CARDS, from.size())));
    }

    private static List<Item> ofApp(List<Item> items, String pkg) {
        List<Item> mine = new ArrayList<>();
        for (Item item : items) if (pkg.equals(item.pkg)) mine.add(item);
        return mine;
    }

    private void render() {
        List<Item> items = pick(all, picks, filter);
        if (items.equals(shown) && cards.getChildCount() == items.size()) return;
        shown = items;
        boolean appsOwn = !items.isEmpty() && filter != null && filter.equals(items.get(0).pkg)
                && !pick(all, picks, null).equals(items);
        boolean fromPicks = appsOwn && picks.contains(items.get(0));
        String app = appsOwn ? appName(getContext().getPackageManager(), filter) : "";
        heading.setText(!appsOwn ? getContext().getString(R.string.watch_next_title)
                : getContext().getString(fromPicks ? R.string.watch_next_picks : R.string.watch_next_on, app));
        boolean hadFocus = cards.findFocus() != null;
        cards.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
            if (i > 0) lp.leftMargin = dp(GAP_DP);
            Item item = items.get(i);
            cards.addView(card(item, item.posterUri == null ? null : posters.get(item.posterUri)), lp);
        }
        scroller.scrollTo(0, 0);
        setVisibility(items.isEmpty() ? GONE : VISIBLE);
        if (onShown != null) onShown.run();
        if (hadFocus && !items.isEmpty()) focusFirst();
    }

    // ---- reading the list ------------------------------------------------------------------------------------

    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
    };

    /**
     * The whole table, newest first. No selection: without ACCESS_ALL_EPG_DATA a selection is refused
     * (docs/watch-next.md), so the rows are filtered here. No permission or no provider gives an empty list.
     */
    static List<Item> read(ContentResolver resolver) {
        List<Item> items = new ArrayList<>();
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext()) {
                String pkg = c.getString(0), title = c.getString(1);
                boolean browsable = c.isNull(11) || c.getInt(11) != 0;
                if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(title) || !browsable) continue;
                String poster = !c.isNull(7) ? c.getString(7) : c.getString(6);
                items.add(new Item(pkg, title, subtitle(c.getString(3), c.getString(4), c.getString(2)),
                        c.getString(5), poster, progress(c.getLong(8), c.getLong(9)), c.getLong(10)));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.i(TAG, "Watch Next not readable: " + e.getMessage());
        }
        return newestFirst(items);
    }

    /** An app's own recommendations kept at most, and from every app together. */
    static final int PICKS_PER_APP = 6, MAX_PICKS = 60;

    private static final String[] PREVIEW_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
    };

    /**
     * The rows apps publish for the Android TV home (preview channels: Netflix, YouTube, Prime Video and others put
     * their picks there): shown for the focused app when it has nothing to keep watching. Same rules as read().
     */
    static List<Item> readPicks(ContentResolver resolver) {
        List<Item> items = new ArrayList<>();
        java.util.Map<String, Integer> perApp = new java.util.HashMap<>();
        try (Cursor c = resolver.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS, null, null, null)) {
            if (c == null) return items;
            while (c.moveToNext() && items.size() < MAX_PICKS) {
                String pkg = c.getString(0), title = c.getString(1);
                boolean browsable = c.isNull(8) || c.getInt(8) != 0;
                if (TextUtils.isEmpty(pkg) || TextUtils.isEmpty(title) || !browsable) continue;
                int count = perApp.containsKey(pkg) ? perApp.get(pkg) : 0;
                if (count >= PICKS_PER_APP) continue;
                perApp.put(pkg, count + 1);
                String poster = !c.isNull(7) ? c.getString(7) : c.getString(6);
                items.add(new Item(pkg, title, subtitle(c.getString(3), c.getString(4), c.getString(2)),
                        c.getString(5), poster, 0f, 0L));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.i(TAG, "preview channels not readable: " + e.getMessage());
        }
        Log.i(TAG, "picks per app: " + perApp);
        return items;
    }

    /** Newest engagement first, at most MAX_KEPT, one card per show. */
    static List<Item> newestFirst(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : sorted) {
            if (!seen.add(item.pkg + '\n' + item.title.toLowerCase(Locale.ROOT))) continue;
            out.add(item);
            if (out.size() == MAX_KEPT) break;
        }
        return out;
    }

    /** "S1 E4" when the app gives the numbers, else the episode's title, else nothing. */
    static String subtitle(String season, String episode, String episodeTitle) {
        boolean s = !TextUtils.isEmpty(season), e = !TextUtils.isEmpty(episode);
        if (s && e) return "S" + season.trim() + " E" + episode.trim();
        if (e) return "E" + episode.trim();
        return TextUtils.isEmpty(episodeTitle) ? "" : episodeTitle.trim();
    }

    /** How far in, 0 to 1; 0 when the app gives no duration. */
    static float progress(long position, long duration) {
        if (duration <= 0 || position <= 0) return 0f;
        return Math.min(1f, position / (float) duration);
    }

    /** True for a picture the TV holds itself; a web address would need the network, which Home never uses. */
    static boolean localPicture(String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        int colon = uri.indexOf(':');
        String scheme = colon > 0 ? uri.substring(0, colon).toLowerCase(Locale.ROOT) : "";
        return scheme.equals(ContentResolver.SCHEME_CONTENT) || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)
                || scheme.equals(ContentResolver.SCHEME_FILE);
    }

    /** True for a picture the app publishes on the web (most streaming apps): it is downloaded once and kept. */
    static boolean webPicture(String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        String u = uri.toLowerCase(Locale.ROOT);
        return u.startsWith("https://") || u.startsWith("http://");
    }

    private static final int CONNECT_MS = 4000, READ_MS = 6000;
    /** A poster bigger than this is not a thumbnail: skipped rather than filling a 1 GB TV's memory. */
    private static final int MAX_POSTER_BYTES = 3 * 1024 * 1024;
    /** Posters kept on the TV at most; the oldest go first. */
    private static final int MAX_CACHED = 40;

    /** The poster, sampled down to the card, or null for none; a web one is downloaded only when download is set. */
    private Bitmap poster(ContentResolver resolver, String uri, boolean download) {
        try {
            if (localPicture(uri)) {
                return download ? null : decode(() -> resolver.openInputStream(Uri.parse(uri)));
            }
            if (webPicture(uri)) {
                File file = cached(uri, download);
                return file == null ? null : decode(() -> new FileInputStream(file));
            }
        } catch (Exception e) {   // a provider or the network can throw anything; a missing picture is never worth a crash
            Log.i(TAG, "no poster from " + uri + ": " + e.getMessage());
        }
        return null;
    }

    private interface Source {
        InputStream open() throws IOException;
    }

    /** Decodes in two passes, sampled down to the card's thumbnail width, in 16-bit colour. */
    private Bitmap decode(Source source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = source.open()) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, Integer.highestOneBit(bounds.outWidth / Math.max(1, dp(THUMB_W_DP))));
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = source.open()) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    /** The poster in Home's cache folder, downloaded the first time it is needed; null when it cannot be had. */
    private File cached(String uri, boolean download) throws IOException {
        File dir = new File(getContext().getCacheDir(), "watchnext");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File file = new File(dir, Integer.toHexString(uri.hashCode()) + "-" + uri.length());
        if (file.isFile() && file.length() > 0) {
            if (!file.setLastModified(System.currentTimeMillis())) Log.i(TAG, "could not touch " + file);
            return file;
        }
        if (!download) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setInstanceFollowRedirects(true);
        File part = new File(dir, file.getName() + ".part");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            int total = 0;
            byte[] buf = new byte[16 * 1024];
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_POSTER_BYTES) return null;
                    out.write(buf, 0, n);
                }
            }
            if (!part.renameTo(file)) return null;
        } finally {
            conn.disconnect();
            if (part.exists() && !part.delete()) Log.i(TAG, "could not remove " + part);
        }
        trim(dir);
        return file;
    }

    /** Keeps the newest MAX_CACHED posters. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_CACHED) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_CACHED; i < files.length; i++) {
            if (!files[i].delete()) Log.i(TAG, "could not remove " + files[i]);
        }
    }

    // ---- drawing a card --------------------------------------------------------------------------------------

    private View card(Item item, Bitmap poster) {
        Context ctx = getContext();
        PackageManager pm = ctx.getPackageManager();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(8), dp(12), dp(8));
        card.setFocusable(true);
        card.setOutlineProvider(rounded(RADIUS_DP));
        card.setClipToOutline(true);
        card.setBackground(glass(false));

        ImageView thumb = new ImageView(ctx);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setOutlineProvider(rounded(8));
        thumb.setClipToOutline(true);
        thumb.setTag(item.posterUri);   // setPoster finds the card by it
        if (poster != null) thumb.setImageBitmap(poster);
        else thumb.setImageDrawable(appArt(pm, item.pkg));
        card.addView(thumb, new LinearLayout.LayoutParams(dp(THUMB_W_DP), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(12);
        text.addView(line(appName(pm, item.pkg).toUpperCase(Locale.getDefault()), 9, 0x99FFFFFF, true));
        text.addView(line(item.title, 15, 0xFFFFFFFF, true));
        if (!item.subtitle.isEmpty()) text.addView(line(item.subtitle, 11, 0xB3FFFFFF, false));
        if (item.progress > 0f) text.addView(progressBar(item.progress), progressLp());
        card.addView(text, textLp);

        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(glass(focused));
            float s = focused ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(focused ? 8 * density : 0f).setDuration(FOCUS_MS).start();
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                host.onWatchNextDown();
                return true;
            }
            // The row's ends: focus stays in the row instead of jumping to the top bar.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return cards.indexOfChild(v) == 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return cards.indexOfChild(v) == cards.getChildCount() - 1;
            return false;
        });
        card.setOnClickListener(v -> open(item));
        return card;
    }

    private ViewOutlineProvider rounded(float radiusDp) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusDp * density);
            }
        };
    }

    private ProgressBar progressBar(float progress) {
        ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(PROGRESS_STEPS);
        bar.setProgress(Math.round(progress * PROGRESS_STEPS));
        bar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        return bar;
    }

    private LinearLayout.LayoutParams progressLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        lp.topMargin = dp(5);
        return lp;
    }

    private TextView line(String value, float sp, int colour, boolean medium) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(getContext(), R.string.font_medium), medium && sp >= 14 ? Typeface.BOLD : Typeface.NORMAL);
        if (sp < 10) t.setLetterSpacing(0.1f);
        return t;
    }

    /** The dark glass behind a card, brighter with a white rim while focused. */
    private GradientDrawable glass(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(RADIUS_DP * density);
        g.setColor(focused ? 0x52FFFFFF : 0x33181820);
        g.setStroke(Math.max(1, dp(focused ? 2 : 1)), focused ? 0xFFFFFFFF : 0x26FFFFFF);
        return g;
    }

    private static Drawable appArt(PackageManager pm, String pkg) {
        try {
            Drawable banner = pm.getApplicationBanner(pkg);
            return banner != null ? banner : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String appName(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Opens the show with the app's own intent; when that fails, the app itself. */
    private void open(Item item) {
        Context ctx = getContext();
        if (!TextUtils.isEmpty(item.intentUri)) {
            try {
                ctx.startActivity(Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (URISyntaxException | ActivityNotFoundException | SecurityException e) {
                Log.i(TAG, "show did not open, opening the app: " + e.getMessage());
            }
        }
        PackageManager pm = ctx.getPackageManager();
        Intent app = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (app == null) app = pm.getLaunchIntentForPackage(item.pkg);
        if (app == null) return;
        try {
            ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open " + item.pkg + ": " + e.getMessage());
        }
    }
}
