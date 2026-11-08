package io.vyro.tv;

/**
 * Home's focus rules (plain Java, tested in tools/tests/HomeNavTest). Home's cards are Continue Watching's first
 * (0 .. cont-1), then the bento's. Every row and section is entered at its first item.
 */
public final class HomeNav {
    private HomeNav() {}

    /**
     * Big Bento: 10 columns. The large card (3 × 2, landscape art) is always the first and has the focus; ← → slide the
     * titles through it (HomeView.slideBento). Like For You's rows: the narrow squares show posters, the large and the
     * 2 wide cards landscape art. Beside Continue Watching, 10 × 2: the large card, 2 wide, 10 squares (13).
     */
    public static final int COLS = 10;

    /** Home 2.0's hero row (Netflix style): the focused title wide, then posters, the last ones past the screen's edge. */
    public static final int HERO_N = 6;

    /**
     * The hero row's places, {left, top, right, bottom} for each of `n` cards: card 0 `heroW` wide, then posters
     * `posterW` wide, `gap` apart, all `height` tall from `top`, starting at `left`. Cards past the screen are kept so a
     * slide has one ready to come in.
     */
    public static float[][] heroRow(float left, float top, float height, float heroW, float posterW, float gap, int n) {
        float[][] out = new float[Math.max(0, n)][];
        float x = left;
        for (int i = 0; i < out.length; i++) {
            float w = i == 0 ? heroW : posterW;
            out[i] = new float[] {x, top, x + w, top + height};
            x += w + gap;
        }
        return out;
    }

    /**
     * A slide's start for a card that wrapped around the list (the old hero sent to the end, or the last one brought
     * to the hero): not across the whole row but in from the nearest side, one card's width plus `gap` away.
     * Returns the start's left; else `fromLeft` as it was. `far` = how far counts as a wrap.
     */
    public static float slideFrom(float fromLeft, float toLeft, float toWidth, float gap, float far) {
        if (Math.abs(fromLeft - toLeft) <= far) return fromLeft;
        return fromLeft > toLeft ? toLeft - toWidth - gap : toLeft + toWidth + gap;
    }

    /**
     * Home 2.0's random background: an index among `count` ready pictures for `r` in [0, 1), never `current` again
     * when there's another to show; -1 when there are none.
     */
    public static int nextBackground(int count, int current, double r) {
        if (count <= 0) return -1;
        if (count == 1) return 0;
        boolean skip = current >= 0 && current < count;
        int i = (int) Math.min(count - (skip ? 2 : 1), Math.floor(r * (count - (skip ? 1 : 0))));
        return skip && i >= current ? i + 1 : i;
    }

    public static final int[][] BENTO = {
        {0, 0, 3, 2}, {3, 0, 1, 1}, {4, 0, 1, 1}, {5, 0, 2, 1}, {7, 0, 1, 1}, {8, 0, 1, 1}, {9, 0, 1, 1},
        {3, 1, 1, 1}, {4, 1, 2, 1}, {6, 1, 1, 1}, {7, 1, 1, 1}, {8, 1, 1, 1}, {9, 1, 1, 1},
    };

    /** Home without Continue Watching: 10 × 3, the same two rows and a row of 10 squares (23). */
    public static final int[][] BENTO_FULL = {
        {0, 0, 3, 2}, {3, 0, 1, 1}, {4, 0, 1, 1}, {5, 0, 2, 1}, {7, 0, 1, 1}, {8, 0, 1, 1}, {9, 0, 1, 1},
        {3, 1, 1, 1}, {4, 1, 2, 1}, {6, 1, 1, 1}, {7, 1, 1, 1}, {8, 1, 1, 1}, {9, 1, 1, 1},
        {0, 2, 1, 1}, {1, 2, 1, 1}, {2, 2, 1, 1}, {3, 2, 1, 1}, {4, 2, 1, 1}, {5, 2, 1, 1}, {6, 2, 1, 1}, {7, 2, 1, 1},
        {8, 2, 1, 1}, {9, 2, 1, 1},
    };


    /**
     * The Big Bento's places, {column, row, columns, rows} for each of `n` cards: card `big` 2 × 2 at column `bc`,
     * row `br` (kept inside the grid), every other card one cell, in their order, row by row around it. The large card
     * follows the focus: the one it leaves goes back to one cell where the others' order puts it.
     */
    public static int[][] focusBento(int cols, int rows, int n, int big, int bc, int br) {
        bc = Math.max(0, Math.min(cols - 2, bc));
        br = Math.max(0, Math.min(rows - 2, br));
        int[][] out = new int[n][];
        if (big >= 0 && big < n) out[big] = new int[] {bc, br, 2, 2};
        int k = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (big >= 0 && big < n && c >= bc && c < bc + 2 && r >= br && r < br + 2) continue;
                while (k < n && (k == big)) k++;
                if (k >= n) return out;
                out[k++] = new int[] {c, r, 1, 1};
            }
        }
        return out;
    }

    /**
     * Coming into Home's cards from the tabs (fromTop) or from the dock: the first card of the section you arrive in,
     * the bento's large card from above, Continue Watching's first from below. With only one section: its first.
     */
    public static int sectionFirst(int count, int cont, boolean fromTop) {
        if (cont <= 0 || cont >= count) return 0;
        return fromTop ? cont : 0;
    }

    /** An arrow from card `from` pointing at card `next` (-1: none): into the other section, its first card instead. */
    public static int across(int from, int next, int cont) {
        if (next < 0 || cont <= 0 || (next < cont) == (from < cont)) return next;
        return next < cont ? 0 : cont;
    }
}
