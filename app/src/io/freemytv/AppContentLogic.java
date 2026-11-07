package io.freemytv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Home's titles straight from the streaming apps, without Android (tools/tests/AppContentLogicTest): what the apps
 * publish to Android TV's own lists (TvProvider: Watch Next, their home screen channels and the programs in them), read
 * with READ_TV_LISTINGS by AppContent. Which apps count, which rows are kept, duplicates, which links may be opened, and
 * what a failed or empty read keeps. Nothing here invents a title: every record is one an app published.
 */
public final class AppContentLogic {
    private AppContentLogic() {}

    /** One title as an app published it (fields as TvContract names them; null when the app left them out). */
    public static final class Record {
        public String pkg, channel, title, art, link, episode, contentId;
        public int type = -1;            // TvContract's TYPE_* (movie 0, series 1, season 2, episode 3, clip 4…)
        public long position, duration, engaged;
        public boolean watchNext;        // from Watch Next (Continue Watching), not a recommendation channel
        public long readAt;              // when it was read (kept with the cache: a source's age)

        public String service() {
            return SOURCES.get(pkg);
        }
    }

    /** A home screen channel: its app and name. */
    public static final class Channel {
        public final String pkg, name;

        public Channel(String pkg, String name) {
            this.pkg = pkg;
            this.name = name;
        }
    }

