package slop.miho.patchfox;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remaps Firefox's XML theme colors to the system monet palette.
 *
 * Covered resource name families:
 *  - fx_mobile_*    : Firefox's own M3-role mirror (day/night variants)
 *  - m3_sys_color_light_* / m3_sys_color_dark_* : the stock Material3 palette shipped
 *    with the app; used by View-based Material widgets (bottom sheets, menus,
 *    dialogs) and by theme attributes resolved outside of Compose.
 *
 * Map values may be prefixed to force a palette side regardless of the caller's
 * uiMode: "l:role" forces the monet light value, "d:role" forces the monet dark
 * value, plain "role" follows the caller's current uiMode. The special value
 * "@black" always resolves to opaque black. Encode the prefixes directly in the
 * map; last-resort roles here.
 */
final class ResRemap {

    private static final Map<String, String> MAP = new HashMap<>();

    /** Sentinel meaning "looked up, not a color we remap". */
    private static final String NONE = "";

    /** id -> encoded role ("l:x"/"d:x"/"x"/"@black"), NONE marker, or absent (not yet looked up). */
    private static final ConcurrentHashMap<Integer, String> ROLE_CACHE = new ConcurrentHashMap<>();

    /** id -> {light, dark} colors (cached once monet resolves). */
    private static final ConcurrentHashMap<Integer, int[]> COLOR_CACHE = new ConcurrentHashMap<>();

    /** Monet roles covered by the fx_mobile_* and m3_sys_color_* families. */
    private static final String[] ROLES = {
            "primary", "on_primary", "primary_container", "on_primary_container", "inverse_primary",
            "secondary", "on_secondary", "secondary_container", "on_secondary_container",
            "tertiary", "on_tertiary", "tertiary_container", "on_tertiary_container",
            "background", "on_background", "surface", "on_surface",
            "surface_variant", "on_surface_variant", "surface_tint",
            "inverse_surface", "inverse_on_surface",
            "error", "on_error", "error_container", "on_error_container",
            "outline", "outline_variant", "scrim",
            "surface_bright", "surface_dim",
            "surface_container", "surface_container_high", "surface_container_highest",
            "surface_container_low", "surface_container_lowest",
            "primary_fixed", "primary_fixed_dim", "on_primary_fixed", "on_primary_fixed_variant",
            "secondary_fixed", "secondary_fixed_dim", "on_secondary_fixed", "on_secondary_fixed_variant",
            "tertiary_fixed", "tertiary_fixed_dim", "on_tertiary_fixed", "on_tertiary_fixed_variant",
    };

