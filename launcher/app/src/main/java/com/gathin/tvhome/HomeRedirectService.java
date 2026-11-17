package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes this launcher the home screen on Fire OS, which ignores the default-home setting.
 *
 * Fire OS never passes the Home key to accessibility services, so this watches windows instead: when Amazon's
 * home screen comes up, this launcher opens over it straight away. One exception: Amazon's home screen also
 * flashes up on the way into Settings, right after a window from Amazon's settings app, so a home screen that
 * follows a settings window within SETTINGS_GRACE_MS is left alone. Also opens the launcher once at boot.
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

    /**
     * Set by the launcher when it opens an app whose screens live inside Amazon's home (the Appstore on Fire OS 8).
     * Amazon's home is then left alone until a window from any other app comes up. Fire OS keeps the Home key and
     * its broadcasts from third-party apps, so from the Appstore the way back is the "Home screen" tile in Amazon's
     * app row, or opening any app.
     */
    static volatile boolean allowAmazonHome;
    private static final String[] AMAZON_HOME_APPS = {AMAZON_LAUNCHER, "com.amazon.venezia", "com.android.systemui"};

    // ---- sleep timer and turn-off (Tools menu) ----------------------------------------------------
    // This service stays running while other apps play, so it holds the timer. Turning off uses the accessibility
    // lock-screen action, which on a TV puts the set to standby; the power menu is the fallback.

    private static HomeRedirectService instance;
    /** SystemClock.uptimeMillis() at which the TV turns off, or 0 when no timer is set. */
    static volatile long sleepAt;
    private final Runnable sleep = () -> {
        sleepAt = 0;
        Log.i(TAG, "sleep timer: turning off");
        turnOff();
    };

    /** Sets the sleep timer, or clears it for minutes <= 0. False when the service isn't running. */
    static boolean setSleepTimer(int minutes) {
        HomeRedirectService s = instance;
        if (s == null) return false;
        s.handler.removeCallbacks(s.sleep);
        sleepAt = minutes > 0 ? SystemClock.uptimeMillis() + minutes * 60_000L : 0;
        if (sleepAt > 0) s.handler.postAtTime(s.sleep, sleepAt);
        return true;
    }

    /** Minutes left on the sleep timer (rounded up), or 0 when it is off. */
    static int sleepMinutesLeft() {
        long left = sleepAt - SystemClock.uptimeMillis();
        return sleepAt == 0 || left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** Puts the TV to standby now. False when the service isn't running. */
    static boolean turnOffNow() {
        HomeRedirectService s = instance;
        if (s == null) return false;
        s.turnOff();
        return true;
    }

    // ---- USB drive plugged in -------------------------------------------------------------------
    // Opens the USB screen when a drive is plugged in while the home screen is in front and the TV has been on a
    // while. Not while something else is on screen (no pop-up over a film), not at boot and not just after the TV
    // wakes (drives that stay plugged in get mounted again then).

    private static final long SCREEN_SETTLE_MS = 30_000;
    private String frontPackage = "";
    private long screenOnAt;
    private final BroadcastReceiver usbDrives = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOnAt = SystemClock.elapsedRealtime();
                return;
            }
            String path = intent.getData() != null ? intent.getData().getPath() : null;
            if (path == null || !path.startsWith("/storage/") || path.startsWith("/storage/emulated")) return;
            long now = SystemClock.elapsedRealtime();
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            boolean settled = now > BOOT_WINDOW_MS && now - screenOnAt > SCREEN_SETTLE_MS
                    && pm != null && pm.isInteractive();
            if (!settled || !getPackageName().equals(frontPackage)) {
                Log.i(TAG, "USB drive " + path + " mounted: not opening the USB screen (front: " + frontPackage + ")");
                return;
            }
            Log.i(TAG, "USB drive " + path + " mounted: opening the USB screen");
            startActivity(new Intent(HomeRedirectService.this, UsbActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    };

    // ---- Restart TV and Developer options (Tools menu) --------------------------------------------
    // Only system apps may reboot, and Developer Options has no link of its own (every developer action opens
    // Device & Software on "About"), so this opens Amazon's Device & Software page and presses the item by its
    // label: "Restart" (Fire OS then shows its own Restart / Cancel confirmation) or "Developer Options". This is
    // the only place the service reads the screen.

    private static final String DEVICE_SETTINGS_PKG = "com.amazon.tv.settings.v2";
    static final String RESTART = "Restart";
    static final String DEVELOPER_OPTIONS = "Developer Options";
    private long restartUntil;
    private int restartTries;
    private String pressLabel = RESTART;
    private final Runnable pressRestart = this::pressRestart;

    /** Opens Device & Software and presses Restart there. False when the service isn't running. */
    static boolean requestRestart(Context ctx) {
        return requestDevicePage(ctx, RESTART);
    }

    /** Opens Device & Software and presses the item labelled {@code label}. False when the service isn't running. */
    static boolean requestDevicePage(Context ctx, String label) {
        HomeRedirectService s = instance;
        if (s == null) return false;
        s.restartUntil = SystemClock.uptimeMillis() + 10_000;
        s.restartTries = 0;
        s.pressLabel = label;
        // CLEAR_TASK: Amazon's Settings otherwise comes back on whatever page it was last left on.
        ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        s.handler.removeCallbacks(s.pressRestart);
        s.handler.postDelayed(s.pressRestart, 800);   // in case the page was already open and sends no event
        return true;
    }

    private void pressRestart() {
        if (SystemClock.uptimeMillis() > restartUntil || restartTries++ > 12) {
            restartUntil = 0;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && DEVICE_SETTINGS_PKG.contentEquals(root.getPackageName())) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(pressLabel);
            for (AccessibilityNodeInfo n : found) {
                AccessibilityNodeInfo target = n;
                while (target != null && !target.isClickable()) target = target.getParent();
                if (target == null) continue;
                // Amazon's settings lists act on the selected row: select the item first, then press it. If Fire OS
                // ignores the press, the item is at least highlighted and one OK on the remote opens it.
                final AccessibilityNodeInfo row = target;
                final String label = pressLabel;
                boolean focused = row.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                handler.postDelayed(() -> {
                    boolean clicked = row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, label + ": focused=" + focused + " clicked=" + clicked);
                }, 350);
                restartUntil = 0;
                return;
            }
            // Not on screen yet: scroll the list down a step and look again.
            scrollDown(root);
        }
        handler.postDelayed(pressRestart, 400);
    }

    private static boolean scrollDown(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (scrollDown(node.getChild(i))) return true;
        return false;
    }

    private void turnOff() {
        // lockNow() switches the screen off, which on Fire TV is standby. Needs the device admin (see AdminReceiver);
        // without it, fall back to the system power menu.
        android.app.admin.DevicePolicyManager dpm = getSystemService(android.app.admin.DevicePolicyManager.class);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            Log.i(TAG, "turning off: lockNow");
            dpm.lockNow();
        } else {
            Log.i(TAG, "turning off: no device admin, showing the power menu");
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        }
    }

    // ---- headphone boost (Tools menu) -------------------------------------------------------------
    // Fire OS keeps the remote's volume keys, so Just Player's own boost (Volume + past the maximum) never gets
    // them. Players that work with equalizer apps announce each audio session they open (Just Player and VLC
    // do); this service is always running, so it hears that and puts a LoudnessEnhancer on the session, the way
    // equalizer apps do. The gain only applies while headphones (Bluetooth or wired) are the output, so the TV's
    // own speakers are never boosted. LoudnessEnhancer limits the peaks itself, so loud scenes don't clip.

    static final int[] BOOST_STEPS_DB = {0, 6, 10, 15};
    private static final String BOOST_PREFS = "headphone_boost";
    private final Map<Integer, LoudnessEnhancer> boosted = new HashMap<>();

    private final BroadcastReceiver audioSessions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioManager.ERROR);
            String pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            if (session <= 0 || getPackageName().equals(pkg)) return;
            if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                LoudnessEnhancer e = boosted.remove(session);
                if (e != null) e.release();
                return;
            }
            if (boosted.containsKey(session)) return;
            try {
                boosted.put(session, new LoudnessEnhancer(session));
                Log.i(TAG, "boost: session " + session + " from " + pkg);
            } catch (RuntimeException e) {
                Log.w(TAG, "boost: can't attach to session " + session + " from " + pkg, e);
            }
            applyBoost();
        }
    };

    private final AudioDeviceCallback outputs = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            applyBoost();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            applyBoost();
        }
    };

    /** The boost in dB the viewer picked (0 = off). */
    static int boostDb(Context ctx) {
        return ctx.getSharedPreferences(BOOST_PREFS, MODE_PRIVATE).getInt("db", 0);
    }

    /** Saves the boost and applies it to what is playing now. False when the service isn't running. */
    static boolean setBoost(Context ctx, int db) {
        ctx.getSharedPreferences(BOOST_PREFS, MODE_PRIVATE).edit().putInt("db", db).apply();
        HomeRedirectService s = instance;
        if (s == null) return false;
        s.handler.post(s::applyBoost);
        return true;
    }

    private void applyBoost() {
        int db = headphonesConnected() ? boostDb(this) : 0;
        for (Map.Entry<Integer, LoudnessEnhancer> e : boosted.entrySet()) {
            try {
                e.getValue().setTargetGain(db * 100);
                e.getValue().setEnabled(db > 0);
            } catch (RuntimeException ex) {
                Log.w(TAG, "boost: session " + e.getKey(), ex);
            }
        }
    }

    private boolean headphonesConnected() {
        return headphonesConnected(this);
    }

    /** Bluetooth, wired or USB headphones are an output (the USB player boosts them separately too). */
    static boolean headphonesConnected(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                    return true;
                default:
            }
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        IntentFilter f = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        registerReceiver(audioSessions, f);
        screenOnAt = SystemClock.elapsedRealtime();
        IntentFilter mounts = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        mounts.addDataScheme("file");
        registerReceiver(usbDrives, mounts);
        registerReceiver(usbDrives, new IntentFilter(Intent.ACTION_SCREEN_ON));
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) am.registerAudioDeviceCallback(outputs, handler);
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openLauncher();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(sleep);
        sleepAt = 0;
        try {
            unregisterReceiver(audioSessions);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(usbDrives);
        } catch (IllegalArgumentException ignored) {
        }
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) am.unregisterAudioDeviceCallback(outputs);
        for (LoudnessEnhancer e : boosted.values()) e.release();
        boosted.clear();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        // Overlays (a "USB drive connected" notice, the volume bar) don't count as what's in front.
        if (!"com.android.systemui".contentEquals(pkg) && !"android".contentEquals(pkg)) frontPackage = pkg.toString();
        long now = SystemClock.uptimeMillis();
        if (restartUntil > now && DEVICE_SETTINGS_PKG.contentEquals(pkg)) {
            handler.removeCallbacks(pressRestart);
            handler.postDelayed(pressRestart, 500);
        }
        for (String app : SETTINGS_APPS) if (pkg.toString().startsWith(app)) settingsSeenAt = now;
        handler.removeCallbacks(redirect);
        if (allowAmazonHome) {
            boolean amazonSide = false;
            for (String app : AMAZON_HOME_APPS) if (app.contentEquals(pkg)) amazonSide = true;
            if (!amazonSide) allowAmazonHome = false;   // left the Appstore for another app (or this launcher)
        }
        if (!AMAZON_LAUNCHER.contentEquals(pkg) || !cls.toString().contains(AMAZON_HOME)) return;
        if (allowAmazonHome) {
            Log.i(TAG, "Amazon home opened from the launcher (Appstore): left alone");
            return;
        }
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
