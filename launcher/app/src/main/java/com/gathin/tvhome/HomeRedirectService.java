package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes this launcher the home screen on Fire OS, which ignores the default-home setting.
 *
 * Fire OS never passes the Home key to accessibility services, so this watches windows instead: when Amazon's
 * home screen comes up, this launcher opens over it straight away. One exception: Amazon's home screen also
 * flashes up on the way into Settings, right after a window from Amazon's settings app, so a home screen that
 * follows a settings window within SETTINGS_GRACE_MS is left alone. Also opens the launcher once at boot.
 *
 * Fire OS 7 shows Settings as a tab of Amazon's home screen, so Home from Settings opens no window: it only moves
 * focus to the Home tab (described as AMAZON_HOME_TAB, English UI). That focus change redirects too.
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
    private static final String AMAZON_HOME_TAB = "Home";
    // measured: settings window -> home flash ~200 ms, up to 2.6 s on Fire OS 7
    private static final long SETTINGS_GRACE_MS = 5000;
    private static final long BOOT_WINDOW_MS = 3 * 60_000;  // service connecting this soon after boot = boot

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = this::openLauncher;
    private long settingsSeenAt = -SETTINGS_GRACE_MS;

    @Override
    protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openLauncher();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (AMAZON_LAUNCHER.contentEquals(pkg) && AMAZON_HOME_TAB.contentEquals(
                    String.valueOf(event.getContentDescription()))) {
                Log.i(TAG, "Home tab focused: opening launcher");
                handler.post(redirect);
            }
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long now = SystemClock.uptimeMillis();
        for (String app : SETTINGS_APPS) if (pkg.toString().startsWith(app)) settingsSeenAt = now;
        handler.removeCallbacks(redirect);
        if (!AMAZON_LAUNCHER.contentEquals(pkg) || !cls.toString().contains(AMAZON_HOME)) return;
        if (now - settingsSeenAt < SETTINGS_GRACE_MS) {
            Log.i(TAG, "home screen on the way into Settings: left alone");
            return;
        }
        Log.i(TAG, "home screen: opening launcher");
        handler.post(redirect);
    }

    @Override
    public void onInterrupt() {
    }

    private void openLauncher() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    // ---- on/off from the launcher's UI -----------------------------------------------------------

    static boolean isEnabled(Context ctx) {
        String on = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(component(ctx).flattenToString());
    }

    /** False when the app lacks WRITE_SECURE_SETTINGS. */
    static boolean setEnabled(Context ctx, boolean on) {
        try {
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    on ? component(ctx).flattenToString() : "");
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, on ? 1 : 0);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static ComponentName component(Context ctx) {
        return new ComponentName(ctx, HomeRedirectService.class);
    }
}
