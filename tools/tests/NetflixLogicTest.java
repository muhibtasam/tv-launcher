import io.vyro.tv.NetflixLogic;
import io.vyro.tv.NetflixLogic.Title;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Tests for Netflix's titles on Home (no Android): run by tools/tests/run.sh. */
public class NetflixLogicTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static String row(String iso, String week, String cat, int rank, String title) {
        return "Pakistan\t" + iso + "\t" + week + "\t" + cat + "\t" + rank + "\t" + title + "\tN/A\t1\n";
    }

    private static int kinds(List<Title> l, int kind) {
        int n = 0;
        for (Title t : l) if (t.kind == kind) n++;
        return n;
    }

    public static void main(String[] args) {
        StringBuilder tsv = new StringBuilder("country_name\tcountry_iso2\tweek\tcategory\tweekly_rank\tshow_title\tseason_title\tcumulative_weeks_in_top_10\n");
        for (int r = 1; r <= 10; r++) {
            tsv.append(row("PK", "2026-11-01", "Films", r, "Movie " + r));
            tsv.append(row("PK", "2026-11-01", "TV", r, "Show " + r));
            tsv.append(row("PK", "2026-10-25", "Films", r, "Old " + r));
        }
        tsv.append(row("IN", "2026-11-01", "Films", 1, "Elsewhere"));
        tsv.append("garbage line\n\tPK\tnot-a-date\tFilms\t1\tBad\n");
        tsv.append(row("PK", "2026-11-01", "Films", 11, "Rank eleven"));
        tsv.append(row("PK", "2026-11-01", "Films", 2, "  "));

        // Top 10: the latest week, the country only, 5 of each, best rank first.
        List<Title> top = NetflixLogic.top10(tsv.toString(), "pk");
        check("5 movies", kinds(top, NetflixLogic.MOVIE) == 5);
        check("5 shows", kinds(top, NetflixLogic.SHOW) == 5);
        check("best rank first", top.get(0).name.equals("Movie 1") && top.get(0).rank == 1);
        check("latest week only", top.stream().noneMatch(t -> t.name.startsWith("Old")));
        check("other countries left out", top.stream().noneMatch(t -> t.name.equals("Elsewhere")));
        check("bad rows skipped", top.stream().noneMatch(t -> t.name.equals("Bad") || t.name.equals("Rank eleven") || t.name.isEmpty()));
        check("unknown country: empty", NetflixLogic.top10(tsv.toString(), "ZZ").isEmpty());
        check("null and empty data", NetflixLogic.top10(null, "PK").isEmpty() && NetflixLogic.top10("", "PK").isEmpty());

        // The global list: English and non-English merged.
        String global = "week\tcategory\tweekly_rank\tshow_title\tseason_title\n"
            + "2026-11-01\tFilms (English)\t1\tG1\t\n2026-11-01\tFilms (Non-English)\t1\tG2\t\n"
            + "2026-11-01\tTV (English)\t1\tGS\t\n2026-10-25\tFilms (English)\t1\tOld\t\nbroken\n";
        List<Title> g = NetflixLogic.top10Global(global);
        check("global: latest week, both languages", g.size() == 3 && kinds(g, NetflixLogic.MOVIE) == 2);

        // The pool: 5 + 5 + 5, each title once.
        List<Title> added = new ArrayList<>();
        for (int i = 1; i <= 7; i++) added.add(new Title("New " + i, NetflixLogic.NEW, 0));
        added.add(new Title("Movie 1", NetflixLogic.NEW, 0));
        added.add(null);
        List<Title> pool = NetflixLogic.pool(top, added);
        check("pool of 15", pool.size() == 15);
        check("5 new", kinds(pool, NetflixLogic.NEW) == 5);
        check("a Top 10 title isn't also new", pool.stream().filter(t -> t.name.equals("Movie 1")).count() == 1);
        check("empty lists: empty pool", NetflixLogic.pool(null, null).isEmpty());

        // Pick 4: one from each list, the same for the same seed.
        List<Title> a = NetflixLogic.pick(pool, NetflixLogic.SHOWN, 42);
        check("4 shown", a.size() == 4);
        check("one of each list", kinds(a, 0) >= 1 && kinds(a, 1) >= 1 && kinds(a, 2) >= 1);
        check("no repeats", new HashSet<>(a).size() == 4);
        check("same seed, same picks", a.equals(NetflixLogic.pick(pool, NetflixLogic.SHOWN, 42)));
        boolean differs = false;
        for (long s = 1; s < 20 && !differs; s++) differs = !new HashSet<>(a).equals(new HashSet<>(NetflixLogic.pick(pool, 4, s)));
        check("another part of the day, other picks", differs);
        List<Title> onlyNew = NetflixLogic.pool(null, added);
        check("only new titles: still 4", NetflixLogic.pick(onlyNew, 4, 7).size() == 4);
        check("short pool: all of it", NetflixLogic.pick(Arrays.asList(new Title("X", 0, 1)), 4, 1).size() == 1);
        check("empty pool", NetflixLogic.pick(new ArrayList<>(), 4, 1).isEmpty() && NetflixLogic.pick(null, 4, 1).isEmpty());

        // Chip and link.
        check("top 10 chip", NetflixLogic.chip(new Title("X", NetflixLogic.SHOW, 3)).equals("TOP 10 · #3"));
        check("new chip", NetflixLogic.chip(new Title("X", NetflixLogic.NEW, 0)).equals("NEW ON NETFLIX"));
        check("link", "https://www.netflix.com/title/81234567".equals(NetflixLogic.link("81234567")));
        check("bad id: no link", NetflixLogic.link("81x") == null && NetflixLogic.link(null) == null && NetflixLogic.link("") == null);

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("NetflixLogicTest: all passed");
    }
}
