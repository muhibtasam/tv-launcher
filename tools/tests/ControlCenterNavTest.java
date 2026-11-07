import io.freemytv.ControlCenterNav;

/** Tests for the Control Center's tiles and arrows (no Android): run by tools/tests/run.sh. */
public class ControlCenterNavTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static final int WIFI = ControlCenterNav.WIFI, BT = ControlCenterNav.BT, AIRPLAY = ControlCenterNav.AIRPLAY,
            OFF = ControlCenterNav.OFF, VOLUME = ControlCenterNav.VOLUME, REFRESH = ControlCenterNav.REFRESH,
            LAUNCHER = ControlCenterNav.HOME_SETTINGS, CLEAR = ControlCenterNav.CLEAR, ALL = ControlCenterNav.ALL, NONE = -1;
    private static final String[] NAMES = {"Wi-Fi", "Bluetooth", "AirPlay/Inputs", "Turn off", "Volume", "Refresh",
            "Launcher", "Clear history", "TV settings"};

    // Where each arrow goes from each tile: {→, ←, ↑, ↓}. The panel, top to bottom:
    //   Wi-Fi | Bluetooth;  AirPlay (Inputs without AirPlay) | Turn off;  Volume (whole width);
    //   Refresh | Launcher | Clear history;  TV settings (whole width).
    private static final int[][] EXPECTED = {
        /* Wi-Fi     */ {BT, NONE, NONE, AIRPLAY},
        /* Bluetooth */ {NONE, WIFI, NONE, OFF},
        /* AirPlay   */ {OFF, NONE, WIFI, VOLUME},
        /* Turn off  */ {NONE, AIRPLAY, BT, VOLUME},
        /* Volume    */ {NONE, NONE, AIRPLAY, LAUNCHER},   // ↑: AirPlay and Turn off tie; the first in order wins
        /* Refresh   */ {LAUNCHER, NONE, VOLUME, ALL},
        /* Launcher  */ {CLEAR, REFRESH, VOLUME, ALL},
        /* Clear     */ {NONE, LAUNCHER, VOLUME, ALL},
        /* TV sett.  */ {NONE, NONE, LAUNCHER, NONE},
    };
    private static final int[][] ARROWS = {{1, 0}, {-1, 0}, {0, -1}, {0, 1}};
    private static final String[] ARROW = {"→", "←", "↑", "↓"};

    private static void everyArrow(String layout, float k) {
        float[][] t = new float[ControlCenterNav.COUNT][4];
        ControlCenterNav.layout(k, 1840, t);
        for (int from = 0; from < ControlCenterNav.COUNT; from++) {
            for (int a = 0; a < 4; a++) {
                int got = ControlCenterNav.move(t, from, ARROWS[a][0], ARROWS[a][1]);
                check(layout + " k=" + k + ": " + NAMES[from] + " " + ARROW[a] + " → " + (got < 0 ? "none" : NAMES[got]),
                        got == EXPECTED[from][a]);
            }
        }
    }

    public static void main(String[] args) {
        // The 1080p UI (k = 1) and the 720p one of TCL's 1 GB models (k = 2/3): the same moves.
        everyArrow("1080p", 1f);
        everyArrow("720p", 1280f / 1920f);
        // Without AirPlay the tile is Inputs in the same place (ControlCenter.ccNoAirplay): one layout, so the same
        // moves; checked as its own case so a future layout for it shows up here.
        everyArrow("Inputs instead of AirPlay", 1f);

        // The layout itself (k = 1): the panel's place, every tile inside it, none overlapping.
        float[][] t = new float[ControlCenterNav.COUNT][4];
        float[] p = ControlCenterNav.layout(1f, 1840, t);
        check("panel left 1200", p[0] == 1200f);
        check("panel top 136", p[1] == 136f);
        check("panel right 1840", p[2] == 1840f);
        check("panel bottom 800", p[3] == 800f);
        check("LAUNCHER heading at 560", p[4] == 560f);
        check("Wi-Fi tile", t[WIFI][0] == 1232f && t[WIFI][1] == 230f && t[WIFI][2] == 1513f && t[WIFI][3] == 322f);
        check("TV settings tile", t[ALL][0] == 1232f && t[ALL][1] == 692f && t[ALL][2] == 1808f && t[ALL][3] == 768f);
        for (int i = 0; i < ControlCenterNav.COUNT; i++) {
            check(NAMES[i] + " inside the panel", t[i][0] >= p[0] && t[i][1] >= p[1] && t[i][2] <= p[2] && t[i][3] <= p[3]);
            for (int j = i + 1; j < ControlCenterNav.COUNT; j++) {
                boolean overlap = t[i][0] < t[j][2] && t[j][0] < t[i][2] && t[i][1] < t[j][3] && t[j][1] < t[i][3];
                check(NAMES[i] + " and " + NAMES[j] + " don't overlap", !overlap);
            }
        }

        // Moves that can't happen: from no tile, or past the panel's edges (also covered in EXPECTED's NONEs).
        check("from -1: none", ControlCenterNav.move(t, -1, 1, 0) == NONE);
        check("from past the last tile: none", ControlCenterNav.move(t, ControlCenterNav.COUNT, 0, 1) == NONE);
        int i = WIFI;
        for (int n = 0; n < 20; n++) { int next = ControlCenterNav.move(t, i, 0, 1); if (next < 0) break; i = next; }
        check("↓ repeatedly ends on TV settings", i == ALL);
        for (int n = 0; n < 20; n++) { int next = ControlCenterNav.move(t, i, 0, -1); if (next < 0) break; i = next; }
        check("↑ repeatedly from TV settings ends on the top row", i == WIFI || i == BT);

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("ControlCenterNavTest: all passed");
    }
}
