package io.vyro.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The update check's rules, in plain Java (tested in tools/tests/UpdateLogicTest): reading GitHub's "latest release"
 * answer, comparing versions, the changelog shown in the update sheet, and when to offer an update. Updates.java does
 * the network, the files and Android's installer.
 */
public final class UpdateLogic {
    private UpdateLogic() {}

    /** The only place an APK may come from: this repository's releases (github.com redirects to its download host). */
    public static final String RELEASES = "https://github.com/muhibtasam/vyro/releases/download/";
    public static final String LATEST = "https://api.github.com/repos/muhibtasam/vyro/releases/latest";
    static final String APK_NAME = "VYRO.apk";
    static final long MAX_APK = 100L * 1024 * 1024;

    /** A release as read from GitHub; `error` set (and the rest unset) when it can't be used. */
    public static final class Release {
        public String error, version, apkUrl, sha256, date = "";
        public long apkSize;
        public List<String> notes = new ArrayList<>();

        static Release failed(String why) {
            Release r = new Release();
            r.error = why;
            return r;
        }
    }

    /** GitHub's answer for the latest release. Never throws: anything unusable comes back with `error`. */
    public static Release parse(String json) {
        Object root;
        try {
            root = new Reader(json == null ? "" : json).document();
        } catch (RuntimeException e) {
            return Release.failed("not JSON");
        }
        if (!(root instanceof Map)) return Release.failed("not a release");
        Map<?, ?> o = (Map<?, ?>) root;
        String tag = text(o.get("tag_name")).trim();
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        if (!tag.matches("\\d+(\\.\\d+){0,3}")) return Release.failed("no version in the tag");
        Object assets = o.get("assets");
        if (!(assets instanceof List)) return Release.failed("no assets");
        Map<?, ?> apk = null;
        for (Object a : (List<?>) assets) {
            if (!(a instanceof Map)) continue;
            String name = text(((Map<?, ?>) a).get("name"));
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
            if (apk == null || APK_NAME.equals(name)) apk = (Map<?, ?>) a;
        }
        if (apk == null) return Release.failed("no APK in the release");
        String url = text(apk.get("browser_download_url"));
        if (!url.startsWith(RELEASES)) return Release.failed("APK not from this repository's releases");
        long size = apk.get("size") instanceof Number ? ((Number) apk.get("size")).longValue() : 0;
        if (size <= 0 || size > MAX_APK) return Release.failed("APK size " + size);
        String digest = text(apk.get("digest")).trim();
        String sha = null;
        if (!digest.isEmpty()) {
            if (!digest.startsWith("sha256:")) return Release.failed("unknown digest");
            sha = digest.substring(7).toLowerCase(Locale.ROOT);
            if (!sha.matches("[0-9a-f]{64}")) return Release.failed("bad digest");
        }
        Release r = new Release();
        r.version = tag;
        r.apkUrl = url;
        r.apkSize = size;
        r.sha256 = sha;
        r.date = dateLabel(text(o.get("published_at")));
        r.notes = changelog(o.get("body") instanceof String ? (String) o.get("body") : "", 6);
        return r;
    }

    private static String text(Object v) {
        return v instanceof String ? (String) v : "";
    }

    /** "1.10.0" vs "1.9": numbers compared one by one, missing ones (and anything not a number) as 0. */
    public static int compare(String a, String b) {
        String[] x = a == null ? new String[0] : a.split("\\."), y = b == null ? new String[0] : b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(number(x, i), number(y, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The download's SHA-256 against GitHub's (none listed: older releases; the signature is still checked). */
    public static boolean digestMatches(String expected, String actual) {
        if (expected == null) return true;
        return actual != null && expected.equalsIgnoreCase(actual);
    }

    /** Offer it: downloaded and checked, newer than this app, and not put off with Later for this version. */
    public static boolean shouldOffer(int availableCode, int installedCode, int laterCode, boolean verified) {
        return verified && availableCode > installedCode && availableCode != laterCode;
    }

    /** The release notes as lines for the sheet: its bullets (markdown removed), else its paragraphs; at most `max`. */
    public static List<String> changelog(String body, int max) {
        List<String> bullets = new ArrayList<>(), paragraphs = new ArrayList<>();
        if (body == null) return bullets;
        for (String raw : body.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.matches("(?i)^(full changelog|\\*\\*full changelog).*")) continue;
            boolean bullet = line.matches("^[-*+]\\s+.*");
            String clean = plain(bullet ? line.replaceFirst("^[-*+]\\s+", "") : line);
            if (clean.isEmpty()) continue;
            if (clean.length() > 160) clean = clean.substring(0, 159).trim() + "…";
            (bullet ? bullets : paragraphs).add(clean);
        }
        List<String> out = bullets.isEmpty() ? paragraphs : bullets;
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static String plain(String s) {
        s = s.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1");   // links and images: their text
        s = s.replaceAll("\\*\\*|__|`|~~", "");
        s = s.replaceAll("(^|\\s)[*_]([^*_]+)[*_](?=\\s|$|[.,;:!?])", "$1$2");
        s = s.replaceAll("https?://\\S+", "").replaceAll("\\s+", " ").trim();
        return s;
    }

    public static String sizeLabel(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", Math.max(0.1, bytes / 1_000_000.0));
    }

    /** "2026-11-02T18:30:17Z" → "2 Nov 2026"; "" when it isn't a date. */
    public static String dateLabel(String iso) {
        if (iso == null || !iso.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return "";
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        int m = Integer.parseInt(iso.substring(5, 7)), d = Integer.parseInt(iso.substring(8, 10));
        if (m < 1 || m > 12 || d < 1 || d > 31) return "";
        return d + " " + months[m - 1] + " " + iso.substring(0, 4);
    }

    /** A failed check tries again in 30 minutes, then 1 hour, 2…, at most a day (the daily check goes on anyway). */
    public static long retryDelayMs(int failures) {
        long d = 30 * 60_000L << Math.min(10, Math.max(0, failures - 1));
        return Math.min(d, 24 * 3_600_000L);
    }

    /** A strict JSON reader (objects, arrays, strings, numbers, true/false/null): enough for GitHub's answer. */
    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        Object document() {
            Object v = value();
            space();
            if (i != s.length()) throw new IllegalArgumentException("trailing text");
            return v;
        }

        private void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            space();
            if (i >= s.length()) throw new IllegalArgumentException("end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad value");
            return Double.parseDouble(s.substring(start, i));
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            space();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                space();
                String key = string();
                space();
                expect(':');
                m.put(key, value());
                space();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            space();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(value());
                space();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return a;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("open string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);
                }
            }
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c);
            i++;
        }
    }
}
