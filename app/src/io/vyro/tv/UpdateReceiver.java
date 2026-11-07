package io.vyro.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Android's installer's answer for an update (Updates.install). It asks the person first (its confirmation screen is
 * opened here), or says why it didn't install. Not exported; in :home, with the home screen.
 */
public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "io.vyro.tv.UPDATE_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("update: can't open the installer: " + e);
                tell(context, Ui.tr("The update couldn't be installed. Try again from Settings › Updates."));
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            AppLog.w("update: not installed (" + status + "): " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            tell(context, Ui.tr("The update couldn't be installed. Try again from Settings › Updates."));
        }
        // Cancelled on the installer's screen (ABORTED): nothing to say; the update stays in Settings › Updates.
    }

    static void tell(Context c, String text) {
        final Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
