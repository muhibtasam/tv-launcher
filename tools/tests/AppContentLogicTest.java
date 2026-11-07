import io.freemytv.AppContentLogic;
import io.freemytv.AppContentLogic.Channel;
import io.freemytv.AppContentLogic.Record;
import io.freemytv.MixLogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AppContentLogic: Home's titles as the streaming apps publish them (rows modelled on the owner's TV, 2026-11-02). */
public class AppContentLogicTest {
    static int failures;
    static final String PRIME = "com.amazon.amazonvideo.livingroom", CR = "com.crunchyroll.crunchyroid",
            YT = "com.google.android.youtube.tv", SPOTIFY = "com.spotify.tv.android", TCL = "com.tcl.partnercustomizer",
            STREMIO = "com.stremio.one", NUVIO = "com.nuvio.tv", NETFLIX = "com.netflix.ninja";
    static final long NOW = 1_793_638_000_000L, DAY = 24 * 3600_000L;

    static void yes(String what, boolean v) { if (!v) { failures++; System.out.println("FAIL " + what); } }
    static void eq(String what, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) { failures++; System.out.println("FAIL " + what + ": want " + want + ", got " + got); }
    }

    static Map<String, String> row(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static final Map<Long, Channel> CHANNELS = new HashMap<>();
    static {
        CHANNELS.put(55L, new Channel(PRIME, "Prime Video"));
        CHANNELS.put(53L, new Channel(CR, "Most Popular"));
        CHANNELS.put(7L, new Channel(YT, "Recommended for you"));
        CHANNELS.put(56L, new Channel(SPOTIFY, "Spotify"));
        CHANNELS.put(747L, new Channel(TCL, "Selected"));
        CHANNELS.put(878L, new Channel(STREMIO, "Continue Watching"));
        CHANNELS.put(1039L, new Channel(NUVIO, "Continue Watching"));
    }
    static final String PRIME_LINK = "intent:#Intent;component=" + PRIME + "/com.amazon.ignition.IgnitionActivity;S.asin=amzn1.dv.gti.1;end";

    static Record prog(String pkg, long channel, String title, String link) {
        return AppContentLogic.preview(row("package_name", pkg, "channel_id", String.valueOf(channel), "title", title,
                "type", "4", "poster_art_uri", "https://img/" + title + ".jpg", "thumbnail_uri", null, "intent_uri", link), CHANNELS, NOW);
    }

    static Set<String> installed(String... pkgs) {
        return new HashSet<>(Arrays.asList(pkgs));
    }

    public static void main(String[] a) {
        // Watch Next parsing: title, episode, progress, engagement, picture, link.
        Record wn = AppContentLogic.watchNext(row("package_name", CR, "title", "JUJUTSU KAISEN", "episode_display_number", "55",
                "poster_art_uri", "https://cr/p.jpg", "thumbnail_uri", null, "intent_uri", "crunchyroll://episode?id=GE00367575ENUS",
                "last_playback_position_millis", "629000", "duration_millis", "1435019", "last_engagement_time_utc_millis", "1791036610265",
                "type", "3"), NOW);
        eq("watch next: title", "JUJUTSU KAISEN", wn.title);
        eq("watch next: episode", "55", wn.episode);
        eq("watch next: position", 629000L, wn.position);
        eq("watch next: duration", 1435019L, wn.duration);
        eq("watch next: engaged", 1791036610265L, wn.engaged);
        eq("watch next: picture (poster when no thumbnail)", "https://cr/p.jpg", wn.art);
        eq("watch next: link", "crunchyroll://episode?id=GE00367575ENUS", wn.link);
        yes("watch next: flagged", wn.watchNext);
        eq("watch next: type", 3, wn.type);
        Record junk = AppContentLogic.watchNext(row("package_name", PRIME, "title", "  ", "last_playback_position_millis", "x"), NOW);
        eq("watch next: blank title is null", null, junk.title);
        eq("watch next: bad number is 0", 0L, junk.position);

        // Preview programs: channel name and app from the channel; thumbnail first; type kept.
        Record cr = prog(CR, 53, "Frieren: Beyond Journey's End", "crunchyroll://series?id=GG5H5XQX4");
        eq("preview: channel", "Most Popular", cr.channel);
        eq("preview: service", "crunchyroll", cr.service());
        eq("preview: type", 4, cr.type);
        Record thumb = AppContentLogic.preview(row("package_name", PRIME, "channel_id", "55", "title", "Ali G: Who Iz I?",
                "poster_art_uri", "https://p/poster", "thumbnail_uri", "https://p/thumb", "intent_uri", PRIME_LINK), CHANNELS, NOW);
        eq("preview: thumbnail before poster", "https://p/thumb", thumb.art);
        Record noPkg = AppContentLogic.preview(row("channel_id", "55", "title", "X"), CHANNELS, NOW);
        eq("preview: app from the channel when the row has none", PRIME, noPkg.pkg);
        Record unknownChannel = AppContentLogic.preview(row("package_name", PRIME, "channel_id", "999", "title", "Y"), CHANNELS, NOW);
        eq("preview: unknown channel has no name", null, unknownChannel.channel);

        // Source identification.
        eq("source: Prime Video", "prime", AppContentLogic.SOURCES.get(PRIME));
        eq("source: Prime's other package", "prime", AppContentLogic.SOURCES.get("com.amazon.avod.thirdpartyclient"));
        eq("source: Crunchyroll", "crunchyroll", AppContentLogic.SOURCES.get(CR));
        for (String other : new String[] {YT, SPOTIFY, TCL, STREMIO, NUVIO, NETFLIX, "com.pitelevision.tapmadtv", "com.mcontent", "com.tcl.channelplus"}) {
            eq("source: not a grid source " + other, null, AppContentLogic.SOURCES.get(other));
        }

        // Filtering: unsupported and irrelevant rows never reach the grid.
        Set<String> all = installed(PRIME, CR, YT, SPOTIFY, TCL, STREMIO, NUVIO, NETFLIX);
        List<Record> rows = new ArrayList<>(Arrays.asList(
                prog(PRIME, 55, "The Terminal List - Season 2", PRIME_LINK),
                prog(CR, 53, "Frieren: Beyond Journey's End", "crunchyroll://series?id=GG5H5XQX4"),
                prog(YT, 7, "Grand Theft Auto VI Launch Trailer", "https://www.youtube.com/watch?v=1"),
                prog(SPOTIFY, 56, "Daily Mix 1", "spotify:playlist:1"),
                prog(TCL, 747, "MagiConnect", "googletvcustomer://com.tcl.partnercustomizer/startapp"),
                prog(STREMIO, 878, "Some Show", "stremio:///detail/1"),
                prog(NUVIO, 1039, "Other Show", "intent:#Intent;component=" + NUVIO + "/.MainActivity;end"),
                wn));
        List<Record> sel = AppContentLogic.select(rows, all);
        eq("filter: only Prime and Crunchyroll recommendations", 2, sel.size());
        eq("filter: Prime first (read order kept)", "The Terminal List - Season 2", sel.get(0).title);
        yes("filter: Watch Next stays in the Continue row", !AppContentLogic.usable(wn, all));
        Record crContinue = AppContentLogic.preview(row("package_name", CR, "channel_id", "53", "title", "Z", "poster_art_uri", "https://a",
                "intent_uri", "crunchyroll://x"), new HashMap<Long, Channel>() {{ put(53L, new Channel(CR, "Continue Watching")); }}, NOW);
        yes("filter: a supported app's continue channel isn't a recommendation", !AppContentLogic.usable(crContinue, all));
        yes("filter: app not installed", AppContentLogic.select(rows, installed(CR)).size() == 1);
        Record noArt = prog(PRIME, 55, "No Picture", PRIME_LINK);
        noArt.art = null;
        Record noLink = prog(PRIME, 55, "No Link", null);
        Record noTitle = prog(PRIME, 55, "T", PRIME_LINK);
        noTitle.title = null;
        yes("filter: no picture", !AppContentLogic.usable(noArt, all));
        yes("filter: no link", !AppContentLogic.usable(noLink, all));
        yes("filter: no title", !AppContentLogic.usable(noTitle, all));
        yes("filter: a null row", !AppContentLogic.usable(null, all));

        // De-duplication: same app + same title once; the same title on two apps stays twice.
        List<Record> dup = Arrays.asList(
                prog(PRIME, 55, "One Piece", PRIME_LINK), prog(PRIME, 55, "  one   PIECE ", PRIME_LINK),
                prog(CR, 53, "One Piece", "crunchyroll://series?id=GRMG8ZQZR"));
        List<Record> d = AppContentLogic.select(dup, all);
        eq("dedupe: two left", 2, d.size());
        eq("dedupe: Prime's once", PRIME, d.get(0).pkg);
        eq("dedupe: Crunchyroll's kept", CR, d.get(1).pkg);
        eq("dedupe: normalised title", "one piece", AppContentLogic.normTitle("  one   PIECE "));

        // Deep links: only inside the app that published them.
        yes("link: Prime's own component", AppContentLogic.linkAllowed(PRIME_LINK, PRIME));
        yes("link: a plain app scheme (opened inside the app)", AppContentLogic.linkAllowed("crunchyroll://series?id=1", CR));
        yes("link: https inside the app", AppContentLogic.linkAllowed("https://www.crunchyroll.com/series/1", CR));
        yes("link: intent naming its own package", AppContentLogic.linkAllowed("intent:#Intent;action=android.intent.action.MAIN;package=" + YT + ";end", YT));
        yes("link: another app's component refused", !AppContentLogic.linkAllowed("intent:#Intent;component=com.evil/.A;end", PRIME));
        yes("link: another package refused", !AppContentLogic.linkAllowed("intent:#Intent;package=com.evil;end", CR));
        yes("link: own component but another package refused", !AppContentLogic.linkAllowed("intent:#Intent;package=com.evil;component=" + CR + "/.A;end", CR));
        yes("link: a selector refused", !AppContentLogic.linkAllowed("intent:#Intent;component=" + CR + "/.A;SEL;package=com.evil;end", CR));
        yes("link: unterminated intent refused", !AppContentLogic.linkAllowed("intent:#Intent;component=" + CR + "/.A", CR));
        yes("link: https with an intent to another app refused", !AppContentLogic.linkAllowed("https://x#Intent;scheme=https;package=com.evil;end", CR));
        for (String bad : new String[] {"file:///sdcard/x", "content://com.x/1", "javascript:alert(1)", "data:text/html,x", "", "   ", null, "noscheme"}) {
            yes("link: refused " + bad, !AppContentLogic.linkAllowed(bad, CR));
        }
        yes("link: no package refused", !AppContentLogic.linkAllowed("crunchyroll://x", null));
        Record evil = prog(CR, 53, "Evil", "intent:#Intent;component=com.evil/.A;end");
        yes("link: a row with a foreign link isn't usable", !AppContentLogic.usable(evil, all));

        // A failed read: the caller keeps the previous records (nothing merged). An empty read for an installed app
        // keeps its previous titles while they're fresh; an app removed loses them; an old empty source drops out.
        List<Record> before = AppContentLogic.select(rows, all);
        List<Record> emptyNow = AppContentLogic.merge(before, new ArrayList<>(), installed(PRIME, CR), NOW + DAY);
        eq("empty read: both apps keep their titles (1 day)", 2, emptyNow.size());
        List<Record> crOnly = AppContentLogic.merge(before, Arrays.asList(prog(CR, 53, "Golden Kamuy", "crunchyroll://series?id=GY8")), installed(PRIME, CR), NOW + DAY);
        eq("partial read: Prime kept, Crunchyroll new", 2, crOnly.size());
        yes("partial read: Crunchyroll's new title replaces its old", crOnly.get(0).title.equals("Golden Kamuy") || crOnly.get(1).title.equals("Golden Kamuy"));
        eq("app removed: its titles go", 1, AppContentLogic.merge(before, new ArrayList<>(), installed(CR), NOW).size());
        eq("too old: kept no longer than KEEP", 0, AppContentLogic.merge(before, null, installed(PRIME, CR), NOW + AppContentLogic.KEEP + 1).size());
        eq("clock back: not kept as fresh", 0, AppContentLogic.merge(before, null, installed(PRIME, CR), NOW - DAY).size());
        eq("first read empty: nothing invented", 0, AppContentLogic.merge(new ArrayList<>(), new ArrayList<>(), all, NOW).size());
        eq("no apps at all: nothing", 0, AppContentLogic.select(new ArrayList<>(), all).size());

        // Offline: TvProvider is on the TV, so a read works offline; the saved records (same signature) keep the grid.
        String sig = AppContentLogic.signature(before);
        yes("offline restart, same records, same part of day: same grid", !AppContentLogic.reseed(sig, AppContentLogic.signature(before), 2, 2));

        // Stable grid and atomic refresh: a new mix only for new content or a new part of the day.
        yes("new content: a new mix", AppContentLogic.reseed(sig, AppContentLogic.signature(crOnly), 2, 2));
        yes("new part of the day: a new mix", AppContentLogic.reseed(sig, sig, 1, 2));
        yes("first read ever: a mix", AppContentLogic.reseed("", sig, -1, 2));
        yes("grid on screen, same seed: kept whole (navigation, Home presses)", !MixLogic.publish(true, false, true, true, false));
        yes("grid on screen, new seed: replaced whole", MixLogic.publish(true, true, true, true, false));
        yes("no grid yet: published", MixLogic.publish(false, false, true, true, false));
        List<Record> reordered = new ArrayList<>(before);
        java.util.Collections.reverse(reordered);
        yes("F2: the apps only reordered their rows: same grid", !AppContentLogic.reseed(sig, AppContentLogic.signature(reordered), 2, 2));
        List<Record> added = new ArrayList<>(before);
        added.add(prog(CR, 53, "Golden Kamuy", "crunchyroll://series?id=GY8"));
        yes("F2: a title added: a new mix", AppContentLogic.reseed(sig, AppContentLogic.signature(added), 2, 2));
        yes("F2: a title removed: a new mix", AppContentLogic.reseed(sig, AppContentLogic.signature(before.subList(0, 1)), 2, 2));
        Record moved = prog(CR, 53, before.get(0).title, before.get(0).link);
        List<Record> other = new ArrayList<>(Arrays.asList(moved, before.get(1)));
        yes("F2: the same title now from another app: a new mix", AppContentLogic.reseed(sig, AppContentLogic.signature(other), 2, 2));

        // Prime re-issuing its links (same titles): no new mix; the log says why a mix happens.
        List<Record> relinked = new ArrayList<>();
        for (Record r : before) { Record c = prog(r.pkg, 53, r.title, r.link + "&t=2"); relinked.add(c); }
        yes("links re-issued, same titles: same grid", !AppContentLogic.reseed(sig, AppContentLogic.signature(relinked), 2, 2));
        eq("why: one added", "+1 -0 titles", AppContentLogic.why(sig, AppContentLogic.signature(added), 2, 2));
        eq("why: one removed", "+0 -1 titles", AppContentLogic.why(sig, AppContentLogic.signature(before.subList(0, 1)), 2, 2));
        eq("why: a new part of the day", "new part of the day", AppContentLogic.why(sig, sig, 1, 2));

        // F1: after a failed read, the next waits 5 minutes; otherwise reads go at once.
        yes("F1: no failure: read now", AppContentLogic.mayRead(0, NOW));
        yes("F1: failed 3 s ago: wait", !AppContentLogic.mayRead(NOW - 3000, NOW));
        yes("F1: failed 4 min 59 s ago: wait", !AppContentLogic.mayRead(NOW - AppContentLogic.RETRY + 1000, NOW));
        yes("F1: failed 5 min ago: read", AppContentLogic.mayRead(NOW - AppContentLogic.RETRY, NOW));
        yes("F1: clock went back: read", AppContentLogic.mayRead(NOW + 60_000, NOW));
        eq("F1: the wait is 5 minutes", 300_000L, AppContentLogic.RETRY);

        // Natural distribution: two apps (Prime 21, Crunchyroll 20 as on the TV), 10 tiles shown, across refreshes.
        List<Record> tv = new ArrayList<>();
        for (int i = 0; i < 21; i++) tv.add(prog(PRIME, 55, "Prime " + i, PRIME_LINK));
        for (int i = 0; i < 20; i++) tv.add(prog(CR, 53, "Crunchy " + i, "crunchyroll://series?id=" + i));
        List<MixLogic.Item> items = AppContentLogic.items(AppContentLogic.select(tv, all));
        eq("items: one per record", 41, items.size());
        eq("items: provider is the app's service", "prime", items.get(0).provider);
        eq("items: an app's first few first", 0, items.get(3).priority);
        eq("items: then the rest", 1, items.get(4).priority);
        Set<Integer> splits = new HashSet<>();
        Set<String> firsts = new HashSet<>();
        for (long seed = 1; seed <= 60; seed++) {
            List<Integer> q = MixLogic.pick(items, 15, seed * 104729L);
            int prime = 0;
            for (int k = 0; k < 10; k++) if ("prime".equals(items.get(q.get(k)).provider)) prime++;
            splits.add(prime);
            firsts.add(items.get(q.get(0)).provider);
            yes("distribution: neither app over 6 of 10 (seed " + seed + ")", prime >= 4 && prime <= 6);
        }
        yes("distribution: the split varies across refreshes", splits.size() >= 3);
        yes("distribution: the large card's app varies", firsts.size() == 2);
        // One app only (the other uninstalled): it fills the grid rather than leaving holes.
        List<MixLogic.Item> one = AppContentLogic.items(AppContentLogic.select(tv, installed(CR)));
        eq("one app: fills all 15", 15, MixLogic.pick(one, 15, 9L).size());

        if (failures > 0) {
            System.out.println("AppContentLogicTest: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("AppContentLogicTest: all passed");
    }
}
