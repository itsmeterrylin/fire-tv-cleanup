package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trending (its own tile in the launcher): rows of posters from TMDB. The focused title's name, year, kind,
 * rating and story show at the top; after a moment on one poster also its length, genres, language, age rating
 * and where it streams in India. OK on a poster offers the app it streams on, its trailer and Stremio. Loads
 * only while open; closes itself (noHistory) when you leave, so its posters are freed. Data: see Trending.
 */
public class TrendingActivity extends Activity {

    private static final float FOCUS_SCALE = 1.08f;
    private static final int MIN_VOTES_FOR_RATING = 10;   // a rating from a handful of votes says little

    // Details (where to watch, genres...) load only once a poster has kept focus this long, so scrolling fast
    // through a row sends no requests.
    private static final long DETAILS_DELAY_MS = 450;
    private static final long CINEMA_WINDOW_MS = 90 * 86_400_000L;   // "in cinemas" for films out in the last 90 days
    // TMDB provider ids (India) -> the app on this TV that plays them.
    private static final Map<Integer, String> APPS = new HashMap<>();
    static {
        APPS.put(8, "com.netflix.ninja");
        APPS.put(119, "com.amazon.firebat");
        APPS.put(9, "com.amazon.firebat");
        APPS.put(2336, "in.startv.hotstar");
        APPS.put(122, "in.startv.hotstar");
        APPS.put(192, News.YOUTUBE_PKG);
    }
    private static final String STREMIO = "com.stremio.one";

