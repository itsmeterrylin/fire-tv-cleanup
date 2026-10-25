package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * follows a settings window within SETTINGS_GRACE_MS is left alone. Also opens the launcher once at boot, and
 * when the TV is turned on over Amazon's home screen (see screenOn).
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
    private static final long SETTINGS_GRACE_MS = 2000;     // measured: settings window -> home flash ~200 ms
    private static final long BOOT_WINDOW_MS = 3 * 60_000;  // service connecting this soon after boot = boot

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = this::openLauncher;
    private long settingsSeenAt = -SETTINGS_GRACE_MS;

    // Turning the TV off brings Amazon's home screen to the front while the screen is dark, and no window event
    // reaches this service then, so on turning it back on Amazon's home would stay. The usage stats (granted
    // over adb: appops set com.gathin.tvhome GET_USAGE_STATS allow) say what is in front; if it is Amazon's
    // home, the launcher opens. Anything else (an HDMI input, an app) is left alone. Checked a few seconds after
    // the screen goes off, so the launcher is already in front when the TV comes back on, and again on screen on
    // in case that missed (SCREEN_ON reaches this app up to ~2.5 s late, after Fire OS's own receivers).
    private static final long SCREEN_OFF_CHECK_MS = 4000;
    private final Runnable homeCheck = () -> {
        if (amazonHomeInFront()) {
            Log.i(TAG, "home screen in front with the screen on/off: opening launcher");
            openLauncher();
        }
    };
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(homeCheck);
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) handler.postDelayed(homeCheck, SCREEN_OFF_CHECK_MS);
            else homeCheck.run();
        }
    };

    @Override
    protected void onServiceConnected() {
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, screen);
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openLauncher();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
            // Never registered: the service was not connected.
        }
        super.onDestroy();
    }

    /** True when the last app to come to the front is Amazon's home screen; false without usage access. */
    private boolean amazonHomeInFront() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm == null) return false;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 60 * 60_000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null, cls = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = e.getPackageName();
                cls = e.getClassName();
            }
        }
        return AMAZON_LAUNCHER.equals(pkg) && cls != null && cls.contains(AMAZON_HOME);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
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
