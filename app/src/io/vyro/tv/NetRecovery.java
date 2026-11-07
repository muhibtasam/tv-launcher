package io.vyro.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

/**
 * The internet came back: the requests that failed during the outage are retried now, not after their usual wait
 * (10-15 minutes). One default-network callback for the home screen's process, held with the app's context (no screen
 * kept): Android calls it only when the connection changes, so it costs nothing at rest. NetRecoveryLogic decides when.
 */
final class NetRecovery {
    private static NetRecovery instance;
    private static Runnable listener;   // main thread: the home screen while it shows

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final NetRecoveryLogic logic = new NetRecoveryLogic();
    private boolean validated, blocked;   // main thread

    private NetRecovery(Context c) {
        context = c;
    }

    /** Once per process, from the home screen (main thread). */
    static void start(Context c) {
        if (instance != null) return;
        NetRecovery n = new NetRecovery(c.getApplicationContext());
        try {
            ConnectivityManager cm = (ConnectivityManager) n.context.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(n.callback, n.main);
            instance = n;
        } catch (RuntimeException e) {
            AppLog.w("network watch unavailable: " + e);
        }
    }

    /** The home screen while it shows: told when failed requests were let go, to ask again for what it shows. */
    static void setListener(Runnable r) {
        listener = r;
    }

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            changed();
        }

        @Override public void onBlockedStatusChanged(Network network, boolean isBlocked) {
            blocked = isBlocked;
            changed();
        }

        @Override public void onLost(Network network) {
            validated = false;
            changed();
        }
    };

    private void changed() {
        long now = System.currentTimeMillis();
        boolean wasOnline = logic.online();
        long at = logic.report(validated && !blocked, now);
        if (!logic.online()) {
            main.removeCallbacks(recover);   // offline again: a recovery waiting to settle is off
            if (wasOnline) AppLog.w("network: offline");
        } else if (at != NetRecoveryLogic.NONE) {   // just came online (a repeated "online" leaves the one waiting)
            main.removeCallbacks(recover);
            main.postDelayed(recover, Math.max(0, at - now));
        }
    }

    private final Runnable recover = new Runnable() {
        @Override public void run() {
            long since = logic.fire(System.currentTimeMillis());
            if (since == NetRecoveryLogic.NONE) return;
            StringBuilder which = new StringBuilder();
            if (Recs.networkBack(since)) which.append(" recommendations");
            if (Discover.networkBack(since)) which.append(" for-you");
            if (Games.networkBack(since)) which.append(" games");
            if (HomeData.networkBack(since)) which.append(" weather/prices");
            AppLog.w("network: online" + (which.length() == 0 ? ", nothing to retry" : ", retrying" + which));
            if (which.length() > 0 && listener != null) listener.run();
        }
    };
}