    static {
        for (String r : ROLES) {
            put("m3_sys_color_light_" + r, "l:" + r);
            put("m3_sys_color_dark_" + r, "d:" + r);
        }

        // Firefox M3 role mirror
        put("fx_mobile_primary", "primary");
        put("fx_mobile_on_primary", "on_primary");
        put("fx_mobile_primary_container", "primary_container");
        put("fx_mobile_on_primary_container", "on_primary_container");
        put("fx_mobile_primary_inverse", "inverse_primary");
        put("fx_mobile_secondary", "secondary");
        put("fx_mobile_on_secondary", "on_secondary");
        put("fx_mobile_secondary_container", "secondary_container");
        put("fx_mobile_on_secondary_container", "on_secondary_container");
        put("fx_mobile_tertiary", "tertiary");
        put("fx_mobile_on_tertiary", "on_tertiary");
        put("fx_mobile_tertiary_container", "tertiary_container");
        put("fx_mobile_on_tertiary_container", "on_tertiary_container");
        put("fx_mobile_background", "background");
        put("fx_mobile_on_background", "on_background");
        put("fx_mobile_surface", "surface");
        put("fx_mobile_on_surface", "on_surface");
        put("fx_mobile_surface_variant", "surface_variant");
        put("fx_mobile_on_surface_variant", "on_surface_variant");
        put("fx_mobile_surface_bright", "surface_bright");
        put("fx_mobile_surface_dim", "surface_dim");
        put("fx_mobile_surface_container", "surface_container");
        put("fx_mobile_surface_container_low", "surface_container_low");
        put("fx_mobile_surface_container_high", "surface_container_high");
        put("fx_mobile_surface_container_highest", "surface_container_highest");
        put("fx_mobile_surface_container_lowest", "surface_container_lowest");
        put("fx_mobile_surface_container_selected", "surface_container_high");
        put("fx_mobile_surface_inverse", "inverse_surface");
        put("fx_mobile_on_surface_inverse", "inverse_on_surface");
        put("fx_mobile_outline", "outline");
        put("fx_mobile_outline_variant", "outline_variant");
        put("fx_mobile_scrim", "scrim");
        put("fx_mobile_error", "error");
        put("fx_mobile_on_error", "on_error");
        put("fx_mobile_error_container", "error_container");
        put("fx_mobile_on_error_container", "on_error_container");
        // Firefox "information" accent = primary
        put("fx_mobile_information", "primary");
        put("fx_mobile_on_information", "on_primary");
        put("fx_mobile_information_container", "primary_container");
        put("fx_mobile_on_information_container", "on_primary_container");
        // Misc
        put("fx_mobile_selected_text", "primary");
        put("fx_mobile_autofill_text", "on_surface_variant");
        put("fx_mobile_sheet_outline", "outline_variant");
        put("fx_mobile_text_color_oncolor_primary", "on_primary");
        put("fx_mobile_splashscreen_background", "background");
        // Button chips on layered surfaces: needs clear contrast vs the sheet (layer2)
        put("fx_mobile_layer_color_2", "surface_container_low");
        put("fx_mobile_layer_color_3", "surface_container_highest");
        put("fx_mobile_icon_color_accent_violet", "primary");
        // Legacy backdrop gradients built from photon constants in code (tab tray, home,
        // protections dashboard) — read via colorResource, i.e. Resources.getColor.
        put("dark_grey_90_gradient_start", "d:surface_dim");
        put("dark_grey_90_gradient_end", "d:surface");
        put("homepage_edge_to_edge_gradient_start", "surface_dim");
        put("homepage_edge_to_edge_gradient_end", "surface");
        put("protections_dashboard_gradient_start", "primary_container");
        put("protections_dashboard_gradient_end", "surface");
        // Note: *_private gradient variants intentionally untouched.
        // Raw legacy palette names that surface directly in styled attr chains instead of
        // their fx_mobile_* wrappers (e.g. the menu sheet shape's ?attr/colorSurface
        // resolves to novaGray75 at night / novaGray5 by day).
        put("novaGray5", "l:surface");
        put("novaGray70", "d:surface_container_highest");
        put("novaGray75", "d:surface");
        put("novaGray80", "d:surface_container_low");
        put("novaGray85", "d:surface_dim");
        put("photonDarkGrey30", "d:surface_container_low");
        put("photonGrey90", "d:surface");
        put("novaViolet70", "primary"); // legacy violet accent (sync icon etc) -> monet
        // Firefox "Nova" ink + accent ramp: the View-based settings UI (category
        // headers, row titles/summaries, framework Switch widgets) resolves its
        // violet-tinted ink from novaVioletDesaturated* and the accent from
        // novaViolet* instead of the fx_mobile_* mirror.
        put("novaVioletDesaturated0", "d:on_surface");         // light ink (dark themes)
        put("novaVioletDesaturated90", "on_surface");          // ink: category + row titles
        put("novaVioletDesaturated80", "on_surface");          // dense ink text
        put("novaVioletDesaturated70", "on_surface_variant");
        put("novaVioletDesaturated60", "on_surface_variant");  // summaries / secondary text
        put("novaVioletDesaturated50", "on_surface_variant");
        put("novaVioletDesaturated40", "outline_variant");
        put("novaVioletDesaturated30", "outline_variant");     // icons / outlines
        put("novaVioletDesaturated20", "outline_variant");     // hairlines / dividers
        put("novaVioletDesaturated10", "surface_container_high"); // disabled fills
        put("novaViolet0", "primary_container");              // faintest accent washes
        put("novaViolet10", "primary_container");
        put("novaViolet20", "primary_container");
        put("novaViolet30", "primary");                        // light-side accents on dark themes
        put("novaViolet40", "primary");                       // emphasized accent
        put("novaViolet50", "primary");                        // accent: switch ON track
        put("novaViolet60", "primary");                        // pressed / dim accent
        put("mozac_browser_icons_generator_default_text_color", "on_surface");
        // OLED theme colors -> dark palette, surfaces forced black
        put("fx_mobile_oled_background", "@black");
        put("fx_mobile_oled_surface", "@black");
        put("fx_mobile_oled_surface_dim", "@black");
        put("fx_mobile_oled_surface_container", "@black");
        put("fx_mobile_oled_surface_container_lowest", "@black");
        put("fx_mobile_oled_surface_container_low", "@black");
        put("fx_mobile_oled_surface_container_high", "@black");
        put("fx_mobile_oled_surface_container_highest", "@black");
        put("fx_mobile_oled_surface_bright", "d:surface_container_high");
        put("fx_mobile_oled_primary_container", "d:primary_container");
        put("fx_mobile_oled_on_primary_container", "d:on_primary_container");
        put("fx_mobile_oled_secondary_container", "d:secondary_container");
        put("fx_mobile_oled_on_secondary_container", "d:on_secondary_container");
        put("fx_mobile_oled_tertiary_container", "d:tertiary_container");
        put("fx_mobile_oled_on_tertiary_container", "d:on_tertiary_container");
        put("fx_mobile_oled_information_container", "d:primary_container");
        put("fx_mobile_oled_on_information_container", "d:on_primary_container");
        put("fx_mobile_oled_error_container", "d:error_container");
        put("fx_mobile_oled_on_error_container", "d:on_error_container");
        put("fx_mobile_oled_on_surface", "d:on_surface");
        put("fx_mobile_oled_on_background", "d:on_background");
        put("fx_mobile_oled_on_surface_variant", "d:on_surface_variant");
        put("fx_mobile_oled_outline", "d:outline");
        put("fx_mobile_oled_outline_variant", "d:outline_variant");
        put("fx_mobile_oled_selected_text", "d:primary");
        put("fx_mobile_oled_autofill_text", "d:on_surface_variant");
        put("fx_mobile_oled_sheet_outline", "d:outline_variant");
        // NOTE: fx_mobile_private_* intentionally untouched (private mode keeps its purple identity).
        // success/warning stay semantic green/yellow.
    }

