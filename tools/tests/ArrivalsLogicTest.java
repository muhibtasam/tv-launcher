import io.freemytv.ArrivalsLogic;
import io.freemytv.ArrivalsLogic.Release;
import io.freemytv.ArrivalsLogic.Result;
import io.freemytv.ArrivalsLogic.Snapshot;
import io.freemytv.ArrivalsLogic.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ArrivalsLogic: daily catalogue snapshots → "New on {service}", and the labels a title earns from its dates. */
public class ArrivalsLogicTest {
    static int failures;
    static final long DAY = ArrivalsLogic.DAY, T0 = 1_790_000_000_000L;

    static void yes(String what, boolean v) { if (!v) { failures++; System.out.println("FAIL " + what); } }
    static void eq(String what, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) { failures++; System.out.println("FAIL " + what + ": want " + want + ", got " + got); }
    }

    static List<String> range(String kind, int from, int to) {
        List<String> l = new ArrayList<>();
        for (int i = from; i <= to; i++) l.add(kind + ":" + i);
        return l;
    }

    static List<String> plus(List<String> a, String... more) {
        List<String> l = new ArrayList<>(a);
        l.addAll(Arrays.asList(more));
        return l;
    }

    public static void main(String[] args) {
        List<String> base = range("movie", 1, 500);

        // First snapshot: the baseline, nothing new.
        Result r = ArrivalsLogic.apply(null, new Snapshot(T0, base), true, T0);
        eq("first snapshot is the baseline", ArrivalsLogic.BASELINE, r.reason);
        yes("baseline labels nothing", r.added.isEmpty() && r.state.arrivals.isEmpty());
        State s = r.state;

        // Next day: two titles appear.
        r = ArrivalsLogic.apply(s, new Snapshot(T0 + DAY, plus(base, "movie:900", "tv:77")), true, T0 + DAY);
        eq("next day compared", ArrivalsLogic.COMPARED, r.reason);
        eq("two new titles", 2, r.added.size());
        yes("new title has its arrival time", Long.valueOf(T0 + DAY).equals(r.state.arrivals.get("movie:900")));
        State s2 = r.state;

        // The day after: still there, not new again; the label stays (within a week).
        r = ArrivalsLogic.apply(s2, new Snapshot(T0 + 2 * DAY, plus(base, "movie:900", "tv:77")), true, T0 + 2 * DAY);
        yes("an existing title isn't new again", r.added.isEmpty());
        yes("earlier arrival keeps its label", r.state.arrivals.containsKey("movie:900"));
        eq("arrival keeps its first date", T0 + DAY, r.state.arrivals.get("movie:900"));

        // After LABEL_FOR, the label goes.
        r = ArrivalsLogic.apply(r.state, new Snapshot(T0 + 9 * DAY, plus(base, "movie:900", "tv:77")), true, T0 + 9 * DAY);
        yes("label expires after a week", !r.state.arrivals.containsKey("movie:900"));

        // A failed (incomplete) refresh keeps the previous state exactly.
        r = ArrivalsLogic.apply(s2, new Snapshot(T0 + 2 * DAY, plus(base, "movie:901")), false, T0 + 2 * DAY);
        eq("incomplete snapshot is ignored", ArrivalsLogic.INCOMPLETE, r.reason);
        yes("previous state preserved on failure", r.state == s2 && r.added.isEmpty());

        // Incomplete pagination: a page missing, or too few titles for the reported total.
        yes("a missing page is incomplete", !ArrivalsLogic.complete(9, 10, 200, 200));
        yes("too few titles for the total is incomplete", !ArrivalsLogic.complete(10, 10, 180, 200));
        yes("all pages and ≥95% is complete", ArrivalsLogic.complete(10, 10, 191, 200));
        yes("an empty catalogue is never complete", !ArrivalsLogic.complete(1, 1, 0, 0));

        // Stale: the previous snapshot is more than MAX_GAP old: new reference, no arrivals.
        r = ArrivalsLogic.apply(s, new Snapshot(T0 + 5 * DAY, plus(base, "movie:950")), true, T0 + 5 * DAY);
        eq("stale gap", ArrivalsLogic.STALE, r.reason);
        yes("stale gap labels nothing", r.added.isEmpty() && !r.state.arrivals.containsKey("movie:950"));
        yes("stale gap still remembers the title (not new later)", r.state.seen.contains("movie:950"));
        r = ArrivalsLogic.apply(r.state, new Snapshot(T0 + 6 * DAY, plus(base, "movie:950")), true, T0 + 6 * DAY);
        yes("title taken in by a stale gap isn't new the next day", r.added.isEmpty());

        // A catalogue that shrank a lot is a bad read: ignored.
        r = ArrivalsLogic.apply(s, new Snapshot(T0 + DAY, range("movie", 1, 300)), true, T0 + DAY);
        eq("shrunken catalogue ignored", ArrivalsLogic.SHRANK, r.reason);
        yes("shrunken catalogue keeps the old state", r.state == s);

        // Implausibly many additions: a data change, no labels.
        r = ArrivalsLogic.apply(s, new Snapshot(T0 + DAY, plus(base, range("tv", 1, 60).toArray(new String[0]))), true, T0 + DAY);
        eq("burst of additions is an anomaly", ArrivalsLogic.ANOMALY, r.reason);
        yes("anomaly labels nothing", r.state.arrivals.isEmpty());

        // A title that left and came back isn't "new" (it was seen before).
        State left = ArrivalsLogic.apply(s, new Snapshot(T0 + DAY, range("movie", 2, 500)), true, T0 + DAY).state;
        r = ArrivalsLogic.apply(left, new Snapshot(T0 + 2 * DAY, base), true, T0 + 2 * DAY);
        yes("a returning title isn't labelled", r.added.isEmpty());

        // Clock went back: ignored.
        eq("snapshot older than the reference is ignored", ArrivalsLogic.CLOCK,
                ArrivalsLogic.apply(s, new Snapshot(T0 - DAY, base), true, T0).reason);

        // Same title new on two services: one entry, the first service in order; newest first.
        State netflix = ArrivalsLogic.apply(ArrivalsLogic.apply(null, new Snapshot(T0, base), true, T0).state,
                new Snapshot(T0 + DAY, plus(base, "movie:900")), true, T0 + DAY).state;
        State prime = ArrivalsLogic.apply(ArrivalsLogic.apply(null, new Snapshot(T0, base), true, T0).state,
                new Snapshot(T0 + 2 * DAY, plus(base, "movie:900", "tv:5")), true, T0 + 2 * DAY).state;
        Map<String, State> all = new HashMap<>();
        all.put("netflix", netflix);
        all.put("prime", prime);
        LinkedHashMap<String, Object[]> a = ArrivalsLogic.arrivals(all, Arrays.asList("netflix", "prime"), T0 + 2 * DAY);
        eq("a title on two services appears once", 2, a.size());
        eq("first service in order wins", "netflix", a.get("movie:900")[0]);
        eq("newest arrival first", "tv:5", a.keySet().iterator().next());
        a = ArrivalsLogic.arrivals(all, Arrays.asList("prime", "netflix"), T0 + 2 * DAY);
        eq("order decides the primary service", "prime", a.get("movie:900")[0]);

        // Persistence: written and read back unchanged (a restart); damaged text starts over.
        State back = ArrivalsLogic.read(ArrivalsLogic.write(s2));
        yes("state survives a restart", back != null && back.last.takenAt == s2.last.takenAt
                && back.last.ids.equals(s2.last.ids) && back.seen.equals(s2.seen) && back.arrivals.equals(s2.arrivals)
                && back.baselineAt == s2.baselineAt);
        r = ArrivalsLogic.apply(back, new Snapshot(T0 + 2 * DAY, plus(base, "movie:900", "tv:77")), true, T0 + 2 * DAY);
        yes("after a restart, known titles aren't new again", r.added.isEmpty());
        yes("damaged file reads as nothing", ArrivalsLogic.read("garbage") == null && ArrivalsLogic.read(null) == null);
        State empty = new State();
        yes("an empty state round-trips", ArrivalsLogic.read(ArrivalsLogic.write(empty)) != null);

        // Movie labels: theatrical never, digital in the region yes, made-for-streaming yes, other country no.
        String today = "2026-11-02";
        List<Release> theatrical = Arrays.asList(new Release("PK", 3, "2026-10-30T00:00:00.000Z"), new Release("US", 3, "2026-10-29"));
        yes("theatrical release this week is not a streaming premiere", !ArrivalsLogic.streamingPremiere("2026-10-29", theatrical, "PK", today));
        List<Release> digitalPk = Arrays.asList(new Release("US", 3, "2026-08-01"), new Release("PK", 4, "2026-10-31"));
        yes("digital release in PK this week is a streaming premiere", ArrivalsLogic.streamingPremiere("2026-08-01", digitalPk, "PK", today));
        List<Release> digitalUs = Arrays.asList(new Release("US", 3, "2026-08-01"), new Release("US", 4, "2026-10-31"));
        yes("digital release elsewhere (not PK) doesn't count", !ArrivalsLogic.streamingPremiere("2026-08-01", digitalUs, "PK", today));
        List<Release> original = Arrays.asList(new Release("US", 4, "2026-10-31"), new Release("GB", 4, "2026-10-31"));
        yes("made for streaming, out this week: premiere", ArrivalsLogic.streamingPremiere("2026-10-31", original, "PK", today));
        List<Release> festival = Arrays.asList(new Release("US", 1, "2026-10-30"), new Release("US", 4, "2026-10-31"));
        yes("festival premiere then digital elsewhere: not a PK premiere", !ArrivalsLogic.streamingPremiere("2026-10-30", festival, "PK", today));
        yes("old original isn't a premiere", !ArrivalsLogic.streamingPremiere("2026-09-01", original, "PK", today));
        yes("no release data: no claim", !ArrivalsLogic.streamingPremiere("2026-10-31", new ArrayList<>(), "PK", today));
        yes("future digital date doesn't count", !ArrivalsLogic.streamingPremiere("2026-08-01",
                Arrays.asList(new Release("PK", 4, "2026-11-05")), "PK", today));

        // Show labels.
        eq("series premiere this week", ArrivalsLogic.PREMIERE, ArrivalsLogic.showLabel(1, "2026-10-31", "2026-10-31", today));
        eq("new season this week (all episodes at once)", ArrivalsLogic.NEW_SEASON, ArrivalsLogic.showLabel(3, "2026-10-31", "2026-10-31", today));
        eq("weekly episode of a running season", ArrivalsLogic.NEW_EPISODE, ArrivalsLogic.showLabel(2, "2026-11-01", "2026-09-20", today));
        eq("nothing this week", ArrivalsLogic.NONE, ArrivalsLogic.showLabel(2, "2026-10-01", "2026-09-20", today));
        eq("missing dates: nothing", ArrivalsLogic.NONE, ArrivalsLogic.showLabel(2, "", null, today));
        yes("window: 6 days ago is in", ArrivalsLogic.inWindow("2026-10-27", today));
        yes("window: 7 days ago is out", !ArrivalsLogic.inWindow("2026-10-26", today));
        yes("window: tomorrow is out", !ArrivalsLogic.inWindow("2026-11-03", today));

        if (failures > 0) {
            System.out.println("ArrivalsLogicTest: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("ArrivalsLogicTest: all passed");
    }
}
