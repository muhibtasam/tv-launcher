package io.vyro.tv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Home's bento, without Android (tools/tests/MixLogicTest): which titles fill the slots, mixed across the TV's
 * streaming services. Random, but stable: the same titles and the same seed (the list's refresh time) give the same
 * grid, so it never moves while you navigate, and a new list brings a new mix. No fixed share per service: each
 * service gets a random weight for this list, which shrinks as it takes slots, and none takes more than 60% of any
 * leading part of the grid while another still has titles. Within a service its strongest titles come first (verified "new" ones, then the freshest).
 */
public final class MixLogic {
    private MixLogic() {}

    public static final class Item {
        public final String key, provider;
        public final int priority;   // lower first: 0 = a verified label, then freshness tiers

        public Item(String key, String provider, int priority) {
            this.key = key;
            this.provider = provider == null ? "" : provider;
            this.priority = priority;
        }
    }

    /** Indexes into `items` for slots 0..n-1 (fewer when there aren't n distinct titles). */
    public static List<Integer> pick(List<Item> items, int n, long seed) {
        Map<String, List<Integer>> byProvider = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) byProvider.computeIfAbsent(items.get(i).provider, x -> new ArrayList<>()).add(i);
        for (List<Integer> l : byProvider.values()) {
            l.sort((a, b) -> {
                int c = Integer.compare(items.get(a).priority, items.get(b).priority);
                return c != 0 ? c : Double.compare(unit(seed, items.get(a).key), unit(seed, items.get(b).key));
            });
        }
        List<String> providers = new ArrayList<>(byProvider.keySet());
        java.util.Collections.sort(providers);   // the seed decides, not the order they came in
        Map<String, Double> weight = new LinkedHashMap<>();
        for (String p : providers) weight.put(p, 0.6 + 0.8 * unit(seed ^ 0x9E3779B97F4A7C15L, p));
        Map<String, Integer> taken = new LinkedHashMap<>(), next = new LinkedHashMap<>();
        for (String p : providers) {
            taken.put(p, 0);
            next.put(p, 0);
        }
        Random rnd = new Random(seed);
        Set<String> used = new HashSet<>();
        List<Integer> out = new ArrayList<>();
        while (out.size() < n) {
            // At most 60% of every leading part of the grid (rounded up): the bento shows only the first 10 or 11, and
            // with two services half would force 5/5 every time; 60% lets the split vary (4/6, 5/5, 6/4).
            int cap = (3 * (out.size() + 1) + 4) / 5;
            List<String> open = new ArrayList<>();
            for (String p : providers) if (remaining(byProvider.get(p), next.get(p), items, used) && taken.get(p) < cap) open.add(p);
            if (open.isEmpty()) {   // the others ran out: the cap gives way rather than leaving slots empty
                for (String p : providers) if (remaining(byProvider.get(p), next.get(p), items, used)) open.add(p);
            }
            if (open.isEmpty()) break;
            // The best titles left go first, whichever app has them (an hour's Indian or Hollywood titles before the
            // rest; the 60% cap gives way for them); among apps with equally good ones, the cap and the weighted draw.
            int best = Integer.MAX_VALUE;
            Map<String, Integer> head = new HashMap<>();
            for (String p : providers) {
                int h = headPriority(byProvider.get(p), next.get(p), items, used);
                head.put(p, h);
                best = Math.min(best, h);
            }
            List<String> top = new ArrayList<>();
            for (String p : open) if (head.get(p) == best) top.add(p);
            if (top.isEmpty()) for (String p : providers) if (head.get(p) == best) top.add(p);
            open = top;
            double total = 0;
            for (String p : open) total += weight.get(p) / (1 + taken.get(p));
            double r = rnd.nextDouble() * total;
            String chosen = open.get(open.size() - 1);
            for (String p : open) {
                r -= weight.get(p) / (1 + taken.get(p));
                if (r <= 0) {
                    chosen = p;
                    break;
                }
            }
            List<Integer> list = byProvider.get(chosen);
            int i = next.get(chosen);
            while (i < list.size() && used.contains(items.get(list.get(i)).key)) i++;   // a title already shown: the next
            next.put(chosen, i + 1);
            if (i >= list.size()) continue;
            used.add(items.get(list.get(i)).key);
            out.add(list.get(i));
            taken.put(chosen, taken.get(chosen) + 1);
        }
        return out;
    }

    /**
     * At least `min` of `provider`'s titles among the first `within` picks, when it has them: its best ones not picked
     * (priority, then the seed) take the places of the last other titles there, which move to the end. Else as given.
     */
    public static List<Integer> atLeast(List<Integer> picked, List<Item> items, String provider, int min, int within, long seed) {
        List<Integer> out = new ArrayList<>(picked);
        int have = 0;
        for (int i = 0; i < Math.min(within, out.size()); i++) if (items.get(out.get(i)).provider.equals(provider)) have++;
        if (have >= min) return out;
        List<Integer> spare = new ArrayList<>();
        for (int i = within; i < out.size(); i++) if (items.get(out.get(i)).provider.equals(provider)) spare.add(out.get(i));
        Set<String> keys = new HashSet<>();
        for (int i : out) keys.add(items.get(i).key);
        List<Integer> unpicked = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (items.get(i).provider.equals(provider) && !keys.contains(items.get(i).key)) unpicked.add(i);
        unpicked.sort((a, b) -> {
            int c = Integer.compare(items.get(a).priority, items.get(b).priority);
            return c != 0 ? c : Double.compare(unit(seed, items.get(a).key), unit(seed, items.get(b).key));
        });
        spare.addAll(unpicked);
        for (int pos = Math.min(within, out.size()) - 1; pos >= 0 && have < min && !spare.isEmpty(); pos--) {
            if (items.get(out.get(pos)).provider.equals(provider)) continue;
            int in = spare.remove(0), gone = out.get(pos);
            out.remove(Integer.valueOf(in));
            out.set(out.indexOf(gone), in);
            out.add(gone);
            have++;
        }
        return out;
    }

    private static int headPriority(List<Integer> list, int from, List<Item> items, Set<String> used) {
        for (int i = from; i < list.size(); i++) if (!used.contains(items.get(list.get(i)).key)) return items.get(list.get(i)).priority;
        return Integer.MAX_VALUE;
    }

    /** Home's mix for an hour: Indian titles first half the hours, Hollywood first a quarter, an even mix the rest. */
    public static final int MIX = 0, INDIAN = 1, HOLLYWOOD = 2;

    public static int mood(long hourSeed) {
        double u = unit(hourSeed, "mood");
        return u < 0.5 ? INDIAN : u < 0.75 ? HOLLYWOOD : MIX;
    }

    private static final Set<String> INDIAN_LANGS = new HashSet<>(java.util.Arrays.asList("hi", "ta", "te", "ml", "kn", "bn", "mr", "pa", "gu", "ur"));

    /** An Indian title (TMDB's original language). */
    public static boolean indian(String lang) {
        return lang != null && INDIAN_LANGS.contains(lang);
    }

    /**
     * A title's priority in the hour's mix (0 first): the hour's kind first (Indian or English-language), the app's own
     * first few (appFirst) ahead within each; in a mixed hour Indian titles stay a step ahead. Unknown language: last.
     */
    public static int priority(int mood, String lang, boolean appFirst) {
        int own = appFirst ? 0 : 1;
        boolean in = indian(lang), en = "en".equals(lang);
        if (mood == INDIAN) return (in ? 0 : en ? 2 : 4) + own;
        if (mood == HOLLYWOOD) return (en ? 0 : in ? 2 : 4) + own;
        return (in ? 0 : 1) + own;
    }

    private static boolean remaining(List<Integer> list, int from, List<Item> items, Set<String> used) {
        for (int i = from; i < list.size(); i++) if (!used.contains(items.get(list.get(i)).key)) return true;
        return false;
    }

    /**
     * Whether Home publishes a new grid now: only for a list it hasn't made one for (or when it has none), and only
     * once that list's candidates are all resolved (their services known), or after the wait with no refresh running.
     * Otherwise the grid on screen stays, whole: nothing moves card by card while details arrive.
     */
    public static boolean publish(boolean haveGrid, boolean newList, boolean resolved, boolean waited, boolean fetching) {
        if (haveGrid && !newList) return false;
        return resolved || (waited && !fetching);
    }

    /**
     * How many titles of each service go into the pool the grid is drawn from: room for about twice the grid, so a new
     * list brings different titles and not only a new order (with two services, 8 each would leave 16 for 15 slots).
     */
    public static int perService(int services, int grid, int floor) {
        if (services <= 0) return floor;
        return Math.max(floor, (2 * grid + services - 1) / services);
    }

    /** A number in [0, 1) from the seed and a text, the same every time (SplitMix64). */
    static double unit(long seed, String s) {
        long z = seed + 0x9E3779B97F4A7C15L * (s.hashCode() + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }
}