    private TextView heroTitle, heroMeta, heroOverview;
    private LinearLayout heroWhere;
    private LinearLayout rows;
    private float density;
    private boolean focusedOnce;
    private String token;
    private Trending.Item current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingDetails;
    private final Map<String, Trending.Details> details = new HashMap<>();
    // Posters in memory while the screen is open: at most 8 MB (about 80 posters), dropped on close.
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    // Posters being downloaded, with every poster view waiting for each: one title can sit in several rows.
    private final Map<String, List<PosterHolder>> waiting = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trending);
        density = getResources().getDisplayMetrics().density;
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroOverview = findViewById(R.id.heroOverview);
        heroWhere = findViewById(R.id.heroWhere);
        rows = findViewById(R.id.rows);
        Trending.prune(this);
        // Posters are also kept on disk (TMDB lets them be cached for a year), so reopening downloads nothing new.
        if (HttpResponseCache.getInstalled() == null) {
            try {
                HttpResponseCache.install(new File(getCacheDir(), "http"), 12L * 1024 * 1024);
            } catch (IOException ignored) {
            }
        }

        token = getString(R.string.tmdb_token).trim();
        if (token.isEmpty()) {
            heroTitle.setText(R.string.trending_no_key);
            return;
        }
        heroTitle.setText(R.string.trending_loading);
        for (Trending.Row row : Trending.rows()) addRow(token, row);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        posters.evictAll();
        super.onDestroy();
        if (!isFinishing()) return;
        // This screen has its own process (see the manifest): end it, so every poster, texture and download
        // buffer goes back to the TV at once instead of whenever Android gets round to it.
        HttpResponseCache cache = HttpResponseCache.getInstalled();
        if (cache != null) cache.flush();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void addRow(String token, Trending.Row row) {
        TextView label = new TextView(this);
        label.setText(row.title);
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(getResources().getFont(R.font.inter));
        label.setFontVariationSettings("'wght' 600");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * density);
        rows.addView(label, lp);

        TextView status = new TextView(this);
        status.setText(R.string.trending_loading);
        status.setTextColor(0x80FFFFFF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTypeface(getResources().getFont(R.font.inter));
        status.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
        rows.addView(status);

        Trending.load(this, token, row, items -> {
            if (isDestroyed()) return;
            if (items == null || items.isEmpty()) {
                status.setText(items == null ? R.string.trending_failed : R.string.trending_empty);
                return;
            }
            int at = rows.indexOfChild(status);
            rows.removeView(status);
            rows.addView(posterRow(items), at);
            if (!focusedOnce) {
                focusedOnce = true;
                rows.post(() -> focusFirst());
            }
        });
    }

    /** Focuses the first poster of the top-most row that has loaded. */
    private void focusFirst() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i) instanceof RecyclerView) {
                RecyclerView list = (RecyclerView) rows.getChildAt(i);
                View first = list.getChildAt(0);
                if (first != null) first.requestFocus();
                return;
            }
        }
    }

    private RecyclerView posterRow(List<Trending.Item> items) {
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setHasFixedSize(true);
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        int pad = (int) (8 * density);
        list.setPadding((int) (4 * density), pad, pad, pad);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(new PosterAdapter(items));
        return list;
    }

    private void showHero(Trending.Item it) {
        current = it;
        heroTitle.setText(it.title);
        heroOverview.setText(it.overview.isEmpty() ? getString(R.string.no_overview) : it.overview);
        Trending.Details d = details.get(key(it));
        showMeta(it, d);
        handler.removeCallbacks(pendingDetails);
        if (d != null) {
            showWhere(it, d);
            return;
        }
        whereText(getString(R.string.where_loading), 0x80FFFFFF);
        pendingDetails = () -> loadDetails(it, null);
        handler.postDelayed(pendingDetails, DETAILS_DELAY_MS);
    }

    private static String key(Trending.Item it) {
        return it.kind + it.id;
    }

    /** Loads a title's details once; then shows them if it is still focused, and hands them to then (if any). */
    private void loadDetails(Trending.Item it, java.util.function.Consumer<Trending.Details> then) {
        Trending.details(this, token, it, d -> {
            if (isDestroyed()) return;
            if (d != null) details.put(key(it), d);
            if (current == it) {
                showMeta(it, d);
                if (d != null) showWhere(it, d);
                else whereText(getString(R.string.where_failed), 0x80FFFFFF);
            }
            if (then != null) then.accept(d);
        });
    }

    /** 2023  ·  Movie  ·  3h 0m  ·  Drama, History  ·  English  ·  UA  ·  ★ 8.1 */
    private void showMeta(Trending.Item it, Trending.Details d) {
        List<String> parts = new ArrayList<>();
        if (!it.year.isEmpty()) parts.add(it.year);
        parts.add(getString(it.kind.equals("movie") ? R.string.kind_movie : R.string.kind_series));
        if (d != null) {
            if (!d.length.isEmpty()) parts.add(d.length);
            if (!d.genres.isEmpty()) parts.add(d.genres);
            if (!d.language.isEmpty()) parts.add(d.language);
            if (!d.certification.isEmpty()) parts.add(d.certification);
        }
        if (it.votes >= MIN_VOTES_FOR_RATING && it.rating > 0) {
            parts.add("★ " + String.format(Locale.US, "%.1f", it.rating));
        }
        heroMeta.setText(String.join("  ·  ", parts));
    }

    /** "Streaming on [logo] Netflix [logo] JioHotstar", or free / rent-or-buy, or not streaming yet. */
    private void showWhere(Trending.Item it, Trending.Details d) {
        heroWhere.removeAllViews();
        List<Trending.Provider> list;
        int label;
        if (!d.stream.isEmpty()) {
            list = d.stream;
            label = R.string.where_stream;
        } else if (!d.free.isEmpty()) {
            list = d.free;
            label = R.string.where_free;
        } else if (!d.rentOrBuy.isEmpty()) {
            list = d.rentOrBuy;
            label = R.string.where_rent;
        } else {
            whereText(getString(recentFilm(it, d) ? R.string.where_cinema : R.string.where_none), 0xB3FFFFFF);
            heroWhere.addView(whereLabel(getString(R.string.press_ok_hint), 0x66FFFFFF, 24));
            return;
        }
        heroWhere.addView(whereLabel(getString(label), 0xB3FFFFFF, 0));
        for (int i = 0; i < list.size() && i < 4; i++) {
            Trending.Provider p = list.get(i);
            if (!p.logo.isEmpty() && !p.logo.equals("null")) heroWhere.addView(logoView(p.logo));
            heroWhere.addView(whereLabel(p.name, 0xFFFFFFFF, 6));
        }
        heroWhere.addView(whereLabel(getString(R.string.press_ok_hint), 0x66FFFFFF, 24));
    }

    private static boolean recentFilm(Trending.Item it, Trending.Details d) {
        if (!it.kind.equals("movie") || d.releaseDate.length() < 10) return false;
        try {
            long released = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.releaseDate).getTime();
            return System.currentTimeMillis() - released < CINEMA_WINDOW_MS;
        } catch (java.text.ParseException e) {
            return false;
        }
    }

    private void whereText(String text, int color) {
        heroWhere.removeAllViews();
        heroWhere.addView(whereLabel(text, color, 0));
    }

    private TextView whereLabel(String text, int color, int startDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(getResources().getFont(R.font.inter));
        t.setFontVariationSettings(color == 0xFFFFFFFF ? "'wght' 600" : "'wght' 500");
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (startDp * density));
        t.setLayoutParams(lp);
        return t;
    }

    private ImageView logoView(String path) {
        ImageView v = new ImageView(this);
        int size = (int) (22 * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart((int) (14 * density));
        v.setLayoutParams(lp);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundResource(R.drawable.poster_bg);
        v.setClipToOutline(true);
        String cacheKey = "logo:" + path;
        Bitmap cached = posters.get(cacheKey);
        if (cached != null) {
            v.setImageBitmap(cached);
        } else {
            Trending.logo(path, bmp -> {
                if (bmp == null || isDestroyed()) return;
                posters.put(cacheKey, bmp);
                v.setImageBitmap(bmp);
            });
        }
        return v;
    }

    // ---- OK on a poster: open the app it streams on, the trailer, or Stremio --------------------------

    private void showActions(Trending.Item it) {
        Trending.Details d = details.get(key(it));
        if (d != null) {
            showActions(it, d);
            return;
        }
        handler.removeCallbacks(pendingDetails);
        loadDetails(it, loaded -> {
            if (current == it) showActions(it, loaded != null ? loaded : new Trending.Details());
        });
    }

    private void showActions(Trending.Item it, Trending.Details d) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        List<Trending.Provider> watchable = new ArrayList<>(d.stream);
        watchable.addAll(d.free);
        for (Trending.Provider p : watchable) {
            String pkg = APPS.get(p.id);
            Intent launch = pkg != null && !opened.contains(pkg) ? launchIntent(pkg) : null;
            if (launch == null) continue;
            opened.add(pkg);
            labels.add(getString(R.string.open_in, p.name));
            actions.add(() -> start(launch));
        }
        if (!d.trailerKey.isEmpty()) {
            labels.add(getString(R.string.watch_trailer));
            actions.add(() -> openTrailer(d.trailerKey));
        }
        if (launchIntent(STREMIO) != null) {
            String type = it.kind.equals("movie") ? "movie" : "series";
            boolean byId = d.imdbId.startsWith("tt");
            Uri uri = byId ? Uri.parse("stremio:///detail/" + type + "/" + d.imdbId)
                    : Uri.parse("stremio:///search?search=" + Uri.encode(it.title));
            labels.add(getString(byId ? R.string.open_stremio : R.string.search_stremio));
            actions.add(() -> start(new Intent(Intent.ACTION_VIEW, uri).setPackage(STREMIO)));
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.where_none, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it.title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private void openTrailer(String key) {
        Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + key);
        try {
            start(new Intent(Intent.ACTION_VIEW, uri).setPackage(News.YOUTUBE_PKG));
        } catch (ActivityNotFoundException e) {
            Intent launch = launchIntent(News.YOUTUBE_PKG);
            if (launch != null) start(launch.setData(uri));
        }
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterHolder> {
        private final List<Trending.Item> items;

        PosterAdapter(List<Trending.Item> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public PosterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
            v.setClipToOutline(true);   // rounded corners from poster_bg
            return new PosterHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PosterHolder h, int position) {
            Trending.Item it = items.get(position);
            h.path = it.poster;
            h.fallback.setText(it.title);
            Bitmap cached = posters.get(it.poster);
            h.image.setImageBitmap(cached);
            if (cached == null) {
                List<PosterHolder> w = waiting.get(it.poster);
                if (w == null) {
                    w = new ArrayList<>();
                    waiting.put(it.poster, w);
                    Trending.poster(it.poster, bmp -> {
                        List<PosterHolder> views = waiting.remove(it.poster);
                        if (bmp == null || isDestroyed()) return;
                        posters.put(it.poster, bmp);
                        // Views get reused while scrolling: only those still showing this title get the picture.
                        for (PosterHolder v : views) if (it.poster.equals(v.path)) v.image.setImageBitmap(bmp);
                    });
                }
                if (!w.contains(h)) w.add(h);
            }
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                v.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f)
                        .translationZ(focused ? 4 * density : 0).setDuration(150).start();
                if (focused) showHero(it);
            });
            h.itemView.setOnClickListener(v -> showActions(it));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView fallback;
        String path;

        PosterHolder(View v) {
            super(v);
            image = v.findViewById(R.id.image);
            fallback = v.findViewById(R.id.fallback);
        }
    }
}
