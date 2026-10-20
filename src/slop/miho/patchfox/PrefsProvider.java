package slop.miho.patchfox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Exposes the module's settings over standard IPC so the hook code running inside
 * the hooked apps can read them without bridge-specific XSharedPreferences support.
 * Read-only; values come from the plain "settings" SharedPreferences that
 * SettingsActivity writes.
 */
public class PrefsProvider extends ContentProvider {

    /** Boolean keys this provider may reveal. */
    private static final String[] KEYS = {
            "monet_theme", "compact_toolbar", "see_through_bars", "bare_tab_counter"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        String key = uri == null ? null : uri.getLastPathSegment();
        if (key != null && isKnown(key)) {
            SharedPreferences prefs = getContext().getSharedPreferences(
                    "settings", Context.MODE_PRIVATE);
            // Off-by-default key: everything else defaults to on.
            boolean def = !"bare_tab_counter".equals(key);
            cursor.addRow(new Object[]{key, prefs.getBoolean(key, def) ? "true" : "false"});
        }
        return cursor;
    }

    private static boolean isKnown(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.slop.miho.patchfox.prefs";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null; // read-only
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0; // read-only
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0; // read-only
    }
}
