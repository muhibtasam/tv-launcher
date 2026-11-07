package io.vyro.tv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The API keys built into an APK (config.properties → Config), kept on the TV so an update from GitHub's releases,
 * built without keys (build.sh --generic), doesn't take them away. A build with a key saves it (private to this app,
 * like a TMDB key typed on the TV); a build without one uses the saved one. Never logged or shown.
 */
final class BuildKeys {
    private BuildKeys() {}

    private static Context app;
    private static SharedPreferences saved;

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    static String tmdb() { return get("tmdb", Config.TMDB_KEY); }
    static String watchmode() { return get("watchmode", Config.WATCHMODE_KEY); }
    static String trakt() { return get("trakt", Config.TRAKT_CLIENT_ID); }
    static String rawg() { return get("rawg", Config.RAWG_KEY); }
    static String steamGrid() { return get("steamgriddb", Config.STEAMGRIDDB_KEY); }
    static String openCritic() { return get("opencritic", Config.OPENCRITIC_KEY); }
    static String twitchId() { return get("twitch_id", Config.TWITCH_CLIENT_ID); }
    static String twitchSecret() { return get("twitch_secret", Config.TWITCH_CLIENT_SECRET); }

    /**
     * A setting from config.properties (the shelf's apps, the city, crypto…): a build of your own (not --generic)
     * saves it; a release (--generic, without your config) uses what yours saved, else its own default.
     */
    static synchronized String conf(String name, String built) {
        String b = built == null ? "" : built;
        SharedPreferences p = prefs();
        if (p == null) return b;
        if (!Config.GENERIC) {
            if (!b.equals(p.getString("conf." + name, null))) p.edit().putString("conf." + name, b).apply();
            return b;
        }
        return p.getString("conf." + name, b);
    }

    static boolean conf(String name, boolean built) {
        return Boolean.parseBoolean(conf(name, String.valueOf(built)));
    }

    /** The built-in key (saved for later builds), else the one an earlier build saved, else "". */
    private static synchronized String get(String name, String built) {
        String b = built == null ? "" : built.trim();
        SharedPreferences p = prefs();
        if (p == null) return b;   // storage still locked (power-on): the built-in one only, for now
        if (!b.isEmpty()) {
            if (!b.equals(p.getString(name, ""))) p.edit().putString(name, b).apply();
            return b;
        }
        return p.getString(name, "");
    }

    private static SharedPreferences prefs() {
        if (saved != null) return saved;
        if (app == null) return null;
        try {
            android.os.UserManager um = app.getSystemService(android.os.UserManager.class);
            if (um != null && !um.isUserUnlocked()) return null;
            saved = app.getSharedPreferences("build-keys", Context.MODE_PRIVATE);
        } catch (RuntimeException e) {
            return null;
        }
        return saved;
    }
}
