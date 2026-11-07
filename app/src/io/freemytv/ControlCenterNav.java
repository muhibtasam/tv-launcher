package io.freemytv;

/**
 * The Control Center's tiles and its arrow keys (plain Java, tested in tools/tests/ControlCenterNavTest). The tiles'
 * places at scale k (the screen's width / 1920), and where an arrow goes from a tile: the nearest tile that way, close
 * in line counting more than distance. On TVs without AirPlay the AIRPLAY tile is Inputs, in the same place.
 */
public final class ControlCenterNav {
    private ControlCenterNav() {}

    public static final int WIFI = 0, BT = 1, AIRPLAY = 2, OFF = 3, VOLUME = 4, REFRESH = 5, HOME_SETTINGS = 6, CLEAR = 7,
            ALL = 8, COUNT = 9;
    // Top 136: under the round buttons (44..112, ~116 when the focused one grows), never over them; ends at ~884,
    // above the dock's glass (910).
    public static final float W = 640, TOP = 136, PAD = 32, GAP = 14, TILE_H = 92, ROW_H = 100;
    static final int L = 0, T = 1, R = 2, B = 3;

    /**
     * Fills `tiles` ({left, top, right, bottom} per tile) for scale k and the content's right edge (unscaled); returns
     * {panel left, panel top, panel right, panel bottom, the LAUNCHER heading's baseline}.
     */
    public static float[] layout(float k, float contentR, float[][] tiles) {
        return layout(k, contentR, tiles, 0, false);
    }

    // The Google section (alerts) under TV settings: a heading as LAUNCHER's, rows, and the "See all · Clear all" footer.
    // With it the panel reaches down to above the dock (884) and its content scrolls; without it nothing changes.
    public static final float ALERT_H = 72, FOOTER_H = 64, MAX_BOTTOM = 884;

    /**
     * As layout(k, contentR, tiles), plus `alerts` rows and, with `footer`, its footer: their places go in
     * tiles[COUNT ..] (rows, then the footer). Returns {panel left, panel top, panel right, panel bottom (what's shown),
     * the LAUNCHER heading's baseline, the GOOGLE heading's baseline (0 without alerts), the content's bottom (with the
     * panel's padding: past the panel's bottom when it scrolls)}.
     */
    public static float[] layout(float k, float contentR, float[][] tiles, int alerts, boolean footer) {
        float[] p = tiles(k, contentR, tiles);
        if (alerts <= 0 && !footer) return new float[] {p[0], p[1], p[2], p[3], p[4], 0, p[3]};
        float pad = PAD * k, gap = GAP * k, x0 = p[0] + pad, x1 = p[2] - pad;
        float y = tiles[ALL][B] + 30 * k;
        float headingY = y + 16 * k;   // "GOOGLE · 3 NEW", as "LAUNCHER"
        y += 34 * k;
        int at = COUNT;
        for (int i = 0; i < alerts; i++, at++) {
            set(tiles[at], x0, y, x1, y + ALERT_H * k);
            y += ALERT_H * k + gap;
        }
        if (footer) {
            set(tiles[at], x0, y, x1, y + FOOTER_H * k);
            y += FOOTER_H * k + gap;
        }
        float contentBottom = y - gap + pad;
        return new float[] {p[0], p[1], p[2], Math.min(contentBottom, MAX_BOTTOM * k), p[4], headingY, contentBottom};
    }

    /** How far the content can scroll (0 when it fits). `p`: layout's result. */
    public static float maxScroll(float[] p) {
        return Math.max(0, p[6] - p[3]);
    }

