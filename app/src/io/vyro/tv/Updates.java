package io.vyro.tv;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Updates from this repository's GitHub releases (the rules: UpdateLogic). Once a day (a job that waits for the network,
 * ModeJob "update"), and from Settings › Updates, the latest release is read; a newer one's APK is downloaded and
 * checked (GitHub's size and SHA-256, a newer version code, this package, signed with the same key as this app) before
 * it's offered. Installing is always the person's choice: the update sheet's Install hands it to Android's installer,
 * which asks again. Nothing installs by itself.
 *
 * The check runs in either process (the job in the main one, "Check now" in :home); only it writes update.json, and
 * both read it.
 */
final class Updates {
    private Updates() {}

    static final String READY = "ready", CURRENT = "current", ERROR = "error", CHECKING = "checking";
    private static final int DAILY_JOB = 7301, RETRY_JOB = 7302;
    private static final Object LOCK = new Object();

    /** What the sheet shows: a checked download that can be installed. */
    static final class Offer {
        String version, date, file, installed;
        int code;
        long size;
        List<String> notes = new ArrayList<>();
    }

    // MARK: - Schedule

    /** The daily check (once; it survives restarts), and a first one soon when there has never been one. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        try {
            if (js.getPendingJob(DAILY_JOB) == null) js.schedule(job(c, DAILY_JOB).setPeriodic(24 * 3_600_000L).build());
            if (state(c).optLong("at") == 0 && js.getPendingJob(RETRY_JOB) == null) retry(c, 60_000);
        } catch (RuntimeException e) {
            AppLog.w("update: can't schedule: " + e);
        }
    }

    private static void retry(Context c, long delayMs) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.schedule(job(c, RETRY_JOB).setMinimumLatency(delayMs).build());
    }

    private static JobInfo.Builder job(Context c, int id) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString("action", ModeJob.UPDATE);
        return new JobInfo.Builder(id, new ComponentName(c, ModeJob.class)).setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true);
    }

    // MARK: - State (update.json)

    private static AtomicFile stateFile(Context c) {
        return new AtomicFile(new File(c.getFilesDir(), "update.json"));
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "updates");   // not the cache: Android empties that first
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static JSONObject state(Context c) {
        try {
            return new JSONObject(new String(stateFile(c).readFully(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject o) {
        AtomicFile f = stateFile(c);
        FileOutputStream out = null;
        try {
            o.put("at", System.currentTimeMillis());
            out = f.startWrite();
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (Exception e) {
            if (out != null) f.failWrite(out);
            AppLog.w("update: state not saved: " + e);
        }
    }

    /** How often the running home screen asks GitHub for a new release (HomeView.offerUpdate). */
    static final long HOME_CHECK_MS = 60 * 60_000L;

    /** No check under way and none in the last `everyMs` (or the clock went back): time to ask again. */
    static boolean due(Context c, long everyMs) {
        JSONObject s = state(c);
        if (CHECKING.equals(s.optString("state"))) return false;
        long at = s.optLong("at"), now = System.currentTimeMillis();
        return at == 0 || now - at >= everyMs || now < at;
    }

    // MARK: - Check and download

    /** Reads the latest release and, when it's newer, downloads and checks it. On a background thread; never throws. */
    static void checkNow(Context c) {
        synchronized (LOCK) {
            JSONObject before = state(c);
            try {
                JSONObject busy = new JSONObject(before.toString()).put("state", CHECKING);
                save(c, busy);
                JSONObject now = check(c, before);
                now.put("failures", 0);
                save(c, now);
                AppLog.w("update: " + now.optString("state") + " " + now.optString("version"));
            } catch (Exception e) {
                int failures = before.optInt("failures") + 1;
                JSONObject failed = new JSONObject();
                try {
                    failed.put("state", ERROR).put("error", offline(e) ? "offline" : "failed").put("failures", failures);
                    if (READY.equals(before.optString("state")) || READY.equals(before.optString("was"))) {
                        // A checked download is still there: keep offering it.
                        failed = new JSONObject(before.toString()).put("state", READY).put("failures", failures);
                    }
                } catch (Exception ignored) {
                }
                save(c, failed);
                AppLog.w("update: check failed (" + failures + "): " + e);
                retry(c, UpdateLogic.retryDelayMs(failures));
            }
        }
    }

    private static boolean offline(Exception e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException;
    }

    private static JSONObject check(Context c, JSONObject before) throws Exception {
        UpdateLogic.Release r = UpdateLogic.parse(get(UpdateLogic.LATEST));
        if (r.error != null) throw new IllegalStateException("release: " + r.error);
        PackageInfo me = me(c);
        JSONObject out = new JSONObject().put("checked", r.version);
        if (UpdateLogic.compare(r.version, me.versionName) <= 0) {
            deleteDownloads(c, null);
            return out.put("state", CURRENT);
        }
        File apk = new File(dir(c), "VYRO-" + r.version + ".apk");
        boolean have = READY.equals(before.optString("state")) && r.version.equals(before.optString("version"))
                && apk.length() == r.apkSize && apk.getAbsolutePath().equals(before.optString("file"));
        if (!have) download(r, apk);
        int code = verify(c, apk, me);
        deleteDownloads(c, apk);
        JSONArray notes = new JSONArray();
        for (String n : r.notes) notes.put(n);
        return out.put("state", READY).put("version", r.version).put("code", code).put("size", r.apkSize)
                .put("date", r.date).put("notes", notes).put("file", apk.getAbsolutePath());
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "vyro");
        try {
            int status = h.getResponseCode();
            if (status != 200) throw new IllegalStateException("GitHub answered " + status);
            try (InputStream in = h.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 2_000_000) throw new IllegalStateException("answer too large");
                }
                return b.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }

    /** The APK into `to`, only if its size and SHA-256 are GitHub's. */
    private static void download(UpdateLogic.Release r, File to) throws Exception {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        h.setConnectTimeout(15_000);
        h.setReadTimeout(30_000);
        h.setRequestProperty("User-Agent", "vyro");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try {
            if (h.getResponseCode() != 200) throw new IllegalStateException("download answered " + h.getResponseCode());
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > r.apkSize) throw new IllegalStateException("download larger than listed");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
        } finally {
            h.disconnect();
        }
        if (total != r.apkSize || !UpdateLogic.digestMatches(r.sha256, hex(sha.digest()))) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IllegalStateException("download doesn't match the release (" + total + " bytes)");
        }
        if (!part.renameTo(to)) throw new IllegalStateException("can't keep the download");
    }

    /** This package, a newer version code, and signed with this app's own key; its version code, else it throws. */
    private static int verify(Context c, File apk, PackageInfo me) {
        PackageManager pm = c.getPackageManager();
        PackageInfo a = pm.getPackageArchiveInfo(apk.getPath(), signingFlags());
        String why = a == null ? "not an APK"
                : !c.getPackageName().equals(a.packageName) ? "another app (" + a.packageName + ")"
                : code(a) <= code(me) ? "not newer (" + code(a) + ")"
                : !certs(me).equals(certs(a)) || certs(a).isEmpty() ? "not signed with this app's key" : null;
        if (why != null) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            throw new IllegalStateException("download refused: " + why);
        }
        return code(a);
    }

    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    static int code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certs(PackageInfo p) {
        Set<String> out = new HashSet<>();
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            sigs = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners()
                    : p.signingInfo.getSigningCertificateHistory();
        } else if (p.signatures != null) {
            sigs = p.signatures;
        }
        if (sigs == null) return out;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) out.add(hex(md.digest(s.toByteArray())));
        } catch (Exception ignored) {
        }
        return out;
    }

    static PackageInfo me(Context c) throws PackageManager.NameNotFoundException {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), signingFlags());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** Every download but `keep` (null: all of them). */
    private static void deleteDownloads(Context c, File keep) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (keep == null || !f.equals(keep)) f.delete();
        }
    }

    /** This app was replaced (MY_PACKAGE_REPLACED): the downloads go, and the state says it's up to date. */
    static void cleanUp(Context c) {
        synchronized (LOCK) {
            deleteDownloads(c, null);
            try {
                save(c, new JSONObject().put("state", CURRENT));
            } catch (Exception ignored) {
            }
        }
    }

    // MARK: - Offer, Later and Install

    /** The update to offer now, or null (none, not checked, put off with Later, or already installed). */
    static Offer offer(Context c) {
        JSONObject s = state(c);
        if (!READY.equals(s.optString("state"))) return null;
        File f = new File(s.optString("file"));
        int installed;
        String installedName;
        try {
            PackageInfo me = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            installed = code(me);
            installedName = me.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        int code = s.optInt("code");
        int later = c.getSharedPreferences("updates", Context.MODE_PRIVATE).getInt("later", 0);
        if (!UpdateLogic.shouldOffer(code, installed, later, f.isFile())) return null;
        return toOffer(s, f, installedName);
    }

    /** The checked download whatever Later says (Settings › Updates opens the sheet with it). */
    static Offer available(Context c) {
        JSONObject s = state(c);
        File f = new File(s.optString("file"));
        if (!READY.equals(s.optString("state")) || !f.isFile()) return null;
        try {
            PackageInfo me = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (s.optInt("code") <= code(me)) return null;
            return toOffer(s, f, me.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static Offer toOffer(JSONObject s, File f, String installed) {
        Offer o = new Offer();
        o.version = s.optString("version");
        o.code = s.optInt("code");
        o.size = s.optLong("size");
        o.date = s.optString("date");
        o.file = f.getAbsolutePath();
        o.installed = installed;
        JSONArray n = s.optJSONArray("notes");
        if (n != null) for (int i = 0; i < n.length(); i++) o.notes.add(n.optString(i));
        return o;
    }

    /** Later: this version isn't offered again (Settings › Updates still shows it). */
    static void later(Context c, int code) {
        c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putInt("later", code).apply();
    }

    /**
     * Install: Android's installer gets the checked APK and asks the person. Null when it was handed over, else a
     * sentence to show (the TV doesn't let this app install yet: its "unknown apps" setting opens).
     */
    static String install(Activity a, Offer o) {
        PackageManager pm = a.getPackageManager();
        if (!pm.canRequestPackageInstalls()) {
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return Ui.tr("Allow VYRO to install apps, then choose Install again.");
            } catch (RuntimeException e) {
                return Ui.tr("Allow VYRO in TV Settings › Apps › Security › Unknown sources, then choose Install again.");
            }
        }
        final Context app = a.getApplicationContext();
        final File apk = new File(o.file);
        HomeView.LOADER.execute(() -> {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            try {
                for (PackageInstaller.SessionInfo s : installer.getMySessions()) installer.abandonSession(s.getSessionId());
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(apk.length());
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    Intent done = new Intent(app, UpdateReceiver.class).setAction(UpdateReceiver.ACTION);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getBroadcast(app, 0, done, flags).getIntentSender());
                }
                AppLog.w("update: handed " + apk.getName() + " to the installer");
            } catch (Exception e) {
                AppLog.w("update: install failed: " + e);
                UpdateReceiver.tell(app, Ui.tr("The update couldn't be installed. Try again from Settings › Updates."));
            }
        });
        return null;
    }
}
