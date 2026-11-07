import io.freemytv.UpdateLogic;

import java.util.List;

/** Tests for the update check's rules (no Android): run by tools/tests/run.sh. */
public class UpdateLogicTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static final String DL = "https://github.com/muhibtasam/tv-launcher/releases/download/v1.2/FreeMyTV.apk";

    private static String release(String tag, String body, String assets) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"ibtzy " + tag + "\",\"published_at\":\"2026-11-02T18:30:17Z\","
                + "\"body\":" + (body == null ? "null" : "\"" + body + "\"") + ",\"assets\":" + assets + "}";
    }

    private static String apk(String name, long size, String url, String digest) {
        return "{\"name\":\"" + name + "\",\"size\":" + size + ",\"browser_download_url\":\"" + url + "\""
                + (digest == null ? "" : ",\"digest\":\"" + digest + "\"") + "}";
    }

    public static void main(String[] args) {
        String sha = "ab".repeat(32);

        // A normal release: version from the tag, the APK, its size and digest, the date.
        UpdateLogic.Release r = UpdateLogic.parse(release("v1.2", "## What's new\\n- One\\n- Two",
                "[" + apk("FreeMyTV.apk", 4_900_000, DL, "sha256:" + sha) + "]"));
        check("parses", r != null && r.error == null);
        check("version from tag without v", r != null && "1.2".equals(r.version));
        check("apk url", r != null && DL.equals(r.apkUrl));
        check("apk size", r != null && r.apkSize == 4_900_000);
        check("digest lower-case hex", r != null && sha.equals(r.sha256));
        check("date label", r != null && "2 Nov 2026".equals(r.date));

        // Malformed and incomplete answers: an error, never a crash or an offer.
        check("empty text", UpdateLogic.parse("").error != null);
        check("null", UpdateLogic.parse(null).error != null);
        check("not JSON", UpdateLogic.parse("<html>rate limited</html>").error != null);
        check("JSON array", UpdateLogic.parse("[]").error != null);
        check("no tag", UpdateLogic.parse("{\"assets\":[]}").error != null);
        check("no assets", UpdateLogic.parse(release("v1.2", "x", "[]")).error != null);
        check("assets not an array", UpdateLogic.parse(release("v1.2", "x", "{}")).error != null);
        check("no apk among assets", UpdateLogic.parse(release("v1.2", "x", "[" + apk("notes.txt", 10, DL, null) + "]")).error != null);
        check("apk from another host", UpdateLogic.parse(release("v1.2", "x",
                "[" + apk("FreeMyTV.apk", 10, "https://evil.example/FreeMyTV.apk", null) + "]")).error != null);
        check("apk from another repository", UpdateLogic.parse(release("v1.2", "x",
                "[" + apk("FreeMyTV.apk", 10, "https://github.com/someone/else/releases/download/v1/a.apk", null) + "]")).error != null);
        check("http, not https", UpdateLogic.parse(release("v1.2", "x",
                "[" + apk("FreeMyTV.apk", 10, DL.replace("https:", "http:"), null) + "]")).error != null);
        check("size 0", UpdateLogic.parse(release("v1.2", "x", "[" + apk("FreeMyTV.apk", 0, DL, null) + "]")).error != null);
        check("huge size", UpdateLogic.parse(release("v1.2", "x", "[" + apk("FreeMyTV.apk", 400_000_000L, DL, null) + "]")).error != null);
        check("bad digest text", UpdateLogic.parse(release("v1.2", "x", "[" + apk("FreeMyTV.apk", 10, DL, "sha256:xyz") + "]")).error != null);
        check("bad tag", UpdateLogic.parse(release("latest", "x", "[" + apk("FreeMyTV.apk", 10, DL, null) + "]")).error != null);
        UpdateLogic.Release noDigest = UpdateLogic.parse(release("1.3", null, "[" + apk("FreeMyTV.apk", 10, DL, null) + "]"));
        check("no digest listed: allowed (the signature is still checked)", noDigest.error == null && noDigest.sha256 == null);
        check("tag without v", "1.3".equals(noDigest.version));
        check("null body: no notes", noDigest.notes.isEmpty());
        UpdateLogic.Release two = UpdateLogic.parse(release("v1.2", "x", "[" + apk("other.apk", 10, DL.replace("ibtzyTVLauncher", "other"), null)
                + "," + apk("FreeMyTV.apk", 20, DL, null) + "]"));
        check("FreeMyTV.apk preferred among APKs", two.apkSize == 20);

        // Versions: numbers compared one by one, missing as 0.
        check("1.2 > 1.1", UpdateLogic.compare("1.2", "1.1") > 0);
        check("1.10 > 1.9", UpdateLogic.compare("1.10", "1.9") > 0);
        check("1.2 = 1.2.0", UpdateLogic.compare("1.2", "1.2.0") == 0);
        check("1.1 < 1.1.1", UpdateLogic.compare("1.1", "1.1.1") < 0);
        check("junk is 0", UpdateLogic.compare("1.x", "1.0") == 0);

        // Digest: listed ones must match; none listed passes.
        check("digest match", UpdateLogic.digestMatches(sha, sha.toUpperCase()));
        check("digest mismatch", !UpdateLogic.digestMatches(sha, "cd".repeat(32)));
        check("no digest listed", UpdateLogic.digestMatches(null, sha));
        check("no digest computed", !UpdateLogic.digestMatches(sha, null));

        // The offer: a verified download, newer than this app, not put off with Later.
        check("offer", UpdateLogic.shouldOffer(3, 2, 0, true));
        check("not verified", !UpdateLogic.shouldOffer(3, 2, 0, false));
        check("same version", !UpdateLogic.shouldOffer(2, 2, 0, true));
        check("older", !UpdateLogic.shouldOffer(1, 2, 0, true));
        check("Later for this version", !UpdateLogic.shouldOffer(3, 2, 3, true));
        check("Later for an older version: a newer one is offered", UpdateLogic.shouldOffer(4, 2, 3, true));

        // Changelog: bullets, markdown removed, at most `max`; plain paragraphs when there are no bullets.
        List<String> notes = UpdateLogic.changelog("## What's new\n- **Home** grid uses [titles](https://x.y) from `apps`\n"
                + "* Second one\n  - nested\n\nFull changelog: https://github.com/x\n- Third\n- Fourth\n- Fifth\n- Sixth\n- Seventh", 6);
        check("six bullets at most", notes.size() == 6);
        check("markdown removed", notes.size() > 0 && "Home grid uses titles from apps".equals(notes.get(0)));
        check("star bullets", notes.size() > 1 && "Second one".equals(notes.get(1)));
        check("nested bullet kept as a line", notes.size() > 2 && "nested".equals(notes.get(2)));
        List<String> plain = UpdateLogic.changelog("Faster Home.\r\n\r\nFewer retries offline.", 6);
        check("paragraphs without bullets", plain.size() == 2 && "Faster Home.".equals(plain.get(0)));
        check("empty body", UpdateLogic.changelog("", 6).isEmpty() && UpdateLogic.changelog(null, 6).isEmpty());
        check("headings only", UpdateLogic.changelog("## Notes\n### More", 6).isEmpty());
        check("long line cut at 160", UpdateLogic.changelog("- " + "a".repeat(300), 6).get(0).length() <= 160);

        // Labels and retry delays.
        check("size label", "4.9 MB".equals(UpdateLogic.sizeLabel(4_900_000)));
        check("small size label", "0.1 MB".equals(UpdateLogic.sizeLabel(20_000)));
        check("bad date", "".equals(UpdateLogic.dateLabel("yesterday")) && "".equals(UpdateLogic.dateLabel(null)));
        check("retry 30 min first", UpdateLogic.retryDelayMs(1) == 30 * 60_000L);
        check("retry doubles", UpdateLogic.retryDelayMs(2) == 60 * 60_000L);
        check("retry at most a day", UpdateLogic.retryDelayMs(20) == 24 * 3_600_000L);

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("UpdateLogicTest: all passed");
    }
}
