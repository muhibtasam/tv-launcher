package io.freemytv;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Google apps' notifications for the Control Center's "Google" section (the rules: AlertsLogic). A notification
 * listener in :home, with the home screen: what it keeps lives in memory only (the system holds the notifications
 * themselves), at most 40, text and the app's tap action, no pictures. Google TV has no screen for notification access,
 * so Settings turns it on through the TV's ADB (as the remote buttons' service), `cmd notification allow_listener`.
 */
public class GoogleAlerts extends NotificationListenerService {
    private static final Object LOCK = new Object();
    private static final List<AlertsLogic.Alert> alerts = new ArrayList<>();
    private static final Map<String, PendingIntent> taps = new HashMap<>();
    private static final Set<String> dismissed = new HashSet<>();   // cleared here but kept by the system
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile GoogleAlerts instance;
    private static Runnable listener;

    // MARK: - The listener

    @Override public void onListenerConnected() {
        instance = this;
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            active = null;
        }
        synchronized (LOCK) {
            alerts.clear();
            taps.clear();
            if (active != null) for (StatusBarNotification n : active) add(n);
        }
        changed();
    }

    @Override public void onListenerDisconnected() {
        instance = null;
        synchronized (LOCK) {
            alerts.clear();
            taps.clear();
        }
        changed();
    }

    @Override public void onNotificationPosted(StatusBarNotification n) {
        boolean kept;
        synchronized (LOCK) {
            kept = add(n);
        }
        if (kept) changed();
    }

    @Override public void onNotificationRemoved(StatusBarNotification n) {
        boolean had;
        synchronized (LOCK) {
            had = AlertsLogic.find(alerts, n.getKey()) != null;
            AlertsLogic.remove(alerts, n.getKey());
            taps.remove(n.getKey());
            dismissed.remove(n.getKey());
        }
        if (had) changed();
    }

    /** Keeps it when AlertsLogic does (a Google app's real alert); with the lock held. */
    private boolean add(StatusBarNotification n) {
        Notification x = n.getNotification();
        if (x == null) return false;
        CharSequence title = x.extras == null ? null : x.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.extras == null ? null : x.extras.getCharSequence(Notification.EXTRA_TEXT);
        boolean progress = x.extras != null && x.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0;
        boolean media = x.extras != null && x.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        int f = x.flags;
        if (!AlertsLogic.keep(n.getPackageName(), (f & Notification.FLAG_ONGOING_EVENT) != 0 || media,
                (f & Notification.FLAG_GROUP_SUMMARY) != 0, (f & Notification.FLAG_FOREGROUND_SERVICE) != 0,
                x.category, progress, title == null ? null : title.toString(), text == null ? null : text.toString())) {
            return false;
        }
        AlertsLogic.post(alerts, new AlertsLogic.Alert(n.getKey(), n.getPackageName(),
                title == null ? null : title.toString(), text == null ? null : text.toString(), n.getPostTime()));
        if (x.contentIntent != null) taps.put(n.getKey(), x.contentIntent);
        else taps.remove(n.getKey());
        return true;
    }

    private static void changed() {
        main.post(() -> {
            if (listener != null) listener.run();
        });
    }

    // MARK: - For the home screen

    /** The home screen's callback (null to stop), on the main thread, when the list changes. */
    static void listen(Runnable r) {
        listener = r;
    }

    /** What the Control Center shows: newest first, at most `max`; empty when turned off in Settings. */
    static List<AlertsLogic.Alert> visible(Context c, int max) {
        if (!on(c)) return new ArrayList<>();
        synchronized (LOCK) {
            return AlertsLogic.visible(alerts, dismissed, max);
        }
    }

    static int count(Context c) {
        return visible(c, AlertsLogic.STORE_MAX).size();
    }

    /** The orange dot: something arrived since the list was last seen. */
    static boolean unread(Context c) {
        if (!on(c)) return false;
        long seen = prefs(c).getLong("seen", 0);
        synchronized (LOCK) {
            return AlertsLogic.unread(alerts, dismissed, seen);
        }
    }

    static int unreadCount(Context c) {
        if (!on(c)) return 0;
        long seen = prefs(c).getLong("seen", 0);
        synchronized (LOCK) {
            return AlertsLogic.unreadCount(alerts, dismissed, seen);
        }
    }

    /** The list was seen (the focus reached it): the dot goes until something newer arrives. */
    static void markSeen(Context c) {
        long newest = 0;
        synchronized (LOCK) {
            for (AlertsLogic.Alert a : alerts) newest = Math.max(newest, a.when);
        }
        if (newest > prefs(c).getLong("seen", 0)) prefs(c).edit().putLong("seen", newest).apply();
    }

    /** OK on a row: what the notification opens (its own tap action), else the app. False when nothing could open. */
    static boolean open(Context c, AlertsLogic.Alert a) {
        PendingIntent tap;
        synchronized (LOCK) {
            tap = taps.get(a.key);
        }
        boolean opened = false;
        if (tap != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Android 14: the home screen (in front) lends its right to open the app's screen.
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    tap.send(c, 0, null, null, null, null, o.toBundle());
                } else {
                    tap.send();
                }
                opened = true;
            } catch (PendingIntent.CanceledException e) {
                AppLog.w("alerts: tap action gone for " + a.pkg);
            }
        }
        if (!opened) {
            Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
            if (launch == null) launch = c.getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (launch != null) {
                try {
                    c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    opened = true;
                } catch (RuntimeException e) {
                    AppLog.w("alerts: can't open " + a.pkg + ": " + e);
                }
            }
        }
        if (opened) markSeen(c);
        return opened;
    }

    /** Clear all: the system's copies go where they can be cleared, and all of them leave the list here. */
    static void clearAll(Context c) {
        List<String> keys = new ArrayList<>();
        synchronized (LOCK) {
            for (AlertsLogic.Alert a : alerts) {
                keys.add(a.key);
                dismissed.add(a.key);
            }
        }
        GoogleAlerts l = instance;
        if (l != null && !keys.isEmpty()) {
            try {
                l.cancelNotifications(keys.toArray(new String[0]));   // ongoing ones aren't listed; others the system may keep
            } catch (RuntimeException e) {
                AppLog.w("alerts: clear: " + e);
            }
        }
        markSeen(c);
        changed();
    }

    // MARK: - On or off (Settings)

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("alerts", Context.MODE_PRIVATE);
    }

    /** Turned on in Settings (on unless turned off) and allowed by the system. */
    static boolean on(Context c) {
        return wanted(c) && granted(c);
    }

    static boolean wanted(Context c) {
        return prefs(c).getBoolean("on", true);
    }

    /** Low-memory TVs (Android Go) don't run notification listeners at all. */
    static boolean supported(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        return am == null || !am.isLowRamDevice();
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, GoogleAlerts.class);
    }

    static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(component(c));
        }
        String list = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return list != null && list.contains(component(c).flattenToString());
    }

    /**
     * Turns them on or off: the setting, and the system's access through the TV's ADB (on a background thread). `done`
     * gets null when it worked, else what to show, on the main thread.
     */
    static void set(Context c, boolean on, java.util.function.Consumer<String> done) {
        final Context app = c.getApplicationContext();
        prefs(app).edit().putBoolean("on", on).putBoolean("asked", true).apply();
        changed();
        HomeView.LOADER.execute(() -> {
            String result = null;
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                String out = adb.shell("cmd notification " + (on ? "allow_listener " : "disallow_listener ")
                        + component(app).flattenToString()).trim();
                AppLog.w("alerts: " + (on ? "allow" : "disallow") + "_listener: " + (out.isEmpty() ? "ok" : out));
                if (on && !granted(app)) result = Ui.tr("The TV didn't allow notification access.");
            } catch (Exception e) {
                AppLog.w("alerts: adb: " + e.getMessage());
                if (on) result = Ui.tr("Google notifications need Free My TV mode (USB debugging on).");
            }
            final String r = result;
            main.post(() -> done.accept(r));
        });
    }

    /** Once, quietly: on unless turned off, so access is asked for the first time the home screen shows. */
    static void firstRun(Context c) {
        if (prefs(c).getBoolean("asked", false) || !supported(c)) return;
        // Only where the TV's ADB is already allowed (Free My TV mode): never a USB debugging prompt from here.
        if (!HomeMode.FREE.equals(HomeMode.state(c).optString("mode"))) return;
        if (granted(c)) {
            prefs(c).edit().putBoolean("asked", true).apply();
            return;
        }
        set(c, true, r -> {});
    }
}
