package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Trending screen data from TMDB (themoviedb.org): what's trending worldwide, what's popular on each streaming
 * service in India (availability data from JustWatch, via TMDB), and popular Indian titles. View only.
 *
 * Everything is on demand: nothing runs until the screen opens, answers are kept for CACHE_MS so reopening is
 * instant and offline-safe, and the threads exit after 30 s idle. api.tmdb.org is TMDB's alternative API host;
 * on this TV's network api.themoviedb.org dropped about half its connections (2026-10-20) while api.tmdb.org
 * answered every time, so it's tried first.
 */
final class Trending {

    private static final String TAG = "Trending";
    private static final String[] API_HOSTS = {"https://api.tmdb.org/3", "https://api.themoviedb.org/3"};
    private static final String[] IMAGE_HOSTS = {"https://image.tmdb.org/t/p/", "https://media.themoviedb.org/t/p/"};
    private static final long CACHE_MS = 3 * 60 * 60_000L;
    static final int PER_ROW = 20;

    // TMDB watch-provider ids for India (GET /watch/providers/movie?watch_region=IN, checked 2026-10-20).
    private static final int NETFLIX = 8, PRIME_VIDEO = 119, APPLE_TV = 350, JIOHOTSTAR = 2336;
    // Discover filters that keep rows current: films out in the last 6 months, and series that started in the
    // last 3 years and aired an episode in the last 2 months (without these the rows fill up with long-running
    // shows like Law & Order). Talk and news shows (genres 10767, 10763) are left out.
    private static final String MOVIE_FILTER = "&sort_by=popularity.desc&include_adult=false"
            + "&primary_release_date.gte=%1$s&primary_release_date.lte=%3$s";
    private static final String TV_FILTER = "&sort_by=popularity.desc&include_adult=false&without_genres=10763,10767"
            + "&first_air_date.gte=%2$s&air_date.gte=%4$s";

    static final class Item {
        int id;
        String title, kind, year, overview, poster, backdrop = "";
        String lang = "";   // original language, ISO 639-1 ("ja"); the USB player plays that audio
        double rating;
        int votes;
        double popularity;
    }

    /** One streaming service from TMDB's watch providers (JustWatch data). */
    static final class Provider {
        int id;
        String name, logo;
    }

    /** What the Trending screen shows once a title has been focused for a moment: one request per title. */
    static final class Details {
        String genres = "", length = "", language = "", certification = "", releaseDate = "";
        String imdbId = "", trailerKey = "";
        final List<Provider> stream = new ArrayList<>();   // with a subscription
        final List<Provider> free = new ArrayList<>();     // free, or free with ads
        final List<Provider> rentOrBuy = new ArrayList<>();
    }

    static final class Row {
        final int title;              // string resource
        final boolean keepOrder;      // TMDB's own order (trending) rather than by popularity
        final String[] queries;       // API paths with query strings

        Row(int title, boolean keepOrder, String... queries) {
            this.title = title;
            this.keepOrder = keepOrder;
            this.queries = queries;
        }
    }

