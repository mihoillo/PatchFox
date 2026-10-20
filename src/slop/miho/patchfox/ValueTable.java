package slop.miho.patchfox;

import java.util.HashMap;
import java.util.Map;

/** GENERATED from Firefox 158 legacy color values: raw ARGB -> monet role.
 *  Used as a fallback when colors reach us without a resource id (styled
 *  attribute references resolved to plain ints, shape drawables, etc).
 */
final class ValueTable {

    private static final Map<Integer, String> MAP = new HashMap<>();

    static {
        MAP.put((int) 0x80000000L, "scrim");
        MAP.put((int) 0x8CB0A3D2L, "on_surface_variant");
        MAP.put((int) 0xB2180E30L, "on_surface_variant");
        MAP.put((int) 0xB2F2F0F8L, "on_surface_variant");
        MAP.put((int) 0xCC817F84L, "primary");
        MAP.put((int) 0xFF0A1A3BL, "surface_dim");
        MAP.put((int) 0xFF0C0C0DL, "d:surface");
        MAP.put((int) 0xFF0F0D13L, "d:surface_container_lowest");
        MAP.put((int) 0xFF121114L, "d:tertiary_container");
        MAP.put((int) 0xFF131215L, "surface_dim");
        MAP.put((int) 0xFF141218L, "d:background");
        MAP.put((int) 0xFF15141AL, "d:surface_dim");
        MAP.put((int) 0xFF171519L, "on_primary");
        MAP.put((int) 0xFF17214CL, "d:primary_container");
        MAP.put((int) 0xFF180E30L, "on_primary_container");
        MAP.put((int) 0xFF1C1B22L, "surface_container_highest");
        MAP.put((int) 0xFF1D1B1FL, "background");
        MAP.put((int) 0xFF1D1B20L, "l:on_background");
        MAP.put((int) 0xFF210340L, "background");
        MAP.put((int) 0xFF211F26L, "d:surface_container");
        MAP.put((int) 0xFF23327BL, "primary_container");
        MAP.put((int) 0xFF252428L, "surface_container_high");
        MAP.put((int) 0xFF281D44L, "d:secondary_container");
        MAP.put((int) 0xFF293564L, "surface");
        MAP.put((int) 0xFF2B2930L, "d:surface_container_high");
        MAP.put((int) 0xFF312F33L, "surface_variant");
        MAP.put((int) 0xFF322F35L, "l:inverse_surface");
        MAP.put((int) 0xFF332D41L, "d:on_secondary");
        MAP.put((int) 0xFF36343BL, "d:surface_container_highest");
        MAP.put((int) 0xFF381E72L, "d:on_primary");
        MAP.put((int) 0xFF3B383EL, "d:surface_bright");
        MAP.put((int) 0xFF3E2976L, "primary");
        MAP.put((int) 0xFF3E315FL, "secondary_container");
        MAP.put((int) 0xFF3F3E42L, "outline_variant");
        MAP.put((int) 0xFF42121FL, "d:error_container");
        MAP.put((int) 0xFF42414DL, "surface_container_low");
        MAP.put((int) 0xFF455FE7L, "primary");
        MAP.put((int) 0xFF492532L, "d:on_tertiary");
        MAP.put((int) 0xFF49454FL, "d:surface_variant");
        MAP.put((int) 0xFF4A4458L, "d:secondary_container");
        MAP.put((int) 0xFF4F378BL, "d:primary_container");
        MAP.put((int) 0xFF515054L, "surface_container_high");
        MAP.put((int) 0xFF584A7DL, "d:on_surface_variant");
        MAP.put((int) 0xFF592ACBL, "primary");
        MAP.put((int) 0xFF5939A8L, "primary_container");
        MAP.put((int) 0xFF601410L, "d:on_error");
        MAP.put((int) 0xFF625B71L, "l:secondary");
        MAP.put((int) 0xFF633B48L, "d:tertiary_container");
        MAP.put((int) 0xFF6750A4L, "l:primary");
        MAP.put((int) 0xFF67666AL, "secondary");
        MAP.put((int) 0xFF69172DL, "error_container");
        MAP.put((int) 0xFF70ABFFL, "primary");
        MAP.put((int) 0xFF711D08L, "surface");
        MAP.put((int) 0xFF764EDDL, "tertiary");
        MAP.put((int) 0xFF79747EL, "l:outline");
        MAP.put((int) 0xFF7D5260L, "l:tertiary");
        MAP.put((int) 0xFF817F84L, "outline");
        MAP.put((int) 0xFF8C1D18L, "d:error_container");
        MAP.put((int) 0xFF938F99L, "d:outline");
        MAP.put((int) 0xFF949297L, "d:on_surface_variant");
        MAP.put((int) 0xFFA6A4A9L, "primary");
        MAP.put((int) 0xFFB0A3D2L, "on_surface_variant");
        MAP.put((int) 0xFFB3261EL, "l:error");
        MAP.put((int) 0xFFB393FFL, "tertiary");
        MAP.put((int) 0xFFB7B6BAL, "inverse_surface");
        MAP.put((int) 0xFFBAE5FFL, "primary_container");
        MAP.put((int) 0xFFC52D4FL, "error");
        MAP.put((int) 0xFFC7C6CBL, "surface_container_high");
        MAP.put((int) 0xFFCAC4D0L, "d:on_surface_variant");
        MAP.put((int) 0xFFCB9EFFL, "primary");
        MAP.put((int) 0xFFCCC2DCL, "d:secondary");
        MAP.put((int) 0xFFCDB7FFL, "primary");
        MAP.put((int) 0xFFD0BCFFL, "d:primary");
        MAP.put((int) 0xFFD6D5DAL, "secondary");
        MAP.put((int) 0xFFDED8E1L, "l:surface_dim");
        MAP.put((int) 0xFFE2DCF2L, "secondary_container");
        MAP.put((int) 0xFFE3E2E7L, "surface_variant");
        MAP.put((int) 0xFFE5D6FFL, "primary_container");
        MAP.put((int) 0xFFE6E0E9L, "d:on_background");
        MAP.put((int) 0xFFE7E0ECL, "l:surface_variant");
        MAP.put((int) 0xFFE8DEF8L, "l:secondary_container");
        MAP.put((int) 0xFFEADDFFL, "l:primary_container");
        MAP.put((int) 0xFFECE6F0L, "l:surface_container_high");
        MAP.put((int) 0xFFEFB8C8L, "d:tertiary");
        MAP.put((int) 0xFFEFEDF2L, "surface_container_high");
        MAP.put((int) 0xFFF0F0F4L, "surface_container_highest");
        MAP.put((int) 0xFFF2B8B5L, "d:error");
        MAP.put((int) 0xFFF2F0F8L, "on_primary_container");
        MAP.put((int) 0xFFF3EDF7L, "l:surface_container");
        MAP.put((int) 0xFFF5ECFFL, "tertiary_container");
        MAP.put((int) 0xFFF5EFF7L, "l:inverse_on_surface");
        MAP.put((int) 0xFFF7EAE8L, "surface_dim");
        MAP.put((int) 0xFFF7F2FAL, "l:surface_container_low");
        MAP.put((int) 0xFFF7F6FBL, "background");
        MAP.put((int) 0xFFF8F3F4L, "surface");
        MAP.put((int) 0xFFF9DEDCL, "l:error_container");
        MAP.put((int) 0xFFFBFBFEL, "on_primary");
        MAP.put((int) 0xFFFCF3EEL, "background");
        MAP.put((int) 0xFFFCFBFFL, "surface_container_low");
        MAP.put((int) 0xFFFEF7FFL, "l:background");
        MAP.put((int) 0xFFFF8090L, "error");
        MAP.put((int) 0xFFFFD0D7L, "error_container");
        MAP.put((int) 0xFFFFD4B7L, "surface");
        MAP.put((int) 0xFFFFD8E4L, "l:tertiary_container");
        MAP.put((int) 0xFFFFFFFFL, "on_primary");
    }

    /** Returns the monet role for a legacy ARGB value, or null if unknown. */
    static String roleFor(int argb) {
        return MAP.get(argb);
    }

    private ValueTable() {}
}
