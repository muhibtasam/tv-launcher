import io.vyro.tv.MixLogic;
import io.vyro.tv.MixLogic.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MixLogic: Home's bento mixed across services, random per list, stable for a list. */
public class MixLogicTest {
    static int failures;
    static final int Recs_HOME_GRID = 15;   // Recs.HOME_GRID: the largest bento (11) + a Continue row it may skip (4)

    static void yes(String what, boolean v) { if (!v) { failures++; System.out.println("FAIL " + what); } }
    static void eq(String what, Object want, Object got) {
        if (!want.equals(got)) { failures++; System.out.println("FAIL " + what + ": want " + want + ", got " + got); }
    }

    static List<Item> pool(Object... spec) {   // "netflix", 12, "prime", 12, …
        List<Item> l = new ArrayList<>();
        for (int s = 0; s < spec.length; s += 2) {
            String p = (String) spec[s];
            int n = (Integer) spec[s + 1];
            for (int i = 0; i < n; i++) l.add(new Item(p + ":" + i, p, i < 2 ? 0 : 2));
        }
        return l;
    }

    static Map<String, Integer> counts(List<Item> items, List<Integer> picked) {
        Map<String, Integer> c = new HashMap<>();
        for (int i : picked) c.merge(items.get(i).provider, 1, Integer::sum);
        return c;
    }

    static boolean distinct(List<Item> items, List<Integer> picked) {
        Set<String> s = new HashSet<>();
        for (int i : picked) if (!s.add(items.get(i).key)) return false;
        return true;
    }

