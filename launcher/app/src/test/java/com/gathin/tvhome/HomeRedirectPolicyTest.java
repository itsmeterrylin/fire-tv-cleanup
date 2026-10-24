package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeRedirectPolicyTest {

    private static final String OWN = "com.gathin.tvhome";

    @Test
    public void actsOnPlainHomeEvent() {
        assertTrue(new HomeRedirectPolicy().shouldAct(1000));
    }

    @Test
    public void settingsGraceBlocksThenExpires() {
        HomeRedirectPolicy p = new HomeRedirectPolicy();
        p.onSettingsSeen(10_000);
        assertFalse(p.shouldAct(10_200));
        assertFalse(p.shouldAct(11_999));
        assertTrue(p.shouldAct(12_000));
    }

    @Test
    public void secondEventWithinDedupeIsIgnored() {
        HomeRedirectPolicy p = new HomeRedirectPolicy();
        assertTrue(p.shouldAct(5000));
        assertFalse(p.shouldAct(5200));
        assertFalse(p.shouldAct(6499));
        assertTrue(p.shouldAct(6500));
    }

    @Test
    public void loopGuardStopsAfterThreeActsThenResumes() {
        HomeRedirectPolicy p = new HomeRedirectPolicy();
        assertTrue(p.shouldAct(0));
        assertTrue(p.shouldAct(2000));
        assertTrue(p.shouldAct(4000));
        assertFalse(p.takeLoopTripped());
        assertFalse(p.shouldAct(6000));          // fourth within 10 s: trips
        assertTrue(p.takeLoopTripped());
        assertFalse(p.takeLoopTripped());
        assertFalse(p.shouldAct(30_000));        // cooling down
        assertFalse(p.shouldAct(65_999));
        assertTrue(p.shouldAct(66_000));         // cooldown (60 s from 6000) over
    }

    @Test
    public void slowActsDoNotTripLoopGuard() {
        HomeRedirectPolicy p = new HomeRedirectPolicy();
        for (int i = 0; i < 10; i++) assertTrue(p.shouldAct(i * 5000L));
    }

    @Test
    public void labelMatchIsTrimmedCaseInsensitiveExact() {
        assertTrue(HomeRedirectPolicy.matchesLabel("My Home", "My Home"));
        assertTrue(HomeRedirectPolicy.matchesLabel("  my home ", "My Home"));
        assertFalse(HomeRedirectPolicy.matchesLabel("Home", "My Home"));
        assertFalse(HomeRedirectPolicy.matchesLabel("My Home screen", "My Home"));
        assertFalse(HomeRedirectPolicy.matchesLabel("My Stuff", "My Home"));
        assertFalse(HomeRedirectPolicy.matchesLabel(null, "My Home"));
        assertFalse(HomeRedirectPolicy.matchesLabel("", "My Home"));
    }

    @Test
    public void bootGuardFiresForUnrelatedAppEarlyWithoutKey() {
        assertTrue(HomeRedirectPolicy.shouldBootGuard(60_000, false, "com.nst.iptvsmarterstvbox", OWN));
    }

    @Test
    public void bootGuardOffAfterKeyOrWindow() {
        assertFalse(HomeRedirectPolicy.shouldBootGuard(60_000, true, "com.nst.iptvsmarterstvbox", OWN));
        assertFalse(HomeRedirectPolicy.shouldBootGuard(HomeRedirectPolicy.BOOT_WINDOW_MS, false, "com.nst.iptvsmarterstvbox", OWN));
        assertTrue(HomeRedirectPolicy.shouldBootGuard(HomeRedirectPolicy.BOOT_WINDOW_MS - 1, false, "com.nst.iptvsmarterstvbox", OWN));
    }

    @Test
    public void bootGuardIgnoresOwnAmazonAndSystemPackages() {
        for (String pkg : new String[]{OWN, "com.amazon.tv.launcher", "com.amazon.tv.settings",
                "com.amazon.tv.settings.v2", "com.amazon.tv.quicksettings", "android", "com.android.systemui", null}) {
            assertFalse(String.valueOf(pkg), HomeRedirectPolicy.shouldBootGuard(1000, false, pkg, OWN));
        }
    }

    @Test
    public void bootCoverHoldsUntilItsTime() {
        assertFalse(HomeRedirectPolicy.coverMayDrop(1000, 3000));
        assertTrue(HomeRedirectPolicy.coverMayDrop(3000, 3000));
        assertTrue(HomeRedirectPolicy.coverMayDrop(5000, 0));   // no hold set
    }
}