    /**
     * The scroll that keeps tile `focus` inside the panel with `margin` to spare, moving as little as possible from
     * `current`; the top two rows (Wi-Fi to Turn off) bring the panel back to its top, title included.
     */
    public static float scrollFor(float[][] tiles, int focus, float[] p, float current, float margin) {
        float max = maxScroll(p);
        if (max <= 0 || focus < 0 || focus >= tiles.length || tiles[focus][T] <= tiles[AIRPLAY][T]) return 0;
        float s = Math.max(0, Math.min(max, current));
        if (tiles[focus][T] - margin < p[1] + s) s = tiles[focus][T] - margin - p[1];
        if (tiles[focus][B] + margin > p[3] + s) s = tiles[focus][B] + margin - p[3];
        return Math.max(0, Math.min(max, s));
    }

    private static float[] tiles(float k, float contentR, float[][] tiles) {
        float pad = PAD * k, gap = GAP * k, w = W * k;
        float right = contentR * k, left = right - w, x0 = left + pad, x1 = right - pad, half = (x1 - x0 - gap) / 2;
        float y = TOP * k + pad + 62 * k;   // under the title
        set(tiles[WIFI], x0, y, x0 + half, y + TILE_H * k);
        set(tiles[BT], x1 - half, y, x1, y + TILE_H * k);
        y += TILE_H * k + gap;
        set(tiles[AIRPLAY], x0, y, x0 + half, y + TILE_H * k);
        set(tiles[OFF], x1 - half, y, x1, y + TILE_H * k);
        y += TILE_H * k + gap;
        set(tiles[VOLUME], x0, y, x1, y + 72 * k);
        y += 72 * k + 30 * k;
        float sectionY = y + 16 * k;   // "LAUNCHER"
        y += 34 * k;
        float third = (x1 - x0 - 2 * gap) / 3;   // Refresh, Launcher, Clear history side by side
        set(tiles[REFRESH], x0, y, x0 + third, y + ROW_H * k);
        set(tiles[HOME_SETTINGS], x0 + third + gap, y, x0 + 2 * third + gap, y + ROW_H * k);
        set(tiles[CLEAR], x1 - third, y, x1, y + ROW_H * k);
        y += ROW_H * k + gap;
        set(tiles[ALL], x0, y, x1, y + 76 * k);
        y += 76 * k + pad;
        return new float[] {left, TOP * k, right, y, sectionY};
    }

    /**
     * The tile an arrow (dx, dy: one of them ±1) goes to from tile `from`: the best-scored tile wholly that way, or -1
     * when there's none (the panel's edge).
     */
    public static int move(float[][] tiles, int from, int dx, int dy) {
        if (from < 0 || from >= tiles.length) return -1;
        float[] a = tiles[from];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < tiles.length; i++) {
            if (i == from) continue;
            float[] b = tiles[i];
            float along, across;
            if (dx > 0) {
                if (b[L] < a[R] - 1) continue;
                along = b[L] - a[R];
                across = gap(a[T], a[B], b[T], b[B]);
            } else if (dx < 0) {
                if (b[R] > a[L] + 1) continue;
                along = a[L] - b[R];
                across = gap(a[T], a[B], b[T], b[B]);
            } else if (dy > 0) {
                if (b[T] < a[B] - 1) continue;
                along = b[T] - a[B];
                across = gap(a[L], a[R], b[L], b[R]);
            } else {
                if (b[B] > a[T] + 1) continue;
                along = a[T] - b[B];
                across = gap(a[L], a[R], b[L], b[R]);
            }
            float centers = dx != 0 ? Math.abs((a[T] + a[B]) * 0.5f - (b[T] + b[B]) * 0.5f)
                    : Math.abs((a[L] + a[R]) * 0.5f - (b[L] + b[R]) * 0.5f);
            float score = along + 3 * across + 0.1f * centers;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** The space between two spans on one axis (0 when they overlap). */
    static float gap(float a0, float a1, float b0, float b1) {
        return Math.max(0, Math.max(b0 - a1, a0 - b1));
    }

    private static void set(float[] r, float l, float t, float rr, float b) {
        r[L] = l;
        r[T] = t;
        r[R] = rr;
        r[B] = b;
    }
}
