package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link #dockCount()} positions are the pinned dock, in the order the user set; the rest
 * is alphabetical. The pinned list is saved on the device and starts from res/values/config.xml (dock_apps).
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Hold OK or press Menu on a tile: pin, unpin, reorder, app info. */
        void onTileMenu(int position, View card);
    }

    // Optional square icons, drawn locally by tools/make_dock_icons.py and kept out of git (they are trademarked
    // logos). Looked up by name so the app builds without them; missing ones fall back to the app's own icon.
    private static final Map<String, String> DOCK_ICONS = new HashMap<>();
    static {
        DOCK_ICONS.put("com.amazon.firetv.youtube", "dock_youtube");
        DOCK_ICONS.put("com.netflix.ninja", "dock_netflix");
        DOCK_ICONS.put("com.amazon.firebat", "dock_prime");
        DOCK_ICONS.put("com.apple.airplay.amazon.airplay", "dock_airplay");
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        /** Square dock art, or null: then the app's own icon sits on a graphite tile. */
        final Drawable square;
        final Drawable icon;
        /** How the icon fills a dock tile (ICON_*), worked out on first use; edge is the colour behind ICON_FIT_EDGE. */
        int iconFit;
        int edge;

        Entry(String label, ComponentName component, Drawable square, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.square = square;
            this.icon = icon;
        }
    }

    // App tiles: a square opaque icon fills the tile; a wide opaque one sits whole on its own edge colour; one with
    // transparent corners (SmartTube) is shown as drawn, with no tile behind it; one without a size gets graphite.
    private static final int ICON_FILL = 1, ICON_FIT_EDGE = 2, ICON_FIT_GRAPHITE = 3, ICON_AS_IS = 4;

    static final float CORNER_RATIO = 0.225f;

    private final Context ctx;
    private final TileListener listener;
    private static final String PREFS = "tvhome";
    private static final String PREF_DOCK = "dock";
    private final int maxDock;
    private int dockCount;
    /** Pinned packages in dock order, including any that are not installed right now. */
    private final List<String> pinned = new ArrayList<>();
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int maxDock) {
        this.ctx = ctx;
        this.listener = listener;
        this.maxDock = maxDock;
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    /**
     * Reads the dock order on every reload, not just at start, so a backup restored over adb (by rewriting the
     * prefs file with run-as) takes effect when the launcher comes back. Force-stopping the app to make it re-read
     * would also switch off its Home redirect (Android drops a force-stopped app's accessibility services).
     */
    @SuppressWarnings("deprecation")   // MODE_MULTI_PROCESS: re-reads the file when it changed on disk
    private void loadPinned() {
        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).getString(PREF_DOCK, null);
        pinned.clear();
        pinned.addAll(saved != null
                ? (saved.isEmpty() ? Collections.emptyList() : Arrays.asList(saved.split(",")))
                : Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
    }

    void reload() {
        loadPinned();
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            // One tile per app: skip extra launcher entries (AirPlay registers three).
            Intent main = pm.getLeanbackLaunchIntentForPackage(pkg);
            ComponentName mainComponent = main != null ? main.getComponent() : null;
            if (mainComponent != null && !mainComponent.getClassName().equals(ri.activityInfo.name)) continue;
            if (!seen.add(pkg)) continue;
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            String squareName = DOCK_ICONS.get(pkg);
            int square = squareName == null ? 0
                    : ctx.getResources().getIdentifier(squareName, "drawable", ctx.getPackageName());
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name),
                    square != 0 ? ctx.getDrawable(square) : null, icon));
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = pinned.indexOf(a.pkg), fb = pinned.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        int docked = 0;
        while (docked < items.size() && pinned.contains(items.get(docked).pkg)) docked++;
        dockCount = Math.min(docked, maxDock);
        notifyDataSetChanged();
    }

    // ---- dock editing ----------------------------------------------------------------------------

    int dockCount() {
        return dockCount;
    }

    boolean isDocked(int position) {
        return position >= 0 && position < dockCount;
    }

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : "";
    }

    int positionOf(String pkg) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    /** Adds the app to the end of the dock. False when the dock is full. */
    boolean pin(String pkg) {
        if (pinned.contains(pkg)) return true;
        if (dockCount >= maxDock) return false;
        pinned.removeIf(p -> positionOf(p) < 0);   // forget uninstalled pins so they don't hold slots
        pinned.add(pkg);
        saveAndReload();
        return true;
    }

    void unpin(String pkg) {
        pinned.remove(pkg);
        saveAndReload();
    }

    /** Swaps the app with its dock neighbour on the left (-1) or right (+1). */
    void move(String pkg, int delta) {
        int from = pinned.indexOf(pkg), to = from + delta;
        if (from < 0 || to < 0 || to >= pinned.size()) return;
        Collections.swap(pinned, from, to);
        saveAndReload();
    }

    private void saveAndReload() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_DOCK, String.join(",", pinned)).apply();
        reload();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener, this);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(View.INVISIBLE);   // every row uses the tooltip above the magnified icon instead
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        h.card.setTranslationY(0f);
        h.shadow.setVisibility(View.VISIBLE);
        h.shadow.setScaleX(1f);
        h.shadow.setScaleY(1f);
        h.shadow.setTranslationY(0f);
        h.label.setVisibility(View.GONE);
        // The app's own icon fills the rounded tile, like an icon on a phone, unless there is square art.
        Drawable full = e.square != null ? e.square : e.icon;
        boolean art = full != null;
        int fit = e.square == null && art ? iconFit(e) : ICON_FILL;
        int padDp = fit == ICON_FIT_EDGE ? 4 : fit == ICON_FIT_GRAPHITE ? 8 : 0;
        int pad = (int) (ctx.getResources().getDisplayMetrics().density * padDp);
        h.banner.setScaleType(fit == ICON_FILL ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        h.banner.setPadding(pad, pad, pad, pad);
        if (!art || fit == ICON_FIT_GRAPHITE) h.card.setBackground(ctx.getDrawable(R.drawable.dock_tile_bg));
        else if (fit == ICON_FIT_EDGE) h.card.setBackground(new ColorDrawable(e.edge));
        else h.card.setBackground(null);
        // A rounded clip would cut the corners off an icon that has its own shape.
        h.card.setClipToOutline(fit != ICON_AS_IS);
        h.banner.setImageDrawable(full);
        h.banner.setVisibility(art ? View.VISIBLE : View.GONE);
        h.icon.setImageDrawable(art ? null : e.icon);
        h.icon.setVisibility(art ? View.GONE : View.VISIBLE);
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onTileMenu(pos, v);
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** Classifies the icon once: square with opaque corners fills the tile, otherwise it is shown whole. */
    private static int iconFit(Entry e) {
        if (e.iconFit != 0) return e.iconFit;
        Drawable live = e.icon;
        int w = live == null ? 0 : live.getIntrinsicWidth(), h = live == null ? 0 : live.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return e.iconFit = ICON_FIT_GRAPHITE;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 32;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        boolean square = Math.abs((float) w / h - 1f) < 0.06f;
        boolean opaqueCorners = Color.alpha(bmp.getPixel(1, 1)) > 200 && Color.alpha(bmp.getPixel(n - 2, 1)) > 200
                && Color.alpha(bmp.getPixel(1, n - 2)) > 200 && Color.alpha(bmp.getPixel(n - 2, n - 2)) > 200;
        int edge = bmp.getPixel(1, n / 2);
        bmp.recycle();
        if (!opaqueCorners) return e.iconFit = ICON_AS_IS;
        if (square) return e.iconFit = ICON_FILL;
        if (Color.alpha(edge) > 200) {
            e.edge = edge | 0xFF000000;
            return e.iconFit = ICON_FIT_EDGE;
        }
        return e.iconFit = ICON_FIT_GRAPHITE;
    }


    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final View shadow;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener, AppAdapter owner) {
            super(v);
            card = v.findViewById(R.id.card);
            shadow = v.findViewById(R.id.shadow);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // Squircle-ish corners: 22.5% of the short side, matching the dock icon artwork.
                    float radius = Math.min(view.getWidth(), view.getHeight()) * CORNER_RATIO;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (listener == null || pos == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    listener.onTileMenu(pos, view);
                    return true;
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                return listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                // Icons are magnified by row in MainActivity, and named by its tooltip.
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