    /**
     * The apps whose recommendation channels fill Home's grid, by package → service key (the key the cards' logos and
     * the mix use). Verified on the owner's TV (read-only probe, 2026-11-02): Prime Video's "Prime Video" channel and
     * Crunchyroll's "Most Popular" carry titles with artwork and the app's own link. Not here, on purpose: YouTube
     * (videos), Spotify (music), TCL's rows (app promotions, live TV), Netflix (publishes nothing readable), tapmad and
     * myco (no Android TV integration). Stremio and Nuvio publish only "Continue Watching", which the Continue row reads.
     */
    public static final Map<String, String> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("com.amazon.amazonvideo.livingroom", "prime");
        SOURCES.put("com.amazon.avod.thirdpartyclient", "prime");
        SOURCES.put("com.crunchyroll.crunchyroid", "crunchyroll");
    }

    /** Channel names that mean "continue watching": those rows belong to the Continue row, not the grid. */
    private static final Set<String> CONTINUE = new HashSet<>(java.util.Arrays.asList(
            "continue watching", "seguir viendo", "continuar viendo", "continuar assistindo", "continue assistindo"));

    /** A source with no rows in a read keeps its previous titles this long (an app clearing and refilling its row). */
    public static final long KEEP = 3 * 24 * 3600_000L;

    // MARK: - Parsing (rows as the cursor gave them: column name → text, null for SQL null)

    public static Record watchNext(Map<String, String> row, long now) {
        Record r = base(row, now);
        r.watchNext = true;
        r.channel = "Watch Next";
        r.position = num(row.get("last_playback_position_millis"));
        r.duration = num(row.get("duration_millis"));
        r.engaged = num(row.get("last_engagement_time_utc_millis"));
        String ep = text(row.get("episode_display_number"));
        r.episode = ep;
        return r;
    }

    public static Record preview(Map<String, String> row, Map<Long, Channel> channels, long now) {
        Record r = base(row, now);
        Channel c = channels.get(num(row.get("channel_id")));
        r.channel = c == null ? null : c.name;
        if (r.pkg == null && c != null) r.pkg = c.pkg;
        return r;
    }

    private static Record base(Map<String, String> row, long now) {
        Record r = new Record();
        r.pkg = text(row.get("package_name"));
        r.title = text(row.get("title"));
        String poster = text(row.get("poster_art_uri")), thumb = text(row.get("thumbnail_uri"));
        r.art = thumb != null ? thumb : poster;   // a 16:9 card: the thumbnail (usually 16:9) before the poster
        r.link = text(row.get("intent_uri"));
        r.contentId = text(row.get("content_id"));
        String t = text(row.get("type"));
        r.type = t == null ? -1 : (int) num(t);
        r.readAt = now;
        return r;
    }

    private static String text(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static long num(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // MARK: - Choosing

    /** Is this a row Home's grid may show? (A supported, installed app; a recommendation channel; a usable card.) */
    public static boolean usable(Record r, Set<String> installed) {
        if (r == null || r.pkg == null || r.service() == null || !installed.contains(r.pkg)) return false;
        if (r.watchNext) return false;   // the Continue row's
        if (r.channel != null && CONTINUE.contains(r.channel.trim().toLowerCase(Locale.ROOT))) return false;
        return r.title != null && r.art != null && r.link != null && linkAllowed(r.link, r.pkg);
    }

    /** "The Terminal List  - Season 2 " → "the terminal list - season 2" (case, spaces; the words stay). */
    public static String normTitle(String t) {
        return t == null ? "" : t.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The usable rows, in the order read, each title once per app (the same title on two apps stays twice). */
    public static List<Record> select(List<Record> rows, Set<String> installed) {
        List<Record> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Record r : rows) {
            if (!usable(r, installed)) continue;
            if (!seen.add(r.pkg + "|" + normTitle(r.title))) continue;
            out.add(r);
        }
        return out;
    }

    /**
     * After a read: each source's new rows; a source that came back empty keeps its previous ones while its app is
     * installed and they're under KEEP old; an app removed takes its titles with it. A failed read isn't merged at
     * all (the caller keeps everything as it was).
     */
    public static List<Record> merge(List<Record> previous, List<Record> current, Set<String> installed, long now) {
        Map<String, List<Record>> cur = bySource(current), prev = bySource(previous);
        Set<String> order = new java.util.LinkedHashSet<>(cur.keySet());
        order.addAll(prev.keySet());
        List<Record> out = new ArrayList<>();
        for (String pkg : order) {
            if (!installed.contains(pkg)) continue;
            List<Record> rows = cur.get(pkg);
            if (rows == null || rows.isEmpty()) {
                rows = new ArrayList<>();
                for (Record r : prev.getOrDefault(pkg, new ArrayList<>())) {
                    long age = now - r.readAt;
                    if (age >= 0 && age < KEEP) rows.add(r);
                }
            }
            out.addAll(rows);
        }
        return out;
    }

    private static Map<String, List<Record>> bySource(List<Record> rows) {
        Map<String, List<Record>> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (Record r : rows) out.computeIfAbsent(r.pkg, k -> new ArrayList<>()).add(r);
        return out;
    }

    /**
     * The records' identity as a set (app + title, sorted): a title added, removed or moved to another app mixes the
     * grid again; the apps reordering their rows or re-issuing a title's link (Prime Video does when it opens) doesn't.
     */
    public static String signature(List<Record> rows) {
        List<String> lines = new ArrayList<>();
        for (Record r : rows) lines.add(r.pkg + "|" + normTitle(r.title));
        java.util.Collections.sort(lines);
        return String.join("\n", lines);
    }

    /** Why a read mixes again, for the log: "+2 -1 titles" (added, removed) or "new part of the day". */
    public static String why(String oldSignature, String newSignature, int oldPeriod, int newPeriod) {
        Set<String> was = new HashSet<>(java.util.Arrays.asList(oldSignature.split("\n"))), now = new HashSet<>(java.util.Arrays.asList(newSignature.split("\n")));
        int added = 0, removed = 0;
        for (String s : now) if (!s.isEmpty() && !was.contains(s)) added++;
        for (String s : was) if (!s.isEmpty() && !now.contains(s)) removed++;
        return added + removed > 0 ? "+" + added + " -" + removed + " titles" : oldPeriod != newPeriod ? "new part of the day" : "no change";
    }

    /** After a failed read, the next waits this long (a read that keeps failing isn't repeated every few seconds). */
    public static final long RETRY = 5 * 60_000L;

    /** May a read start now? Always, unless the last one failed less than RETRY ago (a clock going back doesn't wait). */
    public static boolean mayRead(long failedAt, long now) {
        long since = now - failedAt;
        return failedAt == 0 || since < 0 || since >= RETRY;
    }

    /**
     * Whether a read starts a new mix: something changed in what the apps publish, or a new part of the day began
     * (the grid otherwise stays as it is: navigation, Home presses and restarts show the same one).
     */
    public static boolean reseed(String oldSignature, String newSignature, int oldPeriod, int newPeriod) {
        return !newSignature.equals(oldSignature) || oldPeriod != newPeriod;
    }

    // MARK: - Links

    /**
     * May this link be opened for `pkg`? Only links that stay inside the app that published them: an intent: link
     * naming another package or component, carrying a selector, or file/content/javascript/data links are refused. A
     * plain link (crunchyroll://…, https://…) is allowed and opened restricted to `pkg` (AppContent.intent).
     */
    public static boolean linkAllowed(String link, String pkg) {
        if (link == null || pkg == null || link.trim().isEmpty()) return false;
        String l = link.trim();
        String lower = l.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("javascript:") || lower.startsWith("data:")) return false;
        int hash = l.indexOf("#Intent;");
        if (lower.startsWith("intent:") || hash >= 0) {
            if (hash < 0) return false;
            String body = l.substring(hash + "#Intent;".length());
            if (body.contains("SEL;") || !body.endsWith("end") && !body.contains(";end")) return false;
            String target = null;
            for (String part : body.split(";")) {
                if (part.startsWith("package=")) {
                    if (!pkg.equals(part.substring("package=".length()))) return false;
                    target = pkg;
                } else if (part.startsWith("component=")) {
                    String comp = part.substring("component=".length());
                    int slash = comp.indexOf('/');
                    if (slash <= 0 || !pkg.equals(comp.substring(0, slash))) return false;
                    target = pkg;
                }
            }
            String scheme = l.substring(0, Math.max(0, l.indexOf(':'))).toLowerCase(Locale.ROOT);
            return target != null || !scheme.isEmpty();   // no package named: opened inside pkg only
        }
        return l.indexOf(':') > 0;   // a scheme: opened inside pkg only
    }

    // MARK: - The mix's input

    /** What MixLogic draws from: one item per record, the service as the provider, in the apps' own order. */
    public static List<MixLogic.Item> items(List<Record> rows) {
        List<MixLogic.Item> out = new ArrayList<>();
        Map<String, Integer> rank = new HashMap<>();
        for (Record r : rows) {
            int n = rank.merge(r.service(), 1, Integer::sum);
            out.add(new MixLogic.Item(r.pkg + "|" + normTitle(r.title), r.service(), n <= 4 ? 0 : 1));   // an app's first few first
        }
        return out;
    }
}
