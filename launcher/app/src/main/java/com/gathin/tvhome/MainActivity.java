package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private View dockDot;
    // macOS dock magnification: focused icon and its neighbours grow from their bottom edge and lift.
    private static final float DOCK_MAGNIFY = 1.30f;
    private static final float DOCK_NEIGHBOUR = 1.10f;
    private static final float DOCK_LIFT_DP = 10f;
    private static final float DOCK_NEIGHBOUR_LIFT_DP = 4f;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private ImageView bgPhoto;
    private ImageView bgBand;
    private TextView wallCaption;
    private String bgKey;   // which picture bgPhoto shows: a file path or "res:<id>"; null when let go in onStop
    private DockDecoration dockDecoration;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private float density;
    private boolean resumed;   // reopen Live News after a background look-up only while the home screen is up

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int COLOR_WARN = 0xFFFFB340;   // connected, no internet
    private static final int COLOR_ERROR = 0xFFFF5A52;  // no network
    private static final int[] WIFI_ICONS = {R.drawable.ic_wifi_1, R.drawable.ic_wifi_2, R.drawable.ic_wifi};
    // Refresh the Wi-Fi pill as soon as the connection changes, not on the next minute tick.
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onLost(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
        @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties p) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateBackground();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        dockDot = findViewById(R.id.dockDot);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        bgPhoto = findViewById(R.id.bgPhoto);
        bgBand = findViewById(R.id.bgBand);
        wallCaption = findViewById(R.id.wallCaption);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        // One span unit per cell of any dock size 1..COLUMNS: the dock row splits evenly, grid rows are COLUMNS wide.
        final int spans = lcmUpTo(COLUMNS);
        GridLayoutManager glm = new GridLayoutManager(this, spans);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isDocked(position) ? spans / adapter.dockCount() : spans / COLUMNS;
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);

        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        dockDecoration = new DockDecoration(adapter, COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((125 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow + (int) (8 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showHomeScreenMenu(v);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_MENU && v.getId() == R.id.btnSettings) {
                showHomeScreenMenu(v);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                hideHero();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        dockDot.setAlpha(0f);
        ambient.setAlpha(0f);
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateBackground();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(minuteTick);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Another app (or the screensaver) is in front: let go of the photo and its band, about 8.5 MB, so that
        // app gets the memory. onResume loads them again before the home screen is drawn.
        bgPhoto.animate().cancel();
        bgBand.animate().cancel();
        bgPhoto.setImageDrawable(null);
        bgBand.setImageDrawable(null);
        if (frostFade != null) frostFade.cancel();
        dockDecoration.setFrost(null);
        bgPhoto.setAlpha(1f);
        bgBand.setAlpha(1f);
        bgKey = null;
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        if (adapter.isDocked(position)) {
            onDockFocused(position, card);
            return;
        }
        magnifyDock(-1);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        moveGlass(card, r.exactCenterX(), r.exactCenterY(), AppAdapter.FOCUS_SCALE);
        View item = (View) card.getParent();
        hideHero();
        // Scroll just enough so the whole glass (not only the tile) is visible.
        Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
        grid.requestChildRectangleOnScreen(item, want, false);
    }

    /** Sizes the glass to the card at the given scale and springs it to centre on (cx, cy), root coordinates. */
    private void moveGlass(View card, float cx, float cy, float scale) {
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = cx - w / 2f;
        float y = cy - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(Math.min(card.getWidth(), card.getHeight()) * AppAdapter.CORNER_RATIO * scale + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void onDockFocused(int position, View card) {
        View item = (View) card.getParent();
        int decoratedTop = item.getTop() - (int) (6 * density);
        int dy = decoratedTop - grid.getPaddingTop();
        magnifyDock(position);
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        // The same glass as the grid, around the magnified icon (scaled from its bottom centre, then lifted).
        // Placed before the scroll starts: the grid's scroll listener carries it along by dy.
        float magnifiedH = card.getHeight() * DOCK_MAGNIFY;
        moveGlass(card, r.exactCenterX(), r.bottom - DOCK_LIFT_DP * density - magnifiedH / 2f, DOCK_MAGNIFY);
        if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        r.offset(0, -dy);
        showHero(position, r);
    }

    /** Scale dock icons by distance from the focused one; focused < 0 resets them all. */
    private void magnifyDock(int focused) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm == null) return;
        for (int i = 0; i < adapter.dockCount(); i++) {
            View item = lm.findViewByPosition(i);
            View card = item != null ? item.findViewById(R.id.card) : null;
            if (card == null) continue;
            int d = focused < 0 ? Integer.MAX_VALUE : Math.abs(i - focused);
            float s = d == 0 ? DOCK_MAGNIFY : d == 1 ? DOCK_NEIGHBOUR : 1f;
            float lift = d == 0 ? DOCK_LIFT_DP : d == 1 ? DOCK_NEIGHBOUR_LIFT_DP : 0f;
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight());
            card.animate().scaleX(s).scaleY(s).translationY(-lift * density)
                    .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            // The shadow grows from the same point (the icon's bottom centre) so it stays under the icon.
            View shadow = item.findViewById(R.id.shadow);
            shadow.setPivotX(shadow.getWidth() / 2f);
            shadow.setPivotY(card.getBottom() - shadow.getTop());
            shadow.animate().scaleX(s).scaleY(s).translationY(-lift * density)
                    .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // Name tooltip above the magnified icon, dot below it, and a soft glow in the app's colour.
    private void showHero(int position, Rect card) {
        heroName.setText(adapter.labelAt(position));
        heroName.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // Above the magnified icons (the same height for every dock icon), lined up with the shelf's first icon.
        float iconTop = card.bottom - card.height() * DOCK_MAGNIFY - DOCK_LIFT_DP * density;
        Rect dock = new Rect();
        float titleX = card.left;
        if (dockDecoration.dockCards(grid, dock)) {
            root.offsetDescendantRectToMyCoords(grid, dock);
            titleX = dock.left;
        }
        heroName.setX(titleX);
        heroName.setY(iconTop - 10 * density - heroName.getMeasuredHeight());
        dockDot.setX(card.exactCenterX() - dockDot.getWidth() / 2f);
        dockDot.setY(card.bottom + 3 * density);
        heroName.animate().alpha(1f).setDuration(160).start();
        dockDot.animate().alpha(1f).setDuration(160).start();
        int color = adapter.ambientColorAt(position);
        if (!heroShown) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (color != ambientColor) {
            ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(500).start();
            }).start();
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        // Kept faint so the photo keeps its own colours (was 36% / 15%).
        ambientGlow.setColors(new int[]{0x26000000 | c, 0x0D000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        dockDot.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        magnifyDock(-1);
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int dock = adapter.dockCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < dock) return false;             // dock row: let focus move to the top bar
            int col = (position - dock) % COLUMNS;
            if (position - dock < COLUMNS) {
                if (dock == 0) return false;
                target = Math.min(col, dock - 1);          // first grid row: up to the dock
            } else {
                target = position - COLUMNS;
            }
        } else {
            if (position < dock) {
                if (count == dock) return true;            // nothing below the dock
                target = Math.min(dock + position, count - 1);
            } else {
                int lastRowStart = dock + ((count - 1 - dock) / COLUMNS) * COLUMNS;
                if (position >= lastRowStart) return true; // already on the last row: stay put
                target = Math.min(position + COLUMNS, count - 1);
            }
        }
        focusTile(target);
        return true;
    }

    private static int lcmUpTo(int n) {
        int l = 1;
        for (int i = 2; i <= n; i++) {
            int a = l, b = i;
            while (b != 0) { int t = a % b; a = b; b = t; }
            l = l / a * i;
        }
        return l;
    }

    // ---- dock editing ----------------------------------------------------------------------------

    @Override
    public void onTileMenu(int position, View card) {
        String pkg = adapter.pkgAt(position);
        boolean docked = adapter.isDocked(position);
        int dock = adapter.dockCount();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (220 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        if (docked) {
            addInputRow(rows, popup, R.drawable.ic_unpin, getString(R.string.unpin_from_dock), -1, () -> {
                adapter.unpin(pkg);
                focusAfterLayout(pkg);
            });
            if (position > 0) addInputRow(rows, popup, R.drawable.ic_left, getString(R.string.move_left), -1, () -> {
                adapter.move(pkg, -1);
                focusAfterLayout(pkg);
            });
            if (position < dock - 1) addInputRow(rows, popup, R.drawable.ic_right, getString(R.string.move_right), -1, () -> {
                adapter.move(pkg, 1);
                focusAfterLayout(pkg);
            });
        } else {
            addInputRow(rows, popup, R.drawable.ic_pin, getString(R.string.pin_to_dock), -1, () -> {
                if (adapter.pin(pkg)) focusAfterLayout(pkg);
                else Toast.makeText(this, R.string.dock_full, Toast.LENGTH_SHORT).show();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_info, getString(R.string.app_info), -1, () ->
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + (pkg.startsWith(getPackageName()) ? getPackageName() : pkg)))));

        // Float the menu just above the tile, centred on it; the name tooltip steps aside while it's open.
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        float top = docked ? r.bottom - r.height() * DOCK_MAGNIFY - DOCK_LIFT_DP * density : r.top;
        int x = (int) (r.exactCenterX() - width / 2f);
        int y = (int) (top - 12 * density - panel.getMeasuredHeight());
        heroName.animate().alpha(0f).setDuration(120).start();
        popup.setOnDismissListener(() -> {
            if (adapter.isDocked(lastFocused) && heroShown) heroName.animate().alpha(1f).setDuration(160).start();
        });
        panel.setAlpha(0f);
        panel.setTranslationY(8 * density);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    // Escape hatch on the TV itself: Menu (or hold OK) on the Settings button switches the Home redirect.
    private void showHomeScreenMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (290 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        boolean on = HomeRedirectService.isEnabled(this);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(on ? R.string.use_amazon_home : R.string.use_this_home), -1, () -> {
            if (!HomeRedirectService.setEnabled(this, !on)) {
                Toast.makeText(this, R.string.redirect_needs_permission, Toast.LENGTH_LONG).show();
            }
        });
        addToolRows(rows, popup);
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    // ---- Tools: sleep timer, turn off, settings shortcuts, free memory and storage ------------------

    /**
     * Remote colour buttons on the home screen: blue (or INFO, where Fire OS lets it through) opens Tools (which
     * has the sleep timer), red opens Live News, green opens the Inputs menu, yellow opens the USB player (Gallery).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_PROG_BLUE || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_PROG_RED
                || code == KeyEvent.KEYCODE_PROG_GREEN || code == KeyEvent.KEYCODE_PROG_YELLOW) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_PROG_RED) {
                    showNews();
                } else if (code == KeyEvent.KEYCODE_PROG_GREEN) {
                    showInputs();
                } else if (code == KeyEvent.KEYCODE_PROG_YELLOW) {
                    Intent gallery = getPackageManager().getLaunchIntentForPackage("com.amazon.tv.localgallery");
                    if (gallery != null) open(gallery);
                    else Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
                } else {
                    showHomeScreenMenu(findViewById(R.id.btnSettings));
                }
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) android.util.Log.d("TvHomeKeys", "key " + code);
        return super.dispatchKeyEvent(event);
    }

    private static final int[] SLEEP_STEPS = {0, 30, 60, 90};

    private void addToolRows(LinearLayout rows, PopupWindow popup) {
        int left = HomeRedirectService.sleepMinutesLeft();
        addToolRow(rows, popup, R.drawable.ic_settings, getString(R.string.sleep_timer),
                left > 0 ? getString(R.string.minutes_left, left) : getString(R.string.off), () -> {
            // Each press moves to the next step: off -> 30 -> 60 -> 90 -> off.
            int next = 0;
            for (int i = 0; i < SLEEP_STEPS.length; i++) {
                if (left > 0 && left <= SLEEP_STEPS[i]) {
                    next = SLEEP_STEPS[(i + 1) % SLEEP_STEPS.length];
                    break;
                }
            }
            if (left == 0) next = SLEEP_STEPS[1];
            if (!HomeRedirectService.setSleepTimer(next)) {
                Toast.makeText(this, R.string.tools_need_service, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, next == 0 ? getString(R.string.sleep_off)
                        : getString(R.string.sleep_set, next), Toast.LENGTH_SHORT).show();
            }
        });
        // Each press: off -> +6 -> +10 -> +15 dB -> off. Headphones only, applied by the Home-redirect service.
        int boost = HomeRedirectService.boostDb(this);
        addToolRow(rows, popup, R.drawable.ic_settings, getString(R.string.headphone_boost),
                boost > 0 ? getString(R.string.boost_value, boost) : getString(R.string.off), () -> {
            int next = 0;
            int[] steps = HomeRedirectService.BOOST_STEPS_DB;
            for (int i = 0; i < steps.length; i++) {
                if (steps[i] == boost) next = steps[(i + 1) % steps.length];
            }
            if (!HomeRedirectService.setBoost(this, next)) {
                Toast.makeText(this, R.string.tools_need_service, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, next == 0 ? getString(R.string.boost_off)
                        : getString(R.string.boost_set, next), Toast.LENGTH_LONG).show();
            }
        });
        addToolRow(rows, popup, R.drawable.ic_settings, getString(R.string.turn_off), null, () -> {
            if (!HomeRedirectService.turnOffNow()) {
                Toast.makeText(this, R.string.tools_need_service, Toast.LENGTH_LONG).show();
            }
        });
        // Fire OS: Bluetooth lives under "Controllers & Bluetooth Devices". Its Display, Sound and Picture pages
        // need Amazon-only permissions (LAUNCHER_SETTINGS, LAUNCH_INPUTS), so there are no shortcuts for them.
        addToolRow(rows, popup, R.drawable.ic_settings, getString(R.string.restart_tv), null, () -> {
            if (!HomeRedirectService.requestRestart(this)) {
                Toast.makeText(this, R.string.tools_need_service, Toast.LENGTH_LONG).show();
            }
        });
        // INPUT_DEVICES opens "Other Bluetooth Devices" (the paired headphones) inside Remotes & Bluetooth Devices;
        // CONTROLLERS_SETTINGS, the fallback, stops on the voice remotes. CLEAR_TASK because Amazon's Settings
        // otherwise comes back on whatever page it was last left on.
        addToolRow(rows, popup, R.drawable.ic_settings, getString(R.string.bluetooth), null, () -> {
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
            try {
                startActivity(new Intent("com.amazon.device.settings.action.INPUT_DEVICES").addFlags(flags));
            } catch (ActivityNotFoundException e) {
                open(new Intent("android.settings.CONTROLLERS_SETTINGS").addFlags(flags));
            }
        });
        addToolRow(rows, popup, R.drawable.ic_settings, getString(R.string.developer_options), null, () -> {
            if (!HomeRedirectService.requestDevicePage(this, HomeRedirectService.DEVELOPER_OPTIONS)) {
                open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
            }
        });

        android.app.ActivityManager.MemoryInfo mem = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        if (am != null) am.getMemoryInfo(mem);
        long storage = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes();
        addToolRow(rows, popup, R.drawable.ic_info, getString(R.string.free_memory),
                getString(R.string.megabytes, mem.availMem / (1024 * 1024)), () -> { });
        addToolRow(rows, popup, R.drawable.ic_info, getString(R.string.free_storage),
                getString(R.string.gigabytes, storage / (1024 * 1024 * 1024.0)), () -> { });
    }

    // ---- Live News (red button) ------------------------------------------------------------------

    /** Red button: the viewer's own list of YouTube channels (see News), with Add channel at the end. */
    private void showNews() {
        List<News.Channel> list = News.load(this);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newsPopup(panel, 320);
        addHeader(rows, getString(R.string.live_news));
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            final News.Channel ch = list.get(i);
            addInputRow(rows, popup, R.drawable.ic_livetv, ch.name, -1, () -> playNews(ch));
            View row = rows.getChildAt(rows.getChildCount() - 1);
            android.graphics.Bitmap logo = News.logo(this, ch);
            if (logo != null) {
                ImageView icon = row.findViewById(R.id.icon);
                icon.setImageTintList(null);
                icon.setImageBitmap(logo);
            }
            // Hold OK (or press Menu) on a channel: move, rename, delete.
            row.setOnLongClickListener(v -> {
                popup.dismiss();
                showNewsMenu(index);
                return true;
            });
            row.setOnKeyListener((v, keyCode, e) -> {
                if (keyCode != KeyEvent.KEYCODE_MENU || e.getAction() != KeyEvent.ACTION_DOWN) return false;
                popup.dismiss();
                showNewsMenu(index);
                return true;
            });
        }
        if (!list.isEmpty()) addSeparator(rows);
        addInputRow(rows, popup, R.drawable.ic_add, getString(R.string.add_channel), -1, this::askForChannel);
        if (!list.isEmpty()) addHeader(rows, getString(R.string.news_edit_hint)).setSingleLine(false);
        showNewsPopup(popup, panel);
        rows.getChildAt(1).requestFocus();   // the first channel (row 0 is the heading)
    }

    private void showNewsMenu(int index) {
        List<News.Channel> list = News.load(this);
        if (index >= list.size()) return;
        News.Channel ch = list.get(index);
        Context app = getApplicationContext();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newsPopup(panel, 320);
        addHeader(rows, ch.name);
        if (index > 0) addInputRow(rows, popup, R.drawable.ic_up, getString(R.string.move_up), -1, () -> {
            Collections.swap(list, index, index - 1);
            News.save(app, list);
            showNews();
        });
        if (index < list.size() - 1) addInputRow(rows, popup, R.drawable.ic_down, getString(R.string.move_down), -1, () -> {
            Collections.swap(list, index, index + 1);
            News.save(app, list);
            showNews();
        });
        addInputRow(rows, popup, R.drawable.ic_edit, getString(R.string.rename), -1, () ->
                askForText(R.string.rename, null, ch.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                        text -> {
                            if (!text.trim().isEmpty()) {
                                ch.name = text.trim();
                                News.save(app, list);
                            }
                            if (resumed) showNews();
                        }));
        addInputRow(rows, popup, R.drawable.ic_delete, getString(R.string.delete), -1, () -> {
            list.remove(index);
            News.deleteLogo(app, ch);
            News.save(app, list);
            Toast.makeText(app, getString(R.string.removed, ch.name), Toast.LENGTH_SHORT).show();
            showNews();
        });
        showNewsPopup(popup, panel);
        rows.getChildAt(1).requestFocus();
    }

    private void askForChannel() {
        Context app = getApplicationContext();
        askForText(R.string.add_channel_title, getString(R.string.add_channel_message), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, text -> {
            String link = News.normalize(text);
            if (link == null) {
                if (!text.trim().isEmpty()) Toast.makeText(app, R.string.not_youtube_link, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(app, R.string.looking_up, Toast.LENGTH_SHORT).show();
            News.lookUp(app, link, ch -> {
                List<News.Channel> list = News.load(app);
                list.add(ch);
                News.save(app, list);
                Toast.makeText(app, getString(R.string.channel_added, ch.name), Toast.LENGTH_SHORT).show();
                if (resumed) showNews();
            });
        });
    }

    /**
     * A channel: its streams live right now, to pick from when there are several (one plays straight away). A
     * video or playlist entry opens as it is.
     */
    private void playNews(News.Channel ch) {
        Context app = getApplicationContext();
        Toast.makeText(app, getString(R.string.opening, ch.name), Toast.LENGTH_SHORT).show();
        if (!News.isChannel(ch.url)) {
            openYouTube(ch.url);
            return;
        }
        News.liveStreams(ch.url, live -> {
            if (live.size() > 1 && resumed) {
                showStreams(ch, live);
            } else if (!live.isEmpty()) {
                openYouTube(live.get(0).url());
            } else {
                // Nothing found on the Live tab: let the channel's live page decide.
                News.resolve(ch.url, (url, liveNow) -> {
                    if (!liveNow) Toast.makeText(app, getString(R.string.not_live, ch.name), Toast.LENGTH_LONG).show();
                    openYouTube(url);
                });
            }
        });
    }

    private static final int MAX_STREAMS = 10;   // fits the screen height

    /**
     * A channel with several streams live at once (TV9 Kannada often has 8): pick one, in the middle of the
     * screen. Most watched first, with the viewer count, and focus starts on it.
     */
    private void showStreams(News.Channel ch, List<News.Stream> live) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = newsPopup(panel, 640);
        addHeader(rows, getString(R.string.live_streams, ch.name, live.size()));
        for (int i = 0; i < Math.min(live.size(), MAX_STREAMS); i++) {
            News.Stream s = live.get(i);
            addToolRow(rows, popup, R.drawable.ic_livetv, s.title, s.watching, () -> openYouTube(s.url()));
        }
        panel.setAlpha(0f);
        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.animate().alpha(1f).setDuration(200).start();
        rows.getChildAt(1).requestFocus();
    }

    /** Opens a youtube.com link in the TV's YouTube app; falls back to its launch screen with the link attached. */
    private void openYouTube(String url) {
        Uri uri = Uri.parse(url);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(News.YOUTUBE_PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(News.YOUTUBE_PKG);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(News.YOUTUBE_PKG);
        open(launch != null ? launch.setData(uri) : new Intent(Intent.ACTION_VIEW, uri));
    }

    /**
     * A one-line text box in a dialog. On Fire TV the on-screen keyboard opens with it, and the Fire TV phone
     * app's keyboard can type or paste into it.
     */
    private void askForText(int title, String message, String initial, int inputType,
                            java.util.function.Consumer<String> done) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setText(initial);
        field.setSelection(field.length());
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> done.accept(field.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        field.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.dismiss();
            done.accept(field.getText().toString());
            return true;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        field.requestFocus();
    }

    private PopupWindow newsPopup(View panel, int widthDp) {
        PopupWindow popup = new PopupWindow(panel, (int) (widthDp * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        return popup;
    }

    /** Hangs the panel under the clock and date, unfolding like the Inputs menu. */
    private void showNewsPopup(PopupWindow popup, View panel) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(findViewById(R.id.leftBlock), 0, (int) (14 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** A small grey line of text in a menu: a heading or a hint. Not focusable. */
    private TextView addHeader(LinearLayout rows, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(getResources().getFont(R.font.inter));
        t.setFontVariationSettings("'wght' 600");
        t.setLetterSpacing(0.04f);
        t.setSingleLine(true);
        int h = (int) (12 * density), v = (int) (8 * density);
        t.setPadding(h, v, h, v);
        rows.addView(t);
        return t;
    }

    private void addToolRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, String status,
                            Runnable action) {
        addInputRow(rows, popup, iconRes, title, -1, action);
        if (status == null) return;
        TextView st = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
        st.setText(status);
        st.setVisibility(View.VISIBLE);
    }

    /** After the dock changes, focus the app's new tile once the grid has re-laid out. */
    private void focusAfterLayout(String pkg) {
        hideHero();
        ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup().invalidateSpanIndexCache();
        grid.invalidateItemDecorations();
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int pos = adapter.positionOf(pkg);
                RecyclerView.ViewHolder vh = pos >= 0 ? grid.findViewHolderForAdapterPosition(pos) : null;
                if (vh == null) {
                    focusTile(Math.max(pos, 0));
                    return;
                }
                View c = vh.itemView.findViewById(R.id.card);
                c.requestFocus();
                onTileFocused(pos, c);
            }
        });
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    // Morning 5-12, afternoon 12-17, evening 17-22, night 22-5. Indexes GREETINGS and BACKGROUNDS.
    private static final int[] GREETINGS = {
            R.string.good_morning, R.string.good_afternoon, R.string.good_evening, R.string.good_night};

    private static int dayPart() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return 3;
        if (hour < 12) return 0;
        if (hour < 17) return 1;
        if (hour < 22) return 2;
        return 3;
    }

    private void updateGreeting() {
        String text = getString(GREETINGS[dayPart()]);
        String name = getString(R.string.user_name).trim();
        greeting.setText(name.isEmpty() ? text : text + ", " + name);
    }

    // ---- wallpapers -----------------------------------------------------------------------------
    // A rotation, one picture every WALL_ROTATE_MS, with its name under the top-right buttons. The built-in photo
    // comes first (bg_morning 5:00-17:00, bg_night otherwise; named by builtin_caption_day/_night), then any JPEG, PNG or WebP
    // in this app's media folder, /sdcard/Android/media/com.gathin.tvhome/wallpapers (adb push, no permission
    // needed). Names come from captions.txt there ("<file><TAB><name>" per line), else from the file name.

    private static final long WALL_ROTATE_MS = 30 * 60_000L;
    private static final String BUILT_IN = "builtin";

    /** The rotation: BUILT_IN, then the folder's pictures in name order. Re-read every time, it is a few files. */
    private List<String> wallpapers() {
        List<String> list = new ArrayList<>();
        list.add(BUILT_IN);
        File dir = wallpaperDir();
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) {
            List<String> pics = new ArrayList<>();
            for (File f : files) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) {
                    pics.add(f.getAbsolutePath());
                }
            }
            Collections.sort(pics);
            list.addAll(pics);
        }
        return list;
    }

    private File wallpaperDir() {
        File[] media = getExternalMediaDirs();
        if (media == null || media.length == 0 || media[0] == null) return null;
        File dir = new File(media[0], "wallpapers");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** The picture due now: moves on one step once WALL_ROTATE_MS has passed since the last step. */
    private String currentWallpaper() {
        List<String> list = wallpapers();
        android.content.SharedPreferences prefs = getSharedPreferences("wallpaper", MODE_PRIVATE);
        String shown = prefs.getString("path", BUILT_IN);
        long since = prefs.getLong("since", 0);
        int index = Math.max(0, list.indexOf(shown));
        long now = System.currentTimeMillis();
        if (list.size() > 1 && now - since >= WALL_ROTATE_MS) {
            index = since == 0 ? index : (index + 1) % list.size();
            prefs.edit().putString("path", list.get(index)).putLong("since", now).apply();
        }
        return list.get(index);
    }

    private String wallpaperCaption(String path) {
        if (BUILT_IN.equals(path)) return getString(isDaytime() ? R.string.builtin_caption_day : R.string.builtin_caption_night);
        File f = new File(path);
        File captions = new File(f.getParentFile(), "captions.txt");
        if (captions.isFile()) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(captions))) {
                for (String line; (line = in.readLine()) != null; ) {
                    int tab = line.indexOf('\t');
                    if (tab > 0 && line.substring(0, tab).trim().equals(f.getName())) return line.substring(tab + 1).trim();
                }
            } catch (java.io.IOException ignored) {
            }
        }
        // "02-coastal_train.jpg" -> "Coastal train"
        String name = f.getName().replaceFirst("\\.[^.]+$", "").replaceFirst("^[0-9]+[-_ ]*", "").replace('_', ' ').replace('-', ' ').trim();
        return name.isEmpty() ? "" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isDaytime() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 17;
    }

    private void updateBackground() {
        String path = currentWallpaper();
        String key = BUILT_IN.equals(path) ? "res:" + (isDaytime() ? R.drawable.bg_morning : R.drawable.bg_night) : path;
        if (key.equals(bgKey)) return;
        android.graphics.Bitmap photo = loadWallpaper(key);
        if (photo == null && !BUILT_IN.equals(path)) {   // unreadable file: fall back to the built-in photo
            key = "res:" + (isDaytime() ? R.drawable.bg_morning : R.drawable.bg_night);
            path = BUILT_IN;
            if (key.equals(bgKey)) return;
            photo = loadWallpaper(key);
        }
        if (photo == null) return;
        boolean first = bgKey == null;
        bgKey = key;
        final android.graphics.Bitmap pic = photo;
        final String caption = wallpaperCaption(path);
        if (first) {
            bgPhoto.setImageBitmap(pic);
            setFrost(frostedBand(pic), false);
            wallCaption.setText(caption);
            return;
        }
        // Fade through black so the swap isn't a jump cut.
        bgPhoto.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            bgPhoto.setImageBitmap(pic);
            setFrost(frostedBand(pic), true);
            wallCaption.setText(caption);
            bgPhoto.animate().alpha(1f).setDuration(900);
        });
    }

    private android.animation.ValueAnimator frostFade;

    /** Puts the blurred photo behind the dock shelf only (the photo stays sharp elsewhere); fades it in on a swap. */
    private void setFrost(android.graphics.Bitmap blurred, boolean fade) {
        if (frostFade != null) frostFade.cancel();
        dockDecoration.setFrost(blurred);
        dockDecoration.setFrostAlpha(fade ? 0 : 255);
        grid.invalidate();
        if (!fade || blurred == null) return;
        frostFade = android.animation.ValueAnimator.ofInt(0, 255).setDuration(900);
        frostFade.addUpdateListener(a -> {
            dockDecoration.setFrostAlpha((int) a.getAnimatedValue());
            grid.invalidate();
        });
        frostFade.start();
    }

    /** Decodes "res:<id>" or a file, scaled down to about the screen's size so a large photo costs no more memory. */
    private android.graphics.Bitmap loadWallpaper(String key) {
        int screenW = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        boolean res = key.startsWith("res:");
        int id = res ? Integer.parseInt(key.substring(4)) : 0;
        if (res) android.graphics.BitmapFactory.decodeResource(getResources(), id, o);
        else android.graphics.BitmapFactory.decodeFile(key, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
        return res ? android.graphics.BitmapFactory.decodeResource(getResources(), id, o)
                : android.graphics.BitmapFactory.decodeFile(key, o);
    }

    /**
     * The photo, blurred (shrunk to 1/16 and smoothly scaled back up) and darkened, for the dock shelf: only the
     * shelf shows it (DockDecoration), the rest of the photo stays sharp. About 0.5 MB.
     */
    private android.graphics.Bitmap frostedBand(android.graphics.Bitmap src) {
        if (src == null) return null;
        int w = 480, h = Math.round(w * src.getHeight() / (float) src.getWidth());
        // Shrink in steps, then grow back in steps: a soft blur without blocky edges.
        android.graphics.Bitmap a = android.graphics.Bitmap.createScaledBitmap(src, w / 2, h / 2, true);
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(a, w / 10, Math.max(1, h / 10), true);
        a.recycle();
        android.graphics.Bitmap mid = android.graphics.Bitmap.createScaledBitmap(small, w / 4, h / 4, true);
        small.recycle();
        android.graphics.Bitmap band = android.graphics.Bitmap.createScaledBitmap(mid, w, h, true)
                .copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        mid.recycle();
        android.graphics.Canvas c = new android.graphics.Canvas(band);
        c.drawColor(0x8C060810);   // darken
        return band;
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    // Signal bars only (no network name, so screenshots stay private); text only when something is wrong.
    private void updateWifiLabel() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
        if (caps == null) {
            setWifiPill(R.drawable.ic_wifi_off, getString(R.string.offline), COLOR_ERROR);
            return;
        }
        boolean online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            setWifiPill(R.drawable.ic_ethernet, online ? getString(R.string.ethernet) : getString(R.string.no_internet),
                    online ? 0 : COLOR_WARN);
            return;
        }
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        int bars = info != null ? WifiManager.calculateSignalLevel(info.getRssi(), WIFI_ICONS.length) : 0;
        if (!online) {
            setWifiPill(WIFI_ICONS[bars], getString(R.string.no_internet), COLOR_WARN);
            return;
        }
        setWifiPill(WIFI_ICONS[bars], getString(R.string.wifi), 0);
    }

    private void setWifiPill(int icon, String label, int alertColor) {
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        btnWifi.setContentDescription(label);
        // Healthy: a round icon button like Inputs and Settings. Alert: a pill with the status text.
        boolean iconOnly = alertColor == 0;
        ViewGroup.LayoutParams lp = btnWifi.getLayoutParams();
        lp.width = iconOnly ? (int) (42 * density) : ViewGroup.LayoutParams.WRAP_CONTENT;
        btnWifi.setLayoutParams(lp);
        btnWifi.setPaddingRelative((int) ((iconOnly ? 10 : 16) * density), 0, (int) ((iconOnly ? 10 : 20) * density), 0);
        btnWifi.setCompoundDrawablePadding(iconOnly ? 0 : (int) (9 * density));
        if (iconOnly) {
            btnWifi.setText("");
            return;
        }
        SpannableString text = new SpannableString("\u25CF  " + label);
        text.setSpan(new ForegroundColorSpan(alertColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
