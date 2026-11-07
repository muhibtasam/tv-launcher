import io.freemytv.AppsLogic;
import io.freemytv.ContinueLogic;
import io.freemytv.GamesV1Logic;
import io.freemytv.Tr;
import java.util.Locale;
import java.util.TimeZone;

/** Tests for the logic classes' translation (Tr, no Android): which catalogue, English fallbacks, a plugged-in source. */
public class TrTest {
    private static int failed;
    private static final String[] LANGS = {"cs", "da", "de", "el", "en", "es", "fi", "fr", "hi", "id", "it", "ja", "ko", "nb",
            "nl", "pl", "pt", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh-CN"};
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Karachi");
    private static final long DAY = 86_400_000L;

    private static void check(String name, Object got, Object want) {
        if (!String.valueOf(got).equals(String.valueOf(want))) { failed++; System.out.println("FAIL " + name + ": " + got + " (want " + want + ")"); }
    }

    public static void main(String[] args) {
        // Which catalogue: a picked one wins; "auto" follows the TV; anything without a catalogue is English.
        check("auto english", Tr.resolve("auto", LANGS, Locale.US), "en");
        check("auto german", Tr.resolve("auto", LANGS, Locale.GERMANY), "de");
        check("auto austrian", Tr.resolve("auto", LANGS, new Locale("de", "AT")), "de");
        check("auto brazil", Tr.resolve("auto", LANGS, new Locale("pt", "BR")), "pt");
        check("auto simplified", Tr.resolve("auto", LANGS, Locale.SIMPLIFIED_CHINESE), "zh-CN");
        check("auto taiwan", Tr.resolve("auto", LANGS, Locale.TRADITIONAL_CHINESE), "en");
        check("auto hant", Tr.resolve("auto", LANGS, Locale.forLanguageTag("zh-Hant-SG")), "en");
        check("auto indonesian old code", Tr.resolve("auto", LANGS, new Locale("in", "ID")), "id");
        check("auto norwegian", Tr.resolve("auto", LANGS, new Locale("no", "NO")), "nb");
        check("auto nynorsk", Tr.resolve("auto", LANGS, new Locale("nn", "NO")), "nb");
        check("auto urdu: none", Tr.resolve("auto", LANGS, new Locale("ur", "PK")), "en");
        check("null setting", Tr.resolve(null, LANGS, Locale.FRANCE), "fr");
        check("picked", Tr.resolve("ru", LANGS, Locale.US), "ru");
        check("picked over tv", Tr.resolve("ja", LANGS, Locale.GERMANY), "ja");
        check("picked case", Tr.resolve("ZH-cn", LANGS, Locale.US), "zh-CN");
        check("picked underscore", Tr.resolve("zh_CN", LANGS, Locale.US), "zh-CN");
        check("picked region", Tr.resolve("pt-BR", LANGS, Locale.US), "pt");
        check("picked english", Tr.resolve("en", LANGS, Locale.GERMANY), "en");
        check("picked unknown: tv's", Tr.resolve("xx", LANGS, Locale.ITALY), "it");

        // English without a catalogue: the context goes, plurals pick one/other, dates use the key's pattern.
        Tr.source = null;
        check("context", Tr.tr("apps|Watch"), "Watch");
        check("plain", Tr.tr("Yesterday"), "Yesterday");
        check("trf", Tr.trf("{n}h ago", "n", 14), "14h ago");
        check("plural one", Tr.plural("plural|one:{n} week ago|other:{n} weeks ago", 1), "1 week ago");
        check("plural other", Tr.plural("plural|one:{n} week ago|other:{n} weeks ago", 3), "3 weeks ago");
        check("plural zero", Tr.plural("plural|one:{n} day|other:{n} days", 0), "0 days");
        long nov13 = 1794571200000L;   // 2026-11-13, noon UTC
        check("date", Tr.datePattern("format|MMM d", nov13, TZ), "Nov 13");
        check("month", GamesV1Logic.monthLabel(nov13, TZ), "NOV");

        // A catalogue plugged in (as Ui does): every text of the logic classes goes through it.
        Tr.source = new Tr.Source() {
            @Override public String tr(String english) {
                return "<" + english + ">";
            }

            @Override public String plural(String key, long n) {
                return "<" + key + "#" + n + ">";
            }

            @Override public String date(String key, long ms, TimeZone zone) {
                return "<" + key + ">";
            }

            @Override public Locale locale() {
                return Locale.GERMANY;
            }
        };
        long now = 1793530000000L;
        check("chip", AppsLogic.chip(AppsLogic.SPORTS), "<apps|Sports>");
        check("last used h", AppsLogic.lastUsed(now - 3 * 3_600_000L, now, TZ).matches("<(3h ago|Yesterday)>"), true);
        check("last used yesterday", AppsLogic.lastUsed(now - DAY - 3_600_000L, now, TZ).startsWith("<"), true);
        check("last used weeks", AppsLogic.lastUsed(now - 20 * DAY, now, TZ), "<plural|one:{n} week ago|other:{n} weeks ago#2>");
        check("game ago", GamesV1Logic.ago(now - 4 * DAY, now, TZ), "<plural|one:{n} day ago|other:{n} days ago#4>");
        check("game days", GamesV1Logic.daysBadge(now + 9 * DAY, now, TZ), "<plural|one:{n} day|other:{n} days#9>");
        check("game date", GamesV1Logic.shortDate(nov13, TZ), "<format|MMM d>");
        check("want it", GamesV1Logic.wantIt(1200), "<1.2K people want it>");
        check("month german", GamesV1Logic.monthLabel(nov13, TZ), "NOV");
        check("opened date", ContinueLogic.opened(now - 12 * DAY, now, TZ), "<Opened <format|MMM d>>");
        check("opened weekday", ContinueLogic.opened(now - 3 * DAY, now, TZ).matches("<Opened \\p{L}+\\.?>"), true);
        Tr.source = null;

        if (failed > 0) {
            System.out.println("TrTest: " + failed + " failed");
            System.exit(1);
        }
        System.out.println("TrTest: all passed");
    }
}