    private static void put(String name, String role) {
        MAP.put(name, role);
    }

    private ResRemap() {}

    /** Returns the remapped ARGB, or 0 to keep the original. */
    static int remapColor(Resources res, int id) {
        int[] pair = pairFor(res, id);
        if (pair == null) return 0;
        return pair[isNight(res) ? 1 : 0];
    }

    /** Returns a remapped solid ColorStateList, or null to keep the original. */
    static ColorStateList remapCsl(Resources res, int id) {
        int[] pair = pairFor(res, id);
        if (pair == null) return null;
        int color = pair[isNight(res) ? 1 : 0];
        return new ColorStateList(new int[][]{new int[0]}, new int[]{color});
    }

    private static int[] pairFor(Resources res, int id) {
        String role = ROLE_CACHE.get(id);
        if (role == null) {
            String computed = computeRole(res, id);
            role = computed == null ? NONE : computed;
            String prev = ROLE_CACHE.putIfAbsent(id, role);
            if (prev != null) role = prev;
        }
        if (role == NONE) return null;

        int[] cached = COLOR_CACHE.get(id);
        if (cached != null) return cached;

        int[] pair = pairForRole(role);
        if (pair == null) return null;
        COLOR_CACHE.putIfAbsent(id, pair);
        return pair;
    }

    /** Decodes an encoded role into its {light, dark} monet pair (null while monet is absent). */
    private static int[] pairForRole(String role) {
        boolean forceDay = false, forceNight = false;
        if (role.startsWith("l:")) { forceDay = true; role = role.substring(2); }
        else if (role.startsWith("d:")) { forceNight = true; role = role.substring(2); }

        int light, dark;
        if (role.equals("@black")) {
            light = dark = 0xFF000000;
        } else {
            light = forceNight ? Monet.role(role, true) : Monet.role(role, false);
            dark = forceDay ? light : Monet.role(role, true);
            if (light == 0 && dark == 0) return null; // monet not ready yet; retry later
        }
        return new int[]{light, dark};
    }

    /**
     * Fallback for legacy colors that reach us as plain ints with no resource id —
     * styled attrs already resolved to raw values, gradients baked in code, etc.
     * Returns the remapped ARGB, or 0 to keep the original.
     */
    static int remapValueColor(Resources res, int argb) {
        String role = ValueTable.roleFor(argb);
        if (role == null) return 0;
        int[] pair = pairForRole(role);
        if (pair == null) return 0;
        return pair[isNight(res) ? 1 : 0];
    }

    private static String computeRole(Resources res, int id) {
        try {
            String name = res.getResourceName(id); // "pkg:color/entry"
            int slash = name.indexOf('/');
            if (slash < 0 || !name.contains(":color/")) return null;
            return MAP.get(name.substring(slash + 1));
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isNight(Resources res) {
        return (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
