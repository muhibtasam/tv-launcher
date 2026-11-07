package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which notifications reach the Control Center's "Google" section, and in what order (plain Java, tested in
 * tools/tests/AlertsLogicTest). GoogleAlerts (the notification listener) feeds it; only Google's own TV apps are kept,
 * and only real alerts (no playback, progress, ongoing or service notifications).
 */
public final class AlertsLogic {
    private AlertsLogic() {}

    /** Google's TV apps (V1: nothing else, whatever else is installed). */
    public static final Set<String> GOOGLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.google.android.youtube.tv",           // YouTube
            "com.google.android.youtube.tvkids",       // YouTube Kids
            "com.google.android.youtube.tvmusic",      // YouTube Music
            "com.google.android.youtube.tvunplugged",  // YouTube TV
            "com.android.vending",                     // Play Store
            "com.google.android.gms",                  // Play services (account, Find My Device)
            "com.google.android.apps.tv.launcherx",    // Google TV
            "com.google.android.tvlauncher",           // Android TV home
            "com.google.android.videos",               // Google TV / Play Movies
            "com.google.android.apps.mediashell",      // Cast
            "com.google.android.katniss",              // Assistant
            "com.google.android.tv",                   // Live Channels
            "com.google.android.apps.tv.dreamx",       // Ambient mode
            "com.google.android.apps.chromecast.app"   // Google Home
    )));
    static final int STORE_MAX = 40;

    public static final class Alert {
        public final String key, pkg, title, text;
        public final long when;

        public Alert(String key, String pkg, String title, String text, long when) {
            this.key = key;
            this.pkg = pkg;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.when = when;
        }
    }

    /** A notification worth showing: from a Google app, a one-off alert with something to read. */
    public static boolean keep(String pkg, boolean ongoing, boolean groupSummary, boolean foregroundService,
                               String category, boolean hasProgress, String title, String text) {
        if (pkg == null || !GOOGLE.contains(pkg)) return false;
        if (ongoing || groupSummary || foregroundService || hasProgress) return false;
        if (category != null && (category.equals("transport") || category.equals("progress") || category.equals("service")
                || category.equals("sys") || category.equals("call"))) return false;
        return !fold(title).isEmpty() || !fold(text).isEmpty();
    }

    /** Adds or replaces one (by key), newest first; the store keeps the newest 40. False when it has no key. */
    public static boolean post(List<Alert> list, Alert a) {
        if (a == null || a.key == null) return false;
        remove(list, a.key);
        int at = 0;
        while (at < list.size() && list.get(at).when >= a.when) at++;
        list.add(at, a);
        while (list.size() > STORE_MAX) list.remove(list.size() - 1);
        return true;
    }

    public static void remove(List<Alert> list, String key) {
        if (key == null) return;
        for (int i = list.size() - 1; i >= 0; i--) if (key.equals(list.get(i).key)) list.remove(i);
    }

    public static Alert find(List<Alert> list, String key) {
        if (key == null) return null;
        for (Alert a : list) if (key.equals(a.key)) return a;
        return null;
    }

    /** What the Control Center shows: newest first, without the ones cleared here, at most `max`. */
    public static List<Alert> visible(List<Alert> list, Set<String> dismissed, int max) {
        List<Alert> out = new ArrayList<>();
        for (Alert a : list) {
            if (out.size() >= max) break;
            if (!dismissed.contains(a.key)) out.add(a);
        }
        return out;
    }

    /** Something arrived after the list was last seen (the orange dot on the Control Center button). */
    public static boolean unread(List<Alert> list, Set<String> dismissed, long seenAt) {
        return unreadCount(list, dismissed, seenAt) > 0;
    }

    public static int unreadCount(List<Alert> list, Set<String> dismissed, long seenAt) {
        int n = 0;
        for (Alert a : list) if (a.when > seenAt && !dismissed.contains(a.key)) n++;
        return n;
    }

    /** The row's line: "title: text", or whichever of them there is, on one line. */
    public static String line(Alert a) {
        String t = fold(a.title), x = fold(a.text);
        if (t.isEmpty()) return x;
        if (x.isEmpty() || x.equals(t)) return t;
        return t + ": " + x;
    }

    private static String fold(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
