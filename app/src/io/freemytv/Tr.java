package io.freemytv;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Translation for the plain-Java logic classes (AppsLogic, ContinueLogic, GamesV1Logic: tested without Android). The
 * keys are the same as Ui's and tools/translate.py finds them the same way (Tr.tr, Tr.trf, Tr.plural, Tr.datePattern).
 * Ui plugs its catalogue in as the source; without one (the unit tests) the text is the English in the code.
 */
public final class Tr {
    private Tr() {
    }

    /** What Ui gives: the current language's text, plural forms, dates and locale. */
    public interface Source {
        String tr(String english);

        String plural(String key, long n);

        String date(String key, long ms, TimeZone zone);

        Locale locale();
    }

    public static volatile Source source;

    public static String tr(String english) {
        Source s = source;
        if (s != null) return s.tr(english);
        int bar = english.indexOf('|');
        return bar < 0 ? english : english.substring(bar + 1);   // "apps|Watch" → "Watch"
    }

    /** One named placeholder: trf("{n} min ago", "n", 12). */
    public static String trf(String english, String name, Object value) {
        return tr(english).replace("{" + name + "}", String.valueOf(value));
    }

    /** plural("plural|one:{n} day ago|other:{n} days ago", 3): in English "one" for 1, else "other". */
    public static String plural(String key, long n) {
        Source s = source;
        if (s != null) return s.plural(key, n);
        String one = null, other = null;
        for (String form : key.substring(key.indexOf('|') + 1).split("\\|")) {
            if (form.startsWith("one:")) one = form.substring(4);
            else if (form.startsWith("other:")) other = form.substring(6);
        }
        String text = n == 1 && one != null ? one : other != null ? other : "";
        return text.replace("{n}", String.valueOf(n));
    }

    /** A date with the catalogue's pattern for "format|MMM d" (the key's own pattern in English). */
    public static String datePattern(String key, long ms, TimeZone zone) {
        Source s = source;
        if (s != null) return s.date(key, ms, zone);
        return format(key.substring("format|".length()), Locale.US, ms, zone);
    }

    /** The current language's locale (dates, weekday and month names). */
    public static Locale locale() {
        Source s = source;
        return s != null ? s.locale() : Locale.US;
    }

    /** A pattern no language changes ("EEE", "LLL"), in the current locale. */
    static String format(String pattern, long ms, TimeZone zone) {
        return format(pattern, locale(), ms, zone);
    }

    static String format(String pattern, Locale locale, long ms, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
        f.setTimeZone(zone);
        return f.format(new java.util.Date(ms));
    }

    // MARK: - Which catalogue

    /**
     * The catalogue for a setting ("auto", "fr", "pt-BR"…) among the languages with one (always "en"), for a TV in
     * tvLocale: a picked language when it has a catalogue, else the TV's own, else English. Simplified Chinese only
     * where it is the written form (not Taiwan, Hong Kong or Macau).
     */
    public static String resolve(String setting, String[] languages, Locale tvLocale) {
        if (setting != null && !setting.equals("auto")) {
            String code = match(setting.replace('_', '-'), languages);
            if (code != null) return code;
        }
        Locale tv = tvLocale != null ? tvLocale : Locale.US;
        String lang = base(tv.getLanguage());
        if (lang.equals("zh") && ("Hant".equals(tv.getScript()) || "TW".equals(tv.getCountry())
                || "HK".equals(tv.getCountry()) || "MO".equals(tv.getCountry()))) return "en";
        String code = match(lang, languages);
        return code != null ? code : "en";
    }

    private static String match(String tag, String[] languages) {
        for (String code : languages) if (code.equalsIgnoreCase(tag)) return code;
        int dash = tag.indexOf('-');
        String lang = base(dash < 0 ? tag : tag.substring(0, dash));
        for (String code : languages) {
            int d = code.indexOf('-');
            if ((d < 0 ? code : code.substring(0, d)).equalsIgnoreCase(lang)) return code;
        }
        return null;
    }

    /** Java's old codes for Indonesian and Hebrew, and Norwegian, as the catalogues name them. */
    public static String base(String lang) {
        lang = lang.toLowerCase(Locale.ROOT);
        return lang.equals("in") ? "id" : lang.equals("iw") ? "he" : lang.equals("no") || lang.equals("nn") ? "nb" : lang;
    }
}
