package io.freemytv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What's New, without Android (tools/tests/ArrivalsLogicTest): which titles arrived on a streaming service in the
 * country (a daily snapshot of the service's catalogue there, compared with the previous one), and which streaming
 * label a title earns from its own dates. The rule everywhere: when the data can't prove it, no label.
 *
 * A title is "new on" a service when it's in today's complete snapshot and was in none since the first one (the
 * baseline, which labels nothing). An incomplete snapshot is never kept; one too long after the last, or one that
 * shrank or grew implausibly, only becomes the new reference, without arrivals.
 */
public final class ArrivalsLogic {
    private ArrivalsLogic() {}

    public static final long DAY = 24 * 3600_000L;
    /** A snapshot this long after the previous one can't date its additions: they're taken in without a label. */
    public static final long MAX_GAP = 3 * DAY;
    /** An arrival keeps its label this long. */
    public static final long LABEL_FOR = 7 * DAY;
    /** A snapshot counts as complete when every page came and it holds this share of the total the service reported. */
    public static final double MIN_SHARE = 0.95;
    /** More additions than this in one day is a data change, not new titles. */
    public static final int ANOMALY_MIN = 40;
    public static final double ANOMALY_SHARE = 0.10;
    /** A catalogue that lost more than this share since the previous snapshot is treated as a bad read. */
    public static final double SHRINK_LIMIT = 0.90;

    // Labels, strongest first when a title has several.
    public static final int NONE = 0, NEW_EPISODE = 1, NEW_SEASON = 2, PREMIERE = 3, NEW_ON = 4;
    /** Release days counted as "this week": today and the 6 days before. */
    public static final int WINDOW_DAYS = 7;

    /** One service's catalogue in the country at one moment: "movie:123", "tv:456". */
    public static final class Snapshot {
        public final long takenAt;
        public final Set<String> ids;

        public Snapshot(long takenAt, Collection<String> ids) {
            this.takenAt = takenAt;
            this.ids = new HashSet<>(ids);
        }
    }

    /** What's kept per service across restarts. */
    public static final class State {
        public Snapshot last;                                       // the reference for the next comparison
        public final Set<String> seen = new HashSet<>();            // every title seen there since the baseline
        public final Map<String, Long> arrivals = new HashMap<>();  // title → when it was found, for LABEL_FOR
        public long baselineAt;
    }

    /** Why a snapshot was or wasn't used (logged). */
    public static final String BASELINE = "baseline", COMPARED = "compared", INCOMPLETE = "incomplete", STALE = "stale",
            SHRANK = "shrank", ANOMALY = "anomaly", CLOCK = "clock";

    public static final class Result {
        public final State state;
        public final List<String> added;
        public final String reason;

        public Result(State state, List<String> added, String reason) {
            this.state = state;
            this.added = added;
            this.reason = reason;
        }
    }

    /** Every page was read and the titles cover MIN_SHARE of what the service reported. */
    public static boolean complete(int pagesRead, int pagesWanted, int titles, int reportedTotal) {
        return pagesWanted > 0 && pagesRead == pagesWanted && reportedTotal > 0 && titles >= MIN_SHARE * reportedTotal;
    }

    /** The state after a new snapshot (a copy: `prev` is never changed). */
    public static Result apply(State prev, Snapshot cur, boolean complete, long now) {
        if (!complete || cur == null) return new Result(prev, new ArrayList<>(), INCOMPLETE);
        if (prev == null || prev.last == null) {
            State s = new State();
            s.last = cur;
            s.seen.addAll(cur.ids);
            s.baselineAt = cur.takenAt;
            return new Result(s, new ArrayList<>(), BASELINE);
        }
        long gap = cur.takenAt - prev.last.takenAt;
        if (gap <= 0) return new Result(prev, new ArrayList<>(), CLOCK);
        if (cur.ids.size() < SHRINK_LIMIT * prev.last.ids.size()) return new Result(prev, new ArrayList<>(), SHRANK);
        State s = copy(prev);
        List<String> added = new ArrayList<>();
        for (String id : cur.ids) if (!prev.seen.contains(id)) added.add(id);
        String reason = COMPARED;
        if (gap > MAX_GAP) {
            reason = STALE;
        } else if (added.size() > Math.max(ANOMALY_MIN, ANOMALY_SHARE * prev.last.ids.size())) {
            reason = ANOMALY;
        } else {
            for (String id : added) s.arrivals.put(id, cur.takenAt);
        }
        s.last = cur;
        s.seen.addAll(cur.ids);
        prune(s, now);
        return new Result(s, reason.equals(COMPARED) ? added : new ArrayList<>(), reason);
    }

    /** Drops labels older than LABEL_FOR and arrivals no longer on the service. */
    public static void prune(State s, long now) {
        s.arrivals.entrySet().removeIf(e -> now - e.getValue() > LABEL_FOR || (s.last != null && !s.last.ids.contains(e.getKey())));
    }

    private static State copy(State p) {
        State s = new State();
        s.last = p.last;
        s.seen.addAll(p.seen);
        s.arrivals.putAll(p.arrivals);
        s.baselineAt = p.baselineAt;
        return s;
    }

    /**
     * Current arrivals across services, each title once: newest first; a title new on several services goes to the
     * first of them in `order` (the TV's services in their usual order). Value: {service, detected at}.
     */
    public static LinkedHashMap<String, Object[]> arrivals(Map<String, State> byService, List<String> order, long now) {
        Map<String, Object[]> best = new HashMap<>();
        for (String service : order) {
            State s = byService.get(service);
            if (s == null) continue;
            for (Map.Entry<String, Long> e : s.arrivals.entrySet()) {
                if (now - e.getValue() > LABEL_FOR) continue;
                if (!best.containsKey(e.getKey())) best.put(e.getKey(), new Object[] {service, e.getValue()});
            }
        }
        List<Map.Entry<String, Object[]>> list = new ArrayList<>(best.entrySet());
        list.sort((a, b) -> {
            int c = Long.compare((Long) b.getValue()[1], (Long) a.getValue()[1]);
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        LinkedHashMap<String, Object[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object[]> e : list) out.put(e.getKey(), e.getValue());
        return out;
    }

    // MARK: - Labels from a title's own dates

    /** One entry of TMDB's release_dates: a country, its type (1 premiere, 2 limited, 3 theatrical, 4 digital, 5 physical, 6 TV), a day. */
    public static final class Release {
        public final String country;
        public final int type;
        public final String date;

        public Release(String country, int type, String date) {
            this.country = country;
            this.type = type;
            this.date = date == null ? "" : date.length() > 10 ? date.substring(0, 10) : date;
        }
    }

    /** "2026-10-30" is in the window ending `today` (WINDOW_DAYS days, today included). */
    public static boolean inWindow(String date, String today) {
        if (date == null || date.length() < 10 || today == null) return false;
        try {
            LocalDate d = LocalDate.parse(date.substring(0, 10)), t = LocalDate.parse(today);
            return !d.isAfter(t) && d.isAfter(t.minusDays(WINDOW_DAYS));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * A movie's streaming premiere this week: its digital release in the country (type 4), or its first release when
     * the movie never had a premiere or theatrical one anywhere (made for streaming). A theatrical date never counts.
     */
    public static boolean streamingPremiere(String primary, List<Release> releases, String region, String today) {
        boolean theatrical = false;
        for (Release r : releases) {
            if (r.type == 4 && region.equals(r.country) && inWindow(r.date, today)) return true;
            if (r.type >= 1 && r.type <= 3) theatrical = true;
        }
        return !theatrical && !releases.isEmpty() && inWindow(primary, today);
    }

    /**
     * A show's label from its latest aired episode and the start of that episode's season: the season started this
     * week → its first season is a streaming premiere, a later one a new season; else an episode out this week.
     */
    public static int showLabel(int season, String lastAired, String seasonStart, String today) {
        if (inWindow(seasonStart, today)) return season <= 1 ? PREMIERE : NEW_SEASON;
        return inWindow(lastAired, today) ? NEW_EPISODE : NONE;
    }

    // MARK: - Storage (plain text, so the tests can read and write it)

    /** One service's state as text: four lines. */
    public static String write(State s) {
        StringBuilder b = new StringBuilder("v1\t").append(s.last == null ? 0 : s.last.takenAt).append('\t').append(s.baselineAt).append('\n');
        b.append("last\t").append(s.last == null ? "" : String.join(",", sorted(s.last.ids))).append('\n');
        b.append("seen\t").append(String.join(",", sorted(s.seen))).append('\n');
        b.append("arrivals\t");
        boolean first = true;
        for (String id : sorted(s.arrivals.keySet())) {
            if (!first) b.append(',');
            b.append(id).append('=').append(s.arrivals.get(id));
            first = false;
        }
        return b.append('\n').toString();
    }

    /** The state back from write(), or null when the text isn't one (a damaged file starts a new baseline). */
    public static State read(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 4 || !lines[0].startsWith("v1\t") || !lines[1].startsWith("last\t") || !lines[2].startsWith("seen\t")
                || !lines[3].startsWith("arrivals\t")) return null;
        try {
            String[] head = lines[0].split("\t");
            long takenAt = Long.parseLong(head[1]);
            State s = new State();
            s.baselineAt = Long.parseLong(head[2]);
            if (takenAt > 0) s.last = new Snapshot(takenAt, ids(lines[1].substring(5)));
            s.seen.addAll(ids(lines[2].substring(5)));
            for (String part : lines[3].substring(9).split(",")) {
                if (part.isEmpty()) continue;
                int eq = part.lastIndexOf('=');
                s.arrivals.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
            }
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> ids(String csv) {
        List<String> out = new ArrayList<>();
        for (String id : csv.split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    private static List<String> sorted(Collection<String> c) {
        List<String> l = new ArrayList<>(c);
        java.util.Collections.sort(l);
        return l;
    }
}
