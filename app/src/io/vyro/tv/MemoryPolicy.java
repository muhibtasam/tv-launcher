package io.vyro.tv;

/**
 * How much image memory the launcher keeps, by the TV's memory tier (plain Java, tested in
 * tools/tests/MemoryPolicyTest). The tier is decided once per process, from Android's low-RAM flag and the app's
 * memory class (its heap limit: Android's own measure of what an app may use on this device), total RAM only breaking
 * ties; it never changes afterwards. Unknown or odd values fall back to the current behavior (MID).
 *
 * Measured on the 2.4 GB TCL (Priority 4.4): For You's posters reach ~26 MB while browsing at the 32 MB budget and are
 * made again from disk in 43-67 ms each; the sharp icon copies hold ~4 MB and come back identical. So on a LOW tier
 * the posters get 12 MB and the sharp copies go when memory is short; nothing else changes on any tier.
 */
public final class MemoryPolicy {
    public enum Tier { LOW, MID, HIGH }

    static final long MB = 1L << 20;
    static final long GB = 1L << 30;

    private static volatile Tier tier;   // null: not decided (the tests, or before App.onCreate): MID's budgets

    /** The tier for these readings. memoryClassMb <= 0 or totalRam <= 0: unknown. */
    public static Tier classify(boolean lowRam, int memoryClassMb, long totalRam) {
        if (lowRam) return Tier.LOW;   // the system says so: trusted over everything else
        boolean ramKnown = totalRam > 0;
        if (memoryClassMb <= 0) {      // no memory class: total RAM decides
            if (!ramKnown) return Tier.MID;
            return totalRam <= 3 * GB / 2 ? Tier.LOW : Tier.MID;
        }
        if (memoryClassMb <= 128) return Tier.LOW;
        if (memoryClassMb <= 256) {
            // A mid memory class on a device with about 1 GB (a vendor's generous heap limit): the RAM wins, low.
            return ramKnown && totalRam <= 5 * GB / 4 ? Tier.LOW : Tier.MID;
        }
        // A high memory class with 2 GB or less: not high (vendors set it inconsistently).
        return ramKnown && totalRam <= 2 * GB ? Tier.MID : Tier.HIGH;
    }

    /** Decides the tier once (later calls are ignored: it doesn't change while the process runs). */
    public static synchronized Tier init(boolean lowRam, int memoryClassMb, long totalRam) {
        if (tier == null) tier = classify(lowRam, memoryClassMb, totalRam);
        return tier;
    }

    public static Tier tier() {
        Tier t = tier;
        return t == null ? Tier.MID : t;
    }

    /** For You's poster cache, in bytes. */
    public static long posterBudget() {
        return posterBudget(tier());
    }

    public static long posterBudget(Tier t) {
        return t == Tier.LOW ? 12 * MB : 32 * MB;
    }

    /** Whether the home screen's sharp icon copies go when memory is short (made again for what's shown). */
    public static boolean releaseIconsOnTrim() {
        return releaseIconsOnTrim(tier());
    }

    public static boolean releaseIconsOnTrim(Tier t) {
        return t == Tier.LOW;
    }
}
