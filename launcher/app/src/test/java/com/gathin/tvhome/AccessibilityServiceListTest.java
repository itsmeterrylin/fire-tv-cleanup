package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccessibilityServiceListTest {

    private static final String ME = "com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService";
    private static final String OTHER = "com.other/com.other.Svc";

    @Test
    public void addToNullOrEmpty() {
        assertEquals(ME, AccessibilityServiceList.add(null, ME));
        assertEquals(ME, AccessibilityServiceList.add("", ME));
        assertEquals(ME, AccessibilityServiceList.add(":", ME));
    }

    @Test
    public void addKeepsOthersAndIsIdempotent() {
        String once = AccessibilityServiceList.add(OTHER, ME);
        assertEquals(OTHER + ":" + ME, once);
        assertEquals(once, AccessibilityServiceList.add(once, ME));
    }

    @Test
    public void addCollapsesDuplicates() {
        assertEquals(OTHER + ":" + ME, AccessibilityServiceList.add(OTHER + ":" + OTHER, ME));
    }

    @Test
    public void removeKeepsOthers() {
        assertEquals(OTHER, AccessibilityServiceList.remove(OTHER + ":" + ME, ME));
        assertEquals(OTHER, AccessibilityServiceList.remove(ME + ":" + OTHER, ME));
    }

    @Test
    public void removeLastGivesEmpty() {
        assertEquals("", AccessibilityServiceList.remove(ME, ME));
        assertEquals("", AccessibilityServiceList.remove(null, ME));
        assertEquals("", AccessibilityServiceList.remove("", ME));
    }

    @Test
    public void removeDropsDuplicatesOfMe() {
        assertEquals(OTHER, AccessibilityServiceList.remove(ME + ":" + OTHER + ":" + ME, ME));
    }

    @Test
    public void containsIsExactNotSubstring() {
        assertTrue(AccessibilityServiceList.contains(OTHER + ":" + ME, ME));
        assertFalse(AccessibilityServiceList.contains(OTHER, ME));
        assertFalse(AccessibilityServiceList.contains(null, ME));
        assertFalse(AccessibilityServiceList.contains(ME + "X", ME));
    }

    @Test
    public void shortFormCountsAsSame() {
        String shortForm = "com.gathin.tvhome/.HomeRedirectService";
        String flat = "com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService";
        assertTrue(AccessibilityServiceList.contains(shortForm, flat));
        assertEquals(OTHER, AccessibilityServiceList.remove(shortForm + ":" + OTHER, flat));
    }

    @Test
    public void whitespaceIsTrimmed() {
        assertEquals(OTHER + ":" + ME, AccessibilityServiceList.add(" " + OTHER + " ", ME));
    }
}