    static List<Row> rows() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long now = System.currentTimeMillis(), day = 86_400_000L;
        String movieFrom = f.format(new Date(now - 180 * day)), tvFrom = f.format(new Date(now - 3 * 365 * day));
        String today = f.format(new Date(now)), airedFrom = f.format(new Date(now - 60 * day));
        String movie = String.format(Locale.US, MOVIE_FILTER, movieFrom, tvFrom, today, airedFrom);
        String tv = String.format(Locale.US, TV_FILTER, movieFrom, tvFrom, today, airedFrom);
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(R.string.trending_world, true, "/trending/all/week"));
        int[][] services = {{R.string.popular_netflix, NETFLIX}, {R.string.popular_prime, PRIME_VIDEO},
                {R.string.popular_apple, APPLE_TV}, {R.string.popular_hotstar, JIOHOTSTAR}};
        for (int[] s : services) {
            String where = "?watch_region=IN&with_watch_providers=" + s[1];
            rows.add(new Row(s[0], false, "/discover/movie" + where + movie, "/discover/tv" + where + tv));
        }
        rows.add(new Row(R.string.popular_indian, false,
                "/discover/movie?with_origin_country=IN" + movie, "/discover/tv?with_origin_country=IN" + tv));
        return rows;
    }

    // ---- loading (background threads, results on the main thread) -------------------------------

    private static final ExecutorService IO = pool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static ExecutorService pool(int threads) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** Deletes cached answers older than 3 days: each focused title adds one, so they would pile up. */
    static void prune(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "trending");
        IO.execute(() -> {
            File[] files = dir.listFiles();
            long cutoff = System.currentTimeMillis() - 3 * 86_400_000L;
            for (int i = 0; files != null && i < files.length; i++) {
                if (files[i].lastModified() < cutoff) files[i].delete();
            }
        });
    }

    /** Loads one row; done gets null when nothing could be loaded. */
    static void load(Context ctx, String token, Row row, Consumer<List<Item>> done) {
        File dir = new File(ctx.getCacheDir(), "trending");
        IO.execute(() -> {
            List<Item> items = new ArrayList<>();
            boolean any = false;
            for (String q : row.queries) {
                try {
                    items.addAll(parse(new JSONObject(get(dir, token, q))));
                    any = true;
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "couldn't load " + q, e);
                }
            }
            if (!row.keepOrder) items.sort((a, b) -> Double.compare(b.popularity, a.popularity));
            List<Item> top = any ? new ArrayList<>(items.subList(0, Math.min(PER_ROW, items.size()))) : null;
            MAIN.post(() -> done.accept(top));
        });
    }

    /**
     * A title's details for India: where it streams, genres, length, language, age rating, IMDb id and trailer.
     * /movie/{id} or /tv/{id} with the rest appended, so it is one request, kept in the same 3-hour cache.
     * done gets null when it could not be loaded.
     */
    static void details(Context ctx, String token, Item it, Consumer<Details> done) {
        File dir = new File(ctx.getCacheDir(), "trending");
        boolean movie = it.kind.equals("movie");
        String query = movie
                ? "/movie/" + it.id + "?append_to_response=watch/providers,release_dates,videos"
                : "/tv/" + it.id + "?append_to_response=watch/providers,content_ratings,videos,external_ids";
        IO.execute(() -> {
            Details d = null;
            try {
                d = parseDetails(new JSONObject(get(dir, token, query)), movie);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "couldn't load " + query, e);
            }
            final Details result = d;
            MAIN.post(() -> done.accept(result));
        });
    }

    private static Details parseDetails(JSONObject o, boolean movie) {
        Details d = new Details();
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < genres.length() && names.size() < 3; i++) {
                JSONObject g = genres.optJSONObject(i);
                if (g != null && !g.optString("name").isEmpty()) names.add(g.optString("name"));
            }
            d.genres = String.join(", ", names);
        }
        if (movie) {
            int minutes = o.optInt("runtime", 0);
            if (minutes > 0) d.length = minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
            d.releaseDate = o.optString("release_date");
            d.imdbId = o.optString("imdb_id");
        } else {
            int seasons = o.optInt("number_of_seasons", 0);
            if (seasons > 0) d.length = seasons + (seasons == 1 ? " season" : " seasons");
            JSONObject ext = o.optJSONObject("external_ids");
            if (ext != null) d.imdbId = ext.optString("imdb_id");
        }
        if (d.imdbId.equals("null")) d.imdbId = "";
        String lang = o.optString("original_language");
        if (!lang.isEmpty()) d.language = new Locale(lang).getDisplayLanguage(Locale.ENGLISH);
        d.certification = movie ? movieCertification(o.optJSONObject("release_dates"))
                : tvCertification(o.optJSONObject("content_ratings"));

        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject in = wp != null && wp.optJSONObject("results") != null ? wp.optJSONObject("results").optJSONObject("IN") : null;
        if (in != null) {
            addProviders(in.optJSONArray("flatrate"), d.stream);
            addProviders(in.optJSONArray("free"), d.free);
            addProviders(in.optJSONArray("ads"), d.free);
            addProviders(in.optJSONArray("rent"), d.rentOrBuy);
            addProviders(in.optJSONArray("buy"), d.rentOrBuy);
        }

        // An official YouTube trailer first, then any trailer, then a teaser.
        JSONObject videos = o.optJSONObject("videos");
        JSONArray vs = videos != null ? videos.optJSONArray("results") : null;
        int best = 0;
        for (int i = 0; vs != null && i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type");
            int score = type.equals("Trailer") ? (v.optBoolean("official") ? 3 : 2) : type.equals("Teaser") ? 1 : 0;
            if (score > best) {
                best = score;
                d.trailerKey = v.optString("key");
            }
        }
        return d;
    }

    /** Each service once, in TMDB's order (most relevant first). */
    private static void addProviders(JSONArray list, List<Provider> into) {
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) continue;
            int id = p.optInt("provider_id");
            boolean seen = false;
            for (Provider q : into) seen |= q.id == id;
            if (seen) continue;
            Provider pr = new Provider();
            pr.id = id;
            pr.name = p.optString("provider_name");
            pr.logo = p.optString("logo_path");
            into.add(pr);
        }
    }

    private static String movieCertification(JSONObject releaseDates) {
        JSONArray countries = releaseDates != null ? releaseDates.optJSONArray("results") : null;
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            if (c == null || !"IN".equals(c.optString("iso_3166_1"))) continue;
            JSONArray dates = c.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject r = dates.optJSONObject(j);
                String cert = r != null ? r.optString("certification").trim() : "";
                if (!cert.isEmpty()) return cert;
            }
        }
        return "";
    }

    private static String tvCertification(JSONObject contentRatings) {
        JSONArray countries = contentRatings != null ? contentRatings.optJSONArray("results") : null;
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            if (c != null && "IN".equals(c.optString("iso_3166_1"))) return c.optString("rating").trim();
        }
        return "";
    }

    /**
     * The best TMDB match for a title read from a file name (USB screen): /search/movie or /search/tv, with the
     * year when known; with no hit, once more without the year. Runs on the calling (background) thread; null
     * when nothing matched or TMDB could not be reached.
     */
    static Item search(Context ctx, String token, String title, String year, boolean tv) {
        File dir = new File(ctx.getCacheDir(), "tmdb-search");
        String base = (tv ? "/search/tv" : "/search/movie") + "?include_adult=false&query=" + android.net.Uri.encode(title);
        String withYear = year.isEmpty() ? null : base + (tv ? "&first_air_date_year=" : "&year=") + year;
        for (String q : withYear != null ? new String[]{withYear, base} : new String[]{base}) {
            try {
                JSONObject answer = new JSONObject(get(dir, token, q));
                JSONArray results = answer.optJSONArray("results");
                if (results == null) continue;
                // search results carry no media_type: set it so parse() reads the right fields
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.optJSONObject(i);
                    if (o != null) o.put("media_type", tv ? "tv" : "movie");
                }
                List<Item> items = parse(answer);
                if (!items.isEmpty()) return items.get(0);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "search " + q, e);
                return null;
            }
        }
        return null;
    }

    /** A wide still for the USB screen's backdrop, 780 px wide. */
    static void backdrop(String path, Consumer<Bitmap> done) {
        image("w780", path, done);
    }

    /** A service's logo, 92 px square. */
    static void logo(String path, Consumer<Bitmap> done) {
        image("w92", path, done);
    }

    /** A poster, small (185 px wide) and in 16-bit colour: about 100 KB of memory each. */
    static void poster(String path, Consumer<Bitmap> done) {
        image("w185", path, done);
    }

    /** A cast member's photo, small (w185). */
    static void face(String path, Consumer<Bitmap> done) {
        image("w185", path, done);
    }

    private static void image(String size, String path, Consumer<Bitmap> done) {
        IO.execute(() -> {
            Bitmap b = null;
            for (String host : IMAGE_HOSTS) {
                try {
                    byte[] bytes = download(host + size + path, false, null);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    if (b != null) break;
                } catch (IOException e) {
                    Log.w(TAG, "poster " + path + " from " + host, e);
                }
            }
            final Bitmap result = b;
            MAIN.post(() -> done.accept(result));
        });
    }

    /** A TMDB API answer: from the cache when fresh, else fetched (each host tried twice), else a stale copy. */
    private static String get(File dir, String token, String query) throws IOException {
        return get(dir, token, query, CACHE_MS);
    }

    /** A TMDB answer, kept on disk for maxAgeMs; offline, an older copy is used. Background thread only. */
    static String get(File dir, String token, String query, long maxAgeMs) throws IOException {
        File cached = new File(dir, Integer.toHexString(query.hashCode()) + ".json");
        if (cached.exists() && System.currentTimeMillis() - cached.lastModified() < maxAgeMs) return read(cached);
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String host : API_HOSTS) {
                try {
                    String body = new String(download(host + query, true, token), StandardCharsets.UTF_8);
                    // Written aside, then renamed: the screen's process can end mid-write when you leave.
                    dir.mkdirs();
                    File part = new File(dir, cached.getName() + ".part");
                    try (FileOutputStream out = new FileOutputStream(part)) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    if (!part.renameTo(cached)) part.delete();
                    return body;
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        if (cached.exists()) return read(cached);   // offline: an older list beats an empty row
        throw last;
    }

    private static List<Item> parse(JSONObject answer) {
        List<Item> out = new ArrayList<>();
        JSONArray results = answer.optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("media_type", o.has("title") ? "movie" : "tv");
            if (!type.equals("movie") && !type.equals("tv")) continue;   // trending also lists people
            String poster = o.optString("poster_path");
            if (poster.isEmpty() || poster.equals("null")) continue;
            Item it = new Item();
            it.id = o.optInt("id");
            it.title = o.optString(type.equals("movie") ? "title" : "name");
            it.kind = type;
            String date = o.optString(type.equals("movie") ? "release_date" : "first_air_date");
            it.year = date.length() >= 4 ? date.substring(0, 4) : "";
            it.overview = o.optString("overview");
            it.poster = poster;
            String backdrop = o.optString("backdrop_path");
            it.backdrop = backdrop.equals("null") ? "" : backdrop;
            it.lang = o.optString("original_language");
            it.rating = o.optDouble("vote_average", 0);
            it.votes = o.optInt("vote_count", 0);
            it.popularity = o.optDouble("popularity", 0);
            out.add(it);
        }
        return out;
    }

    private static byte[] download(String url, boolean api, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        if (api) {
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            c.setUseCaches(false);   // kept in our own 3-hour cache instead
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
