package slop.miho.patchfox;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Builds monet-ified instances of Firefox's Compose theme objects
 * ({@code AcornColors} and Material3 {@code ColorScheme}) via reflection.
 *
 * Everything is located structurally (constructor parameter counts, public field names)
 * so it survives R8 renaming and minor Firefox updates.
 */
final class Palette {

    static final int MODE_LIGHT = 0;
    static final int MODE_DARK = 1;
    static final int MODE_OLED = 2;

    /**
     * ColorScheme constructor argument order (48 longs) with the monet role for each.
     * Verified against androidx.compose.material3.ColorScheme in Firefox 158.
     */
    private static final String[] SCHEME_FIELDS = {
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
            "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
            "background", "onBackground", "surface", "onSurface",
            "surfaceVariant", "onSurfaceVariant", "surfaceTint",
            "inverseSurface", "inverseOnSurface",
            "error", "onError", "errorContainer", "onErrorContainer",
            "outline", "outlineVariant", "scrim",
            "surfaceBright", "surfaceDim",
            "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
            "surfaceContainerLow", "surfaceContainerLowest",
            "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
            "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
            "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
    };

    private static final String[] SCHEME_ROLES = {
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

    /** Indices into SCHEME_FIELDS of surface-ish roles that become black in OLED mode. */
    private static final int[] OLED_BLACK = {13, 15, 30, 31, 35}; // background, surface, surfaceDim, surfaceContainer, surfaceContainerLowest

    /** Indices that get a subtle monet-tinted near-black in OLED mode. */
    private static final int[] OLED_NEAR_BLACK = {29, 32, 33, 34}; // surfaceBright, High, Highest, Low

    /**
     * AcornColors constructor argument order (19 longs) with the monet role for each.
     * null = keep Firefox's original semantic color (success/warning/iconPrivate).
     */
    private static final String[] ACORN_FIELDS = {
            "formDefault", "information", "onInformation", "informationContainer", "onInformationContainer",
            "success", "onSuccess", "successContainer", "onSuccessContainer",
            "warning", "onWarning", "warningContainer", "onWarningContainer",
            "surfaceDimVariant", "surfaceContainerSelected", "autofillText", "selectedText",
            "iconPrivate", "sheetOutline",
    };

    private static final String[] ACORN_ROLES = {
            "on_surface", "primary", "on_primary", "primary_container", "on_primary_container",
            null, null, null, null,
            null, null, null, null,
            "surface_container_high", "surface_container_highest", "on_surface_variant", "primary",
            null, "outline_variant",
    };

    // Firefox originals used as fallback when reading the original palette fails.
    // Dark palette (NovaColors/PhotonColors values from Firefox 158).
    private static final long[] ACORN_FALLBACK_DARK = {
            4294704126L, // formDefault = Photon LightGrey05
            0L, 0L, 0L, 0L,
            4281190302L, 4279702809L, 4278208819L, 4294111480L, // success Green30, onSuccess Gray80, successContainer Green70, onSuccessContainer VioletDesat0
            4293959680L, 4279702809L, 4284428544L, 4294111480L, // warning Yellow30, onWarning Gray80, warningContainer Yellow70, onWarningContainer VioletDesat0
            0L, 0L, 0L, 0L,
            4280097567L, 0L, // iconPrivate Gray75
    };

    private static final long[] ACORN_FALLBACK_LIGHT = {
            4279571482L, // formDefault = Photon DarkGrey90
            0L, 0L, 0L, 0L,
            4278224997L, 4294967295L, 4290309849L, 4279766576L, // success Green50, onSuccess White, successContainer Green10, onSuccessContainer VioletDesat90
            4289880320L, 4294967295L, 4294632103L, 4279766576L, // warning Yellow50, onWarning White, warningContainer Yellow10, onWarningContainer VioletDesat90
            0L, 0L, 0L, 0L,
            4279766576L, 0L, // iconPrivate VioletDesaturated90
    };

    private final ClassLoader cl;

    /** cache[mode] = { AcornColors, ColorScheme } */
    private final Object[] cache = new Object[3];

    private static boolean loggedPalette = false;

    private Constructor<?> schemeCtor;
    private Constructor<?> acornCtor;
    private Class<?> acornColorsClass;
    private Class<?> colorClass;
    private boolean broken;

    Palette(ClassLoader cl) {
        this.cl = cl;
    }

    /** Returns {AcornColors, ColorScheme} for the mode, or null if monet/build is unavailable. */
    Object[] get(int mode, boolean night, Object originalColors) {
        if (broken) return null;
        Object[] pair = (Object[]) cache[mode];
        if (pair == null) {
            try {
                pair = build(mode, night, originalColors);
                cache[mode] = pair;
            } catch (Throwable t) {
                broken = true;
                XposedBridge.log("PatchFox: palette build failed, disabling: " + t);
                return null;
            }
        }
        return pair;
    }

    private Object[] build(int mode, boolean night, Object originalColors) throws Exception {
        initClasses();

        if (Monet.role("primary", night) == 0 || Monet.role("surface", night) == 0) {
            XposedBridge.log("PatchFox: system monet colors unavailable, aborting");
            return null;
        }

        boolean oled = mode == MODE_OLED;
        int tint = Monet.role("primary", true);
        long black = pack(0xFF000000);
        if (!loggedPalette) {
            loggedPalette = true;
            StringBuilder all = new StringBuilder("PatchFox monet roles (L/D):");
            for (String r : SCHEME_ROLES) {
                all.append(' ').append(r)
                        .append('=').append(String.format("%08X", Monet.role(r, false)))
                        .append('/').append(String.format("%08X", Monet.role(r, true)));
            }
            XposedBridge.log(all.toString());
        }

        // --- ColorScheme (48 roles) ---
        Object[] schemeArgs = new Object[48];
        for (int i = 0; i < 48; i++) {
            long v = pack(Monet.role(SCHEME_ROLES[i], night));
            if (v == 0) v = fallbackForSchemeRole(i, night);
            if (oled) {
                if (contains(OLED_BLACK, i)) v = black;
                else if (contains(OLED_NEAR_BLACK, i)) v = blend(0xFF000000, tint, 0.08f);
            }
            schemeArgs[i] = v;
        }
        // The system's light monet palette collapses surfaceBright onto surface, which
        // erases the menu's layered structure (menu items are painted on surfaceBright:
        // stock shows them on pure white above a surface-tinted header). Restore the
        // separation with the palette's brightest container. Dark is already distinct.
        if (!night && !oled) {
            schemeArgs[29] = pack(Monet.role("surface_container_lowest", false));
        }
        Object scheme = schemeCtor.newInstance(schemeArgs);

        // --- AcornColors (19 roles) ---
        long[] originals = readOriginalAcorn(originalsOf(mode, originalColors, night), night);
        Object[] acornArgs = new Object[19];
        for (int i = 0; i < 19; i++) {
            String role = ACORN_ROLES[i];
            long v;
            if (role != null) {
                v = pack(Monet.role(role, night));
                if (v == 0) v = originals[i];
            } else {
                v = originals[i];
            }
            acornArgs[i] = v;
        }
        Object colors = acornCtor.newInstance(acornArgs);

        return new Object[]{colors, scheme};
    }

    private long fallbackForSchemeRole(int i, boolean night) {
        switch (i) {
            case 19: return pack(Monet.role("primary", night));          // surfaceTint
            case 28: return pack(0xFF000000);                            // scrim
            case 37: case 38: return pack(Monet.role("primary_container", night));   // primaryFixed/Dim
            case 39: case 40: return pack(Monet.role("on_primary", night));          // onPrimaryFixed/Variant
            case 41: case 42: return pack(Monet.role("secondary_container", night));
            case 43: case 44: return pack(Monet.role("on_secondary", night));
            case 45: case 46: return pack(Monet.role("tertiary_container", night));
            case 47: return pack(Monet.role("on_tertiary", night));
            default: return 0L;
        }
    }

    /** Reads the 19 current values out of an AcornColors instance (mutableStateOf delegates). */
    private long[] readOriginalAcorn(Object colors, boolean night) {
        long[] out = new long[19];
        long[] fallback = night ? ACORN_FALLBACK_DARK : ACORN_FALLBACK_LIGHT;
        // Fallback arrays hold raw unsigned-ARGB longs; pack them into the Color long format.
        for (int i = 0; i < 19; i++) {
            out[i] = fallback[i] << 32;
        }
        if (colors == null) return out;
        try {
            for (int i = 0; i < 19; i++) {
                Field f = acornColorsClass.getField(ACORN_FIELDS[i] + "$delegate");
                Object delegate = f.get(colors);
                Method getValue = delegate.getClass().getMethod("getValue");
                Object boxed = getValue.invoke(delegate);
                out[i] = colorClass.getField("value").getLong(boxed);
            }
        } catch (Throwable t) {
            XposedBridge.log("PatchFox: reading original AcornColors failed, using fallbacks: " + t);
        }
        return out;
    }

    /** Picks the best original palette to source kept colors from. */
    private Object originalsOf(int mode, Object originalColors, boolean night) {
        if (originalColors != null) return originalColors;
        try {
            Class<?> ack = Class.forName(
                    "mozilla.components.compose.base.theme.AcornColorsKt", false, cl);
            String field = mode == MODE_LIGHT ? "lightColorPalette"
                    : mode == MODE_OLED ? "oledColorPalette" : "darkColorPalette";
            return ack.getField(field).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- gradient scheme

    private final Object[] gradientCache = new Object[3];
    private Constructor<?> gradientSchemeCtor;
    private Constructor<?> gradientCtor;
    private Constructor<?> colorStopBoxCtor;
    private Class<?> gradientClass;
    private Class<?> colorStopClass;
    private Class<?> positionClass;
    private Method colorStopFactory;
    private boolean gradBroken;

    /**
     * Monet replacement for AcornGradientScheme (tab outlines, CFR and accent gradients,
     * subtle highlight gradients). Returns null (and caches nothing) if unavailable.
     */
    Object gradientFor(int mode, boolean night) {
        if (broken || gradBroken) return null;
        Object cached = gradientCache[mode];
        if (cached == null) {
            try {
                cached = buildGradientScheme(night);
                if (cached == null) return null;
                gradientCache[mode] = cached;
            } catch (Throwable t) {
                gradBroken = true;
                XposedBridge.log("PatchFox: gradient scheme unavailable: " + t);
                return null;
            }
        }
        return cached;
    }

    private Object buildGradientScheme(boolean night) throws Exception {
        initGradientClasses();
        Class<?> schemeKt = Class.forName(
                "mozilla.components.compose.base.theme.AcornGradientSchemeKt", false, cl);
        Object original = schemeKt.getField(
                night ? "darkAcornGradientScheme" : "lightAcornGradientScheme").get(null);
        if (original == null) return null;

        int primary = Monet.role("primary", night);
        if (primary == 0) return null;
        int surface = Monet.role("surface", night);

        // Solid accents (cfr, accent, tabOutline): flat monet primary.
        long[] bold = {pack(primary)};
        // Subtle highlight: barely primary-tinted surface, flat as well.
        long[] subtle = {surface != 0 ? blend(surface, primary, 0.10f) : pack(primary)};

        Object cfr = copyGradient(field(original, "cfr"), bold);
        Object accent = copyGradient(field(original, "accent"), bold);
        Object accentSubtle = copyGradient(field(original, "accentSubtle"), subtle);
        Object tabOutline = copyGradient(field(original, "tabOutline"), bold);
        Object privacyMask = field(original, "privacyMask"); // kept as-is
        return gradientSchemeCtor.newInstance(cfr, accent, accentSubtle, tabOutline, privacyMask);
    }

    /** Rebuilds an AcornGradient with monet colors at the original stop positions. */
    private Object copyGradient(Object original, long[] packedColors) throws Exception {
        Object type = field(original, "type");
        List<?> stops = (List<?>) field(original, "colorStops");
        List<Object> newStops = new ArrayList<>(stops.size());
        Field firstField = null;
        for (int i = 0; i < stops.size(); i++) {
            Object pair = field(stops.get(i), "colorStop");
            if (firstField == null) firstField = pair.getClass().getField("first");
            Object position = firstField.get(pair);
            float f = positionClass.getField("position").getFloat(position);
            long color = i < packedColors.length ? packedColors[i]
                    : packedColors[packedColors.length - 1];
            newStops.add(colorStopBoxCtor.newInstance(colorStopFactory.invoke(null, color, f)));
        }
        return gradientCtor.newInstance(type, newStops);
    }

    private static Object field(Object obj, String name) throws Exception {
        return obj.getClass().getField(name).get(obj);
    }

    private void initGradientClasses() throws Exception {
        if (gradientSchemeCtor != null) return;
        Class<?> schemeClass = Class.forName(
                "mozilla.components.compose.base.theme.AcornGradientScheme", false, cl);
        gradientClass = Class.forName(
                "mozilla.components.compose.base.theme.AcornGradient", false, cl);
        colorStopClass = Class.forName(
                "mozilla.components.compose.base.utils.ColorStop", false, cl);
        positionClass = Class.forName(
                "mozilla.components.compose.base.utils.Position", false, cl);
        gradientSchemeCtor = findCtorByParamCount(schemeClass, 5);
        gradientCtor = findCtorByParamCount(gradientClass, 2);
        colorStopBoxCtor = findCtorByParamCount(colorStopClass, 1);
        colorStopFactory = colorStopClass.getMethod("constructor-impl", long.class, float.class);
        if (gradientSchemeCtor == null || gradientCtor == null || colorStopBoxCtor == null) {
            throw new IllegalStateException("gradient constructors not found");
        }
    }

    private static Constructor<?> findCtorByParamCount(Class<?> clazz, int paramCount) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == paramCount) {
                c.setAccessible(true);
                return c;
            }
        }
        return null;
    }

