package io.vyro.tv;

import android.util.Log;

/** The app's log (adb logcat -s VYRO). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("VYRO", message);
    }
}
