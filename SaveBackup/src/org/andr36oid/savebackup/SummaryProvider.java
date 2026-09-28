package org.andr36oid.savebackup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The summary of the Save backup tile in Settings ("Last backup: Today, 14:02, 123 files"),
 * through the tile's com.android.settings.summary_uri. Settings calls getDynamicSummary and
 * watches the URI, so changed() updates an open Settings page.
 */
public class SummaryProvider extends ContentProvider {

    static final String AUTHORITY = "org.andr36oid.savebackup.summary";
    private static final String METHOD = "getDynamicSummary";
    private static final String KEY = "top_level_save_backup";
    /** TileUtils.META_DATA_PREFERENCE_SUMMARY */
    private static final String EXTRA_SUMMARY = "com.android.settings.summary";

    static void changed(Context c) {
        c.getContentResolver().notifyChange(new Uri.Builder().scheme("content")
                .authority(AUTHORITY).appendPath(METHOD).appendPath(KEY).build(), null);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD.equals(method)) return null;
        final Bundle b = new Bundle();
        b.putString(EXTRA_SUMMARY, Texts.lastSummary(getContext(), Helper.last()));
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
