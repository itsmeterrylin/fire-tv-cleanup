package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live News (red button): YouTube channels the viewer adds on the TV, kept in this app's own storage.
 *
 * A pasted link becomes a channel's live page (https://www.youtube.com/@NDTV/live), a video or a playlist. A live
 * stream's link is kept as its channel's live page, because news channels restart their streams and the video id
 * changes. Opening a channel lists every stream it has live right now (liveStreams), since big news channels run
 * several at once; with none listed, its live page is looked up instead (resolve).
 */
final class News {

    static final String YOUTUBE_PKG = "com.amazon.firetv.youtube";
    private static final String TAG = "News";
    private static final String PREFS = "news", KEY = "channels", LOGO_DIR = "news";
    private static final String YT = "https://www.youtube.com";
    private static final String LIVE = "/live";
    // A desktop browser's user agent, so YouTube serves the full page with its og: tags and canonical link.
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int LOGO_PX = 96;
    private static final int PAGE_MAX = 3_000_000;   // chars; reading stops earlier once the needed tags are in

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");
    private static final Pattern HANDLE = Pattern.compile("@[\\w.\\-]{2,}");
    private static final Pattern BARE_LINK = Pattern.compile("(?i)(www\\.|m\\.)?(youtube\\.com|youtu\\.be)/\\S+");
    private static final Pattern CANONICAL = Pattern.compile("<link rel=\"canonical\" href=\"([^\"]+)\"");

    // One background thread, started on demand and gone again after 30 s idle: nothing runs while News isn't used.
    private static final ExecutorService IO = onDemandThread();

    private static ExecutorService onDemandThread() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private News() {
    }

    static final class Channel {
        String name, url;
        String logo;   // file name in files/news, or null
    }

    /** One stream that is live right now on a channel. */
    static final class Stream {
        final String id, title;
        final String watching;   // as YouTube shows it, e.g. "4.8K watching", or null
        final long viewers;      // the same as a number, for sorting; 0 when unknown

        Stream(String id, String title, String watching) {
            this.id = id;
            this.title = title;
            this.watching = watching;
            this.viewers = parseViewers(watching);
        }

        String url() {
            return video(id);
        }
    }

    interface Target {
        void open(String url, boolean liveNow);
    }

    interface StreamsFound {
        void found(List<Stream> live);
    }

    // ---- storage ---------------------------------------------------------------------------------

