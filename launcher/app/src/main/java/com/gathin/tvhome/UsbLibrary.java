package com.gathin.tvhome;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The USB screen's data: the videos on removable drives, what each one is (title, year, episode, read from the
 * file name, then matched on TMDB once and remembered), and how far you watched (saved from Just Player's answer
 * when you leave it). Only used while the USB screen is open, in its own process.
 *
 * Videos come from Android's media index (MediaStore), which Fire OS fills for USB drives on its own, so opening
 * the screen reads no folders. A drive the index has not reached yet (just plugged in) is read directly instead.
 */
final class UsbLibrary {

    private static final String TAG = "UsbLibrary";
    static final String[] VIDEO_EXT = {"mkv", "mp4", "avi", "m4v", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg"};
    static final String[] SUB_EXT = {"srt", "ass", "ssa", "vtt"};

    static final class Video {
        long id = -1;            // MediaStore id; -1 when read straight from the drive
        String path;             // absolute file path
        String file;             // file name
        long durationMs, size, addedSec;
        int width, height;
        // From the file name
        String title = "", year = "", episodeName = "";
        int season = -1, episode = -1;
        // From TMDB (may stay empty offline)
        Trending.Item match;

        boolean isEpisode() {
            return episode >= 0;
        }

        /** Shows group their episodes under one key. */
        String showKey() {
            return title.toLowerCase(Locale.ROOT);
        }

        /** For the player: the media index's link, else this launcher's own (a file:// link can't be shared). */
        Uri uri() {
            return id >= 0 ? ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    : UsbFilesProvider.uriFor(path);
        }
    }

    // ---- scanning ---------------------------------------------------------------------------------

    /** Paths of the mounted removable drives (USB), e.g. /storage/66CE544ACE541521. */
    static List<String> drives(Context ctx) {
        List<String> out = new ArrayList<>();
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm == null) return out;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !"mounted".equals(v.getState())) continue;
            String uuid = v.getUuid();
            File dir = android.os.Build.VERSION.SDK_INT >= 30 ? v.getDirectory()
                    : uuid != null ? new File("/storage/" + uuid) : null;
            if (dir != null) out.add(dir.getAbsolutePath());
        }
        return out;
    }

    /** Every video on the USB drives. Slow-ish (a database query, or a folder walk): call off the main thread. */
    static List<Video> scan(Context ctx) {
        List<String> drives = drives(ctx);
        List<Video> out = new ArrayList<>();
        if (drives.isEmpty()) return out;
        Set<String> indexed = new HashSet<>();
        String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT};
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String path = c.getString(1);
                String drive = driveOf(path, drives);
                if (drive == null || !new File(path).exists()) continue;
                Video v = new Video();
                v.id = c.getLong(0);
                v.path = path;
                v.file = c.getString(2) != null ? c.getString(2) : new File(path).getName();
                v.durationMs = c.getLong(3);
                v.size = c.getLong(4);
                v.addedSec = c.getLong(5);
                v.width = c.getInt(6);
                v.height = c.getInt(7);
                out.add(v);
                indexed.add(drive);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "media index", e);
        }
        // A drive with nothing in the index yet: read it directly (4 folders deep).
        for (String drive : drives) {
            if (!indexed.contains(drive)) walk(new File(drive), 0, out);
        }
        for (Video v : out) parseName(v);
        return out;
    }

    private static String driveOf(String path, List<String> drives) {
        if (path == null) return null;
        for (String d : drives) if (path.startsWith(d + "/")) return d;
        return null;
    }

    private static void walk(File dir, int depth, List<Video> out) {
        File[] files = dir.listFiles();
        if (files == null || depth > 4) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals("System Volume Information") || name.equals("LOST.DIR")) continue;
            if (f.isDirectory()) {
                walk(f, depth + 1, out);
            } else if (hasExt(name, VIDEO_EXT) && f.length() > 20L * 1024 * 1024) {   // skip samples and clips
                Video v = new Video();
                v.path = f.getAbsolutePath();
                v.file = name;
                v.size = f.length();
                v.addedSec = f.lastModified() / 1000;
                out.add(v);
            }
        }
    }

    static boolean hasExt(String name, String[] exts) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.asList(exts).contains(ext);
    }

    /** Subtitle files next to a video: "Film.srt", "Film.en.srt", or the only subtitle in its folder. */
    static List<File> subtitles(Video v) {
        List<File> out = new ArrayList<>();
        File video = new File(v.path);
        File[] siblings = video.getParentFile() != null ? video.getParentFile().listFiles() : null;
        if (siblings == null) return out;
        String base = stripExt(video.getName()).toLowerCase(Locale.ROOT);
        List<File> all = new ArrayList<>();
        int videos = 0;
        for (File f : siblings) {
            if (hasExt(f.getName(), SUB_EXT)) all.add(f);
            else if (hasExt(f.getName(), VIDEO_EXT)) videos++;
        }
        for (File f : all) if (f.getName().toLowerCase(Locale.ROOT).startsWith(base)) out.add(f);
        if (out.isEmpty() && videos == 1) out.addAll(all);   // one film per folder: its subtitles, whatever the name
        // A Subs/ folder beside the video (common with downloads)
        if (out.isEmpty()) {
            for (String sub : new String[]{"Subs", "subs", "Subtitles"}) {
                File[] inner = new File(video.getParentFile(), sub).listFiles();
                if (inner == null) continue;
                for (File f : inner) if (hasExt(f.getName(), SUB_EXT)) out.add(f);
            }
        }
        out.sort((a, b) -> Boolean.compare(!isEnglish(a), !isEnglish(b)));   // English first
        return out;
    }

    private static boolean isEnglish(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.contains(".en.") || n.contains(".eng.") || n.contains("english");
    }

    /** "Film.en.srt", "Film.fre.srt": a 2-3 letter code just before the extension (what Just Player reads). */
    private static final Pattern LANG_TAG = Pattern.compile("(?i)\\.[a-z]{2,3}\\.[a-z0-9]+$");

    /**
     * The language to tag an untagged .srt with ("en"), or null. Just Player only takes a subtitle's language from
     * its name, and without one it loses to a film's built-in "forced" track (French forced on a French-audio
     * MKV). Named "English", or mostly common English words in its first 16 KB: "en".
     */
    static String subtitleLanguage(File f) {
        String n = f.getName();
        if (!n.toLowerCase(Locale.ROOT).endsWith(".srt") || LANG_TAG.matcher(n).find()) return null;
        if (n.toLowerCase(Locale.ROOT).contains("english")) return "en";
        byte[] buf = new byte[16 * 1024];
        int len;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            len = in.read(buf);
        } catch (java.io.IOException e) {
            return null;
        }
        if (len <= 0) return null;
        String text = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        int words = 0, common = 0;
        for (String w : text.split("[^a-z']+")) {
            if (w.isEmpty()) continue;
            words++;
            if (COMMON_EN.contains(w)) common++;
        }
        return words >= 100 && common * 100 >= words * 20 ? "en" : null;
    }

    private static final java.util.Set<String> COMMON_EN = new java.util.HashSet<>(Arrays.asList(
            "the", "you", "i", "to", "a", "and", "it", "is", "that", "of", "what", "me", "my", "this", "in", "don't",
            "i'm", "your", "we", "he", "she", "be", "have", "do", "no", "not", "are", "was", "for", "on", "with",
            "just", "can", "there", "so", "know", "it's", "but", "all", "her", "him", "they", "if", "go", "up"));

    // ---- names: "L.A.Confidential.1997.1080p.BluRay..." -> "L A Confidential", 1997 ---------------

    private static final Pattern EPISODE = Pattern.compile("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b");
    private static final Pattern EPISODE_X = Pattern.compile("(?i)\\b(\\d{1,2})x(\\d{2})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(19[3-9]\\d|20[0-4]\\d)\\b");
    private static final Pattern JUNK = Pattern.compile("(?i)\\b(2160p|1080p|720p|576p|480p|4k|uhd|hdr10?\\+?|hdr|dv|"
            + "dolby ?vision|blu-?ray|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|hdrip|remux|x26[45]|h ?26[45]|hevc|"
            + "avc|10 ?bit|ddp?(5 1|2 0|7 1)?|dts(-hd)?|aac|ac3|atmos|truehd|eac3|remastered|extended|proper|repack|"
            + "multi|dual audio|triple audio|hindi|tamil|telugu|esubs?|subs?|nf|amzn|dsnp|hmax|atvp|ita|eng)\\b");

    static void parseName(Video v) {
        String name = stripExt(v.file);
        // "[Toonworld4all] Ramayana ..." -> drop bracketed tags; keep "(1993)" for the year
        name = name.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\{[^}]*\\}", " ");
        name = name.replace('_', ' ');
        // dots and underscores as spaces, but keep "5.1" style audio and leave initials readable
        name = name.replaceAll("(?<!\\d)\\.|\\.(?!\\d)", " ");
        name = name.replaceAll("\\s+", " ").trim();

        String rest = "";
        Matcher ep = EPISODE.matcher(name);
        Matcher epx = EPISODE_X.matcher(name);
        if (ep.find()) {
            v.season = Integer.parseInt(ep.group(1));
            v.episode = Integer.parseInt(ep.group(2));
            rest = name.substring(ep.end());
            name = name.substring(0, ep.start());
        } else if (epx.find() && epx.start() > 0) {
            v.season = Integer.parseInt(epx.group(1));
            v.episode = Integer.parseInt(epx.group(2));
            rest = name.substring(epx.end());
            name = name.substring(0, epx.start());
        }
        Matcher y = YEAR.matcher(name);
        while (y.find()) {
            if (y.start() == 0) continue;   // a title that starts with a year ("1917", "2012")
            v.year = y.group(1);
            name = name.substring(0, y.start());
            break;
        }
        Matcher junk = JUNK.matcher(name);
        if (junk.find() && junk.start() > 0) name = name.substring(0, junk.start());
        v.title = tidy(name);
        if (v.title.isEmpty()) v.title = tidy(stripExt(v.file));
        // folder name for generic file names ("movie.mkv", "video_ts")
        if (v.title.length() < 3 && new File(v.path).getParentFile() != null) {
            Video folder = new Video();
            folder.file = new File(v.path).getParentFile().getName();
            folder.path = v.path;
            parseName(folder);
            v.title = folder.title;
            if (v.year.isEmpty()) v.year = folder.year;
        }
        if (v.isEpisode()) {
            Matcher j = JUNK.matcher(rest);
            if (j.find()) rest = rest.substring(0, j.start());
            v.episodeName = tidy(rest);
        }
    }

    private static String tidy(String s) {
        s = s.replaceAll("[()\\[\\]]", " ").replaceAll("\\s+-\\s*$|^\\s*-\\s+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.endsWith("-") || s.endsWith(",") || s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        // initials that lost their dots: "L A Confidential" -> "L.A. Confidential"
        Matcher m = Pattern.compile("\\b([A-Z])((?: [A-Z]\\b)+)").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, (m.group(1) + m.group(2).replace(" ", ".") + ".").replace(".", "\\."));
        m.appendTail(sb);
        return sb.toString();
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---- TMDB matches, kept for good ----------------------------------------------------------------

    private static final String MATCHES = "usb_matches";

    /** The remembered TMDB match for this title, if any. A remembered "no match" gives an empty Item. */
    static Trending.Item cachedMatch(Context ctx, Video v) {
        String json = ctx.getSharedPreferences(MATCHES, Context.MODE_PRIVATE).getString(matchKey(v), null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("id") > 0 && !o.has("lang")) return null;   // saved before the language was kept: look up again (cached)
            Trending.Item it = new Trending.Item();
            it.id = o.optInt("id");
            it.title = o.optString("title");
            it.kind = o.optString("kind");
            it.year = o.optString("year");
            it.overview = o.optString("overview");
            it.poster = o.optString("poster");
            it.backdrop = o.optString("backdrop");
            it.rating = o.optDouble("rating");
            it.votes = o.optInt("votes");
            it.lang = o.optString("lang");
            return it;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Looks the title up on TMDB (background thread) and remembers the answer, a miss included. */
    static Trending.Item lookUp(Context ctx, String token, Video v) {
        Trending.Item it = Trending.search(ctx, token, v.title, v.isEpisode() ? "" : v.year, v.isEpisode());
        if (it == null && !v.isEpisode() && v.year.isEmpty()) {
            it = Trending.search(ctx, token, v.title, "", true);   // a series saved without SxxEyy
        }
        try {
            JSONObject o = new JSONObject();
            if (it != null) {
                o.put("id", it.id).put("title", it.title).put("kind", it.kind).put("year", it.year)
                        .put("overview", it.overview).put("poster", it.poster).put("backdrop", it.backdrop)
                        .put("rating", it.rating).put("votes", it.votes).put("lang", it.lang);
            }
            ctx.getSharedPreferences(MATCHES, Context.MODE_PRIVATE).edit().putString(matchKey(v), o.toString()).apply();
        } catch (JSONException ignored) {
        }
        return it != null ? it : new Trending.Item();
    }

    private static String matchKey(Video v) {
        return (v.isEpisode() ? "tv:" : "movie:") + v.showKey() + ":" + (v.isEpisode() ? "" : v.year);
    }

    // ---- how far you watched ----------------------------------------------------------------------

    private static final String PROGRESS = "usb_progress";
    static final long MIN_RESUME_MS = 60_000;   // less than a minute in: start again from the beginning

    /** Saved position in ms (0 = from the start), and when it was saved. Keyed by file path. */
    static long position(Context ctx, Video v) {
        return ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).getLong("pos:" + v.path, 0);
    }

    static long watchedAt(Context ctx, Video v) {
        return ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).getLong("at:" + v.path, 0);
    }

    static boolean finished(Context ctx, Video v) {
        return ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).getBoolean("done:" + v.path, false);
    }

    static void savePosition(Context ctx, Video v, long positionMs, long durationMs, boolean completed) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).edit();
        long length = durationMs > 0 ? durationMs : v.durationMs;
        boolean nearEnd = length > 0 && positionMs > length * 0.94;
        if (completed || nearEnd) {
            e.remove("pos:" + v.path).putBoolean("done:" + v.path, true);
        } else if (positionMs >= MIN_RESUME_MS) {
            e.putLong("pos:" + v.path, positionMs).remove("done:" + v.path);
        }
        e.putLong("at:" + v.path, System.currentTimeMillis());
        if (durationMs > 0 && v.durationMs <= 0) e.putLong("len:" + v.path, durationMs);
        e.apply();
    }

    /** Length remembered from Just Player for files the media index had none for. */
    static long savedLength(Context ctx, Video v) {
        return ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).getLong("len:" + v.path, 0);
    }

    static void clearProgress(Context ctx, Video v) {
        ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).edit()
                .remove("pos:" + v.path).remove("at:" + v.path).remove("done:" + v.path).apply();
    }

    static void markWatched(Context ctx, Video v) {
        ctx.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).edit()
                .remove("pos:" + v.path).putBoolean("done:" + v.path, true)
                .putLong("at:" + v.path, System.currentTimeMillis()).apply();
    }

    // ---- labels -------------------------------------------------------------------------------------

    /** "4K · HDR · Dolby Vision · Atmos · 5.1" from the file name and the picture size. */
    static String quality(Video v) {
        String n = v.file.toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        int h = v.width > 0 && v.height > 0 ? Math.min(v.width, v.height) : 0;
        if (h >= 1600 || n.contains("2160p") || n.matches(".*\\b4k\\b.*")) parts.add("4K");
        else if (h >= 1000 || n.contains("1080p")) parts.add("1080p");
        else if (h >= 700 || n.contains("720p")) parts.add("720p");
        else if (h > 0) parts.add(h + "p");
        boolean dv = n.matches(".*[ ._-](dv|dovi|dolby[ ._-]?vision)[ ._-].*");
        if (n.contains("hdr")) parts.add("HDR");
        if (dv) parts.add("Dolby Vision");
        if (n.contains("atmos")) parts.add("Atmos");
        if (n.matches(".*(5[ ._]1|ddp5|dd5).*")) parts.add("5.1");
        else if (n.matches(".*7[ ._]1.*")) parts.add("7.1");
        return String.join(" · ", parts);
    }

    static String length(long ms) {
        long min = Math.round(ms / 60_000.0);
        if (min <= 0) return "";
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String size(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30))
                : String.format(Locale.US, "%d MB", bytes >> 20);
    }
}
