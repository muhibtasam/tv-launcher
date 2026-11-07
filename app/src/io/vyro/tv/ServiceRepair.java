package io.vyro.tv;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.Objects;

/**
 * Puts the remote's app buttons service (RemoteKeyService) back on Android's enabled list when it's missing in Free
 * My TV mode: Android takes it off when the app is force-stopped or updated, and the TV's buttons then open
 * nothing. Written directly (WRITE_SECURE_SETTINGS, granted once by HomeMode through the TV's own ADB), so it costs
 * no ADB connection and no job: checked every time the home screen starts. Adapted from CleanYourTV's ServiceRepair.
 */
final class ServiceRepair {
    private ServiceRepair() {}

    static final String SERVICE = "io.vyro.tv/io.vyro.tv.RemoteKeyService";

    /**
     * In the background, 5 s after the home screen starts: right away it cost ~50 ms of the cold start (measured:
     * 405 against 355 ms to the first frame). The add, remove, add cycle below waits between steps too.
     */
    static void checkSoon(Context context) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                return;
            }
            check(app);
        }, "service-repair");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void check(Context c) {
        try {
            if (!RemoteKeys.supported() || !HomeMode.FREE.equals(HomeMode.state(c).optString("mode"))) return;
            if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                HomeMode.ensureRemoteService(c, 0);   // no permission yet: the ADB way (it grants it for next time)
                return;
            }
            ContentResolver cr = c.getContentResolver();
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String now = list == null || list.equals("null") ? "" : list;
            if ((":" + now + ":").contains(":" + SERVICE + ":")) return;
            String with = now.isEmpty() ? SERVICE : now + ":" + SERVICE;
            // A service killed while bound is on Android's "crashed" list and isn't bound again by being listed; it
            // drops that mark when it sees the service leave the list. So add, remove, add, each write only if the list
            // still reads what the last step left (someone else's change in a pause wins).
            if (!replace(cr, now, with)) return;
            Thread.sleep(1000);
            if (!replace(cr, with, now)) return;
            Thread.sleep(1000);
            if (!replace(cr, now, with)) return;
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            AppLog.w("remote buttons service: put back on");
        } catch (RuntimeException | InterruptedException e) {
            AppLog.w("remote buttons service: " + e);
        }
    }

    private static boolean replace(ContentResolver cr, String expected, String updated) {
        String now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Objects.equals(now == null || now.equals("null") ? "" : now, expected)
                && Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
    }
}
