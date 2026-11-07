import io.freemytv.AlertsLogic;
import io.freemytv.AlertsLogic.Alert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tests for which notifications reach the Control Center, and in what order (no Android): run by tools/tests/run.sh. */
public class AlertsLogicTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL " + name); }
    }

    private static final String YT = "com.google.android.youtube.tv", PLAY = "com.android.vending";

    private static Alert a(String key, String pkg, String title, String text, long when) {
        return new Alert(key, pkg, title, text, when);
    }

    public static void main(String[] args) {
        // Google apps only.
        check("YouTube kept", AlertsLogic.keep(YT, false, false, false, null, false, "New video", ""));
        check("Play Store kept", AlertsLogic.keep(PLAY, false, false, false, null, false, "3 apps updated", ""));
        check("Google TV kept", AlertsLogic.keep("com.google.android.apps.tv.launcherx", false, false, false, null, false, "Tip", ""));
        check("Netflix dropped", !AlertsLogic.keep("com.netflix.ninja", false, false, false, null, false, "New episode", ""));
        check("this launcher dropped", !AlertsLogic.keep("io.freemytv", false, false, false, null, false, "x", ""));
        check("a look-alike name dropped", !AlertsLogic.keep("com.google.android.youtube.tv.fake", false, false, false, null, false, "x", ""));
        check("null package dropped", !AlertsLogic.keep(null, false, false, false, null, false, "x", ""));
        // Not alerts: ongoing, foreground services, group summaries, media, progress, empty.
        check("ongoing dropped", !AlertsLogic.keep(YT, true, false, false, null, false, "Playing", ""));
        check("group summary dropped", !AlertsLogic.keep(YT, false, true, false, null, false, "2 new", ""));
        check("foreground service dropped", !AlertsLogic.keep(PLAY, false, false, true, null, false, "Updating", ""));
        check("media dropped", !AlertsLogic.keep(YT, false, false, false, "transport", false, "Song", "Artist"));
        check("progress dropped", !AlertsLogic.keep(PLAY, false, false, false, "progress", false, "Downloading", ""));
        check("progress bar dropped", !AlertsLogic.keep(PLAY, false, false, false, null, true, "Downloading", ""));
        check("service category dropped", !AlertsLogic.keep(PLAY, false, false, false, "service", false, "Sync", ""));
        check("no text dropped", !AlertsLogic.keep(YT, false, false, false, null, false, " ", null));
        check("text only kept", AlertsLogic.keep(YT, false, false, false, null, false, null, "Something new"));

        // The list: newest first, one per key, dismissed ones hidden, at most 20.
        List<Alert> list = new ArrayList<>();
        AlertsLogic.post(list, a("k1", YT, "One", "", 100));
        AlertsLogic.post(list, a("k2", PLAY, "Two", "", 300));
        AlertsLogic.post(list, a("k3", YT, "Three", "", 200));
        check("newest first", list.get(0).key.equals("k2") && list.get(1).key.equals("k3") && list.get(2).key.equals("k1"));
        AlertsLogic.post(list, a("k1", YT, "One again", "", 400));
        check("same key replaced, not added", list.size() == 3);
        check("replaced one moves up", list.get(0).key.equals("k1") && list.get(0).title.equals("One again"));
        AlertsLogic.remove(list, "k3");
        check("removed", list.size() == 2 && AlertsLogic.find(list, "k3") == null);
        AlertsLogic.remove(list, "nope");
        check("removing an unknown key changes nothing", list.size() == 2);
        AlertsLogic.remove(list, null);
        check("removing null changes nothing", list.size() == 2);
        Set<String> dismissed = new HashSet<>();
        dismissed.add("k2");
        check("dismissed hidden", AlertsLogic.visible(list, dismissed, 20).size() == 1);
        check("empty list", AlertsLogic.visible(new ArrayList<>(), dismissed, 20).isEmpty());
        List<Alert> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) AlertsLogic.post(many, a("m" + i, YT, "T" + i, "", i));
        check("30 kept", many.size() == 30);
        check("visible capped at 20", AlertsLogic.visible(many, new HashSet<>(), 20).size() == 20);
        check("visible are the newest", AlertsLogic.visible(many, new HashSet<>(), 20).get(0).key.equals("m29"));
        List<Alert> flood = new ArrayList<>();
        for (int i = 0; i < 100; i++) AlertsLogic.post(flood, a("f" + i, YT, "T", "", i));
        check("store never grows past 40", flood.size() == 40);
        check("post with null key ignored", AlertsLogic.post(flood, a(null, YT, "x", "", 1)) == false);

        // Unread: anything newer than the last time the list was seen (and not dismissed).
        check("unread", AlertsLogic.unread(list, dismissed, 350));
        check("all seen", !AlertsLogic.unread(list, dismissed, 400));
        check("dismissed never unread", !AlertsLogic.unread(list, new HashSet<>(java.util.Arrays.asList("k1", "k2")), 0));
        check("unread count", AlertsLogic.unreadCount(list, new HashSet<>(), 200) == 2);

        // The row's line: the title, else the text; whitespace and new lines folded.
        check("title alone, folded", "New video".equals(AlertsLogic.line(a("x", YT, " New\n video ", "", 0))));
        check("text when no title", "Lofi Study Room".equals(AlertsLogic.line(a("x", YT, "", "Lofi  Study\tRoom", 0))));
        check("title and text", "Front door: someone is there".equals(AlertsLogic.line(a("x", YT, "Front door", "someone is there", 0))));

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("AlertsLogicTest: all passed");
    }
}
