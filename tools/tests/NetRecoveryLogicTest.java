import io.freemytv.NetRecoveryLogic;

/** NetRecoveryLogic: when a recovery runs, and which failures it covers. */
public class NetRecoveryLogicTest {
    static int failures;
    static final long NONE = -1, SETTLE = 2_000, GAP = 30_000, BEFORE = 60_000;

    static void eq(String what, long want, long got) {
        if (want != got) { failures++; System.out.println("FAIL " + what + ": want " + want + ", got " + got); }
    }
    static void yes(String what, boolean v) { if (!v) { failures++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) {
        long t = 1_000_000_000L;

        // Online → offline → online: one recovery, SETTLE after; it covers the outage (less BEFORE).
        NetRecoveryLogic n = new NetRecoveryLogic();
        eq("first online report schedules (unknown counts as offline)", t + SETTLE, n.report(true, t));
        eq("fires at its time, covering everything since the start", 0, n.fire(t + SETTLE));
        eq("repeated online reports: nothing", NONE, n.report(true, t + 5_000));
        eq("and nothing to fire", NONE, n.fire(t + 9_000));
        eq("offline: nothing scheduled", NONE, n.report(false, t + 100_000));
        eq("offline again (repeated): nothing", NONE, n.report(false, t + 110_000));
        eq("back online", t + 200_000 + SETTLE, n.report(true, t + 200_000));
        eq("not before its time", NONE, n.fire(t + 200_000 + SETTLE - 1));
        eq("covers failures since the drop, less BEFORE", t + 100_000 - BEFORE, n.fire(t + 200_000 + SETTLE));
        eq("only once per return", NONE, n.fire(t + 300_000));

        // A drop during the settle cancels; staying offline never fires.
        n = new NetRecoveryLogic();
        n.report(true, t);
        n.fire(t + SETTLE);
        n.report(false, t + 50_000);
        n.report(true, t + 60_000);
        n.report(false, t + 61_000);
        eq("dropped before settling: no recovery", NONE, n.fire(t + 70_000));
        eq("still offline much later: no recovery", NONE, n.fire(t + 3_600_000));

        // Flapping: at most one recovery per GAP.
        n = new NetRecoveryLogic();
        n.report(true, t);
        eq("first", 0, n.fire(t + SETTLE));
        n.report(false, t + 5_000);
        long at = n.report(true, t + 6_000);
        eq("next waits for the gap", t + SETTLE + GAP, at);
        eq("not during the gap", NONE, n.fire(t + 10_000));
        yes("after the gap", n.fire(at) != NONE);

        // Boot: offline first (no network yet), then online: failures since the start count.
        n = new NetRecoveryLogic();
        n.report(false, t);
        n.report(true, t + 40_000);
        eq("boot without network: since the start", 0, n.fire(t + 40_000 + SETTLE));

        // Which failures are retried.
        long since = t + 100_000 - BEFORE;
        yes("failed during the outage: retried", NetRecoveryLogic.eligible(t + 150_000, since));
        yes("failed just before the drop was noticed: retried", NetRecoveryLogic.eligible(t + 90_000, since));
        yes("failed long before (a key, a quota): keeps its wait", !NetRecoveryLogic.eligible(t + 10_000, since));
        yes("no failure: nothing", !NetRecoveryLogic.eligible(0, since));
        yes("no recovery: nothing", !NetRecoveryLogic.eligible(t + 150_000, NONE));
        yes("since the start: any failure", NetRecoveryLogic.eligible(5, 0));

        if (failures > 0) { System.out.println("NetRecoveryLogicTest: " + failures + " failed"); System.exit(1); }
        System.out.println("NetRecoveryLogicTest: all passed");
    }
}
