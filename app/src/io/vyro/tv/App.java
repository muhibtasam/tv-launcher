package io.vyro.tv;

import android.app.Application;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        BuildKeys.init(this);   // the built-in API keys, kept for keyless updates
        Place.init(this);   // no storage needed: the weather's place from the time zone, read on first use
        readMemoryTier();
        // At power-on the home screen starts before the TV's storage unlocks (it is direct-boot aware): settings and
        // files can't be read then. HomeActivity calls ready() when it unlocks.
        android.os.UserManager um = getSystemService(android.os.UserManager.class);
        if (um != null && !um.isUserUnlocked()) {
            AppLog.w(String.format(java.util.Locale.ROOT, "startup: %-34s %7.2f s after power-on", "process started, storage locked",
                    android.os.SystemClock.elapsedRealtime() / 1000.0));
            return;
        }
        ready(this);
    }

    /** With "auto", the TV's language (or this app's, Android 13) changed: the text follows without a restart. */
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (ready) Ui.applyLanguage();
    }

    private static boolean ready;

    /** The image caches' memory tier (MemoryPolicy), once per process; needs no storage. */
    private void readMemoryTier() {
        boolean lowRam = false;
        int memoryClass = 0;
        long total = 0;
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            if (am != null) {
                lowRam = am.isLowRamDevice();
                memoryClass = am.getMemoryClass();
                android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                total = info.totalMem;
            }
        } catch (RuntimeException e) {
            AppLog.w("memory tier: " + e);   // unknown: MemoryPolicy keeps the current budgets (MID)
        }
        MemoryPolicy.Tier t = MemoryPolicy.init(lowRam, memoryClass, total);
        AppLog.w("memory tier: " + t + " (low RAM " + lowRam + ", memory class " + memoryClass + " MB, RAM " + (total >> 20) + " MB)");
    }

    /** Settings (and the startup timeline) once the storage can be read; once per process. */
    static synchronized void ready(android.content.Context context) {
        if (ready) return;
        ready = true;
        Application c = (Application) context.getApplicationContext();
        long began = Startup.begin();
        Prefs.init(c);
        String name = android.os.Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "";
        if (name.endsWith(":home")) {
            Startup.start(c);
            // When Android started this process (it may be well after power-on: that part isn't ours).
            Startup.line(String.format(java.util.Locale.ROOT, "startup: %-34s %7.2f s after power-on", "home process started",
                    android.os.Process.getStartElapsedRealtime() / 1000.0));
            Startup.took("Prefs.init", began);
            Startup.mark("App.onCreate done");
        }
    }
}
