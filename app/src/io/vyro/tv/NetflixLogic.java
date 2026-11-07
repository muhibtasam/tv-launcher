package io.vyro.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Netflix on Home (no Android): Netflix doesn't publish its titles to other launchers, so the grid builds its own pool
 * from three lists: Netflix's Top 10 movies and Top 10 shows for the country (its public weekly TSV), and titles newly
 * added to Netflix. Up to POOL_EACH from each list; Home shows SHOWN of them, at least one from every list that has
 * any, the rest random, the same all through a part of the day (the seed). Tested by NetflixLogicTest.
 */
public final class NetflixLogic {
    private NetflixLogic() {}

    public static final int POOL_EACH = 5, SHOWN = 4;
    public static final int MOVIE = 0, SHOW = 1, NEW = 2;
    public static final String PACKAGE = "com.netflix.ninja";

    public static final class Title {
        public final String name;
        public final int kind, rank;   // rank: the Top 10 place (1..10), 0 for a new title
        public String netflixId = "";  // filled later (Watchmode); "" opens Netflix's search instead

        public Title(String name, int kind, int rank) {
            this.name = name == null ? "" : name.trim();
            this.kind = kind;
            this.rank = rank;
        }
    }

    /**
     * The country's latest week from all-weeks-countries.tsv (country_name, country_iso2, week, category, weekly_rank,
     * show_title, season_title, ...): Top 10 movies and shows, best rank first, at most POOL_EACH of each. Lines that
     * don't parse are skipped; an unknown country gives an empty list (the caller falls back to the global list).
     */
    public static List<Title> top10(String tsv, String iso2) {
        List<Title> out = new ArrayList<>();
        if (tsv == null || iso2 == null) return out;
        String want = iso2.trim().toUpperCase(Locale.ROOT), latest = "";
        List<String[]> rows = new ArrayList<>();
        for (String line : tsv.split("\r?\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 6 || !f[1].trim().equalsIgnoreCase(want) || !f[2].trim().matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            rows.add(f);
            if (f[2].trim().compareTo(latest) > 0) latest = f[2].trim();
        }
        for (String[] f : rows) {
            if (!f[2].trim().equals(latest)) continue;
            int rank = rank(f[4]);
            String cat = f[3].trim().toLowerCase(Locale.ROOT);
            int kind = cat.startsWith("film") ? MOVIE : cat.startsWith("tv") ? SHOW : -1;
            if (rank > 0 && kind >= 0 && !f[5].trim().isEmpty()) out.add(new Title(f[5], kind, rank));
        }
        return firstOfEach(out);
    }

    /**
     * The global list (all-weeks-global.tsv: week, category, weekly_rank, show_title, ...), for a country Netflix
     * doesn't rank: its latest week, English and non-English lists merged, best rank first.
     */
    public static List<Title> top10Global(String tsv) {
        List<Title> out = new ArrayList<>();
        if (tsv == null) return out;
        String latest = "";
        List<String[]> rows = new ArrayList<>();
        for (String line : tsv.split("\r?\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 4 || !f[0].trim().matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            rows.add(f);
            if (f[0].trim().compareTo(latest) > 0) latest = f[0].trim();
        }
        for (String[] f : rows) {
            if (!f[0].trim().equals(latest)) continue;
            int rank = rank(f[2]);
            String cat = f[1].trim().toLowerCase(Locale.ROOT);
            int kind = cat.startsWith("film") ? MOVIE : cat.startsWith("tv") ? SHOW : -1;
            if (rank > 0 && kind >= 0 && !f[3].trim().isEmpty()) out.add(new Title(f[3], kind, rank));
        }
        return firstOfEach(out);
    }

    /** The pool: Top 10 movies and shows, then new titles (at most POOL_EACH each), each title once. */
    public static List<Title> pool(List<Title> top10, List<Title> added) {
        List<Title> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] count = new int[3];
        List<Title> all = new ArrayList<>();
        if (top10 != null) all.addAll(top10);
        if (added != null) for (Title t : added) {
            if (t == null) continue;
            Title n = new Title(t.name, NEW, 0);
            n.netflixId = t.netflixId;
            all.add(n);
        }
        for (Title t : all) {
            if (t == null || t.name.isEmpty() || t.kind < 0 || t.kind > NEW || count[t.kind] >= POOL_EACH) continue;
            if (!seen.add(norm(t.name))) continue;
            count[t.kind]++;
            out.add(t);
        }
        return out;
    }

    /**
     * SHOWN titles from the pool for this seed (a part of the day): first one from each list that has any (picked by
     * the seed), then the rest at random; the same seed gives the same titles in the same order.
     */
    public static List<Title> pick(List<Title> pool, int n, long seed) {
        List<Title> out = new ArrayList<>();
        if (pool == null || n <= 0) return out;
        List<Title> rest = new ArrayList<>(pool);
        Collections.sort(rest, (a, b) -> Double.compare(MixLogic.unit(seed, a.name), MixLogic.unit(seed, b.name)));
        for (int kind = MOVIE; kind <= NEW && out.size() < n; kind++)
            for (Title t : rest) if (t.kind == kind) { out.add(t); break; }
        for (Title t : rest) {
            if (out.size() >= n) break;
            if (!out.contains(t)) out.add(t);
        }
        Collections.sort(out, (a, b) -> Double.compare(MixLogic.unit(seed + 1, a.name), MixLogic.unit(seed + 1, b.name)));
        return out;
    }

    /** The chip on a Netflix tile: "TOP 10 · #3" or "NEW ON NETFLIX". */
    public static String chip(Title t) {
        if (t == null) return "";
        return t.kind == NEW ? "NEW ON NETFLIX" : "TOP 10 · #" + t.rank;
    }

    /** Netflix's page for the title (only a numeric id), else null: the caller opens Netflix's search. */
    public static String link(String netflixId) {
        if (netflixId == null || !netflixId.trim().matches("\\d{4,12}")) return null;
        return "https://www.netflix.com/title/" + netflixId.trim();
    }

    private static List<Title> firstOfEach(List<Title> in) {
        Collections.sort(in, (a, b) -> a.kind != b.kind ? a.kind - b.kind : a.rank - b.rank);
        List<Title> out = new ArrayList<>();
        int[] count = new int[2];
        Set<String> seen = new HashSet<>();
        for (Title t : in) if (count[t.kind] < POOL_EACH && seen.add(t.kind + norm(t.name))) { count[t.kind]++; out.add(t); }
        return out;
    }

    private static int rank(String s) {
        try { int r = Integer.parseInt(s.trim()); return r >= 1 && r <= 10 ? r : 0; } catch (RuntimeException e) { return 0; }
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