    static List<Channel> load(Context ctx) {
        List<Channel> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Channel c = new Channel();
                c.name = o.optString("name");
                c.url = o.optString("url");
                String logo = o.optString("logo");
                c.logo = logo.isEmpty() ? null : logo;
                if (!c.url.isEmpty()) list.add(c);
            }
        } catch (JSONException e) {
            Log.w(TAG, "unreadable channel list", e);
        }
        return list;
    }

    static void save(Context ctx, List<Channel> list) {
        JSONArray a = new JSONArray();
        try {
            for (Channel c : list) {
                JSONObject o = new JSONObject().put("name", c.name).put("url", c.url);
                if (c.logo != null) o.put("logo", c.logo);
                a.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(ctx).edit().putString(KEY, a.toString()).apply();
    }

    static Bitmap logo(Context ctx, Channel c) {
        return c.logo == null ? null : BitmapFactory.decodeFile(new File(logoDir(ctx), c.logo).getPath());
    }

    static void deleteLogo(Context ctx, Channel c) {
        if (c.logo != null) new File(logoDir(ctx), c.logo).delete();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File logoDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), LOGO_DIR);
        d.mkdirs();
        return d;
    }

    // ---- links -----------------------------------------------------------------------------------

    /**
     * Turns pasted text into a clean YouTube link, or null when it isn't one. Takes a share text with the link
     * inside, a bare @handle, and youtu.be, watch, /live/, /shorts/, playlist and channel links. Channels become
     * their live page.
     */
    static String normalize(String text) {
        if (text == null) return null;
        text = text.trim();
        Matcher m = URL_IN_TEXT.matcher(text);
        if (m.find()) text = m.group();
        else if (HANDLE.matcher(text).matches()) return YT + "/" + text + LIVE;
        else if (BARE_LINK.matcher(text).matches()) text = "https://" + text;
        else return null;

        Uri u = Uri.parse(text);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        List<String> seg = u.getPathSegments();
        if (host.equals("youtu.be")) return seg.isEmpty() ? null : video(seg.get(0));
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) return null;
        if (seg.isEmpty()) return null;
        String first = seg.get(0);
        switch (first) {
            case "watch": {
                String v = u.getQueryParameter("v");
                return v == null ? null : video(v);
            }
            case "live":
                return seg.size() > 1 ? YT + "/live/" + seg.get(1) : null;
            case "shorts":
                return seg.size() > 1 ? video(seg.get(1)) : null;
            case "playlist": {
                String list = u.getQueryParameter("list");
                return list == null ? null : YT + "/playlist?list=" + list;
            }
            case "channel":
            case "c":
            case "user":
                return seg.size() > 1 ? YT + "/" + first + "/" + seg.get(1) + LIVE : null;
            default:
                return first.startsWith("@") ? YT + "/" + first + LIVE : null;
        }
    }

    private static String video(String id) {
        return YT + "/watch?v=" + id;
    }

    // ---- network (one background thread; callbacks on the main thread) ---------------------------

    /**
     * Finds the name and logo for a link from normalize(). A video that is live right now (or a /live/ link) is
     * kept as its channel's live page. Never fails: without internet the entry gets a name from the link.
     */
    static void lookUp(Context ctx, String link, Consumer<Channel> done) {
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            Channel c = new Channel();
            c.url = link;
            try {
                String channelPage;
                if (link.endsWith(LIVE)) {
                    channelPage = link.substring(0, link.length() - LIVE.length());
                } else {
                    // Video, live stream or playlist: oEmbed gives its title and its channel.
                    boolean liveLink = link.startsWith(YT + "/live/");
                    String item = liveLink ? video(link.substring((YT + "/live/").length())) : link;
                    JSONObject o = new JSONObject(fetch(YT + "/oembed?format=json&url="
                            + URLEncoder.encode(item, "UTF-8"), 64_000));
                    String author = o.optString("author_name"), authorUrl = o.optString("author_url");
                    boolean live = liveLink || (item.contains("watch?v=") && isLiveNow(item));
                    if (live && !authorUrl.isEmpty()) {
                        c.url = authorUrl + LIVE;
                        c.name = author;
                    } else {
                        c.url = item;
                        c.name = o.optString("title", author);
                    }
                    channelPage = authorUrl.isEmpty() ? null : authorUrl;
                }
                if (channelPage != null) {
                    String html = fetch(channelPage, PAGE_MAX, "og:title", "og:image");
                    if (c.name == null || c.name.isEmpty()) c.name = meta(html, "og:title");
                    c.logo = saveLogo(app, meta(html, "og:image"));
                }
            } catch (Exception e) {
                Log.w(TAG, "look-up failed for " + link, e);
            }
            if (c.name == null || c.name.trim().isEmpty()) c.name = fallbackName(c.url);
            MAIN.post(() -> done.accept(c));
        });
    }

    /**
     * What to play for an entry: for a channel's live page, the video that is live right now, or the channel
     * itself (liveNow false) when nothing is; anything else as it is.
     */
    static void resolve(String url, Target done) {
        if (!url.endsWith(LIVE)) {
            done.open(url, true);
            return;
        }
        IO.execute(() -> {
            String target = url;
            boolean live = true;   // when the page can't be read, open the live link and let YouTube decide
            try {
                Matcher m = CANONICAL.matcher(fetch(url, PAGE_MAX, "rel=\"canonical\""));
                if (m.find()) {
                    live = m.group(1).contains("watch?v=");
                    target = live ? m.group(1) : url.substring(0, url.length() - LIVE.length());
                }
            } catch (IOException e) {
                Log.w(TAG, "couldn't check " + url, e);
            }
            final String t = target;
            final boolean l = live;
            MAIN.post(() -> done.open(t, l));
        });
    }

    /** True for an entry that is a channel's live page (rather than one video or playlist). */
    static boolean isChannel(String url) {
        return url.endsWith(LIVE);
    }

    /**
     * Every stream live right now on a channel (url: its live page), read from the channel's Live tab, most
     * watched first. Big news channels run several at once and the live page only gives YouTube's own pick: on
     * 2026-10-20 TV9 Kannada had 8, its 24x7 news stream with 4.8K viewers while the live page picked one with
     * 328. Empty when none is live or the tab can't be read; done runs on the main thread.
     */
    static void liveStreams(String url, StreamsFound done) {
        String channel = url.substring(0, url.length() - LIVE.length());
        IO.execute(() -> {
            List<Stream> live = new ArrayList<>();
            try {
                live = parseLive(fetch(channel + "/streams", PAGE_MAX));
            } catch (IOException e) {
                Log.w(TAG, "couldn't read the live tab of " + channel, e);
            }
            final List<Stream> found = live;
            MAIN.post(() -> done.found(found));
        });
    }

    // The Live tab lists each video as a "lockupViewModel" card; live ones carry the LIVE thumbnail badge.
    private static final String CARD = "\"lockupViewModel\":";
    private static final String LIVE_BADGE = "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE";
    private static final Pattern CONTENT_ID = Pattern.compile("\"contentId\":\"([\\w-]{11})\"");
    private static final Pattern CARD_TITLE = Pattern.compile("\"title\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CARD_WATCHING = Pattern.compile("\"content\":\"([^\"]*watching[^\"]*)\"");
    private static final Pattern VIEWERS = Pattern.compile("([\\d.,]+)\\s*([KMB])?", Pattern.CASE_INSENSITIVE);
    // "🔴LIVE | " and "LIVE: " in front of titles, which every row would otherwise repeat.
    private static final Pattern LIVE_PREFIX = Pattern.compile("^(?:\\x{1F534}|\\s)*(?:LIVE\\s*[|:\\-]\\s*)?",
            Pattern.CASE_INSENSITIVE);

    private static List<Stream> parseLive(String page) {
        List<Stream> live = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        int at = page.indexOf(CARD);
        while (at >= 0) {
            int next = page.indexOf(CARD, at + CARD.length());
            String card = page.substring(at, next < 0 ? page.length() : next);
            at = next;
            if (!card.contains(LIVE_BADGE)) continue;
            Matcher id = CONTENT_ID.matcher(card), title = CARD_TITLE.matcher(card);
            Matcher watching = CARD_WATCHING.matcher(card);
            if (!id.find() || seen.contains(id.group(1))) continue;
            seen.add(id.group(1));
            String t = title.find() ? unescapeJson(title.group(1)) : id.group(1);
            t = LIVE_PREFIX.matcher(t).replaceFirst("").trim();
            live.add(new Stream(id.group(1), t.isEmpty() ? id.group(1) : t,
                    watching.find() ? unescapeJson(watching.group(1)) : null));
        }
        // Most watched first; the sort is stable, so streams without a count keep YouTube's order.
        live.sort((a, b) -> Long.compare(b.viewers, a.viewers));
        return live;
    }

    /** "4.8K watching" -> 4800, "1,234 watching" -> 1234; 0 when there's no number. */
    static long parseViewers(String text) {
        if (text == null) return 0;
        Matcher m = VIEWERS.matcher(text);
        if (!m.find()) return 0;
        try {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            String unit = m.group(2) == null ? "" : m.group(2).toUpperCase(Locale.ROOT);
            return (long) (n * (unit.equals("K") ? 1e3 : unit.equals("M") ? 1e6 : unit.equals("B") ? 1e9 : 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unescapeJson(String s) {
        try {
            return new JSONArray("[\"" + s + "\"]").getString(0);
        } catch (JSONException e) {
            return s;
        }
    }

    private static boolean isLiveNow(String watchUrl) throws IOException {
        // videoDetails lists "isLive":true (only while live) before "isLiveContent".
        return fetch(watchUrl, PAGE_MAX, "\"isLiveContent\"").contains("\"isLive\":true");
    }

    private static String meta(String html, String property) {
        Matcher m = Pattern.compile("<meta property=\"" + Pattern.quote(property) + "\" content=\"([^\"]*)\"")
                .matcher(html);
        return m.find() ? Html.fromHtml(m.group(1), Html.FROM_HTML_MODE_LEGACY).toString().trim() : null;
    }

    private static String fallbackName(String url) {
        Matcher m = Pattern.compile("/(@[^/?]+)").matcher(url);
        return m.find() ? m.group(1) : "YouTube";
    }

    /** Downloads the channel picture small, crops it to a circle and saves it; returns the file name. */
    private static String saveLogo(Context ctx, String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;
        try {
            byte[] bytes = download(imageUrl.replaceAll("=s\\d+", "=s176"), 2_000_000);
            Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (src == null) return null;
            String name = UUID.randomUUID() + ".png";
            try (FileOutputStream out = new FileOutputStream(new File(logoDir(ctx), name))) {
                circle(src).compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return name;
        } catch (IOException e) {
            Log.w(TAG, "logo download failed", e);
            return null;
        }
    }

    private static Bitmap circle(Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
        Bitmap scaled = Bitmap.createScaledBitmap(square, LOGO_PX, LOGO_PX, true);
        Bitmap out = Bitmap.createBitmap(LOGO_PX, LOGO_PX, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(LOGO_PX / 2f, LOGO_PX / 2f, LOGO_PX / 2f, p);
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en");
        c.setRequestProperty("Cookie", "SOCS=CAI");   // skips the cookie-consent page where YouTube shows one
        return c;
    }

    /** Reads a page as text, stopping early once every marker (plus a little after it) has arrived. */
    private static String fetch(String url, int maxChars, String... until) throws IOException {
        HttpURLConnection c = open(url);
        try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[16384];
            int n, checkedAt = 0;
            while (sb.length() < maxChars && (n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (until.length > 0 && sb.length() - checkedAt > 65536) {
                    if (hasAll(sb, until)) break;
                    checkedAt = sb.length();
                }
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static boolean hasAll(StringBuilder sb, String[] markers) {
        for (String m : markers) {
            int i = sb.indexOf(m);
            if (i < 0 || i + 2000 > sb.length()) return false;
        }
        return true;
    }

    private static byte[] download(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (out.size() < maxBytes && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