    public static void main(String[] a) {
        for (int n : new int[] {10, 11, Recs_HOME_GRID}) {   // the two bentos, and the 15 Home asks for
            List<Item> items = pool("netflix", 15, "prime", 15, "crunchyroll", 6, "hbo", 4);
            List<Integer> p = MixLogic.pick(items, n, 1234L);
            eq(n + " slots filled", n, p.size());
            yes(n + ": no duplicates", distinct(items, p));
            Map<String, Integer> c = counts(items, p);
            yes(n + ": several services share the grid", c.size() >= 3);
            for (int v : c.values()) yes(n + ": no service takes more than 60%", v <= (3 * n + 4) / 5);
            // Every leading part too (the bento shows the first 10 or 11 of the 15), over many refreshes.
            for (long seed = 1; seed <= 200; seed++) {
                List<Integer> q = MixLogic.pick(items, n, seed * 104729L);
                Map<String, Integer> run = new HashMap<>();
                boolean ok = true;
                for (int m = 0; m < q.size(); m++) {
                    int v = run.merge(items.get(q.get(m)).provider, 1, Integer::sum);
                    ok &= v <= (3 * (m + 1) + 4) / 5;
                }
                if (!ok) { yes(n + ": every leading part at most 60% (seed " + seed + ")", false); break; }
            }

            // Different refreshes (seeds): different distributions and different first services; no fixed quota.
            Set<Map<String, Integer>> dists = new HashSet<>();
            Set<String> firsts = new HashSet<>();
            Set<Integer> netflixCounts = new HashSet<>();
            for (long seed = 1; seed <= 40; seed++) {
                List<Integer> q = MixLogic.pick(items, n, seed * 7919L);
                Map<String, Integer> cq = counts(items, q);
                dists.add(cq);
                firsts.add(items.get(q.get(0)).provider);
                netflixCounts.add(cq.getOrDefault("netflix", 0));
                yes(n + ": every refresh fills and stays distinct", q.size() == n && distinct(items, q));
            }
            yes(n + ": distributions vary across refreshes", dists.size() >= 5);
            yes(n + ": the large card's service varies (Netflix not always first)", firsts.size() >= 3);
            yes(n + ": Netflix's share varies (no fixed quota)", netflixCounts.size() >= 3);

            // Stable: the same pool and seed give the same grid (redraws, navigation, a restart with the saved list).
            eq(n + ": same seed, same grid", p, MixLogic.pick(items, n, 1234L));
            List<Item> reloaded = new ArrayList<>(items);   // the list read back from disk: same titles, same order
            eq(n + ": same after a restart", p, MixLogic.pick(reloaded, n, 1234L));

            // A small catalogue: it gives what it has, the others fill.
            List<Item> small = pool("netflix", 15, "prime", 15, "crunchyroll", 1);
            List<Integer> s = MixLogic.pick(small, n, 99L);
            eq(n + ": small service doesn't leave holes", n, s.size());
            yes(n + ": small service at most what it has", counts(small, s).getOrDefault("crunchyroll", 0) <= 1);

            // A service with nothing (failed or missing data) simply isn't there.
            List<Item> none = pool("netflix", 15, "prime", 15);
            List<Integer> z = MixLogic.pick(none, n, 5L);
            eq(n + ": two services fill the grid", n, z.size());
            yes(n + ": both used", counts(none, z).size() == 2);

            // Only one service: it fills the grid (the cap gives way) rather than leaving it empty.
            List<Item> one = pool("prime", 20);
            eq(n + ": one service fills every slot", n, MixLogic.pick(one, n, 3L).size());

            // Fewer titles than slots: all of them, no repeats.
            List<Item> few = pool("netflix", 3, "prime", 2);
            List<Integer> f = MixLogic.pick(few, n, 3L);
            eq(n + ": all titles when there are fewer than slots", 5, f.size());
            yes(n + ": still no duplicates", distinct(few, f));
        }

        // What Home actually shows: the 15-title grid, minus titles already in the Continue row (up to 4), then the
        // bento's 10 (with Continue Watching, HomeView.BENTO) or 11 (without, HomeNav.BENTO_FULL), in slot order.
        List<Item> all = pool("netflix", 8, "prime", 8, "crunchyroll", 6, "hbo", 4);
        for (long seed = 1; seed <= 20; seed++) {
            List<Integer> grid = MixLogic.pick(all, Recs_HOME_GRID, seed * 31L);
            Set<String> inContinue = new HashSet<>();
            for (int k = 0; k < 4; k++) inContinue.add(all.get(grid.get(k * 3)).key);   // 4 of them already in Continue
            for (int bento : new int[] {10, 11}) {
                List<Integer> shown = new ArrayList<>();
                for (int i : grid) if (shown.size() < bento && !(bento == 10 && inContinue.contains(all.get(i).key))) shown.add(i);
                eq("bento " + bento + " is full", bento, shown.size());
                yes("bento " + bento + ": no duplicates", distinct(all, shown));
                yes("bento " + bento + ": several services", counts(all, shown).size() >= 2);
            }
        }

        // Publishing: the grid on screen stays until a new list's candidates are resolved; a failed refresh keeps it.
        yes("same list, grid on screen: keep it (redraws, navigation, a failed refresh)", !MixLogic.publish(true, false, true, true, false));
        yes("new list, details still arriving: keep the old grid", !MixLogic.publish(true, true, false, false, true));
        yes("new list, all resolved: publish once", MixLogic.publish(true, true, true, false, true));
        yes("new list, some never resolved, wait over and no refresh running: publish what's known", MixLogic.publish(true, true, false, true, false));
        yes("wait over but a refresh is still running: keep waiting", !MixLogic.publish(true, true, false, true, true));
        yes("no grid yet, nothing resolved: nothing (the skeleton shows)", !MixLogic.publish(false, true, false, false, true));
        yes("no grid yet (first run or restart without one), resolved: publish", MixLogic.publish(false, false, true, false, false));

        // The same title listed twice (two services): shown once.
        List<Item> dup = new ArrayList<>();
        dup.add(new Item("movie:1", "netflix", 0));
        dup.add(new Item("movie:1", "prime", 0));
        dup.add(new Item("movie:2", "prime", 1));
        List<Integer> d = MixLogic.pick(dup, 10, 1L);
        eq("a title on two services appears once", 2, d.size());

        // Verified labels come first within their service.
        List<Item> lab = new ArrayList<>();
        for (int i = 0; i < 10; i++) lab.add(new Item("netflix:" + i, "netflix", 2));
        lab.add(new Item("netflix:new", "netflix", 0));
        List<Integer> l = MixLogic.pick(lab, 3, 8L);
        eq("a verified arrival is its service's first", "netflix:new", lab.get(l.get(0)).key);

        // The pool's room per service: about twice the grid in all, never under the floor (8).
        eq("two services: 15 each (pool 30 for 15 slots)", 15, MixLogic.perService(2, Recs_HOME_GRID, 8));
        eq("three services: 10 each", 10, MixLogic.perService(3, Recs_HOME_GRID, 8));
        eq("four or more: the floor", 8, MixLogic.perService(5, Recs_HOME_GRID, 8));
        eq("one service: all 30", 30, MixLogic.perService(1, Recs_HOME_GRID, 8));
        eq("none: the floor", 8, MixLogic.perService(0, Recs_HOME_GRID, 8));
        // Two services with a pool of 30: refreshes bring different titles, not only a new order.
        List<Item> two = pool("netflix", 15, "prime", 15);
        Set<Set<String>> titleSets = new HashSet<>();
        for (long seed = 1; seed <= 20; seed++) {
            Set<String> s = new HashSet<>();
            for (int i : MixLogic.pick(two, Recs_HOME_GRID, seed * 7L)) s.add(two.get(i).key);
            titleSets.add(s);
        }
        yes("two services: different titles across refreshes", titleSets.size() >= 5);
        // Two services: the split of the 10 tiles shown varies across refreshes (not 5/5 every time), never over 6.
        Set<Integer> splits = new HashSet<>();
        for (long seed = 1; seed <= 60; seed++) {
            List<Integer> q = MixLogic.pick(two, Recs_HOME_GRID, seed * 7919L);
            int netflix = 0;
            for (int k = 0; k < 10; k++) if ("netflix".equals(two.get(q.get(k)).provider)) netflix++;
            splits.add(netflix);
            yes("two services: at most 6 of 10 from one (seed " + seed + ")", netflix >= 4 && netflix <= 6);
        }
        yes("two services: the split varies (4/6, 5/5, 6/4)", splits.size() == 3);

        // Empty pool: nothing (the caller keeps what it has).
        eq("no titles: no slots", 0, MixLogic.pick(new ArrayList<>(), 10, 1L).size());

        if (failures > 0) {
            System.out.println("MixLogicTest: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("MixLogicTest: all passed");
    }
}
