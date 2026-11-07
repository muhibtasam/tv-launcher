package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads what the streaming apps publish to Android TV (TvProvider, READ_TV_LISTINGS, which the Continue row already
 * uses): their home screen channels and the programs in them, and Watch Next. Rows become AppContentLogic records; the
 * choosing is there. Background threads only (content provider queries).
 */
final class AppContent {
    private AppContent() {}

    private static final String[] CHANNEL_COLS = {TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE};
    private static final String[] PROGRAM_COLS = {"package_name", "channel_id", "title", "type", "poster_art_uri",
            "thumbnail_uri", "intent_uri", "content_id"};
    private static final String[] WATCH_NEXT_COLS = {"package_name", "title", "type", "episode_display_number",
            "poster_art_uri", "thumbnail_uri", "intent_uri", "content_id", "last_playback_position_millis",
            "duration_millis", "last_engagement_time_utc_millis"};

    /** Everything readable, in the provider's order. Throws when the provider can't be read (the caller keeps what it had). */
    static List<AppContentLogic.Record> read(Context c) {
        if (!WatchNext.allowed(c)) throw new IllegalStateException("READ_TV_LISTINGS not granted");
        ContentResolver cr = c.getContentResolver();
        long now = System.currentTimeMillis();
        Map<Long, AppContentLogic.Channel> channels = new HashMap<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI, CHANNEL_COLS, null, null, null)) {
            if (cur == null) throw new IllegalStateException("no channels cursor");
            while (cur.moveToNext()) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(cur.getString(3))) continue;   // not the tuner's channels
                channels.put(cur.getLong(0), new AppContentLogic.Channel(cur.getString(1), cur.getString(2)));
            }
        }
        List<AppContentLogic.Record> out = new ArrayList<>();
        for (Map<String, String> row : rows(cr, TvContract.PreviewPrograms.CONTENT_URI, PROGRAM_COLS)) {
            out.add(AppContentLogic.preview(row, channels, now));
        }
        for (Map<String, String> row : rows(cr, TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLS)) {
            out.add(AppContentLogic.watchNext(row, now));
        }
        return out;
    }

    private static List<Map<String, String>> rows(ContentResolver cr, Uri uri, String[] cols) {
        List<Map<String, String>> out = new ArrayList<>();
        try (Cursor cur = cr.query(uri, cols, null, null, null)) {
            if (cur == null) throw new IllegalStateException("no cursor for " + uri);
            while (cur.moveToNext()) {
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < cols.length; i++) row.put(cols[i], cur.isNull(i) ? null : cur.getString(i));
                out.add(row);
            }
        }
        return out;
    }

    /**
     * The intent for a record's own link, kept inside the app that published it (AppContentLogic.linkAllowed): a
     * component or package naming another app, a selector, or permission grants are never passed on. Null when the
     * link is refused or the app has nothing to open it with.
     */
    static Intent intent(Context c, String link, String pkg) {
        if (!AppContentLogic.linkAllowed(link, pkg)) return null;
        Intent in;
        try {
            in = link.startsWith("intent:") || link.contains("#Intent;") ? Intent.parseUri(link, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(link));
        } catch (Exception e) {
            return null;
        }
        in.setSelector(null);
        if (in.getComponent() != null && !pkg.equals(in.getComponent().getPackageName())) return null;
        if (in.getPackage() != null && !pkg.equals(in.getPackage())) return null;
        if (in.getComponent() == null) in.setPackage(pkg);
        in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // the app's own flags but no grants (FLAG_GRANT_* dropped)
        try {
            if (c.getPackageManager().queryIntentActivities(in, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) return null;
        } catch (RuntimeException e) {
            return null;
        }
        return in;
    }
}
