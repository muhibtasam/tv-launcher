package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Navigation background, Option A (Cinematic Dark, approved 2026-11-01): each tab its own fixed gradient, never the
 * artwork. Home a warm umber with a deep teal corner, For You a muted oxblood with midnight blue, Apps graphite; over
 * it a faint key light where the remote is: the tabs (top left), the content (the middle) or the dock (bottom, Home only).
 *
 * Made once per tab and place (7 at most), at 480×270 with dithering (stretched over the screen by the background view,
 * as the photo was), then kept: a focus move between cards never changes it, a tab switch or the remote moving between
 * tabs, content and dock crossfades between two of them (the background's existing 480 ms crossfade).
 * The glass reads the tab's gradient without its key light: one 480×270 copy per tab, shared by its places, so the glass
 * doesn't change when only the key light moves.
 * Memory: each image 506 KB of GPU memory (HARDWARE), each tab's glass 506 KB of heap; the day theme's lightened
 * versions only with the Light theme (dark is the default).
 */
final class Moods {
    static final int TABS_ZONE = 0, CONTENT = 1, DOCK = 2;
    private static final int W = Backdrop.GW, H = Backdrop.GH;
    private static final float S = W / 1920f;   // drawn in 1920×1080 coordinates

    private static final Backdrop[] made = new Backdrop[9];
    private static final int[][] glassDark = new int[3][], glassLight = new int[3][], frostDark = new int[3][], frostLight = new int[3][];

    private Moods() {}

    /** The background for this tab (0 Home, 1 For You, 2 Apps) with the key light at this place. */
    static synchronized Backdrop get(int tab, int zone, boolean withLight) {
        tab = Math.max(0, Math.min(2, tab));
        if (zone == DOCK && tab != 0) zone = CONTENT;   // the dock is Home's only
        int i = tab * 3 + zone;
        Backdrop b = made[i];
        if (b == null || (withLight && b.lightImage == null)) {
            b = make(tab, zone, withLight);
            made[i] = b;
        }
        return b;
    }

    /** Builds all of them ahead (on a background thread), so the first tab switch doesn't make any. */
    static void warm(boolean withLight) {
        Thread t = new Thread(() -> {
            for (int tab = 0; tab < 3; tab++) for (int z = 0; z < 3; z++) if (z != DOCK || tab == 0) get(tab, z, withLight);
        }, "moods");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static Backdrop make(int tab, int zone, boolean withLight) {
        if (glassDark[tab] == null || (withLight && glassLight[tab] == null)) {
            Bitmap g = render(tab, -1);
            int[] px = new int[W * H];
            g.getPixels(px, 0, W, 0, 0, W, H);
            Bitmap f = Bitmap.createScaledBitmap(g, Backdrop.FW, Backdrop.FH, true);
            int[] fx = new int[Backdrop.FW * Backdrop.FH];
            f.getPixels(fx, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
            f.recycle();
            g.recycle();
            glassDark[tab] = px;
            frostDark[tab] = fx;
            if (withLight) {
                glassLight[tab] = Backdrop.whiten(px.clone(), 0.74f);
                frostLight[tab] = Backdrop.whiten(fx.clone(), 0.82f);
            }
        }
        Bitmap dark = render(tab, zone);
        Bitmap light = null;
        if (withLight) {
            int[] px = new int[W * H];
            dark.getPixels(px, 0, W, 0, 0, W, H);
            light = Backdrop.gpu(Bitmap.createBitmap(Backdrop.whiten(px, 0.82f), W, H, Bitmap.Config.ARGB_8888));
        }
        return Backdrop.mood(light, Backdrop.gpu(dark), withLight ? frostLight[tab] : null, withLight ? glassLight[tab] : null,
                frostDark[tab], glassDark[tab]);
    }

    /** The gradient in the design's own terms (CSS: base, then the two colour pools, then the key light on top). */
    private static Bitmap render(int tab, int zone) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.scale(S, S);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        int[][] base = {{0xFF17120F, 0xFF0C0A09, 0xFF080809}, {0xFF130C10, 0xFF0B080A, 0xFF08070A}, {0xFF141416, 0xFF0C0C0E, 0xFF09090A}};
        // linear-gradient(165deg): its line through the middle, as long as CSS makes it (|w sin| + |h cos|)
        double a = Math.toRadians(165);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a), len = 1920 * Math.abs(dx) + 1080 * Math.abs(dy);
        p.setShader(new LinearGradient(960 - dx * len / 2, 540 - dy * len / 2, 960 + dx * len / 2, 540 + dy * len / 2,
                base[tab], new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, p);
        switch (tab) {
            case 0:
                pool(c, p, 1920, 1080, 1200, 900, 24, 52, 62, 0.50f, 0.65f);    // deep teal, bottom right
                pool(c, p, 154, 0, 1300, 900, 150, 88, 46, 0.34f, 0.65f);       // warm umber, top left
                break;
            case 1:
                pool(c, p, 0, 1080, 1300, 900, 32, 34, 86, 0.44f, 0.65f);       // midnight blue, bottom left
                pool(c, p, 1766, 0, 1300, 900, 130, 30, 42, 0.36f, 0.65f);      // muted oxblood, top right
                break;
            default:
                pool(c, p, 1920, 1080, 1200, 800, 40, 46, 58, 0.38f, 0.65f);    // graphite
                pool(c, p, 960, -108, 1500, 800, 96, 104, 120, 0.30f, 0.65f);
        }
        if (zone >= 0) {
            int[][] key = {{255, 214, 168}, {255, 196, 190}, {205, 216, 255}};
            int[] k = key[tab];
            if (zone == TABS_ZONE) pool(c, p, 269, 173, 900, 520, k[0], k[1], k[2], 0.13f, 0.7f);
            else if (zone == DOCK) pool(c, p, 960, 1080, 1100, 460, k[0], k[1], k[2], 0.13f, 0.7f);
            else pool(c, p, 960, 475, 1500, 800, k[0], k[1], k[2], 0.07f, 0.7f);
        }
        return out;
    }

    /** radial-gradient(ellipse rx ry at x y, rgba(r,g,b,alpha) 0%, transparent end). */
    private static void pool(Canvas c, Paint p, float x, float y, float rx, float ry, int r, int g, int b, float alpha, float end) {
        int rgb = (r << 16) | (g << 8) | b, on = (Math.round(alpha * 255) << 24) | rgb;
        p.setShader(new RadialGradient(x, y, rx, new int[] {on, rgb}, new float[] {0, end}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, ry / rx, x, y);
        c.drawRect(x - rx, y - rx, x + rx, y + rx, p);
        c.restore();
        p.setShader(null);
    }
}
