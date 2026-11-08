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
