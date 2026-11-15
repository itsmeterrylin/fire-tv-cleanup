package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * USB (its own tile in the launcher): the films and series on a USB drive as poster rows, Continue watching
 * first. OK plays in Just Player from where you stopped, with the subtitles beside the file; leaving Just Player
 * (Back) hands the position back here, so the next OK resumes. Hold OK for: from the beginning, watched or not,
 * VLC, the file name.
 *
 * Light by design: it reads Android's media index rather than the drive, looks each title up on TMDB once and
 * remembers it, runs in its own process and ends that process when you leave, so nothing stays in memory.
 */
public class UsbActivity extends Activity {

    private static final String JUST_PLAYER = "com.brouken.player";
    private static final String VLC = "org.videolan.vlc";
    private static final int REQ_PLAY = 1;
    private static final float FOCUS_SCALE = 1.08f;
    private static final int MIN_VOTES_FOR_RATING = 10;
    private static final long BACKDROP_DELAY_MS = 300;

    /** One poster: a film, an episode (Continue watching), or a whole series. */
    private static final class Tile {
        UsbLibrary.Video video;                  // what OK plays (a series: the episode to go on with)
        List<UsbLibrary.Video> episodes;         // series only, in order
        boolean nextEpisode;                     // Continue watching: the episode after one you finished
    }

    private TextView heroTitle, heroMeta, heroStatus, heroOverview;
    private android.widget.LinearLayout heroCast;
    private Runnable pendingInfo;
    /** Cast and details already loaded this visit, by TMDB id and episode. */
    private final java.util.Map<String, UsbInfo.Info> infos = new java.util.HashMap<>();
    private ImageView backdrop;
    private LinearLayout rows;
    private float density;
    private String token;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<UsbLibrary.Video> videos = new ArrayList<>();
    private final List<RecyclerView.Adapter<?>> adapters = new ArrayList<>();
    private Tile current;
    private String shownBackdrop;
    private Runnable pendingBackdrop;
    private UsbLibrary.Video playing;
    private boolean awaitingPlayer;
    private String focusPath;   // after a refresh, put focus back on this file's poster
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<String, List<PosterHolder>> waiting = new HashMap<>();

