package io.vyro.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        java.io.File photo;     // one of your own photos (Photos, VYRO): no title, no caption
        boolean kids;           // children's content (kids(): TMDB genres and US rating): never recommended
        int trend;              // Explore with Trakt: its place in Trakt's lists (1 = most searched/watched), 0 = not from them
        String tLang = "", tCountry = "";   // Trakt's language and country for it
        boolean anime;          // Trakt's "anime" genre
        String lang = "";       // TMDB's original_language ("hi", "en", "ja"…)
        String origin = "";     // its countries (origin_country and production countries): ",US,GB," 
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        int runtime;            // movies: minutes (0 = unknown)
        int season;             // shows: the season airing now or last aired (0 = unknown)
        String seasonName = ""; // that season's own name, when it has one beyond "Season 3"
        String want;            // fetched for this service (Explore's per-service picks): preferred when it streams there
        int top10;              // Netflix's Top 10 place (1..10) on Home's Netflix tiles, 0 = none
        int label;              // What's New: ArrivalsLogic.NEW_ON / PREMIERE / NEW_SEASON / NEW_EPISODE, or NONE (no chip)
        String labelApp;        // NEW_ON: the service it was found on
        double rating;          // TMDB's average (0-10), shown only with enough votes
        int votes;
        int[] genres = new int[0];
        String date = "";       // movies: release date; shows: their latest episode's air date ("2026-10-02")
        boolean followed;       // a show you opened before that has a new season out
        int tier = TIER_OLDER;  // how fresh: TIER_WEEK (out in the past 7 days), TIER_MONTH (30), TIER_OLDER
        long openedAt;          // history only: when it was opened from a card (0 = unknown)
        long engaged, position; // Watch Next only: the app's last-watched time and position (a removed title returns after new viewing)
    }

    /** Freshness tiers: the grid fills with this week's titles first, then this month's, then the popular older ones. */
    static final int TIER_WEEK = 0, TIER_MONTH = 1, TIER_OLDER = 2;

    /** A tier by date (a movie's release, a show's latest episode). */
    static int tierOf(String date) {
        long d = daysAgo(date);
        return d <= 7 ? TIER_WEEK : d <= 30 ? TIER_MONTH : TIER_OLDER;
    }

    interface Listener { void onRecs(); }

    // TMDB's own mirror on the images' domain: some ISPs block api.themoviedb.org (#327).
    private static final String API = "https://api.tmdb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** Explore's cards (at most 600 px wide, the frosted background 192): TMDB's 1280 copy, a fraction of "original". */
    private static final String CARD_IMAGES = "https://image.tmdb.org/t/p/w1280";
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Explore's new releases: looked up again after this long, when the day changes, or when Explore is opened (≥ 10 min). */
    /** Explore's data (titles, details, dates, ratings, seasons, artwork, availability) is kept 24 hours, saved to disk. */
    private static final long EXPLORE_TTL = 24 * 3600_000L, EXPLORE_RETRY = 30 * 60_000L;
    /** Explore's release window: today and the 6 days before, in the TV's time zone. */
    static final int WINDOW_DAYS = 7;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    /** By subscription, with ads (Pluto TV) or free: never rent or buy (upstream #348). */
    private static final String WATCH_TYPES = "flatrate%7Cads%7Cfree";
    private static final int CACHE_VERSION = 10;   // 10: free and ad-supported services count   // 9: back to the 7-day releases (the trending cache is dropped)
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"netflix", "netflix", "com.netflix.mediaclient"},   // the phone build some TVs carry
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.avod.thirdpartyclient"},   // the phone build
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"hotstar", "hotstar", "in.startv.hotstartvonly"},   // its Android TV build
        {"jio", "hotstar", "com.jio.media.ondemand"},   // JioCinema, merged into JioHotstar: one service, not two
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();   // config.properties, else the TV's time zone (Place)
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";
    /**
     * Crunchyroll's titles come from TMDB's India catalogue: TMDB has no Crunchyroll listings for many countries
     * (Pakistan among them). Netflix and Prime Video stay on tmdb_region.
     */
    static final String CRUNCHYROLL_REGION = "IN";
    private static final boolean LEGACY_LINKS_OFF = true;

    /** The country whose catalogue says what a service streams. */
    static String regionFor(String app) {
        return "crunchyroll".equals(app) ? CRUNCHYROLL_REGION : REGION;
    }

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final SharedPreferences history;   // titles opened from the cards: kept across cache versions
    private final ExecutorService io = lowPriority(1);
    /**
     * Images build on their own two threads: queued behind the TMDB refresh (on `io`), Explore's posters waited for the
     * whole refresh (about a minute) before even starting.
     */
    private final ExecutorService imagesIo = lowPriority(2);
    /** What's New's daily catalogue reads (minutes, by design): their own thread, so the list never waits for them. */
    private final ExecutorService arrivalsIo = lowPriority(1);
    private final ExecutorService appIo = lowPriority(1);   // TvProvider reads (Home's titles from the apps)
    private final Arrivals arrivals;
    private final java.util.concurrent.atomic.AtomicBoolean arrivalsRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long arrivalsCheckedAt;   // resumeArrivals' last look (0 in a new process: it looks at once)
    /** At most this many arrivals are looked up and offered to the grid (newest first). */
    private static final int ARRIVALS_MAX = 24;

    /**
     * Background threads below the UI's priority: at Android's default they competed with the home screen's own
     * drawing and the system's return-to-home animation on the TV's few cores (the "laggy close" after Home).
     */
    static ExecutorService lowPriority(int threads) {
        return Executors.newFixedThreadPool(threads, r -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "vyro-bg"));
    }
    /** Backdrops that couldn't be downloaded or read: the card shows its fallback instead of waiting forever. */
    private final java.util.Set<String> failedImages = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** When each image last failed: it's tried again only after IMAGE_RETRY (offline, a failure notified the cards,
     *  which asked for it again at once: thousands of tries a second). Cleared when the network is back. */
    private final Map<String, Long> imageFailedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long IMAGE_RETRY = 30_000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> FILE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // This hour's, built and ready to draw. In access order: the tab being looked at keeps its images, the one left
    // longest ago gives way first (three 10-card grids don't all fit in memory at once).
    private final Map<String, Backdrop> images = new LinkedHashMap<>(32, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    /** Home's mix for the current list (MixLogic): set when a list is made, saved with it; a failed refresh keeps it. */
    private volatile long mixSeed;
    /** Home's published grid (its picks, in slot order) and the mixSeed it was made for: replaced only as a whole. */
    private volatile List<Pick> homeGrid = new ArrayList<>();
    private volatile long homeSeed;
    /**
     * Home's titles from the streaming apps themselves (AppContent, AppContentLogic): the records read last (kept for a
     * failed or empty read), as cards, their signature and part of the day (a change in either mixes the grid again),
     * and the seed of that mix. TMDB no longer chooses Home's titles.
     */
    private volatile List<AppContentLogic.Record> appRecords = new ArrayList<>();
    private volatile List<Pick> appContent = new ArrayList<>();
    private volatile String appSignature = "";
    private volatile int appPeriod = -1;
    private volatile long appSeed;
    private volatile boolean appRead;   // a read finished in this process, or one was saved before
    private volatile long appFailedAt;  // the last failed read (0: none): the next waits AppContentLogic.RETRY
    private final java.util.concurrent.atomic.AtomicBoolean appRunning = new java.util.concurrent.atomic.AtomicBoolean();
    /** Home's candidates: the list's first HOME_DETAILED titles that pass the checks (details and pictures fetched for
     *  these); each service gets room for MixLogic.perService titles (at least HOME_PER_SERVICE) in the pool the mix draws from. */
    static final int HOME_DETAILED = 40, HOME_PER_SERVICE = 8;
    /** Titles in the grid: the largest bento (11, HomeNav.BENTO_FULL) plus the Continue row's titles it may skip (4). */
    static final int HOME_GRID = 15;
    /** A new list whose candidates still lack details after this long is published with the ones that have them. */
    private static final long HOME_WAIT = 10 * 60_000L;
    /** 1: the list has the Movies tab's extra movies. An older one (0) is fetched again once; Explore's stays. */
    private static final int LIST_SHAPE = 5;   // 5: Pakistan-only services, date labels, Home's pool per service (4: freshness tiers; 3: per service; 2: Bollywood); a new shape refetches once
    private volatile int listShape;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    private volatile long exploreAt;             // when Explore's new releases were last looked up
    private volatile String exploreDay = "";     // the day they were looked up for
    private volatile String exploreLanguage = "";  // their language (titles and genres change with it)
    private volatile long exploreRetryAt;           // a failed lookup waits this long before trying again
    private volatile int exploreSeed;            // turns over each time Explore is opened: a different mix of what's eligible

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        arrivals = new Arrivals(c);
        history = c.getSharedPreferences("recs-history", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            mixSeed = cache.getLong("mixSeed", listAt);
            String grid = cache.getString("homeGrid", null);
            if (grid != null) homeGrid = parse(new JSONArray(grid));
            for (Pick p : homeGrid) if (!"app".equals(p.kind)) { homeGrid = new ArrayList<>(); break; }   // a TMDB grid from before: not shown
            homeSeed = cache.getLong("homeSeed", 0);
            String apps = cache.getString("appRecords", null);
            if (apps != null) {
                appRecords = parseRecords(new JSONArray(apps));
                appContent = appPicks(appRecords);
                appSignature = cache.getString("appSignature", "");
                appPeriod = cache.getInt("appPeriod", -1);
                appSeed = cache.getLong("appSeed", 0);
                appRead = true;
            }
            listShape = cache.getInt("listShape", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            exploreAt = cache.getLong("exploreAt", 0);
            exploreDay = cache.getString("exploreDay", "");
            exploreLanguage = cache.getString("exploreLanguage", "");
            exploreNoApps = cache.getBoolean("exploreNoApps", false);
            // History and Trakt taste read once, in the background: never from disk on the main thread.
            io.execute(() -> {
                historyCached();
                tasteCached();
            });
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !key().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        return now(kind, 3);
    }

    // The grid holds 15 now: each tab shows its whole pool (no hourly window), Home 15 of Movies and Shows together.
    // 15 in each pool, 12 on the grid at a time (the window moves each hour).
    static final int TAB_POOL = 15, TAB_SHOWN = 12, TAB_CRUNCHYROLL = 3;
    /** Hindi movies and shows added to the list each refresh (each kind), for the Bollywood half of the mix. */
    static final int HINDI_WANTED = 12;
    /**
     * Explore's own lookup (the last 7 days' releases per service): off. The first tab is Home now, made of Movies and
     * Shows (homeShown); nothing of Explore's is fetched or downloaded. Set to true to bring Explore back.
     */
    static final boolean EXPLORE_ON = false;

    /**
     * Home: Movies and Shows together, 12 cards: their pools (tabPool, with all their conditions), freshest first,
     * movies and shows taking turns within each freshness tier, each title once.
     */
    List<Pick> homeShown() {
        return homeShown(TAB_SHOWN);
    }

    /**
     * homeShown with room for `max` (Home asks for more when Continue Watching takes some of its titles): the published
     * grid. Its titles are the ones the streaming apps publish themselves (refreshAppContent), mixed across the apps by
     * MixLogic with that read's seed, published whole and saved. The grid changes only when a read brings something
     * new or a new part of the day begins; a failed or empty read keeps it. TMDB isn't asked (homeCandidates stays for
     * the record, unused). An app removed meanwhile takes its cards with it at once.
     */
    List<Pick> homeShown(int max) {
        List<Pick> grid = homeGrid;
        if (MixLogic.publish(!grid.isEmpty(), homeSeed != appSeed, true, true, false)) {
            List<Pick> pool = homePool(appContent);
            List<MixLogic.Item> items = new ArrayList<>();
            Map<String, Integer> rank = new HashMap<>();
            for (Pick p : pool) {
                int n = rank.containsKey(p.app) ? rank.get(p.app) + 1 : 1;
                rank.put(p.app, n);
                items.add(new MixLogic.Item(key(p), p.app, n <= 4 ? 0 : 1));   // an app's own first few first
            }
            List<Pick> made = new ArrayList<>();
            for (int i : MixLogic.pick(items, HOME_GRID, appSeed)) made.add(pool.get(i));
            if (!made.isEmpty()) {
                homeGrid = grid = made;
                homeSeed = appSeed;
                Map<String, Integer> per = new LinkedHashMap<>();
                for (Pick p : made) per.merge(String.valueOf(p.app), 1, Integer::sum);
                AppLog.w("home grid: " + made.size() + " from " + pool.size() + " app titles " + per);
                io.execute(this::save);
            }
        }
        List<Pick> out = new ArrayList<>();
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : grid) {
            if (out.size() >= max) break;
            if (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)) out.add(p);
        }
        List<Pick> netflix = netflixShown();   // 4 Netflix tiles spread through the grid (NETFLIX_SLOTS)
        for (int i = 0; i < netflix.size() && i < NETFLIX_SLOTS.length; i++) {
            Pick p = netflix.get(i);
            if (p != null && have.computeIfAbsent(p.pkg, this::installed)) out.add(Math.min(NETFLIX_SLOTS[i], out.size()), p);
        }
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    // MARK: - Netflix on Home (NetflixLogic)
    // Netflix publishes nothing to other launchers, so its tiles come from its public Top 10 (this project's site,
    // refreshed weekly by pages.yml: tools/netflix_top10.py) and TMDB's titles on Netflix here, newest first. A pool of
    // up to 5 movies, 5 shows and 5 new titles; Home shows 4 (NetflixLogic.pick), spread through the grid.

    static final String NETFLIX_TOP10 = "https://muhibtasam.github.io/vyro/netflix-top10.tsv";
    private static final long NETFLIX_EVERY = 12 * 3600_000L, NETFLIX_RETRY = 30 * 60_000L;
    private static final int[] NETFLIX_SLOTS = {0, 3, 6, 9};
    private static final int NETFLIX_PROVIDER = 8;   // TMDB's id for Netflix
    private final ExecutorService netflixIo = lowPriority(1);
    private final java.util.concurrent.atomic.AtomicBoolean netflixRunning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile List<Pick> netflixPool;   // null: not read from the cache yet
    private volatile long netflixTriedAt;
    private volatile boolean netflixTopRead;   // the last fetch read the Top 10 (else it's tried again in 30 minutes)

    /** Netflix's pool: from the cache first, then fetched again every 12 hours (Netflix installed and a TMDB key). */
    void refreshNetflix(Runnable done) {
        if (!netflixRunning.compareAndSet(false, true)) return;
        netflixIo.execute(() -> {
            try {
                String pkg = detectApps(context).get("netflix");
                if (pkg == null || readKey() == null) {
                    if (netflixPool == null || !netflixPool.isEmpty()) { netflixPool = new ArrayList<>(); post(done); }
                    return;
                }
                if (netflixPool == null) {
                    netflixPool = netflixFrom(cache.getString("nf:pool", "[]"), pkg);
                    if (!netflixPool.isEmpty()) post(done);
                }
                long now = System.currentTimeMillis();
                if (now - cache.getLong("nf:at2", 0) < NETFLIX_EVERY && !netflixPool.isEmpty()) return;
                if (now - netflixTriedAt < NETFLIX_RETRY) return;
                netflixTriedAt = now;
                List<Pick> made = fetchNetflix(pkg);
                if (made.isEmpty()) return;   // a failed read keeps what was there
                netflixPool = made;
                JSONArray a = new JSONArray();
                for (Pick p : made) {
                    a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                            .put("new", p.label == ArrivalsLogic.NEW_ON).put("top10", p.top10));
                }
                cache.edit().putString("nf:pool", a.toString()).putLong("nf:at2", netflixTopRead ? now : 0).apply();
                AppLog.w("netflix: pool of " + made.size() + (netflixTopRead ? "" : " (no Top 10 yet)"));
                post(done);
            } catch (Exception e) {
                AppLog.w("netflix: " + e.getMessage());
            } finally {
                netflixRunning.set(false);
            }
        });
    }

    private static List<Pick> netflixFrom(String json, String pkg) {
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optInt("id") <= 0 || o.optString("backdrop").isEmpty()) continue;
                out.add(netflixPick(o.optInt("id"), o.optString("kind"), o.optString("title"), o.optString("backdrop"),
                        o.optBoolean("new"), o.optInt("top10"), pkg));
            }
        } catch (org.json.JSONException e) {
            AppLog.w("netflix: cache unreadable");
        }
        return out;
    }

    private static Pick netflixPick(int id, String kind, String title, String backdrop, boolean fresh, int top10, String pkg) {
        Pick p = new Pick();
        p.id = id;
        p.kind = "tv".equals(kind) ? "tv" : "movie";
        p.title = title;
        p.backdrop = backdrop;
        p.app = "netflix";
        p.pkg = pkg;
        p.top10 = fresh ? 0 : top10;
        if (fresh) { p.label = ArrivalsLogic.NEW_ON; p.labelApp = "netflix"; }
        return p;
    }

    /** The Top 10 (found on TMDB by name) and the newest titles on Netflix here, pooled by NetflixLogic. */
    private List<Pick> fetchNetflix(String pkg) throws Exception {
        List<NetflixLogic.Title> top = new ArrayList<>();
        netflixTopRead = false;
        try {
            String tsv = httpText(NETFLIX_TOP10);
            top = NetflixLogic.top10(tsv, REGION);
            if (top.isEmpty()) top = NetflixLogic.top10(tsv, "ZZ");   // a country Netflix doesn't rank: the global list
            netflixTopRead = !top.isEmpty();
        } catch (IOException e) {
            AppLog.w("netflix top 10: " + e.getMessage());
        }
        Map<String, Pick> byName = new HashMap<>();
        List<NetflixLogic.Title> found = new ArrayList<>();
        for (NetflixLogic.Title t : top) {
            String kind = t.kind == NetflixLogic.MOVIE ? "movie" : "tv";
            JSONArray r = get("/search/" + kind + "?query=" + URLEncoder.encode(t.name, "UTF-8")).optJSONArray("results");
            List<Pick> l = new ArrayList<>();
            for (int i = 0; r != null && i < r.length() && l.isEmpty(); i++) add(l, r.getJSONObject(i), kind);
            if (l.isEmpty()) continue;
            byName.put(NetflixLogic.norm(t.name), netflixPick(l.get(0).id, kind, t.name, l.get(0).backdrop, false, t.rank, pkg));
            found.add(t);
        }
        List<JSONObject> fresh = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            String date = kind.equals("movie") ? "primary_release_date" : "first_air_date";
            JSONArray r = get("/discover/" + kind + "?with_watch_providers=" + NETFLIX_PROVIDER + "&watch_region=" + REGION
                    + "&with_watch_monetization_types=flatrate&sort_by=" + date + ".desc&" + date + ".lte=" + day(0)
                    + "&" + date + ".gte=" + day(-120) + "&vote_count.gte=3").optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                fresh.add(o.put("_kind", kind).put("_date", o.optString(date)));
            }
        }
        java.util.Collections.sort(fresh, (a, b) -> b.optString("_date").compareTo(a.optString("_date")));
        List<NetflixLogic.Title> added = new ArrayList<>();
        for (JSONObject o : fresh) {
            List<Pick> l = new ArrayList<>();
            add(l, o, o.optString("_kind"));
            if (l.isEmpty() || byName.containsKey(NetflixLogic.norm(l.get(0).title))) continue;
            byName.put(NetflixLogic.norm(l.get(0).title), netflixPick(l.get(0).id, l.get(0).kind, l.get(0).title, l.get(0).backdrop, true, 0, pkg));
            added.add(new NetflixLogic.Title(l.get(0).title, NetflixLogic.NEW, 0));
        }
        List<Pick> out = new ArrayList<>();
        for (NetflixLogic.Title t : NetflixLogic.pool(found, added)) {
            Pick p = byName.get(NetflixLogic.norm(t.name));
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Home's Netflix tiles for this part of the day: 4 of the pool, at least one Top 10 movie, show and new title. */
    private List<Pick> netflixShown() {
        List<Pick> pool = netflixPool;
        List<Pick> out = new ArrayList<>();
        if (pool == null || pool.isEmpty()) return out;
        Map<NetflixLogic.Title, Pick> of = new java.util.IdentityHashMap<>();
        List<NetflixLogic.Title> titles = new ArrayList<>();
        for (Pick p : pool) {
            int kind = p.label == ArrivalsLogic.NEW_ON ? NetflixLogic.NEW : "movie".equals(p.kind) ? NetflixLogic.MOVIE : NetflixLogic.SHOW;
            NetflixLogic.Title t = new NetflixLogic.Title(p.title, kind, p.top10);
            titles.add(t);
            of.put(t, p);
        }
        for (NetflixLogic.Title t : NetflixLogic.pick(titles, NetflixLogic.SHOWN, appSeed)) out.add(of.get(t));
        return out;
    }

    private static String httpText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Home's titles from the streaming apps (AppContent)

    private ContentObserver appObserver;
    private final Runnable appChanged = () -> refreshAppContent(this::notifyListeners);

    /**
     * Reads what the apps publish (off the main thread; Home's return, a change in the apps' lists, a new part of the
     * day). A failed read keeps everything; `done` runs on the main thread when Home's titles changed, or after the
     * first read.
     */
    /** The listings permission just arrived: the next read runs now, not after the 5 minutes a failed one waits. */
    void retryAppContentNow() {
        appFailedAt = 0;
    }

    void refreshAppContent(Runnable done) {
        observeApps();
        refreshNetflix(done);
        if (!AppContentLogic.mayRead(appFailedAt, System.currentTimeMillis())) return;
        if (!appRunning.compareAndSet(false, true)) return;
        appIo.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                List<AppContentLogic.Record> raw;
                try {
                    raw = AppContent.read(context);
                } catch (RuntimeException e) {
                    appFailedAt = now;
                    AppLog.w("app titles: not read (" + e.getMessage() + "), the last ones stay; next try in 5 min");
                    if (!appRead) {
                        appRead = true;   // nothing to wait for: the grid shows what it has (no skeleton forever)
                        post(done);
                    }
                    return;
                }
                appFailedAt = 0;
                java.util.Set<String> installed = new java.util.HashSet<>();
                for (String pkg : AppContentLogic.SOURCES.keySet()) if (installed(pkg)) installed.add(pkg);
                List<AppContentLogic.Record> fresh = new ArrayList<>();
                for (AppContentLogic.Record r : AppContentLogic.select(raw, installed)) {
                    if (AppContent.intent(context, r.link, r.pkg) != null) fresh.add(r);   // only titles the app can open
                }
                List<AppContentLogic.Record> merged = AppContentLogic.merge(appRecords, fresh, installed, now);
                String sig = AppContentLogic.signature(merged);
                int p = period(now);
                boolean first = !appRead;
                appRead = true;
                if (!AppContentLogic.reseed(appSignature, sig, appPeriod, p)) {
                    // Same titles: links the apps re-issued are taken in place (the grid stays as it is).
                    Map<String, String> links = new HashMap<>();
                    for (Pick n : appPicks(merged)) links.put(key(n), n.link);
                    int changed = 0;
                    for (List<Pick> l : java.util.Arrays.asList(appContent, homeGrid)) {
                        for (Pick o : l) {
                            String link = links.get(key(o));
                            if (link != null && !link.equals(o.link)) { o.link = link; changed++; }
                        }
                    }
                    if (changed > 0) {
                        appRecords = merged;
                        saveApp();
                        io.execute(this::save);
                        AppLog.w("app titles: same titles, " + changed + " links updated (grid kept)");
                    }
                    if (first) post(done);
                    return;
                }
                String why = AppContentLogic.why(appSignature, sig, appPeriod, p);
                appRecords = merged;
                appContent = appPicks(merged);
                appSignature = sig;
                appPeriod = p;
                appSeed = now == appSeed ? now + 1 : now;
                Map<String, Integer> per = new LinkedHashMap<>();
                for (AppContentLogic.Record r : merged) per.merge(r.service(), 1, Integer::sum);
                AppLog.w("app titles: " + merged.size() + " " + per + " (read " + raw.size() + " rows; new mix: " + why + ")");
                saveApp();
                post(done);
            } finally {
                appRunning.set(false);
            }
        });
    }

    private void post(Runnable r) {
        if (r != null) main.post(r);
    }

    /** The apps' lists changed (an app refreshed its channel): read again a moment later, once for a burst. */
    private void observeApps() {
        if (appObserver != null) return;
        appObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                main.removeCallbacks(appChanged);
                main.postDelayed(appChanged, 3000);
            }
        };
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            cr.registerContentObserver(android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, true, appObserver);
            cr.registerContentObserver(android.media.tv.TvContract.Channels.CONTENT_URI, true, appObserver);
        } catch (RuntimeException e) {
            AppLog.w("app titles: no change notices (" + e.getMessage() + ")");
        }
    }

    private static List<Pick> appPicks(List<AppContentLogic.Record> records) {
        List<Pick> out = new ArrayList<>();
        for (AppContentLogic.Record r : records) {
            Pick p = new Pick();
            p.kind = "app";
            p.id = (r.pkg + "|" + AppContentLogic.normTitle(r.title)).hashCode();
            p.title = r.title;
            p.backdrop = r.art;
            p.pkg = r.pkg;
            p.app = r.service();
            p.link = r.link;
            p.detailed = true;   // nothing to look up: the app said it all
            out.add(p);
        }
        return out;
    }

    private void saveApp() {
        try {
            JSONArray a = new JSONArray();
            for (AppContentLogic.Record r : appRecords) {
                a.put(new JSONObject().put("pkg", r.pkg).put("channel", r.channel == null ? "" : r.channel).put("title", r.title)
                        .put("art", r.art).put("link", r.link).put("type", r.type).put("contentId", r.contentId == null ? "" : r.contentId)
                        .put("readAt", r.readAt));
            }
            cache.edit().putString("appRecords", a.toString()).putString("appSignature", appSignature)
                    .putInt("appPeriod", appPeriod).putLong("appSeed", appSeed).apply();
        } catch (Exception e) {
            AppLog.w("app titles: not saved (" + e.getMessage() + ")");
        }
    }

    private static List<AppContentLogic.Record> parseRecords(JSONArray a) {
        List<AppContentLogic.Record> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("pkg").isEmpty() || o.optString("title").isEmpty()) continue;
            AppContentLogic.Record r = new AppContentLogic.Record();
            r.pkg = o.optString("pkg");
            r.channel = o.optString("channel").isEmpty() ? null : o.optString("channel");
            r.title = o.optString("title");
            r.art = o.optString("art").isEmpty() ? null : o.optString("art");
            r.link = o.optString("link").isEmpty() ? null : o.optString("link");
            r.type = o.optInt("type", -1);
            r.contentId = o.optString("contentId").isEmpty() ? null : o.optString("contentId");
            r.readAt = o.optLong("readAt");
            out.add(r);
        }
        return out;
    }

    /**
     * The titles whose details and pictures are fetched for Home: the list's first HOME_DETAILED that pass the checks
     * (not for children, its app on the TV when known, one per franchise), in the list's order (fresh first, the
     * services taking turns).
     */
    private List<Pick> homeCandidates() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        Map<String, Boolean> have = new HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : picks) {
            if (out.size() >= HOME_DETAILED) break;
            if (p.kids) continue;
            if (p.detailed && (p.pkg == null || !have.computeIfAbsent(p.pkg, this::installed))) continue;
            if (!seen.add(franchise(p.title))) continue;
            out.add(p);
        }
        return out;
    }

    /** The mix's pool: candidates on a known installed service, MixLogic.perService of each (about twice the grid), in order. */
    private static List<Pick> homePool(List<Pick> candidates) {
        java.util.Set<String> services = new java.util.HashSet<>();
        for (Pick p : candidates) if (p.detailed && p.app != null) services.add(p.app);
        int cap = MixLogic.perService(services.size(), HOME_GRID, HOME_PER_SERVICE);
        Map<String, Integer> per = new HashMap<>();
        List<Pick> out = new ArrayList<>();
        for (Pick p : candidates) {
            if (!p.detailed || p.app == null) continue;
            int n = per.containsKey(p.app) ? per.get(p.app) : 0;
            if (n >= cap) continue;
            per.put(p.app, n + 1);
            out.add(p);
        }
        return out;
    }

    /** Home, Movies and Shows have nothing yet and their list is on its way (the grid shows skeletons meanwhile). */
    boolean listLoading() {
        return (hasKey(context) && picks.isEmpty() && (fetching || failedAt == 0)) || (homeGrid.isEmpty() && !appRead);
    }
    /** Movies kept in the list for the Movies tab: its 15, with room for the ones the app and franchise checks drop. */
    static final int MOVIES_WANTED = 22;
    private final Map<String, String> poolLogged = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Movies or Shows: a pool of 15 titles, with the same conditions as now() (that kind only, its app on the TV, never
     * two from one franchise), up to 3 of them on Crunchyroll (fewer when there are fewer; the others fill in). In the
     * picks' own order, so the pool stays the same until the picks are refreshed. Crunchyroll's are spread out (one in
     * each group of five), so every 10 shown has some.
     */
    List<Pick> tabPool(String kind) {
        List<Pick> crunchy = new ArrayList<>(), others = new ArrayList<>();
        if (!hasKey(context)) return crunchy;
        Map<String, Boolean> have = new HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : picks) {
            if (!(kind == null || kind.equals(p.kind))) continue;
            if (p.kids) continue;   // children's content: not recommended (cached ones included)
            if (p.detailed && (p.pkg == null || !have.computeIfAbsent(p.pkg, this::installed))) continue;   // app missing: skip
            if (!seen.add(franchise(p.title))) continue;
            ("crunchyroll".equals(p.app) ? crunchy : others).add(p);
        }
        int cr = Math.min(crunchy.size(), Math.max(TAB_CRUNCHYROLL, TAB_POOL - others.size()));
        int rest = Math.min(others.size(), TAB_POOL - cr);
        // Bollywood and Hollywood taking turns (Hindi by TMDB's original language; the rest is "Hollywood"), each in
        // the list's own order; when one runs short the other fills in.
        // Freshest first: this week's titles, then this month's, then the older popular ones; within each tier,
        // Bollywood and Hollywood take turns (so an older Hindi title never comes before a new one of either).
        List<Pick> out = new ArrayList<>();
        for (int tier = TIER_WEEK; tier <= TIER_OLDER && out.size() < rest; tier++) {
            List<Pick> hindi = new ArrayList<>(), west = new ArrayList<>();
            for (Pick p : others) if (Math.min(p.tier, TIER_OLDER) == tier) ("hi".equals(p.lang) ? hindi : west).add(p);
            for (int i = 0; out.size() < rest && (i < west.size() || i < hindi.size()); i++) {
                if (i < west.size()) out.add(west.get(i));
                if (out.size() < rest && i < hindi.size()) out.add(hindi.get(i));
            }
        }
        int step = cr == 0 ? 0 : Math.max(1, (out.size() + cr) / cr);
        for (int i = 0; i < cr; i++) out.add(Math.min(out.size(), i * step + Math.min(2, step - 1)), crunchy.get(i));
        // Logged when it changes: adb logcat -s VYRO | grep "tab pool"
        int hi = 0, week = 0;
        for (Pick p : out) {
            if ("hi".equals(p.lang)) hi++;
            if (p.tier == TIER_WEEK) week++;
        }
        String line = out.size() + " (" + week + " out this week, " + hi + " Bollywood, " + cr + " Crunchyroll; " + (others.size() + crunchy.size()) + " passed the checks)";
        if (!line.equals(poolLogged.put(String.valueOf(kind), line))) AppLog.w("tab pool " + kind + ": " + line);
        return out;
    }

    /** The 12 of tabPool(kind) shown this hour: a window that moves by five each hour, round the 15. */
    List<Pick> tabShown(String kind) {
        List<Pick> pool = tabPool(kind);
        if (pool.size() <= TAB_SHOWN) return pool;
        long hour = System.currentTimeMillis() / 3600_000L;
        int start = (int) ((hour * 5) % pool.size());
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < TAB_SHOWN; i++) out.add(pool.get((start + i) % pool.size()));
        return out;
    }

    /** The same choice (same conditions, same hourly turn), `count` of them. */
    List<Pick> now(String kind, int count) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind)) && !p.kids
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < count; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            Long failed = imageFailedAt.get(key);
            long since = failed == null ? -1 : System.currentTimeMillis() - failed;
            if (since >= 0 && since < IMAGE_RETRY) return null;   // failed a moment ago: the card shows its fallback
            if (!pending.add(key)) return null;
        }
        imagesIo.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** Its image failed (download or decoding): show the fallback card, not a skeleton. */
    boolean imageFailed(Pick p) {
        return p != null && (p.backdrop == null || failedImages.contains(p.backdrop));
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        String name = p.backdrop.startsWith("/") ? p.backdrop.replace("/", "")   // TMDB's path
                : "app-" + Integer.toHexString(p.backdrop.hashCode()) + "-" + p.backdrop.length();   // an app's address
        return new File(new File(context.getCacheDir(), "recs"), name);
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void writeFile(File f, byte[] data) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(data);
            }
            if (!part.renameTo(f)) part.delete();
            else if (dir != null) prune(dir);
        } catch (IOException | RuntimeException e) {
            AppLog.w("app picture not kept: " + e.getMessage());
        }
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    private final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Checks whether anything needs fetching, in the background: the check reads the cache, the app list (one package
     * query per streaming service) and files, which on the main thread delayed the first frames after Home.
     */
    void refreshIfStale() {
        if (appRead && period(System.currentTimeMillis()) != appPeriod) refreshAppContent(this::notifyListeners);   // a new part of the day: a new mix
        if (!hasKey(context) || fetching || !checking.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                checkStale();
            } finally {
                checking.set(false);
            }
        });
    }

    private void checkStale() {
        if (fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language) || listShape != LIST_SHAPE;   // a list made before the extra movies
        // Explore: its own 24-hour cache (a new key, country or language refetches at once; a failure waits 30 min).
        boolean exploreStale = EXPLORE_ON && (newKey || exploreAt == 0 || now - exploreAt > EXPLORE_TTL || now < exploreAt
                || !exploreLanguage.equals(Ui.language)) && now >= exploreRetryAt;
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!newKey) resumeArrivals();   // a read the process lost (killed, restarted) starts again without a new list
        if (!stale && !exploreStale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (exploreStale) {
                    exploreLoading = true;
                    notifyListeners();
                    long t0 = System.currentTimeMillis();
                    try {
                        if (trending()) fetchTrending(); else fetchExplore();
                        AppLog.w("explore: lookup done in " + (System.currentTimeMillis() - t0) + " ms");
                        exploreRetryAt = 0;
                        exploreError = "";
                    } catch (Exception e) {
                        // The cached titles stay (nothing is replaced by an empty or failed answer); try again later.
                        exploreRetryAt = System.currentTimeMillis() + EXPLORE_RETRY;
                        exploreError = e.getMessage() == null ? e.toString() : e.getMessage();
                        AppLog.w("explore: " + exploreError);
                    } finally {
                        exploreLoading = false;
                    }
                    save();
                    notifyListeners();   // Explore's cards now; their images load on their own threads
                }
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
                final String[] lastError = {""};
                List<Pick> todo = wanted();   // Explore's cards first, the large one leading
                int tried = todo.size();
                ExecutorService pool = lowPriority(4);
                try {
                    List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
                    for (Pick pick : todo) {
                        jobs.add(pool.submit(() -> {
                            try {
                                if (!pick.detailed) fetchDetails(pick);
                                if (pick.pkg != null) download(pick);
                            } catch (Exception e) {
                                failures.incrementAndGet();
                                lastError[0] = e.getMessage() == null ? e.toString() : e.getMessage();
                            }
                        }));
                    }
                    for (java.util.concurrent.Future<?> j : jobs) j.get();
                } finally {
                    pool.shutdownNow();
                }
                if (!LEGACY_LINKS_OFF) for (Pick pick : todo) findLink(pick);   // Watchmode: one by one (its quota)
                String last = lastError[0];
                int fails = failures.get();
                save();
                if (tried > 0 && fails == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore's grid, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (EXPLORE_ON) for (Pick p : explore()) if (seen.add(key(p))) out.add(p);
        // Home's titles come from the apps (refreshAppContent): nothing to look up for them on TMDB.
        return out;
    }

    /** Explore's services, and how many cards each gets: Netflix and Prime Video share 7, Crunchyroll always 3. */
    static final String[] EXPLORE = {"netflix", "prime", "crunchyroll"};
    /** The grid's cards (15; Explore itself is off, EXPLORE_ON). */
    static final int EXPLORE_CARDS = 12, CRUNCHYROLL = 3, NETFLIX = 4, PRIME = 3;
    /** Which service fills each grid slot, the large one first (a mix of sizes for each). */
    private static final String[] SLOT_SERVICE =
            {"netflix", "crunchyroll", "prime", "netflix", "prime", "crunchyroll", "netflix", "prime", "crunchyroll", "netflix"};
    private volatile String exploreNote = "";

    /**
     * Explore's 10 cards, in grid order: 4 Netflix, 3 Prime Video, 3 Crunchyroll (Netflix and Prime Video lend each
     * other slots when one has too few; Crunchyroll's 3 are never taken by the others). Only titles TMDB says stream on
     * that service in this country, with their app on the TV. Changes every hour, never two from the same franchise.
     * Fewer than 10 when the data has fewer: exploreNote() says why.
     */
    List<Pick> explore() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        // Before Explore's own first lookup, the other tabs' list may hold a few of this week's titles: showing those
        // dropped the skeletons for a half-empty grid until the real one came, a minute later.
        if (exploreAt == 0) return out;
        if (trending()) return exploreTrending();   // with Trakt: its trending titles, 3 Hindi, 2 anime, 5 Hollywood
        // The same mix for as long as the cached lookup lasts (24 h): coming back Home, or out of standby, shows the
        // same cards, whose images are already decoded, instead of a new mix waiting on skeletons. A new lookup (a
        // new day) brings a new mix.
        long turn = exploreAt / 3600_000L;
        Map<String, List<Pick>> pools = new HashMap<>();
        Map<String, Boolean> have = new HashMap<>();
        for (String app : EXPLORE) pools.put(app, new ArrayList<>());
        for (Pick p : picks) {
            if (!p.detailed || p.app == null || p.pkg == null || p.backdrop == null || !pools.containsKey(p.app)) continue;
            if (!inWindow(p.date)) continue;   // strictly the last 7 days: popularity or taste never let older ones in
            if (!have.computeIfAbsent(p.pkg, this::installed)) continue;
            pools.get(p.app).add(p);
        }
        // Each service's list from this hour's place on, so the grid changes every hour. With a history of titles
        // opened from these cards: new seasons of those shows first, then the best matches (their genres, newest
        // first), leaving out what was already opened. Without one, as before: no pretend personal order.
        List<Pick> past = historyCached();
        Map<Integer, Integer> taste = new HashMap<>();
        Set<String> opened = new HashSet<>();
        for (Pick h : past) {
            opened.add(key(h));
            for (int g : h.genres) taste.put(g, taste.getOrDefault(g, 0) + 1);
        }
        // Trakt, when signed in: movies you've watched are left out, your genres count, your shows' new episodes lead.
        Trakt.Taste trakt = tasteCached();
        opened.addAll(trakt.watchedMovies);
        for (Map.Entry<Integer, Integer> g : trakt.genres.entrySet()) taste.put(g.getKey(), taste.getOrDefault(g.getKey(), 0) + g.getValue());
        final int seenCount = past.size() + trakt.seen;
        boolean personal = !past.isEmpty() || !trakt.isEmpty();
        Map<String, java.util.ArrayDeque<Pick>> queues = new HashMap<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String app : EXPLORE) {
            List<Pick> pool = pools.get(app);
            java.util.ArrayDeque<Pick> q = new java.util.ArrayDeque<>();
            if (personal) {
                List<Pick> ranked = new ArrayList<>();
                for (Pick p : pool) if (p.followed || !opened.contains(key(p))) ranked.add(p);
                final Map<Pick, Double> score = new HashMap<>();
                for (Pick p : ranked) score.put(p, score(p, taste, seenCount));
                java.util.Collections.sort(ranked, (a, b) -> Double.compare(score.get(b), score.get(a)));
                // The best few take turns by the hour (variety), the rest follow in order.
                int window = Math.min(ranked.size(), 8), lead = 0;
                for (Pick p : ranked) if (p.followed) lead++;
                for (int i = 0; i < lead; i++) q.add(ranked.get(i));
                int span = Math.max(0, window - lead);
                for (int i = 0; i < span; i++) q.add(ranked.get(lead + (int) ((turn + i) % span)));
                for (int i = lead + span; i < ranked.size(); i++) q.add(ranked.get(i));
            } else {
                for (int i = 0; i < pool.size(); i++) q.add(pool.get((int) ((turn * 2 + i) % pool.size())));
            }
            queues.put(app, q);
        }
        // Netflix and Prime Video's 7: Hindi and Hollywood in the balance your history shows (both whenever both have
        // titles this week), other titles after them. Crunchyroll's 3 are as before.
        int hiSeen = 0, enSeen = 0;
        for (Pick h : past) {
            if ("hi".equals(h.lang)) hiSeen++;
            else if ("en".equals(h.lang)) enSeen++;
        }
        hiSeen += trakt.langs.getOrDefault("hi", 0);
        enSeen += trakt.langs.getOrDefault("en", 0);
        float hiShare = Math.max(0.3f, Math.min(0.7f, (hiSeen + 1f) / (hiSeen + enSeen + 2f)));
        int[] mix = new int[3];   // Hindi, Hollywood, other: picked so far
        int crunchy = 0, others = 0;
        for (String slot : SLOT_SERVICE) {
            Pick p = null;
            if (slot.equals("crunchyroll")) {
                p = next(queues.get("crunchyroll"), used);
                if (p != null) crunchy++;
            } else {
                String other = slot.equals("netflix") ? "prime" : "netflix";
                int want = mix[0] < hiShare * (mix[0] + mix[1] + 1) ? HINDI : HOLLYWOOD;
                p = nextOf(queues.get(slot), used, want);
                if (p == null) p = nextOf(queues.get(other), used, want);
                if (p == null) p = nextOf(queues.get(slot), used, want == HINDI ? HOLLYWOOD : HINDI);
                if (p == null) p = nextOf(queues.get(other), used, want == HINDI ? HOLLYWOOD : HINDI);
                if (p == null) p = next(queues.get(slot), used);
                if (p == null) p = next(queues.get(other), used);   // the two share their 7
                if (p != null) {
                    others++;
                    mix[category(p)]++;
                }
            }
            if (p != null) out.add(p);
        }
        if (out.size() < EXPLORE_CARDS) {
            exploreNote = Ui.trf("New in the last 7 days: {n} Netflix, {p} Prime Video ({region}), {c} Crunchyroll ({in}). Older titles are left out.",
                    "n", pools.get("netflix").size(), "p", pools.get("prime").size(), "region", REGION,
                    "c", pools.get("crunchyroll").size(), "in", CRUNCHYROLL_REGION);
        } else {
            exploreNote = "";
        }
        return out;
    }

    /** How well a title fits: a new season of a show you follow, then shared genres with what you opened, then newness. */
    private static double score(Pick p, Map<Integer, Integer> taste, int seen) {
        double s = p.followed ? 1000 : 0;
        int match = 0;
        for (int g : p.genres) match += taste.getOrDefault(g, 0);
        s += 10.0 * match / Math.max(1, seen);
        long days = daysAgo(p.date);
        s += days <= 30 ? 3 : days <= 90 ? 2 : days <= 365 ? 1 : 0;
        return s;
    }

    /** Days since "2026-10-02" (a large number when unknown). */
    static long daysAgo(String date) {
        if (date == null || date.length() < 10) return 100_000;
        try {
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)));
            return Math.max(0, (System.currentTimeMillis() - cal.getTimeInMillis()) / 86_400_000L);
        } catch (RuntimeException e) {
            return 100_000;
        }
    }

    // MARK: - Watch history: the titles opened from the home screen's cards (the only history the TV lets an app read)

    private static final int HISTORY_MAX = 60;

    /** Remembers a title opened from a card (newest first): Explore leans toward its genres and its new seasons. */
    void opened(Pick p) {
        if (p == null || p.kind == null) return;
        try {
            JSONArray old = new JSONArray(history.getString("history", "[]"));
            JSONArray now = new JSONArray();
            JSONArray genres = new JSONArray();
            for (int g : p.genres) genres.put(g);
            now.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                    .put("app", p.app == null ? "" : p.app).put("season", p.season).put("genres", genres)
                    .put("lang", p.lang == null ? "" : p.lang)
                    .put("at", System.currentTimeMillis()));
            for (int i = 0; i < old.length() && now.length() < HISTORY_MAX; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !(o.optInt("id") == p.id && p.kind.equals(o.optString("kind")))) now.put(o);
            }
            history.edit().putString("history", now.toString()).apply();
            historyMemo = null;
            Prefs.unhideWatch(continueHideKey(p));   // opened again from a card: back on the Continue row
        } catch (Exception ignored) {
        }
    }

    /**
     * Home's Continue row: the titles last opened from the cards (ContinueLogic picks which), each with its line
     * ("Season 2 · Opened yesterday") in `lines`. Read from the history in memory; no network.
     */
    // Continue Watching from Android TV's Watch Next list (what the apps themselves say you left half-way, with the
    // place you stopped): read in the background, shown first; the cards' own history only when it's empty.
    private volatile List<Pick> watchNext = new ArrayList<>();
    private volatile List<String> watchLines = new ArrayList<>();

    /** Reads Watch Next off the main thread; `done` runs on the main thread when the list changed. */
    void refreshWatchNext(Runnable done) {
        imagesIo.execute(() -> {
            List<Pick> picks = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            // Home Settings > Continue Watching (VYRO: the row is shown only when picked, as its permission is).
            boolean on = Prefs.topContent().equals(Prefs.TOP_CONTINUE);
            for (WatchNext.Item it : on ? WatchNext.load(context) : java.util.Collections.<WatchNext.Item>emptyList()) {
                if (picks.size() >= ContinueLogic.MAX) break;
                String art = it.picture();
                if (it.title == null || art == null) continue;   // a card needs its title and its picture
                Pick p = new Pick();
                p.kind = "watchnext";
                p.id = (it.pkg + "|" + it.title).hashCode();
                p.title = it.title;
                p.backdrop = art;
                p.pkg = it.pkg;
                p.app = serviceOf(it.pkg);
                p.link = it.intent;
                p.engaged = it.engaged;
                p.position = it.position;
                picks.add(p);
                float pr = it.progress();
                String where = it.episode != null ? it.episode : it.app != null ? it.app : "";
                lines.add(pr > 0 ? (where.isEmpty() ? "" : where + " · ") + Ui.trf("{p}% watched", "p", Math.round(pr * 100)) : where);
            }
            boolean changed = picks.size() != watchNext.size();
            for (int i = 0; !changed && i < picks.size(); i++) changed = !key(picks.get(i)).equals(key(watchNext.get(i))) || !lines.get(i).equals(watchLines.get(i));
            if (!changed) return;
            watchNext = picks;
            watchLines = lines;
            if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
        });
    }

    private static String serviceOf(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg)) return s[1];
        return null;
    }

    List<Pick> continueShown(List<String> lines) {
        List<Pick> wn = watchNext;
        if (!wn.isEmpty()) {
            lines.addAll(watchLines);
            return new ArrayList<>(wn);
        }
        List<Pick> history = new ArrayList<>(historyCached());
        java.util.Set<String> hidden = Prefs.watchHidden();
        if (!hidden.isEmpty()) for (java.util.Iterator<Pick> i = history.iterator(); i.hasNext(); ) if (ContinueLogic.stillHidden(hidden, continueHideKey(i.next()), 0, 0)) i.remove();
        List<ContinueLogic.Entry> entries = new ArrayList<>();
        for (Pick p : history) entries.add(new ContinueLogic.Entry(p.id, p.kind, p.app, p.openedAt));
        long now = System.currentTimeMillis();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        List<Pick> out = new ArrayList<>();
        for (ContinueLogic.Entry e : ContinueLogic.select(entries, now, this::appInstalled)) {
            Pick p = history.get(entries.indexOf(e));
            out.add(p);
            lines.add(ContinueLogic.line(p.kind, p.season, p.openedAt, now, zone));
        }
        return out;
    }

    /** A service's app ("netflix", "prime"…) is on the TV (any of its packages). */
    private boolean appInstalled(String app) {
        for (String[] s : SERVICES) if (s[1].equals(app) && installed(s[2])) return true;
        return false;
    }

    /**
     * How a Continue title is remembered as removed: Watch Next's by its app and title (WatchNext.hideKey, which its
     * reader filters), a card's by its kind and id. Null when the title can't be told apart (nothing is hidden then).
     */
    static String continueHideKey(Pick p) {
        if (p == null || p.kind == null) return null;
        if ("watchnext".equals(p.kind)) return p.pkg == null || p.title == null ? null : p.pkg + "|" + p.title;
        return p.id == 0 ? null : "card|" + p.kind + "|" + p.id;
    }

    /**
     * Takes one title off Home's Continue row for good (its history, its place in the picks and the other rows stay).
     * Writes to disk: call off the main thread. False if the title can't be told apart or the write failed.
     */
    boolean hideContinue(Pick p) {
        String key = continueHideKey(p);
        if (key == null) return false;
        java.util.Set<String> hidden = ContinueLogic.without(Prefs.watchHidden(), key);
        boolean fromWatchNext = "watchnext".equals(p.kind);
        hidden.add(ContinueLogic.hiddenEntry(key, fromWatchNext ? p.engaged : 0, fromWatchNext ? p.position : 0));
        if (!Prefs.setWatchHidden(hidden)) return false;
        if ("watchnext".equals(p.kind)) {   // off the list in memory now; the next read leaves it out (WatchNext.load)
            List<Pick> picks = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            List<Pick> wn = watchNext;
            List<String> wl = watchLines;
            for (int i = 0; i < wn.size(); i++) {
                if (key.equals(continueHideKey(wn.get(i)))) continue;
                picks.add(wn.get(i));
                lines.add(i < wl.size() ? wl.get(i) : "");
            }
            watchLines = lines;
            watchNext = picks;
        }
        return true;
    }

    /** Control Center: forgets the titles opened from the cards. */
    void clearHistory() {
        history.edit().remove("history").apply();
        historyMemo = null;
        Prefs.clearWatchHidden();   // removed titles may come back too
    }

    /**
     * NetRecovery: the internet is back. The list (and Explore's new releases) asked for again now if their last try
     * failed during the outage; an older failure (a key, a quota) keeps its wait. True if one was let go.
     */
    static boolean networkBack(long since) {
        Recs r;
        synchronized (Recs.class) {
            r = instance;
        }
        if (r == null) return false;
        boolean list = NetRecoveryLogic.eligible(r.failedAt, since);
        boolean explore = r.exploreRetryAt != 0 && NetRecoveryLogic.eligible(r.exploreRetryAt - EXPLORE_RETRY, since);
        if (list) r.failedAt = 0;
        if (explore) r.exploreRetryAt = 0;
        boolean images = !r.imageFailedAt.isEmpty();
        if (images) {   // pictures that failed offline: tried again now
            r.imageFailedAt.clear();
            r.notifyListeners();
        }
        return list || explore || images;
    }

    /** Control Center: a new list now (the cached one stays on screen until the new one is ready). */
    void refreshNow() {
        listAt = 0;
        failedAt = 0;
        refreshIfStale();
    }

    private volatile List<Pick> historyMemo;
    private volatile Trakt.Taste tasteMemo;

    /** history(), read from disk once (and again after a card is opened). */
    private List<Pick> historyCached() {
        List<Pick> h = historyMemo;
        if (h == null) historyMemo = h = history();
        return h;
    }

    /** Your Trakt taste, read from disk once (and again after each Trakt refresh, in the background). */
    private Trakt.Taste tasteCached() {
        Trakt.Taste t = tasteMemo;
        if (t == null) tasteMemo = t = Trakt.taste(context);
        return t;
    }

    /** The titles opened from the cards, newest first (empty: Explore isn't personalized). */
    List<Pick> history() {
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(history.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Pick p = new Pick();
                p.id = o.optInt("id");
                p.kind = o.optString("kind");
                p.title = o.optString("title");
                p.backdrop = o.optString("backdrop");
                p.app = o.optString("app").isEmpty() ? null : o.optString("app");
                p.season = o.optInt("season");
                p.lang = o.optString("lang");
                p.openedAt = o.optLong("at");
                JSONArray g = o.optJSONArray("genres");
                p.genres = new int[g == null ? 0 : g.length()];
                for (int j = 0; j < p.genres.length; j++) p.genres[j] = g.optInt(j);
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // MARK: - Explore from Trakt: what people are searching for and watching right now

    /** Explore's categories and how many cards each gets (with Trakt): Hindi 3, anime 2, Hollywood 5. */
    static final int T_HINDI = 0, T_ANIME = 1, T_HOLLYWOOD = 2, T_NONE = 3;
    private static final int[] TREND_COUNT = {3, 2, 5};
    /** Which category fills each grid slot, the large one first. */
    private static final int[] TREND_SLOTS = {T_HOLLYWOOD, T_HINDI, T_ANIME, T_HOLLYWOOD, T_HINDI, T_HOLLYWOOD, T_ANIME,
            T_HOLLYWOOD, T_HINDI, T_HOLLYWOOD};
    /** The services Explore's cards may come from (each still needs its app on the TV and TMDB availability). */
    private static final Set<String> TREND_SERVICES = new HashSet<>(Arrays.asList(
            "netflix", "prime", "crunchyroll", "hotstar", "max", "disney"));

    /**
     * Explore from Trakt's trending lists: switched off (it didn't work out). Explore is the last 7 days' releases again
     * (fetchExplore / explore()); Trakt still personalizes them when you're signed in.
     */
    static boolean trending() {
        return false;
    }

    /**
     * A title's category, from TMDB's details first (original language, countries, genres) and Trakt's as a second
     * opinion: anime (Trakt's "anime" genre, or Japanese animation), Hindi (original language Hindi), Hollywood
     * (English, made in the US). Anything else (a British drama, a Tamil film, a Korean series) is none of them.
     */
    static int trendCategory(Pick p) {
        String lang = p.lang != null && !p.lang.isEmpty() ? p.lang : p.tLang;
        boolean animation = false;
        for (int g : p.genres) animation |= g == ANIMATION;
        if (p.anime || ("ja".equals(lang) && animation)) return T_ANIME;
        if ("hi".equals(lang)) return T_HINDI;
        boolean us = (p.origin != null && p.origin.contains(",US,")) || "us".equals(p.tCountry);
        if ("en".equals(lang) && us) return T_HOLLYWOOD;
        return T_NONE;
    }

    /**
     * Trakt's lists, most interest first: trending searches (what people search for now, by searches), then trending
     * (most watchers in the last 24 h); plus India's trending and the anime genre's, so Hindi and anime have enough.
     * Each title's details (artwork, rating, runtime or season, language) and where it streams come from TMDB: a card
     * needs one of TREND_SERVICES with its app on the TV (Crunchyroll judged in India, the others in tmdb_region).
     */
    private void fetchTrending() throws Exception {
        Map<String, String> apps = detectApps(context);   // once per refresh, here in the background
        detectedMemo = apps;
        boolean any = false;
        for (String key : apps.keySet()) any |= TREND_SERVICES.contains(key);
        exploreNoApps = !any;
        List<Pick> base = new ArrayList<>();
        Map<Integer, List<Pick>> cached = new HashMap<>();
        for (Pick p : picks) {
            if (p.want == null && !p.followed) base.add(p);
            else if (p.trend > 0) {
                int cat = trendCategory(p);
                if (!cached.containsKey(cat)) cached.put(cat, new ArrayList<>());
                cached.get(cat).add(p);
            }
        }
        if (!any) {   // none of the supported apps: nothing to recommend, and nothing to ask Trakt or TMDB
            picks = base;
            exploreAt = System.currentTimeMillis();
            exploreDay = day(0);
            exploreLanguage = Ui.language;
            AppLog.w("trakt trending: no supported streaming app installed");
            return;
        }
        AppLog.w("trakt trending: apps found " + apps);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : base) seen.add(key(p));
        String[] lists = {
            "/search/recent_by_id/global/movies?extended=full&limit=100",
            "/search/recent_by_id/global/shows?extended=full&limit=100",
            "/movies/trending?extended=full&limit=100",
            "/shows/trending?extended=full&limit=100",
            "/movies/trending?extended=full&limit=60&countries=in",
            "/shows/trending?extended=full&limit=60&countries=in",
            "/shows/trending?extended=full&limit=60&genres=anime",
            "/movies/trending?extended=full&limit=30&genres=anime",
        };
        ExecutorService pool = lowPriority(6);
        try {
            java.util.concurrent.Future<?> taste = pool.submit(() -> {
                Trakt.refreshTaste(context);
                tasteMemo = Trakt.taste(context);
            });
            List<java.util.concurrent.Future<JSONArray>> answers = new ArrayList<>();
            for (String path : lists) answers.add(pool.submit(() -> Trakt.getPublic(path)));
            // In list order (searches first), each title once: Trakt's own language, country and genres kept.
            Map<String, Pick> found = new LinkedHashMap<>();
            int ok = 0, rank = 0;
            for (int l = 0; l < answers.size(); l++) {
                JSONArray a;
                try {
                    a = answers.get(l).get();
                    ok++;
                } catch (Exception e) {
                    AppLog.w("trakt trending " + lists[l].replaceAll("\\?.*", "") + ": " + e.getMessage());
                    continue;
                }
                for (int i = 0; i < a.length(); i++) {
                    JSONObject row = a.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject item = row.optJSONObject("movie");
                    String kind = "movie";
                    if (item == null) {
                        item = row.optJSONObject("show");
                        kind = "tv";
                    }
                    JSONObject ids = item == null ? null : item.optJSONObject("ids");
                    int tmdb = ids == null ? 0 : ids.optInt("tmdb");
                    if (tmdb <= 0) continue;   // no TMDB id: no artwork or availability to check
                    Pick p = new Pick();
                    p.id = tmdb;
                    p.kind = kind;
                    if (seen.contains(key(p)) || found.containsKey(key(p))) continue;
                    p.title = item.optString("title");
                    p.tLang = item.optString("language", "");
                    p.tCountry = item.optString("country", "");
                    JSONArray g = item.optJSONArray("genres");
                    for (int j = 0; g != null && j < g.length(); j++) p.anime |= "anime".equals(g.optString(j));
                    p.trend = ++rank;
                    p.want = p.anime ? "crunchyroll" : "trend";   // anime: Crunchyroll (India) preferred when it's there
                    found.put(key(p), p);
                }
            }
            if (ok == 0) throw new IOException("Trakt didn't answer");   // the cached cards stay
            // Details and availability, 6 at a time, the most searched/watched first (up to 150 titles).
            List<Pick> list = new ArrayList<>(found.values());
            if (list.size() > 150) list = new ArrayList<>(list.subList(0, 150));
            List<java.util.concurrent.Future<?>> details = new ArrayList<>();
            for (Pick p : list) {
                details.add(pool.submit(() -> {
                    try {
                        fetchDetails(p);
                    } catch (Exception ignored) {
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : details) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
            try {
                taste.get();
            } catch (Exception ignored) {
            }
            int[] count = new int[4];
            int unavailable = 0;
            List<Pick> kept = new ArrayList<>();
            for (Pick p : list) {
                if (!p.detailed || p.backdrop == null || p.title == null || p.title.trim().isEmpty()) continue;
                if (!usable(p) || !apps.containsKey(p.app)) {   // not on an app here
                    unavailable++;
                    continue;
                }
                int cat = trendCategory(p);
                if (cat == T_NONE) continue;
                count[cat]++;
                if (seen.add(key(p))) kept.add(p);
            }
            AppLog.w("trakt trending: " + found.size() + " titles, " + list.size() + " looked up; on your apps: "
                    + count[T_HINDI] + " Hindi, " + count[T_ANIME] + " anime, " + count[T_HOLLYWOOD] + " Hollywood ("
                    + unavailable + " not on your services); by service: " + byService(kept));
            // Categories Trakt's trending can't fill on your apps: this week's real releases of that category on your
            // installed services (each service's region; Crunchyroll India's), checked against the 7-day window.
            List<Integer> short_ = new ArrayList<>();
            for (int cat = 0; cat < 3; cat++) if (count[cat] < TREND_COUNT[cat] * 2) short_.add(cat);
            if (!short_.isEmpty()) {
                List<Pick> recent = recentFallback(pool, apps, short_, seen);
                for (Pick p : recent) {
                    count[trendCategory(p)]++;
                    kept.add(p);
                }
                AppLog.w("explore fallback (last 7 days): " + recent.size() + " added, by service: " + byService(recent));
            }
            // A category Trakt or TMDB left empty this time keeps its cached cards (not replaced by nothing).
            for (int cat = 0; cat < 3; cat++) {
                if (count[cat] > 0 || !cached.containsKey(cat)) continue;
                for (Pick old : cached.get(cat)) if (seen.add(key(old))) kept.add(old);
            }
            List<Pick> merged = new ArrayList<>(base);
            merged.addAll(kept);
            picks = merged;
            exploreAt = System.currentTimeMillis();
            exploreDay = day(0);
            exploreLanguage = Ui.language;
        } finally {
            pool.shutdownNow();
        }
    }

    /** "netflix=4, prime=2" for the log. */
    private static String byService(List<Pick> list) {
        Map<String, Integer> n = new java.util.TreeMap<>();
        for (Pick p : list) n.put(p.app + "/" + new String[] {"hi", "anime", "hollywood", "-"}[trendCategory(p)],
                n.getOrDefault(p.app + "/" + new String[] {"hi", "anime", "hollywood", "-"}[trendCategory(p)], 0) + 1);
        return n.toString();
    }

    /**
     * This week's releases (movies out, or shows with an episode, in the last 7 days) for the categories trending left
     * short, on the installed services: Hindi (original language Hindi), anime (Japanese animation), Hollywood (English,
     * from the US). Ranked after every trending title. Real releases only: each one's dates checked again.
     */
    private List<Pick> recentFallback(ExecutorService pool, Map<String, String> apps, List<Integer> cats,
                                      java.util.Set<String> seen) throws Exception {
        List<String> services = new ArrayList<>();
        for (String key : apps.keySet()) if (TREND_SERVICES.contains(key)) services.add(key);
        Map<String, Integer> ids = exploreProviders(pool, services);
        String start = windowStart(), end = windowEnd();
        Map<String, java.util.concurrent.Future<JSONArray>> searches = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : ids.entrySet()) {
            String app = e.getKey(), region = regionFor(app);
            boolean crunchy = app.equals("crunchyroll");
            String only = "&language=" + lang() + "&watch_region=" + region
                    + "&with_watch_monetization_types=" + URLEncoder.encode(crunchy ? "flatrate|ads|free" : "flatrate", "UTF-8")
                    + "&include_adult=false&sort_by=popularity.desc&with_watch_providers=" + e.getValue();
            for (int cat : cats) {
                if (crunchy && cat != T_ANIME) continue;   // Crunchyroll is anime
                String filter = cat == T_HINDI ? "&with_original_language=hi"
                        : cat == T_ANIME ? "&with_original_language=ja&with_genres=" + ANIMATION
                        : "&with_original_language=en&with_origin_country=US";
                searches.put(app + "|" + cat + "|tv", pool.submit(() -> get("/discover/tv?air_date.gte=" + start
                        + "&air_date.lte=" + end + filter + only).getJSONArray("results")));
                searches.put(app + "|" + cat + "|movie", pool.submit(() -> get("/discover/movie?region=" + region
                        + "&release_date.gte=" + start + "&release_date.lte=" + end + "&with_release_type="
                        + URLEncoder.encode("3|4|6", "UTF-8") + filter + only).getJSONArray("results")));
            }
        }
        Map<String, Pick> candidates = new LinkedHashMap<>();
        int rank = 100_000;   // after every trending title
        for (Map.Entry<String, java.util.concurrent.Future<JSONArray>> e : searches.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            JSONArray results;
            try {
                results = e.getValue().get();
            } catch (Exception ex) {
                AppLog.w("explore fallback " + e.getKey() + ": " + ex.getMessage());
                continue;
            }
            List<Pick> some = new ArrayList<>();
            for (int i = 0; i < results.length() && some.size() < 10; i++) add(some, results.getJSONObject(i), parts[2]);
            for (Pick p : some) {
                if (seen.contains(key(p)) || candidates.containsKey(key(p))) continue;
                p.want = parts[0];
                p.trend = ++rank;
                p.anime = Integer.parseInt(parts[1]) == T_ANIME;
                candidates.put(key(p), p);
            }
        }
        List<java.util.concurrent.Future<?>> details = new ArrayList<>();
        for (Pick p : candidates.values()) {
            details.add(pool.submit(() -> {
                try {
                    fetchDetails(p);
                } catch (Exception ignored) {
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : details) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        List<Pick> out = new ArrayList<>();
        for (Pick p : candidates.values()) {
            if (!usable(p) || !apps.containsKey(p.app) || !inWindow(p.date, start, end)) continue;
            int cat = trendCategory(p);
            if (!cats.contains(cat) || !seen.add(key(p))) continue;
            out.add(p);
        }
        return out;
    }

    /** Enough to make a card: a title, artwork, details, and a service from the list with its app on this TV. */
    private boolean usable(Pick p) {
        return p.detailed && p.title != null && !p.title.trim().isEmpty() && p.backdrop != null && !p.backdrop.isEmpty()
                && p.pkg != null && p.app != null && TREND_SERVICES.contains(p.app);
    }

    /**
     * Explore's 10 with Trakt: 3 Hindi, 2 anime, 5 Hollywood, each in Trakt's order (searched, then watched), the top
     * ones taking turns as Explore is opened; titles you've watched (Trakt) or opened from the cards are left out; no
     * title or franchise twice. A category with too few leaves its slots empty (never filled with others).
     */
    private volatile boolean exploreNoApps;

    private List<Pick> exploreTrending() {
        List<Pick> out = new ArrayList<>();
        if (exploreNoApps) {
            exploreNote = Ui.tr("Recommendations need a supported streaming app: Netflix, Prime Video, JioHotstar, Disney+ Hotstar, Max or Crunchyroll.");
            return out;
        }
        Trakt.Taste taste = tasteCached();
        Set<String> skip = new HashSet<>(taste.watchedMovies);
        for (Pick h : historyCached()) if (!"tv".equals(h.kind)) skip.add(key(h));
        List<List<Pick>> pools = new ArrayList<>();
        for (int c = 0; c < 3; c++) pools.add(new ArrayList<>());
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.trend <= 0 || !usable(p) || skip.contains(key(p))) continue;   // cached entries checked again
            if (!have.computeIfAbsent(p.pkg, this::installed)) continue;
            int cat = trendCategory(p);
            if (cat != T_NONE) pools.get(cat).add(p);
        }
        long turn = exploreAt / 3600_000L;   // fixed for the cache's life, as in explore()
        List<java.util.ArrayDeque<Pick>> queues = new ArrayList<>();
        for (List<Pick> pool : pools) {
            // Released (or with a new episode) in the last 7 days first, in Trakt's order; then the rest of what's
            // trending, in Trakt's order. Within each, the top few take turns as Explore is opened.
            // Trending first (this week's releases among them first), then the 7-day fallback's releases.
            List<Pick> recent = new ArrayList<>(), rest = new ArrayList<>(), fallback = new ArrayList<>();
            for (Pick p : pool) (p.trend >= 100_000 ? fallback : inWindow(p.date) ? recent : rest).add(p);
            java.util.ArrayDeque<Pick> q = new java.util.ArrayDeque<>();
            for (List<Pick> part : Arrays.asList(recent, rest, fallback)) {
                java.util.Collections.sort(part, (a, b) -> Integer.compare(a.trend, b.trend));
                int window = Math.min(part.size(), part == recent ? part.size() : 8);
                for (int i = 0; i < window; i++) q.add(part.get((int) ((turn + i) % window)));
                for (int i = window; i < part.size(); i++) q.add(part.get(i));
            }
            queues.add(q);
        }
        // Each slot from its category, spread over your services: the least-used service among the first few
        // candidates wins, and Netflix stops at 3 while another service can fill the slot (it came first in TMDB's
        // data far more often: six of seven cards were Netflix). Only then may it take what no other service can.
        java.util.Set<String> used = new java.util.HashSet<>();
        Map<String, Integer> perService = new HashMap<>();
        Pick[] chosen = new Pick[TREND_SLOTS.length];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < TREND_SLOTS.length; i++) {
                if (chosen[i] != null) continue;
                chosen[i] = pickBalanced(queues.get(TREND_SLOTS[i]), used, perService, pass == 0);
            }
        }
        int[] got = new int[3];
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i] == null) continue;
            out.add(chosen[i]);
            got[TREND_SLOTS[i]]++;
        }
        if (out.size() < EXPLORE_CARDS) {
            exploreNote = Ui.trf("On your apps now: {h} of 3 Hindi, {a} of 2 anime, {w} of 5 Hollywood (trending and this week's releases).",
                    "h", got[T_HINDI], "a", got[T_ANIME], "w", got[T_HOLLYWOOD]);
        } else {
            exploreNote = "";
        }
        return out;
    }

    /** Explore was opened (home screen shown, or its tab chosen): another mix of the cached eligible titles, no fetch. */
    /** No longer turns the mix: Explore keeps its cards for the whole life of its cache (see explore()). */
    void exploreOpened() {}

    private volatile boolean exploreLoading;
    private volatile String exploreError = "";

    /** Explore's first load: no cached titles yet and a lookup running or about to (the grid shows skeletons). */
    boolean exploreLoading() {
        if (!hasKey(context) || exploreAt != 0) return false;   // cached data (or its empty state) shows at once
        return exploreError.isEmpty();                           // the first load: until it lands, or fails
    }

    /** Why Explore's lookup failed with nothing cached (empty when it didn't). */
    String exploreError() { return exploreAt == 0 ? exploreError : ""; }

    // MARK: - Explore's 7-day window

    /** "2026-10-17" .. "2026-10-23": today and the previous 6 calendar days, in the TV's time zone. */
    static String windowStart() { return day(-(WINDOW_DAYS - 1)); }
    static String windowEnd() { return day(0); }

    /** Released (a movie) or with an episode out (a show) from windowStart() to today, both included. */
    static boolean inWindow(String date) {
        return inWindow(date, windowStart(), windowEnd());
    }

    static boolean inWindow(String date, String start, String end) {
        if (date == null || date.length() < 10) return false;
        String d = date.substring(0, 10);
        return d.compareTo(start) >= 0 && d.compareTo(end) <= 0;
    }

    /**
     * A movie's release date for the window: its first release worldwide when that's in the window, else its latest
     * release in that country so far (theatrical, digital or TV: a streaming premiere there counts), else the first
     * worldwide. Physical (disc) releases don't count.
     */
    static String movieDate(String primary, JSONObject releaseDates, String region, String start, String end) {
        if (inWindow(primary, start, end)) return primary;
        String best = "";
        JSONArray countries = releaseDates == null ? null : releaseDates.optJSONArray("results");
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            if (c == null || !region.equals(c.optString("iso_3166_1"))) continue;
            JSONArray dates = c.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject d = dates.optJSONObject(j);
                int type = d == null ? 0 : d.optInt("type");
                String when = d == null ? "" : d.optString("release_date", "");
                if (when.length() < 10 || (type != 4 && type != 6)) continue;   // digital or TV: never a cinema date
                when = when.substring(0, 10);
                if (when.compareTo(end) <= 0 && when.compareTo(best) > 0) best = when;
            }
        }
        if (inWindow(best, start, end)) return best;
        return primary == null ? "" : primary;
    }

    /** Why Explore has fewer than 10 cards (empty when it has all of them). */
    String exploreNote() { return exploreNote; }

    /** Netflix's most cards while another service can fill a slot. */
    static final int NETFLIX_CAP = 3;

    /**
     * The next title for a slot: among the first 6 still free in the queue (its order: trending, then fallback), the one
     * whose service has the fewest cards so far (the earliest on a tie); with `cap`, no Netflix title past NETFLIX_CAP.
     */
    static Pick pickBalanced(java.util.ArrayDeque<Pick> q, java.util.Set<String> used, Map<String, Integer> perService,
                             boolean cap) {
        if (q == null) return null;
        Pick best = null;
        int bestCount = Integer.MAX_VALUE, looked = 0;
        for (Pick p : q) {
            if (used.contains(key(p)) || used.contains("f:" + franchise(p.title))) continue;
            int n = perService.getOrDefault(p.app, 0);
            if (cap && "netflix".equals(p.app) && n >= NETFLIX_CAP) continue;
            if (n < bestCount) {
                best = p;
                bestCount = n;
            }
            if (++looked >= 6) break;
        }
        if (best == null) return null;
        q.remove(best);
        used.add(key(best));
        used.add("f:" + franchise(best.title));
        perService.put(best.app, perService.getOrDefault(best.app, 0) + 1);
        return best;
    }

    static final int HINDI = 0, HOLLYWOOD = 1, OTHER = 2;

    /**
     * Hindi: TMDB's original language is Hindi. Hollywood: English, made in the US (an English title from elsewhere,
     * or an Indian one in another language, is neither).
     */
    static int category(Pick p) {
        if ("hi".equals(p.lang)) return HINDI;
        if ("en".equals(p.lang) && p.origin != null && p.origin.contains(",US,")) return HOLLYWOOD;
        return OTHER;
    }

    /** The first title of that category still in the queue (in the queue's order), taken out of it; or null. */
    private static Pick nextOf(java.util.ArrayDeque<Pick> q, java.util.Set<String> used, int category) {
        if (q == null) return null;
        for (Pick p : q) {
            if (category(p) != category || used.contains(key(p)) || used.contains("f:" + franchise(p.title))) continue;
            q.remove(p);
            used.add(key(p));
            used.add("f:" + franchise(p.title));
            return p;
        }
        return null;
    }

    private static Pick next(java.util.ArrayDeque<Pick> q, java.util.Set<String> used) {
        while (q != null && !q.isEmpty()) {
            Pick p = q.poll();
            if (used.contains(key(p)) || used.contains("f:" + franchise(p.title))) continue;
            used.add(key(p));
            used.add("f:" + franchise(p.title));
            return p;
        }
        return null;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - What's New: arrivals (ArrivalsLogic, Arrivals)

    /** Starts the daily catalogue reads for the TV's services in the background (once at a time). */
    private void refreshArrivals(Map<Integer, String[]> services) {
        final Map<Integer, String> byId = new java.util.TreeMap<>();
        for (Map.Entry<Integer, String[]> e : services.entrySet()) byId.put(e.getKey(), e.getValue()[1]);
        StringBuilder saved = new StringBuilder();
        for (Map.Entry<Integer, String> e : byId.entrySet()) saved.append(saved.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
        cache.edit().putString("arrivalServices", saved.toString()).apply();   // for resumeArrivals
        readArrivals(byId);
    }

    /**
     * The services the last list found (saved by refreshArrivals): their reads start again when due, so one cut short
     * by the process ending doesn't wait for the next list. Each service's own schedule decides (Arrivals.due).
     */
    private void resumeArrivals() {
        long now = System.currentTimeMillis();
        long since = now - arrivalsCheckedAt;
        if (arrivalsRunning.get() || (since >= 0 && since < 15 * 60_000L)) return;   // the files are read at most every 15 min
        arrivalsCheckedAt = now;
        String saved = cache.getString("arrivalServices", "");
        if (saved.isEmpty()) return;
        Map<Integer, String> byId = new java.util.TreeMap<>();
        for (String part : saved.split(",")) {
            int c = part.indexOf(':');
            try {
                if (c > 0) byId.put(Integer.parseInt(part.substring(0, c)), part.substring(c + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!byId.isEmpty()) readArrivals(byId);
    }

    private void readArrivals(Map<Integer, String> byId) {
        if (!arrivalsRunning.compareAndSet(false, true)) return;
        arrivalsIo.execute(() -> {
            try {
                if (arrivals.refresh(this::get, byId, REGION)) {   // new arrivals: the list is made again with them
                    listAt = 0;
                    refreshIfStale();
                }
            } catch (Exception e) {
                AppLog.w("arrivals: " + e.getMessage());
            } finally {
                arrivalsRunning.set(false);
            }
        });
    }

    /**
     * This week's arrivals on the TV's services, as detailed picks still streaming on an installed service here
     * (the details are looked up once; titles already in the list reuse theirs). Kids' titles and, when hidden,
     * animation stay out, as everywhere.
     */
    private List<Pick> arrivalPicks(Map<Integer, String[]> services) {
        List<String> order = new ArrayList<>();
        for (String[] s : SERVICES) for (String[] v : services.values()) if (v[1].equals(s[1]) && !order.contains(s[1])) order.add(s[1]);
        Map<String, Pick> known = new HashMap<>();
        for (Pick old : picks) if (old.detailed) known.put(key(old), old);
        List<Pick> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : arrivals.current(order).entrySet()) {
            if (out.size() >= ARRIVALS_MAX) break;
            String[] parts = e.getKey().split(":");
            if (parts.length != 2) continue;
            Pick p = known.get(e.getKey());
            try {
                if (p == null) {
                    p = new Pick();
                    p.kind = parts[0];
                    p.id = Integer.parseInt(parts[1]);
                    fetchDetails(p);
                }
            } catch (Exception ex) {
                continue;
            }
            boolean animated = false;
            for (int g : p.genres) animated |= g == ANIMATION;
            if (p.pkg == null || p.backdrop == null || p.kids || (Prefs.hideAnimation() && animated)) continue;
            p.label = ArrivalsLogic.NEW_ON;
            p.labelApp = (String) e.getValue()[0];
            p.tier = TIER_WEEK;
            out.add(p);
        }
        if (!out.isEmpty()) AppLog.w("arrivals: " + out.size() + " new this week on the TV's services");
        return out;
    }

    /** The card's chip for a pick's label, "" for none (never a claim the data doesn't make). */
    static String labelText(Pick p) {
        if (p == null) return "";
        if (p.top10 > 0) return "Top 10 · #" + p.top10;   // the same in every language
        switch (p.label) {
            case ArrivalsLogic.NEW_ON:
                String name = p.labelApp == null ? null : SERVICE_NAME.get(p.labelApp);
                return name == null ? "" : Ui.trf("New on {service}", "service", name);
            case ArrivalsLogic.PREMIERE: return Ui.tr("Streaming premiere");
            case ArrivalsLogic.NEW_SEASON: return Ui.tr("New season");
            case ArrivalsLogic.NEW_EPISODE: return Ui.tr("New episode");
            default: return "";
        }
    }

    /** TMDB's release_dates of a movie, every country. */
    private static List<ArrivalsLogic.Release> releases(JSONObject releaseDates) {
        List<ArrivalsLogic.Release> out = new ArrayList<>();
        JSONArray countries = releaseDates == null ? null : releaseDates.optJSONArray("results");
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject c = countries.optJSONObject(i);
            JSONArray dates = c == null ? null : c.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject d = dates.optJSONObject(j);
                if (d != null) out.add(new ArrivalsLogic.Release(c.optString("iso_3166_1"), d.optInt("type"), d.optString("release_date", "")));
            }
        }
        return out;
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        refreshArrivals(services);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String base = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=" + WATCH_TYPES + "&include_adult=false"
                + "&sort_by=popularity.desc&without_genres=" + KIDS + (Prefs.hideAnimation() ? "," + ANIMATION : "");   // Kids: shows only (movies have no such genre)
        String common = base + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready). Most trending movies are still
        //    in cinemas (on no service yet), so few of them pass: the fresh releases below bring the new movies.
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Fresh on the TV's services, asked per service: movies first out, and shows with an episode out, in the
        //    past 7 days (this week), then in the past 30, by TMDB's popularity (its current one). No vote minimum for
        //    this week's: a title out yesterday has hardly any votes yet. Each service's queue is its trending titles,
        //    then this week's, then this month's; the services take turns, so one with many titles (Netflix) doesn't
        //    crowd out another (Prime Video). A failed query leaves the others.
        for (Pick h : hotMovies) h.tier = tierOf(h.date);
        for (Pick h : hotShows) h.tier = tierOf(h.date);
        Map<String, List<Pick>> movieQueues = new LinkedHashMap<>(), showQueues = new LinkedHashMap<>();
        for (Pick h : hotMovies) movieQueues.computeIfAbsent(h.app, x -> new ArrayList<>()).add(h);
        for (Pick h : hotShows) showQueues.computeIfAbsent(h.app, x -> new ArrayList<>()).add(h);
        // 0. Verified arrivals (new on a service here since yesterday's catalogue) go first in their service's queue.
        for (Pick a : arrivalPicks(services)) {
            Map<String, List<Pick>> q = "movie".equals(a.kind) ? movieQueues : showQueues;
            q.computeIfAbsent(a.labelApp, x -> new ArrayList<>()).add(0, a);
        }
        for (Map.Entry<Integer, String[]> sv : services.entrySet()) {
            String only = base + "&with_watch_providers=" + sv.getKey(), app = sv.getValue()[1];
            List<Pick> m = movieQueues.computeIfAbsent(app, x -> new ArrayList<>());
            List<Pick> t = showQueues.computeIfAbsent(app, x -> new ArrayList<>());
            m.addAll(tiered(discover("movie", "/discover/movie?primary_release_date.gte=" + day(-7)
                    + "&primary_release_date.lte=" + day(0) + only), TIER_WEEK));
            t.addAll(tiered(discover("tv", "/discover/tv?air_date.gte=" + day(-7) + "&air_date.lte=" + day(0) + only), TIER_WEEK));
            m.addAll(tiered(discover("movie", "/discover/movie?vote_count.gte=3&primary_release_date.gte=" + day(-30)
                    + "&primary_release_date.lte=" + day(-8) + only), TIER_MONTH));
            t.addAll(tiered(discover("tv", "/discover/tv?vote_count.gte=3&air_date.gte=" + day(-30) + "&air_date.lte=" + day(-8)
                    + only), TIER_MONTH));
        }
        List<Pick> freshMovies = takeTurns(new ArrayList<>(movieQueues.values()));
        List<Pick> freshShows = takeTurns(new ArrayList<>(showQueues.values()));
        // 3. The established popular ones, after the fresh: movies from the past year and shows with a new episode
        //    in the past 3 months (what the list was made of before; now only what fills in).
        List<Pick> newMovies = discover("movie", "/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common);
        List<Pick> newShows = discover("tv", "/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common);
        // Trending and fresh first (the services taking turns), then the popular ones; movies and shows alternating,
        // no repeats.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, freshMovies, freshShows, 120);
        int fresh = list.size();
        alternate(list, seen, newMovies, newShows, list.size() + 16);
        // 4. Movies' own grid needs 15 after the franchise and app checks: more of the year's popular movies until
        //    there are MOVIES_WANTED; the next page of that query only when page 1 runs out.
        int movieCount = 0;
        for (Pick x : list) if ("movie".equals(x.kind)) movieCount++;
        if (movieCount < MOVIES_WANTED) {
            int before = movieCount;
            List<Pick> more = new ArrayList<>(newMovies);
            for (int page = 2; page <= 3; page++) {
                for (Pick m : more) {
                    if (movieCount >= MOVIES_WANTED) break;
                    if (seen.add(key(m))) {
                        list.add(m);
                        movieCount++;
                    }
                }
                if (movieCount >= MOVIES_WANTED) break;
                more = discover("movie", "/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                        + "&primary_release_date.lte=" + day(0) + common + "&page=" + page);
                if (more.isEmpty()) break;
            }
            AppLog.w("movies: " + before + " in the main list, " + movieCount + " with the extra ones");
        }
        // 5. Bollywood: Hindi movies and shows on the TV's services (per service, taking turns): this week's, then
        //    this month's, then the year's popular ones; HINDI_WANTED of each kind, after the rest (by popularity
        //    alone they hardly appear). The tabs mix them in tier by tier.
        for (String kind : new String[] {"movie", "tv"}) {
            boolean movie = kind.equals("movie");
            List<List<Pick>> week = new ArrayList<>(), month = new ArrayList<>();
            for (int id : services.keySet()) {
                String only = base + "&with_watch_providers=" + id + "&with_original_language=hi";
                week.add(tiered(discover(kind, movie
                        ? "/discover/movie?primary_release_date.gte=" + day(-7) + "&primary_release_date.lte=" + day(0) + only
                        : "/discover/tv?air_date.gte=" + day(-7) + "&air_date.lte=" + day(0) + only), TIER_WEEK));
                month.add(tiered(discover(kind, movie
                        ? "/discover/movie?primary_release_date.gte=" + day(-30) + "&primary_release_date.lte=" + day(-8) + only
                        : "/discover/tv?air_date.gte=" + day(-30) + "&air_date.lte=" + day(-8) + only), TIER_MONTH));
            }
            List<Pick> hindiQueue = new ArrayList<>(takeTurns(week));
            hindiQueue.addAll(takeTurns(month));
            hindiQueue.addAll(discover(kind, movie
                    ? "/discover/movie?with_original_language=hi&vote_count.gte=5&primary_release_date.gte=" + day(-365)
                            + "&primary_release_date.lte=" + day(0) + common
                    : "/discover/tv?with_original_language=hi&air_date.gte=" + day(-180) + "&air_date.lte=" + day(0) + common));
            int added = 0, addedWeek = 0;
            for (Pick h : hindiQueue) {
                if (added >= HINDI_WANTED) break;
                if (seen.add(key(h))) {
                    list.add(h);
                    added++;
                    if (h.tier == TIER_WEEK) addedWeek++;
                }
            }
            AppLog.w("bollywood " + kind + ": " + added + " added (" + addedWeek + " from this week)");
        }
        int week = 0, month = 0;
        for (Pick x : list) if (x.tier == TIER_WEEK) week++; else if (x.tier == TIER_MONTH) month++;
        AppLog.w("list: " + list.size() + " titles (" + (hotMovies.size() + hotShows.size()) + " trending on your apps; "
                + week + " out this week, " + month + " this month)");
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        for (Pick old : picks) if ((old.want != null || old.followed) && seen.add(key(old))) list.add(old);   // Explore's own
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        mixSeed = listAt;   // a new list: a new mix on Home
        listLanguage = Ui.language;
        listShape = LIST_SHAPE;
    }

    /**
     * Explore's titles: per service (Netflix and Prime Video in tmdb_region, Crunchyroll in India), the movies released
     * and the shows with an episode out in the last 7 days, streaming on it there now (TMDB/JustWatch availability),
     * plus new episodes of shows opened from the cards. Each one detailed and checked against the window; the details
     * of titles already known this day are reused (no repeated requests).
     */
    private void fetchExplore() throws Exception {
        String start = windowStart(), end = windowEnd();
        // Fresh details for every title (the 24-hour cache ran out); the old ones are kept per service until a new
        // answer replaces them.
        Map<String, List<Pick>> cached = new HashMap<>();
        List<Pick> base = new ArrayList<>();
        for (Pick p : picks) {
            if (p.want == null && !p.followed) base.add(p);
            else if (p.want != null) {
                if (!cached.containsKey(p.want)) cached.put(p.want, new ArrayList<>());
                cached.get(p.want).add(p);
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : base) seen.add(key(p));
        // Independent requests run 6 at a time (they ran one by one: ~150 in a row, about a minute).
        ExecutorService pool = lowPriority(6);
        try {
            java.util.concurrent.Future<?> trakt = pool.submit(() -> {
                Trakt.refreshTaste(context);
                tasteMemo = Trakt.taste(context);
            });
            Map<String, Integer> ids = exploreProviders(pool);
            // 1. Every service's searches at once.
            Map<String, List<java.util.concurrent.Future<List<Pick>>>> searches = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : ids.entrySet()) {
                List<java.util.concurrent.Future<List<Pick>>> mine = new ArrayList<>();
                for (String q : exploreQueries(e.getKey(), e.getValue(), start, end)) {
                    final String kind = q.startsWith("/discover/tv") ? "tv" : "movie";
                    mine.add(pool.submit(() -> {
                        JSONArray results = get(q).getJSONArray("results");
                        List<Pick> some = new ArrayList<>();
                        for (int i = 0; i < results.length(); i++) add(some, results.getJSONObject(i), kind);
                        return some;
                    }));
                }
                searches.put(e.getKey(), mine);
            }
            // 2. Each title once per service (several searches find the same one), up to 40: details all at once.
            Map<String, List<Pick>> candidates = new LinkedHashMap<>();
            List<java.util.concurrent.Future<?>> details = new ArrayList<>();
            int answered = 0;
            for (Map.Entry<String, List<java.util.concurrent.Future<List<Pick>>>> e : searches.entrySet()) {
                String app = e.getKey();
                Map<String, Pick> unique = new LinkedHashMap<>();
                int ok = 0;
                for (java.util.concurrent.Future<List<Pick>> f : e.getValue()) {
                    try {
                        for (Pick p : f.get()) if (!unique.containsKey(key(p)) && !seen.contains(key(p))) unique.put(key(p), p);
                        ok++;
                    } catch (Exception ex) {
                        AppLog.w("explore " + app + ": " + ex.getMessage());
                    }
                }
                if (ok == 0) {   // TMDB didn't answer for this service: its cached titles stay
                    List<Pick> mine = cached.get(app);
                    if (mine != null) for (Pick old : mine) if (seen.add(key(old))) base.add(old);
                    candidates.put(app, new ArrayList<>());
                    continue;
                }
                answered++;
                List<Pick> list = new ArrayList<>();
                for (Pick p : unique.values()) {
                    if (list.size() >= 40) break;
                    p.want = app;
                    list.add(p);
                    details.add(pool.submit(() -> {
                        try {
                            fetchDetails(p);
                        } catch (Exception ignored) {
                        }
                    }));
                }
                candidates.put(app, list);
            }
            if (answered == 0) throw new IOException("TMDB didn't answer for Explore");   // keep everything as it was
            // 3. Followed shows (Trakt's, and the cards you opened): their details at the same time.
            try {
                trakt.get();
            } catch (Exception ignored) {
            }
            List<Pick> followed = new ArrayList<>();
            java.util.Set<String> followKeys = new java.util.HashSet<>();
            for (int id : Trakt.taste(context).shows) {
                Pick pick = new Pick();
                pick.id = id;
                pick.kind = "tv";
                if (seen.contains(key(pick)) || !followKeys.add(key(pick))) continue;
                followed.add(pick);
            }
            for (Pick past : history()) {
                if (!"tv".equals(past.kind)) continue;
                Pick pick = new Pick();
                pick.id = past.id;
                pick.kind = "tv";
                pick.title = past.title;
                pick.backdrop = past.backdrop;
                pick.want = past.app;
                if (seen.contains(key(pick)) || !followKeys.add(key(pick))) continue;
                followed.add(pick);
            }
            for (Pick pick : followed) {
                details.add(pool.submit(() -> {
                    try {
                        fetchDetails(pick);
                        if (pick.pkg == null && pick.want == null) {   // not here: on Crunchyroll in India?
                            pick.want = "crunchyroll";
                            fetchDetails(pick);
                        }
                    } catch (Exception ignored) {
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : details) {
                try {
                    f.get();
                } catch (Exception ignored) {
                }
            }
            // 4. Only what streams there and is out in the window (TMDB's discover dates are checked again).
            List<Pick> found = new ArrayList<>();
            for (Map.Entry<String, List<Pick>> e : candidates.entrySet()) {
                int kept = 0, old = 0;
                for (Pick pick : e.getValue()) {
                    if (!pick.detailed) continue;
                    if (!inWindow(pick.date, start, end)) {
                        old++;
                        continue;
                    }
                    if (pick.pkg != null && seen.add(key(pick))) {
                        found.add(pick);
                        kept++;
                    }
                }
                AppLog.w("explore " + e.getKey() + " (" + regionFor(e.getKey()) + ", " + start + ".." + end + "): "
                        + e.getValue().size() + " looked up, " + kept + " kept, " + old + " older left out");
            }
            for (Pick pick : followed) {
                if (!pick.detailed || pick.pkg == null || pick.backdrop == null || !inWindow(pick.date, start, end)) continue;
                if (pick.want == null) pick.want = pick.app;
                pick.followed = true;
                if (seen.add(key(pick))) found.add(pick);
            }
            List<Pick> merged = new ArrayList<>(base);
            merged.addAll(found);
            picks = merged;
            exploreAt = System.currentTimeMillis();
            exploreDay = end;
            exploreLanguage = Ui.language;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One service's searches: shows with an episode in the window, movies released in it; Hindi ones by themselves. */
    private List<String> exploreQueries(String app, int provider, String start, String end) throws Exception {
        String region = regionFor(app);
        boolean crunchy = app.equals("crunchyroll");
        String only = "&language=" + lang() + "&watch_region=" + region
                + "&with_watch_monetization_types=" + URLEncoder.encode(crunchy ? "flatrate|ads|free" : "flatrate", "UTF-8")
                + "&include_adult=false&sort_by=popularity.desc&with_watch_providers=" + provider
                + "&without_genres=" + KIDS + (Prefs.hideAnimation() ? "," + ANIMATION : "");
        String tvWindow = "air_date.gte=" + start + "&air_date.lte=" + end;
        String movieWindow = "region=" + region + "&release_date.gte=" + start + "&release_date.lte=" + end
                + "&with_release_type=" + URLEncoder.encode("3|4|6", "UTF-8");
        List<String> queries = new ArrayList<>(Arrays.asList(
            // Shows with an episode (a premiere, a new season's or an ongoing one's) aired in the window.
            "/discover/tv?" + tvWindow + only,
            "/discover/tv?page=2&" + tvWindow + only,
            // Movies released in the window in that country (theatrical, digital or TV), and worldwide premieres.
            "/discover/movie?" + movieWindow + only,
            "/discover/movie?primary_release_date.gte=" + start + "&primary_release_date.lte=" + end + only));
        if (!crunchy) {
            // Hindi titles of the same week by themselves (popularity alone lets English ones crowd them out).
            queries.add("/discover/tv?with_original_language=hi&" + tvWindow + only);
            queries.add("/discover/movie?with_original_language=hi&" + movieWindow + only);
            queries.add("/discover/movie?with_original_language=hi&primary_release_date.gte=" + start
                    + "&primary_release_date.lte=" + end + only);
        }
        return queries;
    }

    /** TMDB's ids for Explore's services: Netflix and Prime Video from tmdb_region's lists, Crunchyroll from India's. */
    private Map<String, Integer> exploreProviders(ExecutorService pool) {
        return exploreProviders(pool, Arrays.asList(EXPLORE));
    }

    private Map<String, Integer> exploreProviders(ExecutorService pool, List<String> services) {
        Map<String, java.util.concurrent.Future<JSONArray>> lists = new LinkedHashMap<>();
        for (String app : services) {
            for (String kind : new String[] {"tv", "movie"}) {
                String path = "/watch/providers/" + kind + "?language=" + lang() + "&watch_region=" + regionFor(app);
                if (!lists.containsKey(path)) lists.put(path, pool.submit(() -> get(path).getJSONArray("results")));   // once per region
            }
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (String app : services) {
            for (String kind : new String[] {"tv", "movie"}) {
                if (ids.containsKey(app)) break;
                String path = "/watch/providers/" + kind + "?language=" + lang() + "&watch_region=" + regionFor(app);
                try {
                    JSONArray list = lists.get(path).get();
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject o = list.getJSONObject(i);
                        String[] s = service(o.optString("provider_name"));
                        if (s != null && s[1].equals(app) && installed(s[2])) ids.put(app, o.getInt("provider_id"));
                    }
                } catch (Exception ex) {
                    AppLog.w("explore providers " + app + ": " + ex.getMessage());
                }
            }
        }
        return ids;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The titles, marked with the freshness tier of the query that found them. */
    private static List<Pick> tiered(List<Pick> titles, int tier) {
        for (Pick p : titles) p.tier = tier;
        return titles;
    }

    /** A discover query's titles (page 1), or none when it fails: the other queries' titles stay. */
    private List<Pick> discover(String kind, String path) {
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray r = get(path).getJSONArray("results");
            for (int i = 0; i < r.length(); i++) add(out, r.getJSONObject(i), kind);
        } catch (Exception e) {
            AppLog.w("discover " + path.replaceAll("[?&](api_key|language)=[^&]*", "") + ": " + e.getMessage());
        }
        return out;
    }

    /** The lists' titles taking turns (the first of each, then the second of each…), each title once. */
    private static List<Pick> takeTurns(List<List<Pick>> lists) {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int longest = 0;
        for (List<Pick> l : lists) longest = Math.max(longest, l.size());
        for (int i = 0; i < longest; i++) {
            for (List<Pick> l : lists) if (i < l.size() && seen.add(key(l.get(i)))) out.add(l.get(i));
        }
        return out;
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Prefs.hideAnimation() && animated(o)) return;
        if (kids(kind, ids(o.optJSONArray("genre_ids")), "")) return;   // children's content: never a candidate
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.lang = o.optString("original_language", "");   // known before the details: "hi" for the Bollywood mix
        list.add(p);
    }

    private static final int FAMILY = 10751, KIDS = 10762;

    private static int[] ids(JSONArray a) {
        int[] out = new int[a == null ? 0 : a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optInt(i);
        return out;
    }

    /**
     * Children's content, from TMDB's own metadata (not "animated" alone: anime and grown-up animation stay):
     * - movies: rated G in the US; or Family with Animation; or Family rated PG or not rated in the US.
     * - shows: TMDB's Kids genre; rated TV-Y / TV-Y7 in the US; or Family with Animation.
     * `rating`: the US certification, "" when unknown (then the genres decide: unknown is never taken as "fine for
     * all" when the genres say family).
     */
    static boolean kids(String kind, int[] genres, String rating) {
        boolean family = false, animation = false, kidsGenre = false;
        for (int g : genres) {
            family |= g == FAMILY;
            animation |= g == ANIMATION;
            kidsGenre |= g == KIDS;
        }
        if ("tv".equals(kind)) {
            return kidsGenre || rating.startsWith("TV-Y") || (family && animation);
        }
        return "G".equals(rating) || (family && animation) || (family && (rating.isEmpty() || "PG".equals(rating)));
    }

    /** The US rating in a details answer: a movie's certification (release_dates), a show's (content_ratings). */
    private static String usRating(JSONObject o, String kind) {
        JSONObject box = o.optJSONObject("movie".equals(kind) ? "release_dates" : "content_ratings");
        JSONArray results = box == null ? null : box.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !"US".equals(r.optString("iso_3166_1"))) continue;
            if (!"movie".equals(kind)) return r.optString("rating", "").trim();
            JSONArray dates = r.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                String c = dates.optJSONObject(j) == null ? "" : dates.optJSONObject(j).optString("certification", "").trim();
                if (!c.isEmpty()) return c;
            }
        }
        return "";
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,release_dates,content_ratings&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        p.rating = o.optDouble("vote_average", 0);
        p.lang = o.optString("original_language", "");
        StringBuilder from = new StringBuilder(",");
        JSONArray oc = o.optJSONArray("origin_country");
        for (int i = 0; oc != null && i < oc.length(); i++) from.append(oc.optString(i)).append(',');
        JSONArray pc = o.optJSONArray("production_countries");
        for (int i = 0; pc != null && i < pc.length(); i++) from.append(pc.getJSONObject(i).optString("iso_3166_1")).append(',');
        p.origin = from.toString();
        p.votes = o.optInt("vote_count");
        p.genres = new int[genres == null ? 0 : genres.length()];
        for (int i = 0; i < p.genres.length; i++) p.genres[i] = genres.getJSONObject(i).optInt("id");
        p.kids = kids(p.kind, p.genres, usRating(o, p.kind));
        if (p.kind.equals("movie")) {
            p.date = movieDate(o.optString("release_date", ""), o.optJSONObject("release_dates"), regionFor(p.want),
                    windowStart(), windowEnd());
        } else {
            // The latest episode that has aired (a premiere is its own first episode; a new season counts by its new
            // episodes' dates, never by TMDB's "last updated").
            JSONObject latest = o.optJSONObject("last_episode_to_air");
            p.date = latest != null ? latest.optString("air_date", "") : "";
            if (p.date.isEmpty() || "null".equals(p.date)) p.date = o.optString("first_air_date", "");
        }
        if ("null".equals(p.date)) p.date = "";
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            p.runtime = Math.max(0, minutes);
            if (minutes > 0) parts.add(duration(minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
            // The current season: the one with the latest episode out (TMDB's last_episode_to_air), else the last one.
            JSONObject last = o.optJSONObject("last_episode_to_air");
            int current = last != null ? last.optInt("season_number") : 0;
            if (current <= 0) current = seasons;
            p.season = Math.max(0, current);
            p.seasonName = "";
            JSONArray list = o.optJSONArray("seasons");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject se = list.optJSONObject(i);
                if (se != null && se.optInt("season_number") == current) p.seasonName = ownSeasonName(se.optString("name"), current);
            }
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        JSONObject br = results == null ? null : results.optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        p.app = null;
        p.pkg = null;
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s == null || !installed(s[2])) continue;
            if (p.app == null || s[1].equals(p.want)) {   // the service it was fetched for wins when it's there too
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        // Crunchyroll: on it in India (any of its tiers: subscription, with ads or free), for titles fetched for it.
        if ("crunchyroll".equals(p.want) && !"crunchyroll".equals(p.app) && results != null) {
            JSONObject in = results.optJSONObject(CRUNCHYROLL_REGION);
            for (String tier : new String[] {"flatrate", "ads", "free"}) {
                JSONArray list = in == null ? null : in.optJSONArray(tier);
                for (int i = 0; list != null && i < list.length(); i++) {
                    String[] s = service(list.getJSONObject(i).optString("provider_name"));
                    if (s != null && s[1].equals("crunchyroll") && installed(s[2])) {
                        p.app = s[1];
                        p.pkg = s[2];
                    }
                }
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        // What's New's label from the title's own dates (an arrival found by the catalogue reads is set afterwards).
        if (p.label != ArrivalsLogic.NEW_ON) {
            String today = day(0);
            if (p.kind.equals("movie")) {
                p.label = ArrivalsLogic.streamingPremiere(o.optString("release_date", ""), releases(o.optJSONObject("release_dates")),
                        REGION, today) ? ArrivalsLogic.PREMIERE : ArrivalsLogic.NONE;
            } else {
                JSONObject last = o.optJSONObject("last_episode_to_air");
                int season = last == null ? 0 : last.optInt("season_number");
                String seasonStart = "";
                JSONArray seasons = o.optJSONArray("seasons");
                for (int i = 0; seasons != null && i < seasons.length(); i++) {
                    JSONObject se = seasons.optJSONObject(i);
                    if (se != null && se.optInt("season_number") == season) seasonStart = se.optString("air_date", "");
                }
                p.label = ArrivalsLogic.showLabel(season, last == null ? "" : last.optString("air_date", ""), seasonStart, today);
            }
            if (p.app == null) p.label = ArrivalsLogic.NONE;   // not streaming on the TV's services here: no claim
        }
        p.detailed = true;
    }

    /** "2h 15m", "45m". */
    static String duration(int minutes) {
        return minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes);
    }

    /**
     * A season's own name ("Shibuya Incident Arc"), or "" when TMDB only has the generic one ("Season 3",
     * "Temporada 3", "Staffel 3"): a name that is just one word plus the season's number.
     */
    private static String ownSeasonName(String name, int number) {
        if (name == null) return "";
        String n = name.trim();
        if (n.isEmpty()) return "";
        String rest = n.replace(String.valueOf(number), "").trim();
        if (n.contains(String.valueOf(number)) && !rest.contains(" ") && rest.length() <= 12) return "";
        return n;
    }

    /**
     * Explore's card line: movies "2h 15m · Netflix"; shows and anime "Season 3 · Its name · Crunchyroll" (the name only
     * when TMDB has one). Nothing that doesn't apply, nothing made up.
     */
    static String cardLine(Pick p, boolean withService) {
        List<String> parts = new ArrayList<>();
        if ("movie".equals(p.kind)) {
            if (p.runtime > 0) parts.add(duration(p.runtime));
        } else if (p.season > 0) {
            parts.add("S" + p.season);   // short: "S3" (Home's cards)
            if (p.seasonName != null && !p.seasonName.isEmpty()) parts.add(p.seasonName);
        }
        // The service's logo is on the card: its name gives way to the title's rating (TMDB's, only with enough votes).
        // "★7.6": the card draws the ★ as its 3D star icon (HomeView.drawMetaLine).
        if (p.rating > 0 && p.votes >= 10) parts.add("\u2605" + String.format(Ui.locale(), "%.1f", p.rating));
        String service = serviceName(p);
        if (withService && service != null && !service.isEmpty()) parts.add(service);
        return String.join(" · ", parts);
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        Map<String, String> apps = detectedMemo;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            String found = apps == null ? null : apps.get(s[1]);   // the app found on this TV for that service
            if (found != null) return new String[] {s[0], s[1], found};
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    // MARK: - Which streaming apps this TV has

    /** Explore's services, as one key each (JioCinema, JioHotstar and Disney+ Hotstar are all "hotstar"). */
    static final String[] SUPPORTED = {"netflix", "prime", "hotstar", "disney", "max", "crunchyroll"};
    private volatile Map<String, String> detectedMemo;

    /**
     * The supported streaming apps installed here, service → package: first by their known packages (TV and phone
     * builds), then by the names of the TV's launchable apps (a regional or TV-only build with another package). Android
     * answers this for apps with a launcher entry, which every streaming app has. Services hidden in config are left out.
     */
    static Map<String, String> detectApps(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (String[] s : SERVICES) {
            if (!Arrays.asList(SUPPORTED).contains(s[1]) || hidden(s[1]) || out.containsKey(s[1])) continue;
            if (HomeApps.launchIntent(pm, s[2]) != null) out.put(s[1], s[2]);
        }
        for (String[] app : HomeApps.launchable(c)) {
            String key = serviceOfLabel(app[1]);
            if (key != null && !hidden(key) && !out.containsKey(key)) out.put(key, app[0]);
        }
        return out;
    }

    /** "Disney+ Hotstar" → hotstar, "JioCinema" → hotstar, "HBO Max" → max, "Prime Video" → prime…; else null. */
    static String serviceOfLabel(String label) {
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (l.contains("netflix")) return "netflix";
        if (l.contains("crunchyroll")) return "crunchyroll";
        if (l.contains("hotstar") || l.contains("jiocinema") || l.contains("jio cinema")) return "hotstar";
        if (l.contains("disney")) return "disney";
        if (l.contains("prime video") || l.contains("amazon prime")) return "prime";
        if (l.matches(".*\\bmax\\b.*") || l.contains("hbo")) return "max";
        return null;
    }

    /** The services found at the last refresh (empty before it). */
    Set<String> detectedServices() {
        Map<String, String> apps = detectedMemo;
        return apps == null ? new HashSet<>() : new HashSet<>(apps.keySet());
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : BuildKeys.conf("hide_services", Config.HIDE_SERVICES).split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** Hidden in config.properties (hide_services), or turned off on the TV (Home Settings > Picks > Picks from). */
    private static boolean hidden(String key) {
        if (HIDDEN.contains(key)) return true;
        int v = Prefs.version;   // installed() asks for every service, often: Prefs read again only after a change
        java.util.Map<String, Boolean> off = offMemo;
        if (offMemoVersion != v || off == null) {
            off = new java.util.concurrent.ConcurrentHashMap<>();
            offMemo = off;
            offMemoVersion = v;
        }
        Boolean known = off.get(key);
        if (known == null) off.put(key, known = Prefs.serviceOff(key));
        return known;
    }

    private static volatile java.util.Map<String, Boolean> offMemo;
    private static volatile int offMemoVersion = -1;

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private final Map<String, long[]> installedAt = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean installed(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg) && hidden(s[1])) return false;
        long now = System.currentTimeMillis();
        long[] known = installedAt.get(pkg);   // {answer, when}: a package query per call was ~1-3 ms on a TV
        if (known != null && now - known[1] < 60_000 && now >= known[1]) return known[0] == 1;
        boolean yes = HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
        installedAt.put(pkg, new long[] {yes ? 1 : 0, now});
        return yes;
    }


    private long sourceStamp() {
        long stamp = (key().hashCode() * 31L + REGION.hashCode()) * 31L + (Prefs.hideAnimation() ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        // Off: titles now open through TitleLaunch (its own verified links, shared by Home and For You); this older
        // lookup would only spend the same Watchmode quota again for a link nothing uses.
        if (LEGACY_LINKS_OFF) return false;
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = watchmodeKey();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", watchmodeKey().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(watchmodeKey(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = key();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        Object lock = FILE_LOCKS.computeIfAbsent(p.backdrop, x -> new Object());
        synchronized (lock) {   // the refresh and the image threads may ask for the same file: one download
            downloadNow(p);
        }
    }

    private void downloadNow(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        // A whole address (For You's Games: RAWG's art, at 1280 px) or a TMDB path.
        String address = p.backdrop.startsWith("https://") ? Games.sized(p.backdrop, 1280)
                : (p.want != null ? CARD_IMAGES : IMAGES) + p.backdrop;
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    /** A Watch Next picture (content://, android.resource:// or http(s)), at most 8 MB. */
    private byte[] watchPicture(String uri) throws IOException {
        android.net.Uri u = android.net.Uri.parse(uri);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        try (java.io.InputStream in = scheme.startsWith("http") ? new java.net.URL(uri).openStream()
                : context.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("no picture");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > (8 << 20)) throw new IOException("picture too large");
            }
            return out.toByteArray();
        }
    }

    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            byte[] wn = null;
            if ("app".equals(p.kind)) {   // the app's own picture, kept on disk (shown offline too)
                if (f.exists()) wn = readFile(f);
                else {
                    wn = watchPicture(p.backdrop);
                    writeFile(f, wn);
                }
            } else if ("watchnext".equals(p.kind)) wn = watchPicture(p.backdrop);   // the app's own picture
            if (wn == null) download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (wn != null) BitmapFactory.decodeByteArray(wn, 0, wn.length, bounds); else BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;   // sampled down before decoding: never more pixels than the card needs
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = wn != null ? BitmapFactory.decodeByteArray(wn, 0, wn.length, opts) : BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            failedImages.remove(p.backdrop);
            imageFailedAt.remove(p.backdrop);
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Over the cap: the least recently used goes, but never one of Home's cards on screen (For You's
                // page backgrounds share this cache: browsing it pushed Home's cards out, and coming back Home
                // re-made them behind skeletons — what looked like Explore reloading).
                int cap = forLight ? 22 : 4;
                java.util.Iterator<String> it = images.keySet().iterator();
                while (images.size() > cap && it.hasNext()) {
                    String old = it.next();
                    if (!pinned.contains(old) && !old.equals(p.backdrop)) it.remove();
                }
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            f.delete();
            failedImages.add(p.backdrop);
            imageFailedAt.put(p.backdrop, System.currentTimeMillis());
            notifyListeners();   // the card stops waiting and shows its fallback
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** Home's cards on screen: kept in memory (setPinned from HomeView whenever Home's cards are set). */
    private volatile java.util.Set<String> pinned = java.util.Collections.emptySet();

    void setPinned(List<Pick> shown) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Pick p : shown) if (p.backdrop != null) keys.add(p.backdrop);
        pinned = keys;
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : wanted()) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 80) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 80; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private static JSONObject pickJson(Pick p) throws org.json.JSONException {
        return new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("runtime", p.runtime).put("season", p.season)
                        .put("seasonName", p.seasonName == null ? "" : p.seasonName).put("want", p.want == null ? "" : p.want)
                        .put("rating", p.rating).put("votes", p.votes).put("genres", new JSONArray(toList(p.genres))).put("kids", p.kids)
                        .put("date", p.date == null ? "" : p.date).put("followed", p.followed)
                        .put("lang", p.lang == null ? "" : p.lang).put("origin", p.origin == null ? "" : p.origin)
                        .put("trend", p.trend).put("tLang", p.tLang).put("tCountry", p.tCountry).put("anime", p.anime)
                        .put("tier", p.tier).put("label", p.label).put("labelApp", p.labelApp == null ? "" : p.labelApp);
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) a.put(pickJson(p));
            JSONArray home = new JSONArray();
            for (Pick p : homeGrid) home.put(pickJson(p));
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt).putLong("mixSeed", mixSeed)
                    .putString("homeGrid", home.toString()).putLong("homeSeed", homeSeed)
                    .putInt("listShape", listShape).putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION)
                    .putLong("exploreAt", exploreAt).putString("exploreDay", exploreDay).putString("exploreLanguage", exploreLanguage)
                    .putBoolean("exploreNoApps", exploreNoApps);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int v : a) out.add(v);
        return out;
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.runtime = o.optInt("runtime");
            p.season = o.optInt("season");
            p.seasonName = o.optString("seasonName");
            p.want = o.optString("want").isEmpty() ? null : o.optString("want");
            p.rating = o.optDouble("rating", 0);
            p.votes = o.optInt("votes");
            JSONArray g = o.optJSONArray("genres");
            p.genres = new int[g == null ? 0 : g.length()];
            for (int j = 0; j < p.genres.length; j++) p.genres[j] = g.optInt(j);
            p.kids = o.has("kids") ? o.optBoolean("kids") : kids(p.kind, p.genres, "");   // saved before the flag: by genres
            p.date = o.optString("date");
            p.followed = o.optBoolean("followed");
            p.lang = o.optString("lang");
            p.origin = o.optString("origin");
            p.trend = o.optInt("trend");
            p.tLang = o.optString("tLang");
            p.tCountry = o.optString("tCountry");
            p.anime = o.optBoolean("anime");
            p.tier = o.optInt("tier", TIER_OLDER);
            p.label = o.optInt("label", ArrivalsLogic.NONE);
            p.labelApp = o.optString("labelApp", "");
            if (p.labelApp.isEmpty()) p.labelApp = null;
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // MARK: - Keys given on the TV (VYRO: Home Settings > TMDB key / Watchmode key, KeyPage)

    /** The most cards per tab and hour (Prefs.cards() picks how many, 3 by default); the home screen shows 3 at a time and scrolls. */
    static final int MAX_CARDS = 6;

    private static volatile int keyVersion = -1;
    private static volatile String cachedKey = "";
    private static volatile int watchmodeKeyVersion = -1;
    private static volatile String cachedWatchmodeKey = "";

    /**
     * The key given on the TV, else the one built in from config.properties ("" for neither). The home screen asks
     * while drawing: read again only when a setting changed (Prefs.version).
     */
    static String key() {
        int version = Prefs.version;
        if (version != keyVersion) {
            String given = Prefs.tmdbKey();
            cachedKey = given.isEmpty() ? BuildKeys.tmdb().trim() : given;
            keyVersion = version;
        }
        return cachedKey;
    }
    /** The Watchmode key given on the TV, else the one built in from config.properties. Asked while drawing, as key(). */
    static String watchmodeKey() {
        int version = Prefs.version;
        if (version != watchmodeKeyVersion) {
            String given = Prefs.watchmodeKey();
            cachedWatchmodeKey = given.isEmpty() ? BuildKeys.watchmode().trim() : given;
            watchmodeKeyVersion = version;
        }
        return cachedWatchmodeKey;
    }
    /**
     * A key as pasted (spaces, line breaks taken out), or "" when it can't be one: an API key (32 hex digits) or an API
     * Read Access Token (a JWT, "eyJ…").
     */
    static String cleanKey(String pasted) {
        String key = pasted == null ? "" : pasted.replaceAll("\\s+", "");
        return key.matches("[A-Za-z0-9._-]{16,1024}") ? key : "";
    }
    /** Asks TMDB whether a key works, before it's saved: null if it does, else why not, for the screen. Never logged. */
    static String checkKey(String key) {
        try {
            request("/configuration", key);
            return null;
        } catch (IOException e) {
            if (REJECTED.equals(e.getMessage())) return Ui.tr("TMDB refused this key.");
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (org.json.JSONException e) {
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage());
        } catch (RuntimeException e) {   // never out of KeyPage's thread (the watchdog counts it); the name only: no header value
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getClass().getSimpleName());
        }
    }
    /** Asks Watchmode whether a key works, before it's saved: null if it does, else why not. The key is in the URL: only our own message or the error's name. */
    static String checkWatchmodeKey(String key) {
        try {
            watchmode("/status/", key);
            return null;
        } catch (IOException | RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            if ("Watchmode returned HTTP 401".equals(message)) return Ui.tr("Watchmode refused this key.");
            return Ui.trf("Couldn't reach Watchmode: {error}", "error", message.startsWith("Watchmode returned") ? message : e.getClass().getSimpleName());
        }
    }
    private static JSONObject request(String path, String key) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(REJECTED);
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }
    private static final String REJECTED = "TMDB rejected the key";
    private static String watchmode(String path, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(key, "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
    /** The streaming services whose app is on this TV, as {key, name}, for Home Settings (Picks from). */
    static List<String[]> installedServices(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] s : SERVICES) {
            if (HomeApps.launchIntent(c.getPackageManager(), s[2]) != null && seen.add(s[1])) out.add(new String[] {s[1], SERVICE_NAME.get(s[1])});
        }
        return out;
    }
}
