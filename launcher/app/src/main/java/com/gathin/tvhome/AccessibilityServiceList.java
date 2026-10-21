package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits Settings.Secure.enabled_accessibility_services (colon-separated component names) without touching the
 * other services in it. No Android dependencies so it can be unit tested.
 */
final class AccessibilityServiceList {

    private AccessibilityServiceList() {
    }

    /** True when the component (flattened, "pkg/pkg.Class") is in the list. Short forms ("pkg/.Class") count. */
    static boolean contains(String list, String component) {
        String want = normalize(component);
        for (String entry : entries(list)) if (normalize(entry).equals(want)) return true;
        return false;
    }

    /** The list with the component added once, others kept in order. */
    static String add(String list, String component) {
        List<String> out = entries(list);
        if (!contains(list, component)) out.add(component.trim());
        return String.join(":", out);
    }

    /** The list without the component (all spellings of it), others kept in order. */
    static String remove(String list, String component) {
        String want = normalize(component);
        List<String> out = new ArrayList<>();
        for (String entry : entries(list)) if (!normalize(entry).equals(want)) out.add(entry);
        return String.join(":", out);
    }

    /** Non-empty, trimmed entries, duplicates dropped. */
    private static List<String> entries(String list) {
        List<String> out = new ArrayList<>();
        if (list == null) return out;
        for (String raw : list.split(":")) {
            String e = raw.trim();
            if (!e.isEmpty() && !out.contains(e)) out.add(e);
        }
        return out;
    }

    /** "pkg/.Class" to "pkg/pkg.Class". */
    private static String normalize(String component) {
        String c = component.trim();
        int slash = c.indexOf('/');
        if (slash > 0 && slash + 1 < c.length() && c.charAt(slash + 1) == '.') {
            return c.substring(0, slash) + "/" + c.substring(0, slash) + c.substring(slash + 1);
        }
        return c;
    }
}