    // A drive plugged in or pulled out while the screen is open: read again.
    private final BroadcastReceiver drives = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(rescan);
            handler.postDelayed(rescan, 1500);   // give the media index a moment to see the files
        }
    };
    private final Runnable rescan = this::scan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb);
        density = getResources().getDisplayMetrics().density;
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroStatus = findViewById(R.id.heroStatus);
        heroOverview = findViewById(R.id.heroOverview);
        heroCast = findViewById(R.id.heroCast);
        backdrop = findViewById(R.id.backdrop);
        rows = findViewById(R.id.rows);
        token = getString(R.string.tmdb_token).trim();
        if (HttpResponseCache.getInstalled() == null) {
            try {
                HttpResponseCache.install(new File(getCacheDir(), "http-usb"), 12L * 1024 * 1024);
            } catch (IOException ignored) {
            }
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addDataScheme("file");
        registerReceiver(drives, f);
        scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        awaitingPlayer = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Left for Home or another app (not for the player): close, so the screen's memory goes back.
        if (!awaitingPlayer && !isChangingConfigurations()) finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(drives);
        } catch (IllegalArgumentException ignored) {
        }
        images.evictAll();
        io.shutdownNow();
        super.onDestroy();
        if (!isFinishing()) return;
        HttpResponseCache cache = HttpResponseCache.getInstalled();
        if (cache != null) cache.flush();
        android.os.Process.killProcess(android.os.Process.myPid());   // own process: see the manifest
    }

    // ---- reading the drive ----------------------------------------------------------------------

    private void scan() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            showMessage(getString(R.string.usb_no_drive), getString(R.string.usb_no_permission));
            return;
        }
        if (videos.isEmpty()) showMessage(getString(R.string.usb_loading), "");
        io.execute(() -> {
            boolean anyDrive = !UsbLibrary.drives(this).isEmpty();
            List<UsbLibrary.Video> found = UsbLibrary.scan(this);
            for (UsbLibrary.Video v : found) v.match = UsbLibrary.cachedMatch(this, v);
            handler.post(() -> {
                if (isDestroyed()) return;
                videos = found;
                if (!anyDrive) showMessage(getString(R.string.usb_no_drive), getString(R.string.usb_no_drive_hint));
                else if (found.isEmpty()) showMessage(getString(R.string.usb_no_videos), getString(R.string.usb_no_videos_hint));
                else build();
                lookUpMissing();
            });
        });
    }

    /** TMDB lookups for titles seen for the first time, one at a time; posters fill in as answers arrive. */
    private void lookUpMissing() {
        if (token.isEmpty()) return;
        Map<String, List<UsbLibrary.Video>> todo = new LinkedHashMap<>();
        for (UsbLibrary.Video v : videos) {
            if (v.match != null) continue;
            String key = (v.isEpisode() ? "tv:" : "movie:") + v.showKey() + ":" + v.year;
            List<UsbLibrary.Video> same = todo.get(key);
            if (same == null) todo.put(key, same = new ArrayList<>());
            same.add(v);
        }
        for (List<UsbLibrary.Video> same : todo.values()) {
            io.execute(() -> {
                Trending.Item it = UsbLibrary.lookUp(this, token, same.get(0));
                handler.post(() -> {
                    if (isDestroyed()) return;
                    for (UsbLibrary.Video v : same) v.match = it;
                    for (RecyclerView.Adapter<?> a : adapters) a.notifyDataSetChanged();
                    if (current != null && same.contains(current.video)) showHero(current);
                });
            });
        }
    }

    private void showMessage(String title, String text) {
        rows.removeAllViews();
        adapters.clear();
        current = null;
        heroTitle.setText(title);
        heroMeta.setText("");
        heroStatus.setText("");
        heroOverview.setText(text);
        backdrop.animate().alpha(0).setDuration(200).start();
        shownBackdrop = null;
    }

    // ---- rows -----------------------------------------------------------------------------------

    private void build() {
        rows.removeAllViews();
        adapters.clear();
        List<Tile> movies = new ArrayList<>(), series = new ArrayList<>(), going = new ArrayList<>();
        Map<String, List<UsbLibrary.Video>> shows = new LinkedHashMap<>();
        List<UsbLibrary.Video> byNewest = new ArrayList<>(videos);
        byNewest.sort((a, b) -> Long.compare(b.addedSec, a.addedSec));
        for (UsbLibrary.Video v : byNewest) {
            if (v.isEpisode()) {
                List<UsbLibrary.Video> eps = shows.get(v.showKey());
                if (eps == null) shows.put(v.showKey(), eps = new ArrayList<>());
                eps.add(v);
            } else {
                Tile t = new Tile();
                t.video = v;
                movies.add(t);
                if (UsbLibrary.position(this, v) > 0) going.add(t);
            }
        }
        for (List<UsbLibrary.Video> eps : shows.values()) {
            eps.sort((a, b) -> a.season != b.season ? Integer.compare(a.season, b.season) : Integer.compare(a.episode, b.episode));
            Tile t = new Tile();
            t.episodes = eps;
            t.video = upNext(eps);
            series.add(t);
            // The episode you're in the middle of, else the one after the last you finished.
            UsbLibrary.Video last = null;
            for (UsbLibrary.Video e : eps) {
                if (UsbLibrary.watchedAt(this, e) > 0 && (last == null || UsbLibrary.watchedAt(this, e) > UsbLibrary.watchedAt(this, last))) last = e;
            }
            if (last != null) {
                Tile c = new Tile();
                if (UsbLibrary.position(this, last) > 0) {
                    c.video = last;
                    going.add(c);
                } else if (UsbLibrary.finished(this, last) && eps.indexOf(last) + 1 < eps.size()) {
                    c.video = eps.get(eps.indexOf(last) + 1);
                    c.nextEpisode = true;
                    going.add(c);
                }
            }
        }
        going.sort((a, b) -> Long.compare(lastTouched(b), lastTouched(a)));
        if (!going.isEmpty()) addRow(getString(R.string.usb_continue), going);
        if (!movies.isEmpty()) addRow(getString(R.string.usb_movies), movies);
        if (!series.isEmpty()) addRow(getString(R.string.usb_series), series);
        rows.post(this::restoreFocus);
    }

    private long lastTouched(Tile t) {
        if (!t.nextEpisode) return UsbLibrary.watchedAt(this, t.video);
        long latest = 0;
        for (UsbLibrary.Video v : videos) {
            if (v.isEpisode() && v.showKey().equals(t.video.showKey())) latest = Math.max(latest, UsbLibrary.watchedAt(this, v));
        }
        return latest;
    }

    /** The episode a series poster plays: the one in progress, else the first not yet watched, else the first. */
    private UsbLibrary.Video upNext(List<UsbLibrary.Video> eps) {
        for (UsbLibrary.Video e : eps) if (UsbLibrary.position(this, e) > 0) return e;
        for (UsbLibrary.Video e : eps) if (!UsbLibrary.finished(this, e)) return e;
        return eps.get(0);
    }

    private void addRow(String title, List<Tile> tiles) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(getResources().getFont(R.font.inter));
        label.setFontVariationSettings("'wght' 600");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * density);
        rows.addView(label, lp);

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        int pad = (int) (8 * density);
        list.setPadding((int) (4 * density), pad, pad, pad);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        TileAdapter adapter = new TileAdapter(tiles);
        adapters.add(adapter);
        list.setAdapter(adapter);
        rows.addView(list);
    }

    private void restoreFocus() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (!(rows.getChildAt(i) instanceof RecyclerView)) continue;
            RecyclerView list = (RecyclerView) rows.getChildAt(i);
            TileAdapter a = (TileAdapter) list.getAdapter();
            if (focusPath != null && a != null) {
                for (int p = 0; p < a.tiles.size(); p++) {
                    if (!a.tiles.get(p).video.path.equals(focusPath)) continue;
                    final int at = p;
                    list.scrollToPosition(at);
                    list.post(() -> {
                        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(at);
                        if (h != null) h.itemView.requestFocus();
                    });
                    focusPath = null;
                    return;
                }
                continue;
            }
            View first = list.getChildAt(0);
            if (first != null) first.requestFocus();
            return;
        }
        // focusPath not found (file gone): fall back to the first poster
        if (focusPath != null) {
            focusPath = null;
            restoreFocus();
        }
    }

    // ---- the focused title -----------------------------------------------------------------------

    private void showHero(Tile t) {
        current = t;
        UsbLibrary.Video v = t.video;
        Trending.Item m = v.match != null && v.match.title != null && !v.match.title.isEmpty() ? v.match : null;
        String title = m != null ? m.title : v.title;
        if (t.episodes == null && v.isEpisode()) {
            title += "  ·  " + getString(R.string.usb_episode, v.season, v.episode)
                    + (v.episodeName.isEmpty() ? "" : " " + v.episodeName);
        }
        heroTitle.setText(title);

        List<String> parts = new ArrayList<>();
        String year = m != null && !m.year.isEmpty() ? m.year : v.year;
        if (!year.isEmpty()) parts.add(year);
        if (t.episodes != null) {
            parts.add(getString(R.string.usb_episodes, t.episodes.size()));
        } else {
            parts.add(getString(v.isEpisode() ? R.string.kind_series : R.string.kind_movie));
            String len = UsbLibrary.length(length(v));
            if (!len.isEmpty()) parts.add(len);
        }
        String q = UsbLibrary.quality(v);
        if (!q.isEmpty()) parts.add(q);
        if (t.episodes == null) parts.add(UsbLibrary.size(v.size));
        if (m != null && m.votes >= MIN_VOTES_FOR_RATING && m.rating > 0) {
            parts.add("★ " + String.format(Locale.US, "%.1f", m.rating));
        }
        heroMeta.setText(String.join("  ·  ", parts));
        heroStatus.setText(status(t));
        heroOverview.setText(m != null && !m.overview.isEmpty() ? m.overview
                : getString(R.string.usb_file_info, v.file) + "\n" + (v.match != null ? getString(R.string.usb_not_on_tmdb) : ""));

        // Cast and the episode's own story: after the focus settles, like the backdrop
        handler.removeCallbacks(pendingInfo);
        heroCast.removeAllViews();
        if (m != null) {
            final boolean single = t.episodes == null && v.isEpisode();
            final String key = m.id + ":" + (single ? v.season + "x" + v.episode : "");
            UsbInfo.Info known = infos.get(key);
            if (known != null) {
                showInfo(t, known, single);
            } else {
                pendingInfo = () -> UsbInfo.load(this, token, m, single ? v.season : -1, single ? v.episode : -1, info -> {
                    if (info == null || isDestroyed()) return;
                    infos.put(key, info);
                    if (current == t) showInfo(t, info, single);
                });
                handler.postDelayed(pendingInfo, BACKDROP_DELAY_MS + 100);
            }
        }

        handler.removeCallbacks(pendingBackdrop);
        String path = m != null ? m.backdrop : "";
        if (path == null || path.isEmpty()) {
            backdrop.animate().alpha(0).setDuration(200).start();
            shownBackdrop = null;
            return;
        }
        if (path.equals(shownBackdrop)) return;
        pendingBackdrop = () -> loadBackdrop(path);
        handler.postDelayed(pendingBackdrop, BACKDROP_DELAY_MS);
    }

    /** The focused title's cast (up to 4 faces and names) and, for an episode, its own story. */
    private void showInfo(Tile t, UsbInfo.Info info, boolean single) {
        if (single && !info.episodeStory.isEmpty()) heroOverview.setText(info.episodeStory);
        heroCast.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int face = Math.round(30 * d);
        for (int i = 0; i < Math.min(4, info.cast.size()); i++) {
            UsbInfo.Person p = info.cast.get(i);
            ImageView photo = new ImageView(this);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.setBackgroundColor(0x33FFFFFF);
            photo.setOutlineProvider(ROUND);
            photo.setClipToOutline(true);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(face, face);
            if (i > 0) lp.setMarginStart(Math.round(18 * d));
            heroCast.addView(photo, lp);
            TextView name = new TextView(this);
            name.setText(p.name);
            name.setTextColor(0xE6FFFFFF);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setTypeface(heroOverview.getTypeface());
            android.widget.LinearLayout.LayoutParams np = new android.widget.LinearLayout.LayoutParams(-2, -2);
            np.setMarginStart(Math.round(8 * d));
            heroCast.addView(name, np);
            loadFace(p.photo, photo);
        }
    }

    private void loadFace(String path, ImageView into) {
        if (path == null || path.isEmpty()) return;
        Bitmap cached = images.get("face:" + path);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        Trending.face(path, bmp -> {
            if (bmp == null || isDestroyed()) return;
            images.put("face:" + path, bmp);
            into.setImageBitmap(bmp);
        });
    }

    static final android.view.ViewOutlineProvider ROUND = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            outline.setOval(0, 0, view.getWidth(), view.getHeight());
        }
    };

    private long length(UsbLibrary.Video v) {
        return v.durationMs > 0 ? v.durationMs : UsbLibrary.savedLength(this, v);
    }

    private String status(Tile t) {
        UsbLibrary.Video v = t.video;
        String prefix = "";
        if (t.episodes != null || t.nextEpisode) {
            prefix = (t.nextEpisode ? getString(R.string.usb_next_episode) + ": " : "")
                    + getString(R.string.usb_episode, v.season, v.episode)
                    + (v.episodeName.isEmpty() ? "" : " " + v.episodeName) + "  ·  ";
        }
        long pos = UsbLibrary.position(this, v);
        long len = length(v);
        if (pos > 0) {
            int pct = len > 0 ? (int) Math.min(99, pos * 100 / len) : 0;
            return prefix + getString(R.string.usb_progress, UsbLibrary.clock(pos), pct);
        }
        if (t.episodes == null && UsbLibrary.finished(this, v)) return prefix + getString(R.string.usb_watched);
        return prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 5);
    }

    private void loadBackdrop(String path) {
        Bitmap cached = images.get("bd:" + path);
        if (cached != null) {
            setBackdrop(path, cached);
            return;
        }
        Trending.backdrop(path, bmp -> {
            if (bmp == null || isDestroyed()) return;
            images.put("bd:" + path, bmp);
            Trending.Item m = current != null ? current.video.match : null;
            if (m != null && path.equals(m.backdrop)) setBackdrop(path, bmp);
        });
    }

    private void setBackdrop(String path, Bitmap bmp) {
        shownBackdrop = path;
        backdrop.setAlpha(0f);
        backdrop.setImageBitmap(bmp);
        backdrop.animate().alpha(0.85f).setDuration(350).start();
    }

    // ---- OK and hold OK --------------------------------------------------------------------------

    private void onOk(Tile t) {
        if (t.episodes != null) {
            chooseEpisode(t);
            return;
        }
        long pos = UsbLibrary.position(this, t.video);
        if (pos <= 0) {
            play(t.video, 0);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.usb_resume_from, UsbLibrary.clock(pos)));
        actions.add(() -> play(t.video, pos));
        labels.add(getString(R.string.usb_play_start));
        actions.add(() -> play(t.video, 0));
        menu(heroTitle.getText().toString(), labels, actions);
    }

    private void chooseEpisode(Tile t) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int focus = 0;
        for (UsbLibrary.Video e : t.episodes) {
            String label = getString(R.string.usb_episode, e.season, e.episode) + (e.episodeName.isEmpty() ? "" : "  " + e.episodeName);
            long pos = UsbLibrary.position(this, e), len = length(e);
            if (pos > 0) label += "   ·  " + (len > 0 ? Math.min(99, pos * 100 / len) + "%" : UsbLibrary.clock(pos));
            else if (UsbLibrary.finished(this, e)) label += "   ✓";
            if (e == t.video) focus = labels.size();
            labels.add(label);
            Tile one = new Tile();
            one.video = e;
            actions.add(() -> onOk(one));
        }
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(heroTitle.getText())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
        final int at = focus;
        d.getListView().post(() -> d.getListView().setSelection(at));
    }

    private void moreOptions(Tile t) {
        UsbLibrary.Video v = t.video;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (t.episodes != null) {
            labels.add(getString(R.string.usb_episodes, t.episodes.size()));
            actions.add(() -> chooseEpisode(t));
        }
        labels.add(getString(R.string.usb_play_start));
        actions.add(() -> play(v, 0));
        boolean done = UsbLibrary.finished(this, v) && UsbLibrary.position(this, v) == 0;
        labels.add(getString(done ? R.string.usb_mark_unwatched : R.string.usb_mark_watched));
        actions.add(() -> {
            if (done) UsbLibrary.clearProgress(this, v);
            else UsbLibrary.markWatched(this, v);
            focusPath = v.path;
            build();
        });
        if (installed(JUST_PLAYER)) {
            labels.add(getString(R.string.usb_open_just_player));
            actions.add(() -> playIn(JUST_PLAYER, v, UsbLibrary.position(this, v)));
        }
        if (installed(VLC)) {
            labels.add(getString(R.string.usb_open_vlc));
            actions.add(() -> playIn(VLC, v, UsbLibrary.position(this, v)));
        }
        labels.add(getString(R.string.usb_file_info, v.file));
        actions.add(() -> Toast.makeText(this, v.path, Toast.LENGTH_LONG).show());
        menu(heroTitle.getText().toString(), labels, actions);
    }

    private void menu(String title, List<String> labels, List<Runnable> actions) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    // ---- playing ---------------------------------------------------------------------------------

    /** The USB screen's own player (original-language audio, English subtitles, boost); see UsbPlayerActivity. */
    private void play(UsbLibrary.Video v, long fromMs) {
        Intent i = new Intent(this, UsbPlayerActivity.class)
                .putExtra(UsbPlayerActivity.EXTRA_PATH, v.path)
                .putExtra(UsbPlayerActivity.EXTRA_TITLE, heroTitle.getText().toString())
                .putExtra(UsbPlayerActivity.EXTRA_POSITION, fromMs)
                .putExtra(UsbPlayerActivity.EXTRA_LANG, v.match != null ? v.match.lang : "");
        Trending.Item m = v.match;
        if (m != null && m.id > 0) {   // for the pause screen: story, cast and details
            i.putExtra(UsbPlayerActivity.EXTRA_TMDB_ID, m.id)
                    .putExtra(UsbPlayerActivity.EXTRA_TMDB_KIND, m.kind)
                    .putExtra(UsbPlayerActivity.EXTRA_YEAR, m.year)
                    .putExtra(UsbPlayerActivity.EXTRA_STORY, m.overview)
                    .putExtra(UsbPlayerActivity.EXTRA_RATING, m.votes >= MIN_VOTES_FOR_RATING ? m.rating : 0)
                    .putExtra(UsbPlayerActivity.EXTRA_SEASON, v.isEpisode() ? v.season : -1)
                    .putExtra(UsbPlayerActivity.EXTRA_EPISODE, v.isEpisode() ? v.episode : -1);
        }
        playing = v;
        focusPath = v.path;
        awaitingPlayer = true;
        startActivityForResult(i, REQ_PLAY);
    }

    /** Just Player (else VLC), from the menu or when the USB player can't play a file. */
    private void playElsewhere(UsbLibrary.Video v, long fromMs) {
        if (installed(JUST_PLAYER)) playIn(JUST_PLAYER, v, fromMs);
        else if (installed(VLC)) playIn(VLC, v, fromMs);
        else Toast.makeText(this, R.string.usb_no_player, Toast.LENGTH_SHORT).show();
    }

    /**
     * Just Player's (MX Player-compatible) API: start at "position", show "title", add the subtitles in "subs"
     * (first one on), and with "return_result" hand back where you stopped. VLC takes the position and title and
     * also answers with the position. Files only this launcher can open get a read grant for the player.
     */
    private void playIn(String pkg, UsbLibrary.Video v, long fromMs) {
        Uri uri = v.uri();
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").setPackage(pkg);
        String title = heroTitle.getText().toString();
        if (UsbFilesProvider.AUTHORITY.equals(uri.getAuthority())) grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (pkg.equals(JUST_PLAYER)) {
            i.putExtra("position", (int) fromMs);
            i.putExtra("return_result", true);
            i.putExtra("title", title);
            List<File> subs = UsbLibrary.subtitles(v);
            if (!subs.isEmpty()) {
                Parcelable[] uris = new Parcelable[subs.size()];
                String[] names = new String[subs.size()];
                for (int s = 0; s < subs.size(); s++) {
                    Uri su = UsbFilesProvider.uriFor(subs.get(s).getAbsolutePath(), UsbLibrary.subtitleLanguage(subs.get(s)));
                    grantUriPermission(pkg, su, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    uris[s] = su;
                    names[s] = subs.get(s).getName();
                }
                i.putExtra("subs", uris);
                i.putExtra("subs.name", names);
                i.putExtra("subs.enable", new Parcelable[]{uris[0]});
            }
        } else {
            i.putExtra("position", fromMs);
            i.putExtra("title", title);
            i.putExtra("from_start", fromMs == 0);
        }
        playing = v;
        focusPath = v.path;
        awaitingPlayer = true;
        try {
            startActivityForResult(i, REQ_PLAY);
        } catch (ActivityNotFoundException | SecurityException e) {
            awaitingPlayer = false;
            playing = null;
            Toast.makeText(this, R.string.usb_no_player, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PLAY || playing == null) return;
        UsbLibrary.Video v = playing;
        playing = null;
        if (data != null && data.getBooleanExtra(UsbPlayerActivity.EXTRA_FALLBACK, false)) {
            playElsewhere(v, data.getIntExtra("position", 0));
            return;
        }
        if (data != null) {
            boolean completed = "playback_completion".equals(data.getStringExtra("end_by"));
            long pos, len;
            if (data.hasExtra("extra_position")) {   // VLC
                pos = data.getLongExtra("extra_position", 0);
                len = data.getLongExtra("extra_duration", 0);
            } else {
                pos = data.getIntExtra("position", 0);
                len = data.getIntExtra("duration", 0);
            }
            UsbLibrary.savePosition(this, v, pos, len, completed);
        } else {
            UsbLibrary.savePosition(this, v, 0, 0, false);   // still counts as "watched recently"
        }
        if (!videos.isEmpty()) build();
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---- posters ---------------------------------------------------------------------------------

    private final class TileAdapter extends RecyclerView.Adapter<PosterHolder> {
        final List<Tile> tiles;

        TileAdapter(List<Tile> tiles) {
            this.tiles = tiles;
        }

        @NonNull
        @Override
        public PosterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_usb_poster, parent, false);
            v.setClipToOutline(true);
            return new PosterHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PosterHolder h, int position) {
            Tile t = tiles.get(position);
            UsbLibrary.Video v = t.video;
            Trending.Item m = v.match;
            String poster = m != null && m.poster != null && !m.poster.isEmpty() && !m.poster.equals("null") ? m.poster : null;
            h.path = poster;
            String name = m != null && m.title != null && !m.title.isEmpty() ? m.title : v.title;
            if (t.episodes == null && v.isEpisode()) name += "\n" + getString(R.string.usb_episode, v.season, v.episode);
            h.fallback.setText(name);
            h.image.setImageBitmap(poster != null ? images.get(poster) : null);
            if (poster != null && images.get(poster) == null) {
                List<PosterHolder> w = waiting.get(poster);
                if (w == null) {
                    waiting.put(poster, w = new ArrayList<>());
                    Trending.poster(poster, bmp -> {
                        List<PosterHolder> views = waiting.remove(poster);
                        if (bmp == null || isDestroyed() || views == null) return;
                        images.put(poster, bmp);
                        for (PosterHolder p : views) if (poster.equals(p.path)) p.image.setImageBitmap(bmp);
                    });
                }
                if (!w.contains(h)) w.add(h);
            }

            // Badge: episode count for a series, the episode for Continue watching, "Watched" for a finished film.
            String badge = null;
            if (t.episodes != null) badge = getString(R.string.usb_episodes, t.episodes.size());
            else if (v.isEpisode()) badge = (t.nextEpisode ? "▶ " : "") + getString(R.string.usb_episode, v.season, v.episode);
            else if (UsbLibrary.finished(UsbActivity.this, v) && UsbLibrary.position(UsbActivity.this, v) == 0) badge = "✓ " + getString(R.string.usb_watched);
            h.badge.setVisibility(badge != null ? View.VISIBLE : View.GONE);
            if (badge != null) h.badge.setText(badge);

            long pos = UsbLibrary.position(UsbActivity.this, v), len = length(v);
            boolean bar = t.episodes == null && pos > 0 && len > 0;
            h.progress.setVisibility(bar ? View.VISIBLE : View.GONE);
            if (bar) h.progress.setProgress((int) Math.min(1000, pos * 1000 / len));

            h.itemView.setOnFocusChangeListener((view, focused) -> {
                view.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f)
                        .translationZ(focused ? 4 * density : 0).setDuration(150).start();
                if (focused) showHero(t);
            });
            h.itemView.setOnClickListener(view -> onOk(t));
            h.itemView.setOnLongClickListener(view -> {
                moreOptions(t);
                return true;
            });
            h.itemView.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
                    moreOptions(t);
                    return true;
                }
                return false;
            });
            if (current != null && current.video == v && h.itemView.hasFocus()) showHero(t);
        }

        @Override
        public int getItemCount() {
            return tiles.size();
        }
    }

    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView fallback, badge;
        final ProgressBar progress;
        String path;

        PosterHolder(View v) {
            super(v);
            image = v.findViewById(R.id.image);
            fallback = v.findViewById(R.id.fallback);
            badge = v.findViewById(R.id.badge);
            progress = v.findViewById(R.id.progress);
        }
    }
}
