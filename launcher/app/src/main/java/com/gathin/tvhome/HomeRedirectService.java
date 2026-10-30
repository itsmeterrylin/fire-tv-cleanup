package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Makes this launcher the home screen on Fire OS 6, which ignores both the default-home setting and
 * third-party accessibility services (Amazon's AccessibilityManagerService only binds services it
 * trusts, and it logs A11yAuthorizer trust=false for sideloaded apps).
 *
 * Instead this watches logcat for the ActivityManager line that Fire OS emits when it brings Amazon's
 * home screen up, and opens this launcher over it. Reading logcat needs android.permission.READ_LOGS,
 * which is not grantable from the TV's UI but is grantable over adb:
 *   pm grant com.gathin.tvhome android.permission.READ_LOGS
 *
 * Runs as a foreground service so Fire OS keeps the process alive. Turned on and off from the
 * launcher (Menu on the Settings button) or from the Home launcher itself at boot (BootReceiver).
 */
public class HomeRedirectService extends Service {

    private static final String TAG = "HomeRedirect";
    private static final String PREFS = "home_redirect";
    private static final String KEY_ON = "on";
    private static final int NOTIF_ID = 42;
    private static final long REDIRECT_THROTTLE_MS = 800;
    private static final long[] RETRY_DELAYS_MS = {600, 800, 1200, 1500, 1500};

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME_ACTIVITY = "HomeActivity";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Process proc;
    private volatile boolean running;
    private long lastRedirectAt;

    /** Set by the launcher before it opens Amazon's settings, so the redirect does not fight the user. */
    private static long suspendUntil;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, notification());
        RedirectJobService.schedule(this);
        if (!running) {
            startWatching();
            // Fire OS shows Amazon's home at boot before anything else: open over it once, shortly
            // after the watcher starts. Not on later onStartCommand calls, which would yank the user
            // back to the launcher three seconds after opening an app.
            handler.postDelayed(this::openLauncher, 3000);
        }
        return START_STICKY;
    }

    private Notification notification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.home_redirect_label))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void startWatching() {
        running = true;
        Thread reader = new Thread(() -> {
            try {
                proc = new ProcessBuilder("/system/bin/sh", "-c",
                        "logcat -v brief ActivityManager:I *:S")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (isAmazonHome(line)) {
                        Log.i(TAG, "amazon home: " + line.trim());
                        handler.post(this::maybeRedirect);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "logcat watcher stopped", e);
            }
        }, "home-redirect-logcat");
        reader.setDaemon(true);
        reader.start();
    }

    /** The ActivityManager lines Fire OS emits when Amazon's home screen is started or shown. */
    private static boolean isAmazonHome(String line) {
        return line.contains(AMAZON_LAUNCHER)
                && line.contains(AMAZON_HOME_ACTIVITY)
                && (line.contains("START u0") || line.contains("Displayed"));
    }

    private void maybeRedirect() {
        long now = System.currentTimeMillis();
        if (now < suspendUntil) return;
        if (now - lastRedirectAt < REDIRECT_THROTTLE_MS) return;
        lastRedirectAt = now;
        // moveTaskToFront brings the launcher back without its tiles re-animating. If it did not take
        // (no task yet, or another of our screens is on top of it), ensureVisible starts it explicitly.
        moveTaskToFront();
        handler.postDelayed(this::ensureVisible, 350);
    }

    private void ensureVisible() {
        if (System.currentTimeMillis() < suspendUntil || MainActivity.isVisible) return;
        attempt(0);
    }

    /**
     * Fire OS stops activity starts that come from a background process ("Activity start request from
     * &lt;uid&gt; stopped") for a few seconds after the home key. Retry a few times, but stop as soon as
     * the launcher is on screen: every extra start replays its tile layout animation, which looks like
     * flickering icons.
     */
    private void attempt(int n) {
        if (System.currentTimeMillis() < suspendUntil || MainActivity.isVisible) return;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        if (n < RETRY_DELAYS_MS.length) {
            handler.postDelayed(() -> attempt(n + 1), RETRY_DELAYS_MS[n]);
        }
    }

    /** True when this app already has a task that can be raised. */
    private boolean moveTaskToFront() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks != null && !tasks.isEmpty()) {
                tasks.get(0).moveToFront();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "moveTaskToFront failed", e);
        }
        return false;
    }

    /** Used once after the service starts, to put the launcher on screen after a reboot. */
    private void openLauncher() {
        if (MainActivity.isVisible) return;
        moveTaskToFront();
        handler.postDelayed(this::ensureVisible, 350);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (proc != null) proc.destroy();
        stopForeground(true);
        super.onDestroy();
    }

    // ---- on/off from the launcher's UI -----------------------------------------------------------

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, false);
    }

    static boolean setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply();
        Intent i = new Intent(ctx, HomeRedirectService.class);
        if (on) ctx.startService(i);
        else ctx.stopService(i);
        return true;
    }

    static void suspend(long ms) {
        suspendUntil = System.currentTimeMillis() + ms;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
