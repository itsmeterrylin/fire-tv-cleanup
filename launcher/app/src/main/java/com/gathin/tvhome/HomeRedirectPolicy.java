package com.gathin.tvhome;

import java.util.ArrayDeque;

/**
 * When the Home redirect may act, with no Android dependencies so it can be unit tested. Times are passed in
 * (milliseconds on any monotonic clock).
 *
 * Rules, in order: a home screen right after a settings window is left alone (SETTINGS_GRACE_MS); a second Amazon
 * home event right after one we acted on is ignored (DEDUPE_MS: Amazon emits two per Home press); and if we acted
 * LOOP_MAX times within LOOP_WINDOW_MS something is fighting us, so we stop for COOLDOWN_MS.
 */
final class HomeRedirectPolicy {

    static final long SETTINGS_GRACE_MS = 2000;   // measured: settings window -> home flash ~200 ms
    static final long DEDUPE_MS = 1500;
    static final int LOOP_MAX = 3;
    static final long LOOP_WINDOW_MS = 10_000;
    static final long COOLDOWN_MS = 60_000;
    static final long BOOT_WINDOW_MS = 3 * 60_000;   // since device boot

    private static final long NEVER = Long.MIN_VALUE / 4;

    private long settingsSeenAt = NEVER;
    private long lastActAt = NEVER;
    private long cooldownUntil = NEVER;
    private final ArrayDeque<Long> acts = new ArrayDeque<>();
    private boolean loopTripped;

    /** A window from Amazon's settings apps was seen. */
    void onSettingsSeen(long now) {
        settingsSeenAt = now;
    }

    /** True when the loop guard has just stopped us (cleared by reading it), so the caller can log it once. */
    boolean takeLoopTripped() {
        boolean t = loopTripped;
        loopTripped = false;
        return t;
    }

    /** Amazon's home screen came up. True means act now (and the act is recorded). */
    boolean shouldAct(long now) {
        if (now - settingsSeenAt < SETTINGS_GRACE_MS) return false;
        if (now < cooldownUntil) return false;
        if (now - lastActAt < DEDUPE_MS) return false;
        while (!acts.isEmpty() && now - acts.peekFirst() >= LOOP_WINDOW_MS) acts.pollFirst();
        if (acts.size() >= LOOP_MAX) {
            cooldownUntil = now + COOLDOWN_MS;
            acts.clear();
            loopTripped = true;
            return false;
        }
        acts.addLast(now);
        lastActAt = now;
        return true;
    }

    /**
     * Boot guard: some apps open themselves at boot (e.g. IPTV Smarters). Within BOOT_WINDOW_MS of boot, and until
     * the user has pressed any key, a window from an unrelated app means: open our launcher. No Home press
     * happened, so the background-start block does not apply.
     */
    static boolean shouldBootGuard(long elapsedRealtime, boolean userKeySeen, String pkg, String ownPkg) {
        if (userKeySeen || elapsedRealtime >= BOOT_WINDOW_MS || pkg == null) return false;
        if (pkg.equals(ownPkg) || pkg.equals("com.amazon.tv.launcher")) return false;
        if (pkg.startsWith("com.amazon.tv.settings") || pkg.startsWith("com.amazon.tv.quicksettings")) return false;
        return !pkg.equals("android") && !pkg.equals("com.android.systemui");
    }

    /** False while a boot-guard cover must stay up (until holdUntil), whatever windows come and go. */
    static boolean coverMayDrop(long now, long holdUntil) {
        return now >= holdUntil;
    }

    /** Trimmed, case-insensitive exact match, so the "Home" tab does not match "My Home". */
    static boolean matchesLabel(CharSequence candidate, String label) {
        if (candidate == null || label == null) return false;
        String c = candidate.toString().trim();
        return !c.isEmpty() && c.equalsIgnoreCase(label.trim());
    }
}
