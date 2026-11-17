package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Cast and details for a USB title from TMDB, for the USB screen and the player's pause panel: genres, runtime,
 * rating, director, and the cast with their characters and photos. For an episode also its own name, story,
 * director and guest stars. One request per title (two for an episode), kept on the TV for 30 days and used
 * offline after that.
 */
final class UsbInfo {

    private static final String TAG = "UsbInfo";
    private static final long KEEP_MS = 30L * 86_400_000L;
    private static final int MAX_CAST = 8;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Person {
        String name = "", role = "", photo = "";
    }

    static final class Info {
        String genres = "", runtime = "", director = "", episodeName = "", episodeStory = "";
        double rating;
        final List<Person> cast = new ArrayList<>();
    }

    /** Loads on a background thread and answers on the main thread; null when TMDB can't be reached or matched. */
    static void load(Context ctx, String token, Trending.Item match, int season, int episode, Consumer<Info> done) {
        if (match == null || match.id <= 0 || token == null || token.isEmpty()) {
            done.accept(null);
            return;
        }
        File dir = new File(ctx.getCacheDir(), "tmdb-info");
        IO.execute(() -> {
            Info info = null;
            try {
                info = "tv".equals(match.kind) ? series(dir, token, match.id, season, episode) : movie(dir, token, match.id);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "info " + match.id, e);
            }
            final Info result = info;
            MAIN.post(() -> done.accept(result));
        });
    }

    private static Info movie(File dir, String token, int id) throws IOException, JSONException {
        JSONObject o = new JSONObject(Trending.get(dir, token, "/movie/" + id + "?append_to_response=credits", KEEP_MS));
        Info info = new Info();
        info.genres = genres(o);
        info.runtime = minutes(o.optInt("runtime"));
        info.rating = o.optDouble("vote_average", 0);
        JSONObject credits = o.optJSONObject("credits");
        if (credits != null) {
            info.director = directors(credits.optJSONArray("crew"));
            addCast(credits.optJSONArray("cast"), info.cast, false);
        }
        return info;
    }

    private static Info series(File dir, String token, int id, int season, int episode) throws IOException, JSONException {
        JSONObject o = new JSONObject(Trending.get(dir, token, "/tv/" + id + "?append_to_response=aggregate_credits", KEEP_MS));
        Info info = new Info();
        info.genres = genres(o);
        JSONArray runs = o.optJSONArray("episode_run_time");
        if (runs != null && runs.length() > 0) info.runtime = minutes(runs.optInt(0));
        info.rating = o.optDouble("vote_average", 0);
        JSONArray creators = o.optJSONArray("created_by");
        if (creators != null && creators.length() > 0) info.director = creators.optJSONObject(0).optString("name");
        JSONObject credits = o.optJSONObject("aggregate_credits");
        if (credits != null) addCast(credits.optJSONArray("cast"), info.cast, true);
        if (season >= 0 && episode >= 0) {
            try {
                JSONObject e = new JSONObject(Trending.get(dir, token,
                        "/tv/" + id + "/season/" + season + "/episode/" + episode + "?append_to_response=credits", KEEP_MS));
                info.episodeName = e.optString("name");
                info.episodeStory = e.optString("overview");
                if (e.optInt("runtime") > 0) info.runtime = minutes(e.optInt("runtime"));
                if (e.optDouble("vote_average", 0) > 0) info.rating = e.optDouble("vote_average", 0);
                String dir2 = directors(e.optJSONArray("crew"));
                if (!dir2.isEmpty()) info.director = dir2;
                // The episode's guest stars after the regulars, while there's room
                List<Person> guests = new ArrayList<>();
                addCast(e.optJSONArray("guest_stars"), guests, false);
                for (Person g : guests) {
                    if (info.cast.size() >= MAX_CAST) break;
                    boolean dup = false;
                    for (Person p : info.cast) dup |= p.name.equals(g.name);
                    if (!dup) info.cast.add(g);
                }
            } catch (IOException | JSONException e) {
                Log.w(TAG, "episode " + id + " S" + season + "E" + episode, e);   // the show's info is still fine
            }
        }
        return info;
    }

    private static void addCast(JSONArray cast, List<Person> into, boolean aggregate) {
        for (int i = 0; cast != null && i < cast.length() && into.size() < MAX_CAST; i++) {
            JSONObject c = cast.optJSONObject(i);
            if (c == null) continue;
            Person p = new Person();
            p.name = c.optString("name");
            if (aggregate) {
                JSONArray roles = c.optJSONArray("roles");
                if (roles != null && roles.length() > 0) p.role = roles.optJSONObject(0).optString("character");
            } else {
                p.role = c.optString("character");
            }
            String photo = c.optString("profile_path");
            p.photo = photo.equals("null") ? "" : photo;
            if (!p.name.isEmpty()) into.add(p);
        }
    }

    private static String directors(JSONArray crew) {
        List<String> names = new ArrayList<>();
        for (int i = 0; crew != null && i < crew.length(); i++) {
            JSONObject c = crew.optJSONObject(i);
            if (c != null && "Director".equals(c.optString("job")) && !names.contains(c.optString("name"))) {
                names.add(c.optString("name"));
            }
        }
        return String.join(", ", names.subList(0, Math.min(2, names.size())));
    }

    private static String genres(JSONObject o) {
        JSONArray g = o.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && names.size() < 3; i++) names.add(g.optJSONObject(i).optString("name"));
        return String.join(", ", names);
    }

    private static String minutes(int min) {
        if (min <= 0) return "";
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }
}
