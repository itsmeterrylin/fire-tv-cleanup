package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Makes this launcher the home screen on Fire OS, which ignores the default-home setting.
 *
 * Fire OS never passes the Home key to accessibility services, so this watches windows instead: when Amazon's
 * home screen comes up, this launcher opens. Android 9 blocks background activity starts for ~5 s after Home,
 * so first choice is to click this launcher's own tile on Amazon's home screen (Amazon is the resumed app, so
 * its launch is allowed and takes ~0.6 s). If the tile is not found within RETRY_MS, a black cover hides Amazon's
 * home and the launcher is started directly (late, but hidden). The cover goes when this launcher's window
 * appears, or after COVER_MAX_MS. While it shows, D-pad and OK keys are swallowed so blind presses do not open
 * things on Amazon's home. HomeRedirectPolicy decides when to act (settings grace, dedupe, loop guard).
 * Window content is read only when the active window belongs to Amazon's launcher.
 *
 * One exception to acting: Amazon's home screen also flashes up on the way into Settings, so a home screen that
 * follows a settings window is left alone. Also opens the launcher once at boot, and again if an app opens
 * itself during the first minutes after boot before any key is pressed (boot guard).
 *
 * Turned on and off from the launcher (Menu on the Settings button) when the app holds WRITE_SECURE_SETTINGS
 * (granted over adb: pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS), or over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService
 *   settings put secure accessibility_enabled 1
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String TAG = "HomeRedirect";
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "HomeActivity";
    private static final String[] SETTINGS_APPS = {"com.amazon.tv.settings", "com.amazon.tv.quicksettings"};
    // Windows from these never cancel a redirect (status bar, volume, ...).
    private static final String[] SYSTEM_APPS = {"com.android.systemui", "android"};
    private static final long RETRY_EVERY_MS = 100;         // the root window is null for the first ~100-300 ms
    private static final long RETRY_MS = 1500;
    private static final long COVER_MAX_MS = 8000;
    private static final long BOOT_GUARD_GAP_MS = 1500;
    // IPTV Smarters opens a splash and then its dashboard ~2 s later at boot: keep the cover up through both.
    private static final long BOOT_COVER_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HomeRedirectPolicy policy = new HomeRedirectPolicy();
    private final Runnable clickTile = this::tryClickTile;
    private final Runnable hideCover = this::removeCover;
    private long attemptStart;
    private long bootGuardAt = -BOOT_GUARD_GAP_MS;
    private boolean userKeySeen;
    private View cover;
    private long coverHoldUntil;

    @Override
    protected void onServiceConnected() {
        userKeySeen = false;
        if (SystemClock.elapsedRealtime() < HomeRedirectPolicy.BOOT_WINDOW_MS) openLauncher();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        String p = pkg.toString();
        long now = SystemClock.uptimeMillis();
        if (p.equals(getPackageName())) {   // our launcher is up: nothing left to hide or click
            cancelAttempt();
            if (HomeRedirectPolicy.coverMayDrop(now, coverHoldUntil)) removeCover();
            return;
        }
        if (HomeRedirectPolicy.shouldBootGuard(SystemClock.elapsedRealtime(), userKeySeen, p, getPackageName())) {
            if (now - bootGuardAt >= BOOT_GUARD_GAP_MS) {
                Log.i(TAG, "boot guard: " + p + " opened itself, opening launcher");
                bootGuardAt = now;
                coverHoldUntil = now + BOOT_COVER_MS;
                showCover(BOOT_COVER_MS);
                openLauncher();
            }
            return;
        }
        for (String app : SETTINGS_APPS) if (p.startsWith(app)) policy.onSettingsSeen(now);
        boolean amazonHome = AMAZON_LAUNCHER.equals(p) && cls.toString().contains(AMAZON_HOME);
        if (!amazonHome) {
            // Something else opened (not just Amazon's launcher or the system UI): drop any pending redirect.
            boolean ignore = AMAZON_LAUNCHER.equals(p);
            for (String app : SYSTEM_APPS) if (p.equals(app)) ignore = true;
            if (!ignore) {
                cancelAttempt();
                if (HomeRedirectPolicy.coverMayDrop(now, coverHoldUntil)) removeCover();
            }
            return;
        }
        boolean act = policy.shouldAct(now);
        if (policy.takeLoopTripped()) Log.w(TAG, "redirected too often: paused for a minute");
        if (!act) {
            Log.i(TAG, "home screen: left alone");
            return;
        }
        Log.i(TAG, "home screen: opening launcher");
        attemptStart = now;
        handler.removeCallbacks(clickTile);
        handler.post(clickTile);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) userKeySeen = true;   // a person is at the remote
        if (cover == null) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onInterrupt() {
        cancelAttempt();
        removeCover();
    }

    @Override
    public void onDestroy() {
        cancelAttempt();
        removeCover();
        super.onDestroy();
    }

    // ---- redirect --------------------------------------------------------------------------------

    private void cancelAttempt() {
        handler.removeCallbacks(clickTile);
    }

    /** Clicks our tile on Amazon's home screen; retries every RETRY_EVERY_MS, then falls back to the cover. */
    private void tryClickTile() {
        if (clickOurTile()) {
            Log.i(TAG, "clicked our tile");
            return;
        }
        if (SystemClock.uptimeMillis() - attemptStart < RETRY_MS) {
            handler.postDelayed(clickTile, RETRY_EVERY_MS);
            return;
        }
        Log.i(TAG, "tile not found: cover and start");
        showCover(COVER_MAX_MS);
        openLauncher();
    }

    private boolean clickOurTile() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !AMAZON_LAUNCHER.contentEquals(root.getPackageName())) return false;
        String label = getString(R.string.app_name);
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
        if (found == null) return false;
        for (AccessibilityNodeInfo node : found) {
            if (node == null) continue;
            if (!HomeRedirectPolicy.matchesLabel(node.getContentDescription(), label)
                    && !HomeRedirectPolicy.matchesLabel(node.getText(), label)) continue;
            for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
                if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        return false;
    }

    private void openLauncher() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    // ---- black cover -----------------------------------------------------------------------------

    private void showCover(long maxMs) {
        handler.removeCallbacks(hideCover);
        if (cover == null) {
            WindowManager wm = getSystemService(WindowManager.class);
            if (wm == null) return;
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            try {
                wm.addView(v, lp);
                cover = v;
            } catch (RuntimeException e) {
                Log.w(TAG, "cover failed: " + e);
                return;
            }
        }
        handler.postDelayed(hideCover, maxMs);
    }

    private void removeCover() {
        handler.removeCallbacks(hideCover);
        if (cover == null) return;
        View v = cover;
        cover = null;
        try {
            WindowManager wm = getSystemService(WindowManager.class);
            if (wm != null) wm.removeView(v);
        } catch (RuntimeException e) {
            Log.w(TAG, "cover removal failed: " + e);
        }
    }

    // ---- on/off from the launcher's UI -----------------------------------------------------------

    static boolean isEnabled(Context ctx) {
        String on = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return AccessibilityServiceList.contains(on, component(ctx).flattenToString());
    }

    /** Adds or removes only this service in the list, keeping the others. False when the app lacks WRITE_SECURE_SETTINGS. */
    static boolean setEnabled(Context ctx, boolean on) {
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String me = component(ctx).flattenToString();
            String next = on ? AccessibilityServiceList.add(current, me) : AccessibilityServiceList.remove(current, me);
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, next.isEmpty() ? 0 : 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static ComponentName component(Context ctx) {
        return new ComponentName(ctx, HomeRedirectService.class);
    }
}