    private void initClasses() throws Exception {
        if (schemeCtor != null) return;
        colorClass = Class.forName("androidx.compose.ui.graphics.Color", false, cl);
        acornColorsClass = Class.forName(
                "mozilla.components.compose.base.theme.AcornColors", false, cl);
        Class<?> schemeClass = Class.forName("androidx.compose.material3.ColorScheme", false, cl);

        schemeCtor = findLongConstructor(schemeClass, 48);
        acornCtor = findLongConstructor(acornColorsClass, 19);
        if (schemeCtor == null || acornCtor == null) {
            throw new IllegalStateException("theme constructors not found");
        }
    }

    private static Constructor<?> findLongConstructor(Class<?> clazz, int paramCount) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == paramCount) {
                boolean allLong = true;
                for (Class<?> t : p) {
                    if (t != long.class) { allLong = false; break; }
                }
                if (allLong) {
                    c.setAccessible(true);
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Packs an Android ARGB int into the Compose Color long format used by this build
     * (Compose 1.9+ "wide" encoding): ARGB bytes in bits 32..63, color space id in the
     * low 6 bits where 0 = sRGB. Verified against ColorKt.Color(long) => {@code j << 32}
     * and Color.getColorSpace-impl => ColorSpacesArray[(int)(value & 63)].
     */
    private static long pack(int argb) {
        return argb == 0 ? 0L : ((long) argb) << 32;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    /** Simple linear blend between two opaque ARGB colors, packed as a Color long. */
    static long blend(int from, int to, float t) {
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t);
        int g = (int) (((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        int argb = (a << 24) | (r << 16) | (g << 8) | b;
        return ((long) argb) << 32;
    }
}
