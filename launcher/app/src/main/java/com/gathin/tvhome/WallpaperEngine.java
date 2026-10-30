package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Where the home screen's background comes from, and how often it changes.
 *
 * Sources:
 *  - time     the four time-of-day photos (the original behaviour, no rotation)
 *  - bundled  the twelve screensaver photos, shuffled
 *  - random   any photo from picsum.photos (no key, thousands of images)
 *  - photos   image files the user drops into the app's "wallpapers" folder
 */
final class WallpaperEngine {

    static final String SOURCE_TIME = "time";
    static final String SOURCE_BUNDLED = "bundled";
    static final String SOURCE_RANDOM = "random";
    static final String SOURCE_PHOTOS = "photos";

    private static final String PREFS = "wallpaper";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_INTERVAL = "interval_min";

    private static final int[] INTERVALS = {1, 5, 15, 30, 60};

    private WallpaperEngine() {
    }

    static String source(Context ctx) {
        return prefs(ctx).getString(KEY_SOURCE, SOURCE_TIME);
    }

    static void setSource(Context ctx, String source) {
        prefs(ctx).edit().putString(KEY_SOURCE, source).apply();
    }

    static int intervalMinutes(Context ctx) {
        return prefs(ctx).getInt(KEY_INTERVAL, 5);
    }

    static void setIntervalMinutes(Context ctx, int minutes) {
        prefs(ctx).edit().putInt(KEY_INTERVAL, minutes).apply();
    }

    /** The next interval after the current one, for the settings row. */
    static int nextInterval(int current) {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == current) return INTERVALS[(i + 1) % INTERVALS.length];
        }
        return INTERVALS[0];
    }

    static int[] bundled() {
        return PhotoDream.PHOTOS;
    }

    /** Image files in the app's own folder, alphabetically, so packs can be dropped in over adb. */
    static List<File> photos(Context ctx) {
        List<File> out = new ArrayList<>();
        File dir = ctx.getExternalFilesDir("wallpapers");
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) out.add(f);
        }
        return out;
    }

    static File photosDir(Context ctx) {
        return ctx.getExternalFilesDir("wallpapers");
    }

    /** A random photo from picsum.photos; the seed keeps it stable for a given index. */
    static String randomUrl(Random random) {
        return "https://picsum.photos/seed/" + random.nextInt(1_000_000) + "/1920/1080";
    }

    static Bitmap download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setInstanceFollowRedirects(true);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Average perceived brightness of an image (0 = black, 1 = white), for text contrast. */
    static float luminance(Bitmap b) {
        if (b == null) return 0.5f;
        Bitmap small = Bitmap.createScaledBitmap(b, 16, 16, true);
        double sum = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = small.getPixel(x, y);
                sum += (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
            }
        }
        if (small != b) small.recycle();
        return (float) (sum / 256.0);
    }

    static float luminance(Context ctx, int res) {
        if (res == 0) return 0.5f;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeResource(ctx.getResources(), res, o);
        float l = luminance(b);
        if (b != null) b.recycle();
        return l;
    }

    static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; this TV has ~1 GB
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
