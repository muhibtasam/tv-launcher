import io.vyro.tv.MemoryPolicy;
import io.vyro.tv.MemoryPolicy.Tier;

/** MemoryPolicy: the tier from Android's readings, its fallbacks, and the budgets per tier. */
public class MemoryPolicyTest {
    static int failures;
    static final long MB = 1L << 20, GB = 1L << 30;

    static void eq(String what, Object want, Object got) {
        if (!want.equals(got)) { failures++; System.out.println("FAIL " + what + ": want " + want + ", got " + got); }
    }

    public static void main(String[] a) {
        // Before App decides: the current behavior (MID).
        eq("undecided tier", Tier.MID, MemoryPolicy.tier());
        eq("undecided poster budget", 32 * MB, MemoryPolicy.posterBudget());
        eq("undecided icons on trim", false, MemoryPolicy.releaseIconsOnTrim());

        // The low-RAM flag wins over everything.
        eq("low-RAM flag", Tier.LOW, MemoryPolicy.classify(true, 512, 8 * GB));
        // Memory class.
        eq("1 GB TV, class 96", Tier.LOW, MemoryPolicy.classify(false, 96, GB));
        eq("class 128 (boundary)", Tier.LOW, MemoryPolicy.classify(false, 128, 2 * GB));
        eq("this TCL: class 192, 2.4 GB", Tier.MID, MemoryPolicy.classify(false, 192, 2_451_284L * 1024));
        eq("class 256 (boundary)", Tier.MID, MemoryPolicy.classify(false, 256, 3 * GB));
        eq("class 384, 4 GB", Tier.HIGH, MemoryPolicy.classify(false, 384, 4 * GB));
        // Total RAM breaks ties and inconsistent readings.
        eq("mid class on ~1 GB: RAM wins", Tier.LOW, MemoryPolicy.classify(false, 192, GB));
        eq("high class on 2 GB: not high", Tier.MID, MemoryPolicy.classify(false, 512, 2 * GB));
        eq("high class, RAM unknown", Tier.HIGH, MemoryPolicy.classify(false, 384, 0));
        // Missing readings.
        eq("class unknown, 1 GB", Tier.LOW, MemoryPolicy.classify(false, 0, GB));
        eq("class unknown, 2.4 GB", Tier.MID, MemoryPolicy.classify(false, -1, 2_451_284L * 1024));
        eq("nothing known", Tier.MID, MemoryPolicy.classify(false, 0, 0));
        eq("negative RAM", Tier.MID, MemoryPolicy.classify(false, 0, -5));

        // Budgets per tier (only the approved changes: posters and sharp icons on LOW).
        eq("LOW posters", 12 * MB, MemoryPolicy.posterBudget(Tier.LOW));
        eq("MID posters", 32 * MB, MemoryPolicy.posterBudget(Tier.MID));
        eq("HIGH posters", 32 * MB, MemoryPolicy.posterBudget(Tier.HIGH));
        eq("LOW icons on trim", true, MemoryPolicy.releaseIconsOnTrim(Tier.LOW));
        eq("MID icons on trim", false, MemoryPolicy.releaseIconsOnTrim(Tier.MID));
        eq("HIGH icons on trim", false, MemoryPolicy.releaseIconsOnTrim(Tier.HIGH));

        // Decided once: later readings don't change it.
        eq("first decision", Tier.LOW, MemoryPolicy.init(false, 96, GB));
        eq("a second call keeps it", Tier.LOW, MemoryPolicy.init(false, 512, 8 * GB));
        eq("tier after", Tier.LOW, MemoryPolicy.tier());
        eq("budget follows it", 12 * MB, MemoryPolicy.posterBudget());

        if (failures > 0) { System.out.println("MemoryPolicyTest: " + failures + " failed"); System.exit(1); }
        System.out.println("MemoryPolicyTest: all passed");
    }
}
