package slop.miho.patchfox;

import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves Material You (Monet) colors from the framework's {@code system_*} color resources.
 *
 * On Android 12+ the system exposes the generated palette as framework resources such as
 * {@code android:color/system_primary_light}, {@code android:color/system_surface_container_high_dark},
 * etc. Nothing OS 17 provides the full M3 role set including fixed roles, so the mapping is 1:1.
 *
 * Note: deliberately does NOT use AndroidAppHelper (the Vector/LSPosed legacy bridge does not
 * provide it). The Resources instance is supplied by the hooks themselves.
 */
final class Monet {

    /** role name -> {light ARGB, dark ARGB} */
    private static final Map<String, int[]> CACHE = new HashMap<>();

    private static volatile Resources res;

    private Monet() {}

    /** Called by hooks; any app Resources can resolve android: framework colors. */
    static void provide(Resources r) {
        if (res == null && r != null) {
            res = r;
        }
    }

    /** Returns the monet ARGB for a role like "primary" or "surface_container_high", or 0 if unavailable. */
    static int role(String role, boolean night) {
        int[] pair = CACHE.get(role);
        if (pair == null) {
            pair = new int[]{resolve(role, false), resolve(role, true)};
            CACHE.put(role, pair);
        }
        return night ? pair[1] : pair[0];
    }

    static boolean isNight() {
        return isNight(res);
    }

    static boolean isNight(Resources r) {
        if (r == null) r = res;
        if (r == null) return false;
        return (r.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int resolve(String role, boolean night) {
        Resources r = res;
        if (r == null) return 0;
        try {
            int id = r.getIdentifier("system_" + role + (night ? "_dark" : "_light"), "color", "android");
            if (id == 0) id = r.getIdentifier("system_" + role, "color", "android");
            if (id == 0) return 0;
            return r.getColor(id, null);
        } catch (Throwable t) {
            return 0;
        }
    }
}
