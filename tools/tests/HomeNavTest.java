import io.vyro.tv.HomeNav;

/** Tests for Home's focus rules (no Android): run by tools/tests/run.sh. */
public class HomeNavTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) {
        // 4 Continue cards (0..3) and 10 bento cards (4..13): from the tabs, the bento's first; from the dock, Continue's.
        check("from tabs: bento first", HomeNav.sectionFirst(14, 4, true) == 4);
        check("from dock: continue first", HomeNav.sectionFirst(14, 4, false) == 0);
        // No Continue row: the bento's first either way.
        check("no continue, from tabs", HomeNav.sectionFirst(10, 0, true) == 0);
        check("no continue, from dock", HomeNav.sectionFirst(10, 0, false) == 0);
        // Only Continue cards (picks not loaded yet): its first either way.
        check("only continue, from tabs", HomeNav.sectionFirst(4, 4, true) == 0);
        check("only continue, from dock", HomeNav.sectionFirst(4, 4, false) == 0);
        // Home without Continue Watching: a mosaic of 9 filling 8 columns × 3 rows exactly (no gap, no overlap), one large.
        int[][] full = HomeNav.BENTO_FULL;
        check("full bento: 23 tiles", full.length == 23);
        int wide = 0;
        for (int[] b : HomeNav.BENTO) if (b[2] == 2 && b[3] == 1) wide++;
        check("bento: 13 cards, 2 wide", HomeNav.BENTO.length == 13 && wide == 2);
        int[] cells = new int[30];
        int big = 0;
        boolean inside = true;
        for (int[] b : full) {
            if (b[2] >= 2 && b[3] == 2) big++;
            if (b[3] > b[2]) inside = false;   // no tall tiles: squares and wide ones only
            for (int x = b[0]; x < b[0] + b[2]; x++) for (int y = b[1]; y < b[1] + b[3]; y++) {
                if (x < 0 || x >= 10 || y < 0 || y >= 3) inside = false; else cells[y * 10 + x]++;
            }
        }
        boolean exact = true;
        for (int n : cells) exact &= n == 1;
        check("full bento: inside the grid", inside);
        check("full bento: every cell once", exact);
        check("full bento: one large card", big == 1);
        check("full bento: the first is large", full[0][2] == 3 && full[0][3] == 2);
        // The large card follows the focus: card 4 large at column 2; card 0 back to one cell, first; all cells once.
        int[][] moved = HomeNav.focusBento(6, 2, 9, 4, 2, 0);
        check("focus: card 4 large there", moved[4][0] == 2 && moved[4][1] == 0 && moved[4][2] == 2 && moved[4][3] == 2);
        check("focus: card 0 one cell, first", moved[0][0] == 0 && moved[0][1] == 0 && moved[0][2] == 1);
        int[] mc = new int[12];
        for (int[] b : moved) for (int x = b[0]; x < b[0] + b[2]; x++) for (int y = b[1]; y < b[1] + b[3]; y++) mc[y * 6 + x]++;
        boolean once = true;
        for (int n : mc) once &= n == 1;
        check("focus: every cell once", once);
        check("focus: kept inside", HomeNav.focusBento(6, 2, 9, 8, 5, 1)[8][0] == 4 && HomeNav.focusBento(6, 2, 9, 8, 5, 1)[8][1] == 0);
        // Nothing at all.
        check("empty", HomeNav.sectionFirst(0, 0, true) == 0);

        // Moving between sections lands on the other section's first card; within one, the arrow's own target.
        check("bento → continue: its first", HomeNav.across(9, 2, 4) == 0);
        check("continue → bento: its first", HomeNav.across(2, 9, 4) == 4);
        check("within bento: as it is", HomeNav.across(5, 8, 4) == 8);
        check("within continue: as it is", HomeNav.across(1, 2, 4) == 2);
        check("no target: none", HomeNav.across(5, -1, 4) == -1);
        check("no continue row: as it is", HomeNav.across(3, 7, 0) == 7);

        if (failed > 0) { System.out.println("HomeNavTest: " + failed + " failed"); System.exit(1); }
        System.out.println("HomeNavTest: all passed");
    }
}
