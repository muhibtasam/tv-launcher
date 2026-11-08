package io.vyro.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home 2.0's background images, your own: the Backgrounds folder on the TV (/sdcard/Backgrounds, sent from the
 * computer's backgrounds-images/ by tools/backgrounds.sh, or the app's own Android/data/io.vyro.tv/files/Backgrounds).
 * One at random, blurred as the home screen's background (Backdrop.wallpaper), a new one on `advance` (never the same
 * twice in a row). Large images cost little: each is read once at a power-of-two scale just above Backdrop.WALL_W
 * (a 5K PNG decodes at 1/4), shrunk to WALL_W and kept as a small JPEG in the app's cache, which every later turn
 * reads instead (~40 KB, a few ms); the last few built backgrounds stay in memory. All off the main thread.
 * Never in the APK or the repository: they're yours. They come from a phone (Home Settings > Backgrounds' QR code:
 * BgPage), a USB drive's Backgrounds folder (importUsb), or a computer (tools/backgrounds.sh), into the app's folder.
 */
final class Backgrounds {
    private Backgrounds() {}

    private static final int KEEP = 3;   // built backgrounds kept in memory (each ~2 MB)
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Random random = new Random();

    private static List<File> listed = new ArrayList<>();
    private static long listedStamp = -1;
    private static File built, building;
    private static Backdrop backdrop;
    private static final java.util.LinkedHashMap<File, Backdrop> made = new java.util.LinkedHashMap<File, Backdrop>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<File, Backdrop> e) {
            return size() > KEEP;
        }
    };

    private static File[] dirs;

    /** Looked up once: getExternalFilesDir does disk work (and logs) on every call, and files() runs every 15 s. */
    private static synchronized File[] dirs(Context c) {
        if (dirs == null || dirs[1] == null) {
            dirs = new File[] {new File(Environment.getExternalStorageDirectory(), "Backgrounds"), c.getExternalFilesDir("Backgrounds")};
        }
        return dirs;
    }

    /** The images in both folders (listed again only when a folder changed). */
    static synchronized List<File> files(Context c) {
        File[] dirs = dirs(c);
        long stamp = 0;
        for (File d : dirs) if (d != null && d.exists()) stamp = stamp * 31 + d.lastModified();
        if (stamp == listedStamp) return listed;
        List<File> out = new ArrayList<>();
        boolean unread = false;
        for (File d : dirs) {
            File[] all = d == null ? null : d.listFiles();
            if (all == null) {   // missing, or no permission to read it (yet): look again next time
                unread |= d != null && d.exists();
                continue;
            }
            Arrays.sort(all);
            for (File f : all) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) out.add(f);
            }
        }
        listed = out;
        listedStamp = unread ? -1 : stamp;
        if (!unread) forget(c, out);
        return listed;
    }

    /**
     * Removed from the folder means gone: its cached small copy is deleted and its built background dropped, so only
     * the images in the folder ever show (runs only when a folder changed, never per frame).
     */
    private static void forget(Context c, List<File> now) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (File f : now) keep.add(cacheName(f));
        File[] cached = new File(c.getCacheDir(), "backgrounds").listFiles();
        if (cached != null) for (File f : cached) if (!keep.contains(f.getName())) f.delete();
        made.keySet().retainAll(now);
        if (built != null && !now.contains(built)) {
            built = null;
            backdrop = null;
        }
    }

    private static String cacheName(File f) {
        return Integer.toHexString((f.getName() + f.length() + f.lastModified()).hashCode()) + ".jpg";
    }

    static boolean any(Context c) {
        return !files(c).isEmpty();
    }

    /**
     * The background ready to draw, or null (none in the folders, or the first still being built). `advance`: build
     * another one at random; when it's ready, `ready` runs on the main thread (the old one stays until then).
     */
    static synchronized Backdrop current(Context c, boolean advance, Runnable ready) {
        List<File> list = files(c);
        if (list.isEmpty()) {
            backdrop = null;
            built = null;
            return null;
        }
        if (built != null && !list.contains(built)) advance = true;   // deleted on the TV
        if ((advance || built == null) && building == null) {
            int next = HomeNav.nextBackground(list.size(), built == null ? -1 : list.indexOf(built), random.nextDouble());
            File want = list.get(next);
            building = want;
            Context app = c.getApplicationContext();
            worker.execute(() -> {
                Backdrop b;
                synchronized (Backgrounds.class) {
                    b = made.get(want);
                }
                try {
                    if (b == null) {
                        Bitmap src = small(app, want);
                        if (src != null) b = Backdrop.wallpaper(src);
                    }
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("backgrounds: " + want.getName() + ": " + e.getMessage());
                }
                synchronized (Backgrounds.class) {
                    building = null;
                    if (b == null) return;
                    made.put(want, b);
                    backdrop = b;
                    built = want;
                }
                if (ready != null) main.post(ready);
            });
        }
        return backdrop;
    }

    /**
     * The image at Backdrop.WALL_W wide: from the cache when it was read before (keyed by name, size and date, so a
     * changed file is read again), else decoded at the largest power-of-two scale still at least that wide, shrunk,
     * and saved to the cache as a JPEG (quality 85: it's blurred on screen anyway).
     */
    private static Bitmap small(Context c, File f) {
        File dir = new File(c.getCacheDir(), "backgrounds");
        File cached = new File(dir, cacheName(f));
        Bitmap b = cached.exists() ? BitmapFactory.decodeFile(cached.getPath()) : null;
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= Backdrop.WALL_W && o.outHeight / (o.inSampleSize * 2) >= Backdrop.WALL_H) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        float s = Math.max((float) Backdrop.WALL_W / b.getWidth(), (float) Backdrop.WALL_H / b.getHeight());
        if (s < 1) {
            Bitmap scaled = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        if (dir.isDirectory() || dir.mkdirs()) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(cached)) {
                b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (java.io.IOException e) {
                AppLog.w("backgrounds: cache " + f.getName() + ": " + e.getMessage());
            }
        }
        return b;
    }

    // MARK: - Home Settings > Backgrounds: on or off, adding, removing, the thumbnails

    /** Home shows your photos: chosen in Home Settings, and some in the folders. */
    static boolean on(Context c) {
        return Prefs.BG_OWN.equals(Prefs.bgSource()) && any(c);
    }

    static final int MAX_BYTES = 25 << 20;   // one photo, as sent: a phone's page shrinks it to 4K first
    private static final int MIN_SIDE = 480;

    /** The app's own folder (where new ones go), made if missing; null without external storage. */
    private static File own(Context c) {
        File d = dirs(c)[1];
        return d != null && (d.isDirectory() || d.mkdirs()) ? d : null;
    }

    /**
     * Saves `length` bytes of `in` as a new background (named by time, the extension kept), if they decode as an image
     * at least MIN_SIDE on its short side. Null when it's saved, else why not (for the phone or the screen). Off the
     * main thread.
     */
    static String add(Context c, InputStream in, long length, String ext) throws IOException {
        File d = own(c);
        if (d == null) return "storage";
        File part = new File(d, ".part-" + System.nanoTime());
        byte[] buf = new byte[64 << 10];
        long left = length;
        try (OutputStream out = new FileOutputStream(part)) {
            while (left != 0) {
                int n = in.read(buf, 0, (int) (left < 0 ? buf.length : Math.min(buf.length, left)));
                if (n < 0) {
                    if (left < 0) break;   // to the end (a file): done
                    throw new IOException("cut short");
                }
                out.write(buf, 0, n);
                if (left > 0) left -= n;
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(part.getPath(), o);
        if (o.outWidth <= 0 || Math.min(o.outWidth, o.outHeight) < MIN_SIDE) {
            part.delete();
            return "image";
        }
        File to = new File(d, "bg-" + System.currentTimeMillis() + "-" + Integer.toHexString(random.nextInt(0x10000)) + ext);
        if (!part.renameTo(to)) {
            part.delete();
            return "storage";
        }
        d.setLastModified(System.currentTimeMillis());   // files() lists again
        return null;
    }

    /** Deletes one of yours (the screen's OK); true when it's gone. */
    static boolean remove(Context c, File f) {
        boolean gone = f.delete() || !f.exists();
        File d = f.getParentFile();
        if (d != null) d.setLastModified(System.currentTimeMillis());
        synchronized (Backgrounds.class) {
            thumbs.remove(f);
            listedStamp = -1;
        }
        return gone;
    }

    /** A USB drive's Backgrounds folder (any case, at its top) with images in it, or null (no drive, none, or unreadable). */
    static File usbFolder() {
        File[] volumes = new File("/storage").listFiles();
        if (volumes == null) return null;
        for (File v : volumes) {
            String name = v.getName();
            if (name.equals("emulated") || name.equals("self")) continue;
            File[] top = v.listFiles();
            if (top == null) continue;
            for (File f : top) {
                if (f.isDirectory() && f.getName().equalsIgnoreCase("Backgrounds") && !images(f).isEmpty()) return f;
            }
        }
        return null;
    }

    private static List<File> images(File d) {
        List<File> out = new ArrayList<>();
        File[] all = d.listFiles();
        if (all == null) return out;
        Arrays.sort(all);
        for (File f : all) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) out.add(f);
        }
        return out;
    }

    /** Copies the images in a drive's folder into yours (those already copied, by name and size, are skipped). Off the main thread. */
    static int importFrom(Context c, File folder) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (File f : files(c)) have.add(f.length() + ":" + f.getName());
        java.util.Set<String> copied = new java.util.HashSet<>(Prefs.bgCopied());
        int n = 0;
        for (File f : images(folder)) {
            String key = f.length() + ":" + f.getName();
            if (copied.contains(key) || have.contains(key)) continue;
            String name = f.getName().toLowerCase(Locale.ROOT);
            try (InputStream in = new FileInputStream(f)) {
                if (add(c, in, -1, name.substring(name.lastIndexOf('.'))) == null) {
                    n++;
                    copied.add(key);
                }
            } catch (IOException | RuntimeException e) {
                AppLog.w("backgrounds: usb " + f.getName() + ": " + e.getMessage());
            }
        }
        Prefs.setBgCopied(copied);
        return n;
    }

    private static final java.util.Map<File, Bitmap> thumbs = new java.util.HashMap<>();
    private static final java.util.Set<File> thumbing = new java.util.HashSet<>();

    /** A small copy (about `width` wide) for the settings screen, or null until it's made; `ready` runs then (main thread). */
    static synchronized Bitmap thumb(Context c, File f, int width, Runnable ready) {
        Bitmap b = thumbs.get(f);
        if (b != null || !thumbing.add(f)) return b;
        worker.execute(() -> {
            Bitmap t = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                t = BitmapFactory.decodeFile(f.getPath(), o);
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("backgrounds: thumb " + f.getName() + ": " + e.getMessage());
            }
            synchronized (Backgrounds.class) {
                thumbing.remove(f);
                if (t != null) thumbs.put(f, t);
            }
            if (t != null && ready != null) main.post(ready);
        });
        return null;
    }
}
