package io.vyro.tv;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Predicate;

/**
 * Home's Continue row, without Android: which of the titles opened from the cards it shows, and their line. The TV
 * lets no app read where you stopped (only its own home screen may), so the line says when you opened the title, never
 * a made-up progress; the app itself resumes where you left off.
 */
public final class ContinueLogic {
    /** Cards in the row, and how long a title stays in it after it was opened. */
    public static final int MAX = 8;
    public static final long WINDOW_MS = 30L * 24 * 3600_000L;

    private ContinueLogic() {
    }

    /** One title from the history (Recs.history, newest first). */
    public static final class Entry {
        public final int id;
        public final String kind, app;
        public final long at;

        public Entry(int id, String kind, String app, long at) {
            this.id = id;
            this.kind = kind;
            this.app = app;
            this.at = at;
        }
    }

    /**
     * The row: newest first, at most MAX, each title once, opened in the last 30 days, its app still installed. A
     * broken entry (no id, an unknown kind, no time) is skipped.
     */
    public static List<Entry> select(List<Entry> history, long now, Predicate<String> installed) {
        List<Entry> out = new ArrayList<>();
        if (history == null) return out;
        Set<String> seen = new HashSet<>();
        for (Entry e : history) {
            if (out.size() >= MAX) break;
            if (e == null || e.id <= 0 || e.at <= 0 || !("movie".equals(e.kind) || "tv".equals(e.kind))) continue;
            if (now - e.at > WINDOW_MS) continue;
            if (e.app == null || e.app.isEmpty() || !installed.test(e.app)) continue;
            if (seen.add(e.kind + ":" + e.id)) out.add(e);
        }
        return out;
    }

    /** The card's line: "S2 · Opened yesterday", or for a movie just when it was opened. */
    public static String line(String kind, int season, long at, long now, TimeZone zone) {
        String opened = opened(at, now, zone);
        return "tv".equals(kind) && season > 0 ? Tr.trf("S{season}", "season", season) + " · " + opened : opened;
    }

    /** "Opened just now", "5 min ago", "2 h ago" (today), "yesterday", a weekday this week, else the date. */
    public static String opened(long at, long now, TimeZone zone) {
        long ago = now - at;
        if (ago < 60_000L) return Tr.tr("Opened just now");   // the TV's clock moved back too: no "in the future"
        if (ago < 3600_000L) return Tr.trf("Opened {n} min ago", "n", ago / 60_000L);
        Calendar then = Calendar.getInstance(zone, Locale.US), today = Calendar.getInstance(zone, Locale.US);
        then.setTimeInMillis(at);
        today.setTimeInMillis(now);
        int days = dayNumber(today) - dayNumber(then);
        if (days == 0) return Tr.trf("Opened {n} h ago", "n", ago / 3600_000L);
        if (days == 1) return Tr.tr("Opened yesterday");
        if (days < 7) return Tr.trf("Opened {day}", "day", Tr.format("EEE", at, zone));
        return Tr.trf("Opened {date}", "date", Tr.datePattern("format|MMM d", at, zone));
    }

    /** Days since 1970 in the calendar's own zone (whole local days, so midnight is where "yesterday" starts). */
    private static int dayNumber(Calendar c) {
        long local = c.getTimeInMillis() + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET);
        return (int) Math.floorDiv(local, 86_400_000L);
    }

    // MARK: - Removed titles (hold OK → Remove from Continue Watching)

    /**
     * How a removed title is saved: its key and, for Watch Next, the last-watched time and position the app reported
     * when it was removed ("pkg|title@1791036610265:629000"). Card history has neither: the key alone.
     */
    public static String hiddenEntry(String key, long engaged, long position) {
        return engaged > 0 ? key + "@" + engaged + ":" + Math.max(0, position) : key;
    }

    /**
     * Still removed? Until it's genuinely watched again: its app reports a newer last-watched time AND a different
     * position (an app only re-stamping the time when opened, as Crunchyroll does, isn't viewing). An entry saved
     * without a time (card history, or saved before times were kept) stays hidden only for titles that have no time
     * either: a Watch Next title saved that way comes back.
     */
    public static boolean stillHidden(Set<String> entries, String key, long engaged, long position) {
        if (key == null) return false;
        for (String e : entries) {
            if (e.equals(key)) return engaged <= 0;
            long[] at = savedOf(e, key);
            if (at != null) return engaged <= at[0] || (at[1] >= 0 && position == at[1]);
        }
        return false;
    }

    /** The entries without this title's (any time): before saving it again, or when it's opened again. */
    public static Set<String> without(Set<String> entries, String key) {
        Set<String> out = new HashSet<>();
        for (String e : entries) if (!e.equals(key) && savedOf(e, key) == null) out.add(e);
        return out;
    }

    /** {time, position} from "key@time:position" ("key@time": position -1), or null when the entry isn't this key's. */
    private static long[] savedOf(String entry, String key) {
        if (!entry.startsWith(key) || entry.length() <= key.length() + 1 || entry.charAt(key.length()) != '@') return null;
        String rest = entry.substring(key.length() + 1);
        int colon = rest.indexOf(':');
        try {
            return colon < 0 ? new long[] {Long.parseLong(rest), -1}
                    : new long[] {Long.parseLong(rest.substring(0, colon)), Long.parseLong(rest.substring(colon + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
