package slop.miho.patchfox;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the module's own settings from inside the hooked process.
 *
 * The settings UI (SettingsActivity) stores plain SharedPreferences in the module app;
 * PrefsProvider exports them over standard ContentProvider IPC, which works on any
 * Android and needs no bridge-specific support. Legacy XSharedPreferences and direct
 * file reads stay as fallbacks for other bridge flavors. If nothing can be read the
 * features stay enabled, so a broken channel can never leave a half-patched UI.
 */
final class ModulePrefs {

    private static final String SELF_PKG = "slop.miho.patchfox";
    private static final String AUTHORITY = SELF_PKG + ".prefs";

    /** key -> -1 (not read yet), 0 (disabled), 1 (enabled). Accessed on the main thread. */
    private static final Map<String, Integer> FLAGS = new HashMap<>();

    static {
        FLAGS.put("monet_theme", -1);
        FLAGS.put("compact_toolbar", -1);
        FLAGS.put("see_through_bars", -1);
        FLAGS.put("bare_tab_counter", -1);
    }

    private ModulePrefs() {
    }

    /** True when the monet recoloring is on. Not-yet-read counts as on (default). */
    static boolean monetTheme() {
        return flag("monet_theme");
    }

    /** True when the compact address bar is on. Not-yet-read counts as on (default). */
    static boolean compactToolbar() {
        return flag("compact_toolbar");
    }

    /** True when pages show through the transparent system status bar. */
    static boolean seeThroughBars() {
        return flag("see_through_bars");
    }

    /**
     * True when the tab counter box is stripped down to a bare number.
     * Default-off key: not-yet-read and every fallback count as disabled.
     */
    static boolean bareTabCounter() {
        Integer v = FLAGS.get("bare_tab_counter");
        return v != null && v.intValue() == 1;
    }

    /** Per-key defaults: everything is on by default except the bare counter. */
    private static boolean defaultOn(String key) {
        return !"bare_tab_counter".equals(key);
    }

    private static boolean flag(String key) {
        Integer v = FLAGS.get(key);
        return v == null || v != 0;
    }

    /**
     * Called as soon as any app Context is reachable (Application.attach, first
     * Activity.onResume). Reads the settings once per process lifetime.
     */
    static void init(Context ctx) {
        if (ctx == null) return;
        boolean pending = false;
        for (Integer v : FLAGS.values()) if (v < 0) pending = true;
        if (!pending) return;
        for (String key : FLAGS.keySet()) {
            if (FLAGS.get(key) >= 0) continue;
            FLAGS.put(key, readFlag(ctx, key));
        }
        Log.i("PatchFox", "prefs: monet_theme=" + flag("monet_theme")
                + " compact_toolbar=" + flag("compact_toolbar")
                + " see_through_bars=" + flag("see_through_bars")
                + " bare_tab_counter=" + bareTabCounter());
    }

    private static int readFlag(Context ctx, String key) {
        // 1) The module app's ContentProvider: plain Android IPC, works everywhere.
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/" + key);
            Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex("value");
                        if (idx >= 0) {
                            String v = c.getString(idx);
                            Log.i("PatchFox", key + " via provider: " + v);
                            if ("false".equals(v)) return 0;
                            if ("true".equals(v)) return 1;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            Log.i("PatchFox", "provider query failed: " + t);
        }

        // 2) Classic Xposed bridges: XSharedPreferences.
        try {
            Class<?> cls = Class.forName("de.robv.android.xposed.XSharedPreferences");
            Object prefs = cls.getConstructor(String.class, String.class)
                    .newInstance(SELF_PKG, "settings");
            if (Boolean.TRUE.equals(cls.getMethod("reload").invoke(prefs))) {
                Method getBoolean = cls.getMethod("getBoolean", String.class, boolean.class);
                Object v = getBoolean.invoke(prefs, key, Boolean.valueOf(defaultOn(key)));
                Log.i("PatchFox", key + " via XSharedPreferences: " + v);
                if (Boolean.TRUE.equals(v)) return 1;
                if (Boolean.FALSE.equals(v)) return 0;
            }
        } catch (Throwable t) {
            Log.i("PatchFox", "XSharedPreferences unavailable: " + t);
        }

        // 3) Bridges that bind-mount the module's shared_prefs into the target process.
        try {
            String xml = readText("/data/user/0/" + SELF_PKG + "/shared_prefs/settings.xml");
            if (xml == null) {
                xml = readText("/data/data/" + SELF_PKG + "/shared_prefs/settings.xml");
            }
            if (xml != null) {
                Matcher m = Pattern.compile(
                        "name=\\\"" + key + "\\\"[^>]*value=\\\"(true|false)\\\"").matcher(xml);
                if (m.find()) {
                    Log.i("PatchFox", key + " via file: " + m.group(1));
                    return "true".equals(m.group(1)) ? 1 : 0;
                }
            }
        } catch (Throwable ignored) {
        }

        return defaultOn(key) ? 1 : 0; // per-key default
    }

    private static String readText(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
