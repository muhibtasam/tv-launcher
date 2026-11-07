package io.freemytv;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What's New's "New on {service}": once a day, each installed service's whole catalogue in the country (TMDB's
 * availability, from JustWatch), compared with the day before (ArrivalsLogic decides what counts). One small text file
 * per service in the app's files, written atomically; nothing runs on the main thread.
 */
final class Arrivals {
    interface Fetch {
        JSONObject get(String path) throws Exception;
    }

    /** A service's catalogue is read again after this long (a little under a day, so a daily habit keeps it daily). */
    private static final long EVERY = 22 * 3600_000L;
    /** After a failed read, the next try waits this long (no retry loop while offline). */
    private static final long RETRY = 2 * 3600_000L;
    /** TMDB serves at most 500 pages per query; a bigger catalogue can't be read whole (then it's incomplete). */
    private static final int MAX_PAGES = 500;
    /** Between page requests: one catalogue is a few hundred light requests, spread out instead of in a burst. */
    private static final long PACE_MS = 150;

    private final File dir;
    private final Map<String, Long> triedAt = new HashMap<>();

    Arrivals(Context c) {
        dir = new File(c.getFilesDir(), "arrivals");
    }

    private AtomicFile file(String service) {
        return new AtomicFile(new File(dir, service.replaceAll("[^a-z0-9]", "") + ".txt"));
    }

    synchronized ArrivalsLogic.State load(String service) {
        try {
            byte[] b = file(service).readFully();
            return ArrivalsLogic.read(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;   // none yet, or unreadable: the next complete read is a new baseline
        }
    }

    private synchronized boolean save(String service, ArrivalsLogic.State s) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        AtomicFile f = file(service);
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(ArrivalsLogic.write(s).getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
            return true;
        } catch (Exception e) {
            if (out != null) f.failWrite(out);   // the previous file stays as it was
            return false;
        }
    }

    /** The service's catalogue is due: never read, or the last complete one is old, and no recent failed try. */
    private boolean due(String service, ArrivalsLogic.State s, long now) {
        Long tried = triedAt.get(service);
        if (tried != null && now - tried >= 0 && now - tried < RETRY) return false;
        return s == null || s.last == null || now - s.last.takenAt >= EVERY || now < s.last.takenAt;
    }

    /**
     * Reads each due service's catalogue in `region` and records it (background thread only; minutes for a big
     * catalogue, by design). `services`: TMDB provider id → service key. True if any new arrival was found.
     */
    boolean refresh(Fetch fetch, Map<Integer, String> services, String region) {
        boolean found = false;
        for (Map.Entry<Integer, String> sv : services.entrySet()) {
            String service = sv.getValue();
            long now = System.currentTimeMillis();
            ArrivalsLogic.State prev = load(service);
            if (!due(service, prev, now)) continue;
            synchronized (this) {
                triedAt.put(service, now);
            }
            Set<String> ids = new HashSet<>();
            int read = 0, wanted = 0, total = 0;
            boolean ok = true;
            for (String kind : new String[] {"movie", "tv"}) {
                int pages = 1;
                for (int page = 1; page <= pages && ok; page++) {
                    try {
                        JSONObject o = fetch.get("/discover/" + kind + "?watch_region=" + region + "&with_watch_providers=" + sv.getKey()
                                + "&with_watch_monetization_types=flatrate&include_adult=false"
                                + "&sort_by=" + ("movie".equals(kind) ? "primary_release_date.asc" : "first_air_date.asc") + "&page=" + page);
                        if (page == 1) {
                            int all = o.optInt("total_pages");
                            if (all > MAX_PAGES) ok = false;   // too big to read whole: no claims from it
                            pages = Math.min(all, MAX_PAGES);
                            wanted += pages;
                            total += o.optInt("total_results");
                        }
                        JSONArray r = o.optJSONArray("results");
                        for (int i = 0; r != null && i < r.length(); i++) {
                            int id = r.getJSONObject(i).optInt("id");
                            if (id > 0) ids.add(kind + ":" + id);
                        }
                        read++;
                        Thread.sleep(PACE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return found;
                    } catch (Exception e) {
                        ok = false;   // a failed page: the whole read is incomplete, the previous one stays
                    }
                }
                if (!ok) break;
            }
            boolean complete = ok && ArrivalsLogic.complete(read, wanted, ids.size(), total);
            ArrivalsLogic.Result r = ArrivalsLogic.apply(prev, new ArrivalsLogic.Snapshot(now, ids), complete, now);
            boolean saved = r.state == prev || save(service, r.state);
            AppLog.w("arrivals " + service + ": " + r.reason + ", " + ids.size() + " of " + total + " titles, "
                    + r.added.size() + " new" + (saved ? "" : " (not saved)"));
            found |= saved && !r.added.isEmpty();
        }
        return found;
    }

    /** This week's arrivals on the given services, each title once (see ArrivalsLogic.arrivals). */
    LinkedHashMap<String, Object[]> current(List<String> order) {
        Map<String, ArrivalsLogic.State> states = new HashMap<>();
        for (String service : order) {
            if (states.containsKey(service)) continue;
            ArrivalsLogic.State s = load(service);
            if (s != null) states.put(service, s);
        }
        return ArrivalsLogic.arrivals(states, order, System.currentTimeMillis());
    }
}
