package io.freemytv;

/**
 * When to retry after the internet comes back (plain Java, tested in tools/tests/NetRecoveryLogicTest).
 *
 * Fed the connection's state as Android reports it (online = a validated internet connection this app may use). A
 * recovery is due once per offline → online change, SETTLE_MS after it (the connection settles; a quick drop cancels
 * it), at least MIN_GAP_MS after the previous one (a flapping connection makes no retry storm). Repeated "online"
 * reports do nothing. An unknown state (no report yet: the TV just started, before its Wi-Fi is up) counts as
 * offline since the start. A recovery retries only failures from the outage: since it began, less BEFORE_LOSS_MS
 * (a request can fail a little before Android notices the drop). Older failures (a bad key, a quota) keep their wait.
 */
public final class NetRecoveryLogic {
    public static final long SETTLE_MS = 2_000, MIN_GAP_MS = 30_000, BEFORE_LOSS_MS = 60_000;
    public static final long NONE = -1;

    private int state = -1;           // -1 unknown, 0 offline, 1 online
    private long lostAt;              // when it went offline (0: since the start)
    private boolean due;
    private long dueAt;
    private long lastRecovery = Long.MIN_VALUE / 2;

    /** A report of the connection. Returns when a recovery should run (call fire then), or NONE. */
    public long report(boolean online, long now) {
        if (!online) {
            if (state == 1) lostAt = now;   // unknown → offline keeps 0: the whole time since the start
            state = 0;
            due = false;
            return NONE;
        }
        if (state == 1) return NONE;        // still online (capabilities change often: signal strength...)
        state = 1;
        due = true;
        dueAt = Math.max(now + SETTLE_MS, lastRecovery + MIN_GAP_MS);
        return dueAt;
    }

    /** The scheduled time came: the failures since this time are retried now (NONE: nothing to do, or not yet). */
    public long fire(long now) {
        if (!due || state != 1 || now < dueAt) return NONE;
        due = false;
        lastRecovery = now;
        return lostAt == 0 ? 0 : Math.max(0, lostAt - BEFORE_LOSS_MS);
    }

    public boolean online() {
        return state == 1;
    }

    /** A failure at `failedAt` (0: none) is retried by a recovery covering failures `since`. */
    public static boolean eligible(long failedAt, long since) {
        return failedAt != 0 && since != NONE && failedAt >= since;
    }
}
