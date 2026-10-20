package slop.miho.patchfox;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * PatchFox — Material You (Monet) dynamic colors for Firefox Android.
 *
 * Hooks:
 *  1. {@code AcornThemeKt.AcornTheme} — the root of Firefox's Compose theming. The incoming
 *     AcornColors / Material3 ColorScheme are swapped for monet-built equivalents, which
 *     recolors the entire Compose UI (toolbars, menus, home, settings, tabs tray...).
 *  2. {@code Resources.getColor/getColorStateList} — remaps the fx_mobile_* XML colors used
 *     by the remaining View-based UI (status bar, splash, dialogs, old views).
 *
 * Private mode keeps its purple identity by design. OLED theme gets a monet palette with
 * true-black surfaces.
 */
public class PatchFox implements IXposedHookLoadPackage {

    private static final String TAG = "PatchFox";

    private static final String[] TARGETS = {
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.firefox_nightly",
            "org.mozilla.fenix",
            "org.mozilla.fennec_fdroid",
    };

    private Palette palette;
    private Object lightPalette;
    private Object darkPalette;
    private Object oledPalette;
    private Object privatePalette;
    private Object lightGradient;
    private Object darkGradient;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        boolean target = false;
        for (String t : TARGETS) {
            if (t.equals(pkg)) { target = true; break; }
        }
        if (!target) return;

        try {
            hookAppContext();
            hookAcornTheme(lpparam.classLoader);
            hookResources();
            hookCompactDimensions();
            hookCompactCompose(lpparam.classLoader);
            hookCompactTouchTargets(lpparam.classLoader);
            hookMenuEndPadding(lpparam.classLoader);
            hookSearchSelectorPadding(lpparam.classLoader);
            hookBareTabCounter(lpparam.classLoader);
            hookWindowBackground();
            hookLegacyViewBackgrounds();
            hookDialogWindow();
            hookSettingsEntry(lpparam.classLoader);
            hookCategoryHeaders(lpparam.classLoader);
            hookSeeThroughBars(lpparam.classLoader);
            XposedBridge.log(TAG + ": hooks installed in " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAILED to install hooks: " + t);
        }
    }

    // ------------------------------------------------------------------ Category headers

    /**
     * Stock MD2/MD3 settings style: section headers ("General", "Advanced", "About"...)
     * are drawn in the theme accent. Firefox paints them with the same ink resource as
     * row titles, so the resource remap cannot split the two — instead recolor the
     * category title at bind time, when the PreferenceCategory view holder is live.
     */
    private void hookCategoryHeaders(ClassLoader cl) {
        try {
            Class<?> cat = find(cl, "androidx.preference.PreferenceCategory");
            XposedBridge.hookAllMethods(cat, "onBindViewHolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (!ModulePrefs.monetTheme()) return;
                        Object holder = param.args[0];
                        View item = (View) holder.getClass().getField("itemView").get(holder);
                        Monet.provide(item.getResources());
                        int primary = Monet.role("primary", Monet.isNight(item.getResources()));
                        if (primary != 0) tintTextViews(item, primary);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PreferenceCategory not found: " + t);
        }
    }

    private static void tintTextViews(View v, int color) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(color);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) tintTextViews(vg.getChildAt(i), color);
        }
    }

    // ------------------------------------------------------------------ Module settings entry points

    /**
     * Injects a "PatchFox" row at the bottom of the browser's root settings screen
     * (org.mozilla.fenix.settings.SettingsFragment, a PreferenceFragmentCompat that
     * inflates res/xml/preferences). Tapping it opens the module's own settings
     * activity. Deliberately NOT gated by the monet toggle — the user must be able
     * to reach the settings even when the toggle is off.
     *
     * All androidx.preference classes live in the app's classloader, so every call
     * is reflective; the click listener is a dynamic proxy.
     */
    private void hookSettingsEntry(ClassLoader cl) {
        try {
            Class<?> frag = find(cl, "org.mozilla.fenix.settings.SettingsFragment");
            XposedBridge.hookAllMethods(frag, "onCreatePreferences", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        addSettingsEntry(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": settings entry failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SettingsFragment not found: " + t);
        }
    }

    private void addSettingsEntry(Object fragment) throws Exception {
        Object activity = fragment.getClass().getMethod("getActivity").invoke(fragment);
        if (!(activity instanceof Context)) return;
        final Context ctx = (Context) activity;
        ClassLoader cl = ctx.getClassLoader();

        // R8 strips never-called androidx.preference accessors (getPreferenceScreen,
        // setOnPreferenceClickListener, setIntent...), but publicizes the fields, so
        // reach the root screen through the surviving surface instead:
        //   fragment.mPreferenceManager.mPreferenceScreen
        Class<?> fragCls = find(cl, "androidx.preference.PreferenceFragmentCompat");
        Object manager = fragCls.getField("mPreferenceManager").get(fragment);
        if (manager == null) return;
        Object screen = manager.getClass().getField("mPreferenceScreen").get(manager);
        if (screen == null) return;

        Class<?> prefCls = find(cl, "androidx.preference.Preference");
        // Guard against onCreatePreferences running twice on one process.
        // (findPreference searches recursively across categories.)
        Object existing = screen.getClass()
                .getMethod("findPreference", CharSequence.class)
                .invoke(screen, "patchfox_settings");
        if (existing != null) return;

        // Target group: the "Advanced" category, right above its "Extensions" row.
        // Fall back to the root screen on builds with a different layout.
        java.lang.reflect.Method find =
                screen.getClass().getMethod("findPreference", CharSequence.class);
        Object group = find.invoke(screen, "pref_key_advanced");
        Object below = null;
        if (group != null) {
            below = find.invoke(group, "pref_key_addons"); // the "Extensions" row
            if (below == null) { // category exists, Extensions not found: append last
                group = null;
            }
        }

        boolean ru = isRussian(ctx);
        Object pref = prefCls.getConstructor(Context.class).newInstance(ctx);
        prefCls.getMethod("setKey", String.class).invoke(pref, "patchfox_settings");
        prefCls.getMethod("setTitle", CharSequence.class).invoke(pref, "PatchFox");
        prefCls.getMethod("setSummary", CharSequence.class).invoke(pref, ru
                ? "Перекраска интерфейса под системную тему (Material You)"
                : "Recolor the interface to match the system theme (Material You)");
        // No icon: stock Firefox settings rows are icon-less.
        // Click path: Preference.performClick falls back to the public mIntent field
        // when no click listener is set, so no reflective listener is needed.
        Intent open = new Intent();
        open.setClassName("slop.miho.patchfox", "slop.miho.patchfox.SettingsActivity");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        prefCls.getField("mIntent").set(pref, open);

        if (group != null && below != null) {
            // Land directly above Extensions: PreferenceGroup keeps its children
            // sorted by the public mOrder field (binary-search insert).
            java.lang.reflect.Field order = prefCls.getField("mOrder");
            order.setInt(pref, order.getInt(below) - 1);
            // Render exactly like the native rows: copy the layout ids from the
            // Extensions row (programmatic construction falls back to the plain
            // androidx layout which reserves an icon slot).
            java.lang.reflect.Field layoutF = prefCls.getField("mLayoutResId");
            java.lang.reflect.Field widgetF = prefCls.getField("mWidgetLayoutResId");
            layoutF.setInt(pref, layoutF.getInt(below));
            widgetF.setInt(pref, widgetF.getInt(below));
            try {
                java.lang.reflect.Field iconSpace = prefCls.getField("mIconSpaceReserved");
                iconSpace.setAccessible(true);
                iconSpace.setBoolean(pref, false);
            } catch (Throwable ignored) {
                // Final field: if the write is refused, the copied layout already
                // matches the native rows' geometry.
            }
            group.getClass().getMethod("addPreference", prefCls).invoke(group, pref);
        } else {
            screen.getClass().getMethod("addPreference", prefCls).invoke(screen, pref);
        }
        XposedBridge.log(TAG + ": settings entry added ("
                + (group != null ? "Advanced, above Extensions)" : "screen tail)"));
    }

    private static boolean isRussian(Context ctx) {
        try {
            String lang = ctx.getResources().getConfiguration().getLocales().get(0).getLanguage();
            return lang != null && lang.startsWith("ru");
        } catch (Throwable t) {
            return false;
        }
    }

    static void openModuleSettings(Context ctx) {
        try {
            Intent i = new Intent();
            i.setClassName("slop.miho.patchfox", "slop.miho.patchfox.SettingsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cannot open settings: " + t);
        }
    }

    /**
     * Fallback route for the launcher shortcut when the framework refuses
     * cross-package shortcut intents: the shortcut launches the browser itself
     * with the patchfox_settings extra, the first resumed activity consumes it
     * and forwards to the module settings. Works regardless of the monet toggle.
     */
    private static void consumeSettingsExtra(Activity act) {
        try {
            Intent ai = act.getIntent();
            if (ai != null && ai.getBooleanExtra("patchfox_settings", false)) {
                ai.removeExtra("patchfox_settings");
                openModuleSettings(act);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Launcher long-press shortcut ("PatchFox settings") published as the browser's own
     *  dynamic shortcut. Cross-package intents are attempted first (they usually work);
     *  if the framework rejects one, a self-targeted intent with the patchfox_settings
     *  extra is published instead — handled by the Activity.onResume hook below. */
    private void publishShortcuts(Context ctx) {
        try {
            ShortcutManager sm =
                    (ShortcutManager) ctx.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null || sm.getMaxShortcutCountPerActivity() <= 0) return;
            for (ShortcutInfo si : sm.getDynamicShortcuts()) {
                if ("patchfox_settings".equals(si.getId())) return; // already published
            }
            try {
                sm.pushDynamicShortcut(shortcutInfo(ctx, false));
                XposedBridge.log(TAG + ": shortcut published (direct intent)");
            } catch (Throwable first) {
                try {
                    sm.pushDynamicShortcut(shortcutInfo(ctx, true));
                    XposedBridge.log(TAG + ": shortcut published (fallback intent)");
                } catch (Throwable second) {
                    XposedBridge.log(TAG + ": shortcut publish failed: " + second);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": shortcut publish failed: " + t);
        }
    }

    private ShortcutInfo shortcutInfo(Context ctx, boolean selfFallback) throws Exception {
        boolean ru = isRussian(ctx);
        ShortcutInfo.Builder b = new ShortcutInfo.Builder(ctx, "patchfox_settings")
                .setShortLabel("PatchFox")
                .setLongLabel(ru ? "Настройки PatchFox" : "PatchFox settings")
                .setIcon(Icon.createWithBitmap(iconBitmap(ctx)));
        if (selfFallback) {
            // Route through the browser itself; the onResume hook consumes the extra.
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setPackage(ctx.getPackageName());
            i.putExtra("patchfox_settings", true);
            b.setIntent(i);
        } else {
            Intent i = new Intent();
            i.setClassName("slop.miho.patchfox", "slop.miho.patchfox.SettingsActivity");
            b.setIntent(i);
        }
        return b.build();
    }

    /** Monet-tinted "PF" badge used by both the settings row icon and the shortcut. */
    private static Bitmap iconBitmap(Context ctx) {
        Monet.provide(ctx.getResources());
        boolean night = Monet.isNight(ctx.getResources());
        int bg = Monet.role("primary_container", night);
        if (bg == 0) bg = night ? 0xFF38308C : 0xFFEADDFF;
        int fg = Monet.role("on_primary_container", night);
        if (fg == 0) fg = night ? 0xFFEADDFF : 0xFF211A45;

        float density = ctx.getResources().getDisplayMetrics().density;
        int size = Math.round(108 * density);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float inset = size * 0.14f; // adaptive-icon safe zone
        android.graphics.RectF r = new android.graphics.RectF(
                inset, inset, size - inset, size - inset);
        paint.setColor(bg);
        canvas.drawRoundRect(r, r.width() * 0.28f, r.width() * 0.28f, paint);

        paint.setColor(fg);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(r.width() * 0.55f);
        // Centred baseline.
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        float y = r.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("PF", r.centerX(), y, paint);
        return bmp;
    }

    // ------------------------------------------------------------------ Settings channel

    /**
     * Reads the module settings (the monet on/off toggle) as early as possible:
     * the Application context is the first reliable handle, with the first
     * onResume as a safety net. Every recoloring hook checks the cached flag,
     * so a disabled toggle leaves the app completely stock.
     */
    private void hookAppContext() {
        try {
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args.length > 0 && param.args[0] instanceof Context) {
                            Context ctx = (Context) param.args[0];
                            ModulePrefs.init(ctx);
                            publishShortcuts(ctx);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.attach hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------ Compose theme

    private void hookAcornTheme(ClassLoader cl) throws Exception {
        Class<?> acornThemeKt = find(cl, "mozilla.components.compose.base.theme.AcornThemeKt");
        Class<?> acornColorsKt = find(cl, "mozilla.components.compose.base.theme.AcornColorsKt");
        Class<?> gradientKt = find(cl, "mozilla.components.compose.base.theme.AcornGradientSchemeKt");

        lightPalette = staticField(acornColorsKt, "lightColorPalette");
        darkPalette = staticField(acornColorsKt, "darkColorPalette");
        oledPalette = staticField(acornColorsKt, "oledColorPalette");
        privatePalette = staticField(acornColorsKt, "privateColorPalette");
        lightGradient = staticField(gradientKt, "lightAcornGradientScheme");
        darkGradient = staticField(gradientKt, "darkAcornGradientScheme");

        palette = new Palette(cl);

        XposedBridge.hookAllMethods(acornThemeKt, "AcornTheme", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    applyTheme(param);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": applyTheme failed: " + t);
                }
            }
        });
    }

    private void applyTheme(XC_MethodHook.MethodHookParam param) {
        if (!ModulePrefs.monetTheme()) return;
        Object colors = param.args.length > 0 ? param.args[0] : null;

        int mode;
        if (colors == lightPalette) mode = Palette.MODE_LIGHT;
        else if (colors == darkPalette) mode = Palette.MODE_DARK;
        else if (colors == oledPalette) mode = Palette.MODE_OLED;
        else if (colors == privatePalette) return; // keep Firefox private purple
        else if (colors == null) mode = Monet.isNight() ? Palette.MODE_DARK : Palette.MODE_LIGHT;
        else return; // unknown palette, don't touch

        boolean night = mode == Palette.MODE_DARK || mode == Palette.MODE_OLED;

        Object[] pair = palette.get(mode, night, colors);
        if (pair == null) return;

        param.args[0] = pair[0]; // AcornColors
        param.args[1] = pair[1]; // ColorScheme
        // Gradient scheme (tab outlines, CFR and accent gradients): replace always —
        // private mode returned above and keeps its own purple gradients.
        if (param.args.length > 2) {
            Object gradients = palette.gradientFor(mode, night);
            if (gradients != null) param.args[2] = gradients;
        }
        // Clear the Kotlin "use default argument" bits for params 0..2 so our values survive.
        if (param.args.length > 6 && param.args[6] instanceof Integer) {
            param.args[6] = (Integer) param.args[6] & ~7;
        }
    }

    // ------------------------------------------------------------------ Window background

    /**
     * Firefox never paints the areas under the system bars in edge-to-edge mode, so the
     * window background from the theme (violet fx_mobile_surface) shows through in the
     * status/navigation strips and behind pages during tab-switch gestures. Repaint it
     * with the monet surface from inside the app process.
     */
    private void hookWindowBackground() {
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Activity act = (Activity) param.thisObject;
                    ModulePrefs.init(act);
                    consumeSettingsExtra(act);
                    if (!ModulePrefs.monetTheme()) return;
                    Resources res = act.getResources();
                    Monet.provide(res);
                    boolean night = Monet.isNight(res);
                    int bg = Monet.role("surface", night);
                    if (bg == 0) bg = Monet.role("background", night);
                    if (bg == 0) return;
                    Window w = act.getWindow();
                    if (w != null) w.setBackgroundDrawable(new ColorDrawable(bg));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ Dialogs & shape backgrounds

    /**
     * Bottom-sheet dialogs (the three-dot menu) zoom around on top of flat colored
     * window backgrounds taken from the theme (violet fx_mobile_surface at night).
     * Repaint dialog windows whose background is a plain solid color; translucent
     * overlays (the private-mode dim) are left untouched.
     */
    private void hookDialogWindow() {
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!ModulePrefs.monetTheme()) return;
                    Dialog d = (Dialog) param.thisObject;
                    Window w = d.getWindow();
                    if (w == null) return;
                    View decor = w.getDecorView();
                    if (decor == null) return;
                    Drawable cur = decor.getBackground();
                    if (!(cur instanceof ColorDrawable)) return;
                    ColorDrawable cd = (ColorDrawable) cur;
                    if (cd.getAlpha() < 255) return; // private-mode dim overlays
                    Resources res = d.getContext().getResources();
                    Monet.provide(res);
                    int bg = Monet.role("surface", Monet.isNight(res));
                    if (bg == 0 || cd.getColor() == bg) return;
                    w.setBackgroundDrawable(new ColorDrawable(bg));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * XML shape drawables never pass through Resources.getColor: their solid colors are
     * resolved internally against the inflating theme. Two paths here:
     *  a) the menu sheet's bottom_sheet_with_top_rounded_corners — its solid is
     *     ?attr/colorSurface, which cannot be resolved without the dialog's theme, so a
     *     monet replica with identical rounded-top geometry replaces it;
     *  b) any other shape whose solid happens to match a known legacy value (ValueTable)
     *     gets recolored through it.
     */
    private void hookLegacyViewBackgrounds() {
        XposedBridge.hookAllMethods(View.class, "setBackgroundResource", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!ModulePrefs.monetTheme()) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                    int resid = (Integer) param.args[0];
                    if (resid == 0) return;
                    View v = (View) param.thisObject;
                    Resources res = v.getResources();
                    Monet.provide(res);

                    String name = res.getResourceName(resid);
                    if (name.endsWith("bottom_sheet_with_top_rounded_corners")) {
                        int color = Monet.role("surface", Monet.isNight(res));
                        if (color != 0) {
                            String pkg = name.substring(0, name.indexOf(':'));
                            int rid = res.getIdentifier("bottom_sheet_corner_radius_28dp", "dimen", pkg);
                            float radius = rid != 0 ? res.getDimensionPixelSize(rid)
                                    : res.getDisplayMetrics().density * 28f;
                            GradientDrawable sheet = new GradientDrawable();
                            sheet.setShape(GradientDrawable.RECTANGLE);
                            sheet.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
                            sheet.setColor(color);
                            v.setBackground(sheet);
                            param.setResult(null);
                        }
                        return;
                    }

                    if (!name.contains(":drawable/")) return;
                    Drawable d = res.getDrawable(resid);
                    if (!(d instanceof GradientDrawable)) return;
                    GradientDrawable g = (GradientDrawable) d.mutate();
                    int cur;
                    try {
                        ColorStateList csl = g.getColor();
                        cur = csl != null ? csl.getDefaultColor() : 0;
                    } catch (Throwable t) {
                        return;
                    }
                    int replaced = ResRemap.remapValueColor(res, cur);
                    if (replaced != 0 && replaced != cur) {
                        g.setColor(replaced);
                        v.setBackground(g);
                        param.setResult(null);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ Resources (View UI)

    /** Logs styled color reads seen through TypedArray (only color-valued ones). */
    private static void taDebug(String via, TypedArray ta, int index, int rid) {
        try {
            if (rid != 0) {
                String name = ta.getResources().getResourceName(rid);
                if (name != null && name.contains(":color/")) {
                    XposedBridge.log(TAG + " TA " + via + " -> " + name);
                }
            } else {
                TypedValue tv = new TypedValue();
                if (ta.getValue(index, tv) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    XposedBridge.log(TAG + " TA " + via + " raw=#" + Integer.toHexString(tv.data));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Reads a raw color int from a styled array slot that has no resource id attached. */
    private static int taRawColor(TypedArray ta, int index) {
        try {
            TypedValue tv = new TypedValue();
            if (ta.getValue(index, tv) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int resDebugCount = 0;

    /** True if the id refers to the hardcoded rainbow progress drawable of the toolbar. */
    private static boolean isProgressGradient(Resources res, int id) {
        try {
            String name = res.getResourceName(id);
            return name.endsWith(":drawable/progress_gradient");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Monet replacement for the hardcoded rainbow progress_gradient (page load bar). */
    private static Drawable buildProgressDrawable(Resources res) {
        try {
            Monet.provide(res);
            boolean night = Monet.isNight(res);
            int primary = Monet.role("primary", night);
            if (primary == 0) return null;
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(primary);
            ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
            LayerDrawable layers = new LayerDrawable(
                    new Drawable[]{new ColorDrawable(0x00000000), clip});
            layers.setId(0, android.R.id.background);
            layers.setId(1, android.R.id.progress);
            return layers;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Logs color resource lookups we currently do NOT remap — the "what's left" list. */
    private static void resDebug(Resources res, int id) {
        if (resDebugCount >= 500) return;
        try {
            String name = res.getResourceName(id);
            if (name.startsWith("android:")) return;
            resDebugCount++;
            XposedBridge.log(TAG + " RES-unmapped " + name);
        } catch (Throwable ignored) {
        }
    }

    private void hookResources() {
        XposedBridge.hookAllMethods(Resources.class, "getColor", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.monetTheme()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                Resources res = (Resources) param.thisObject;
                Monet.provide(res);
                int id = (Integer) param.args[0];
                int mapped = ResRemap.remapColor(res, id);
                if (mapped != 0) param.setResult(mapped);
                else resDebug(res, id);
            }
        });

        XposedBridge.hookAllMethods(Resources.class, "getColorStateList", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.monetTheme()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                Resources res = (Resources) param.thisObject;
                Monet.provide(res);
                ColorStateList mapped = ResRemap.remapCsl(res, (Integer) param.args[0]);
                if (mapped != null) param.setResult(mapped);
            }
        });

        // Drawables inflated from plain @color references (window backgrounds, View
        // backgrounds and XML drawables whose root is <color>).
        XposedBridge.hookAllMethods(Resources.class, "getDrawable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.monetTheme()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                Resources res = (Resources) param.thisObject;
                Monet.provide(res);
                int id = (Integer) param.args[0];
                if (isProgressGradient(res, id)) {
                    Drawable repl = buildProgressDrawable(res);
                    if (repl != null) param.setResult(repl);
                    return;
                }
                int mapped = ResRemap.remapColor(res, id);
                if (mapped != 0) param.setResult(new ColorDrawable(mapped));
            }
        });

        // Styled attribute reads: every View inflated from XML with android:background="@color/..."
        // or any custom color attr goes through TypedArray, not Resources.getColor.
        XposedBridge.hookAllMethods(TypedArray.class, "getColor", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.monetTheme()) return;
                if (param.args.length < 2 || !(param.args[0] instanceof Integer)) return;
                try {
                    TypedArray ta = (TypedArray) param.thisObject;
                    int idx = (Integer) param.args[0];
                    int id = ta.getResourceId(idx, 0);
                    taDebug("getColor", ta, idx, id);
                    if (id == 0) {
                        int mapped = ResRemap.remapValueColor(ta.getResources(), taRawColor(ta, idx));
                        if (mapped != 0) param.setResult(mapped);
                        return;
                    }
                    int mapped = ResRemap.remapColor(ta.getResources(), id);
                    if (mapped != 0) param.setResult(mapped);
                } catch (Throwable ignored) {
                }
            }
        });

        XposedBridge.hookAllMethods(TypedArray.class, "getColorStateList", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.monetTheme()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                try {
                    TypedArray ta = (TypedArray) param.thisObject;
                    int idx = (Integer) param.args[0];
                    int id = ta.getResourceId(idx, 0);
                    taDebug("getCSL", ta, idx, id);
                    if (id == 0) {
                        int mapped = ResRemap.remapValueColor(ta.getResources(), taRawColor(ta, idx));
                        if (mapped != 0) {
                            param.setResult(new ColorStateList(new int[][]{new int[0]}, new int[]{mapped}));
                        }
                        return;
                    }
                    ColorStateList mapped = ResRemap.remapCsl(ta.getResources(), id);
                    if (mapped != null) param.setResult(mapped);
                } catch (Throwable ignored) {
                }
            }
        });

        XposedBridge.hookAllMethods(TypedArray.class, "getDrawable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.monetTheme()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                try {
                    TypedArray ta = (TypedArray) param.thisObject;
                    int idx = (Integer) param.args[0];
                    int id = ta.getResourceId(idx, 0);
                    taDebug("getDrw", ta, idx, id);
                    if (id != 0 && isProgressGradient(ta.getResources(), id)) {
                        Drawable repl = buildProgressDrawable(ta.getResources());
                        if (repl != null) param.setResult(repl);
                        return;
                    }
                    if (id == 0) {
                        int mapped = ResRemap.remapValueColor(ta.getResources(), taRawColor(ta, idx));
                        if (mapped != 0) param.setResult(new ColorDrawable(mapped));
                        return;
                    }
                    int mapped = ResRemap.remapColor(ta.getResources(), id);
                    if (mapped != 0) param.setResult(new ColorDrawable(mapped));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ compact toolbar

    /**
     * Slimmer address bar. Firefox sources the toolbar strip height from a handful of
     * dimens ({@code composable_browser_toolbar_height} = 64dp is the one the engine
     * insets and Find-in-Page read); remapping them by name to 56dp makes the browser
     * grant less room to the whole strip. The values live in one table so the follow-up
     * tuning (or per-name opt-outs) stays a data edit. Fired on every dimen read in the
     * app, so the hot path is a single map lookup with a negative cache.
     */
    private static final Map<String, Integer> COMPACT_DIMEN_DPS = new HashMap<>();

    static {
        // 40dp pill + 8dp vertical padding above and below = 56dp strip, matching
        // the engine insets it remaps (the strip used to be 64dp for a 48dp pill).
        COMPACT_DIMEN_DPS.put("composable_browser_toolbar_height", 56);        // 64dp
        COMPACT_DIMEN_DPS.put("composable_browser_toolbar_height_small", 56); // 56dp
        COMPACT_DIMEN_DPS.put("browser_toolbar_height", 56);                   // 56dp
    }

    /** dimen resource id -> target dp, -1 = seen and not ours. Main-thread safe. */
    private static final Map<Integer, Integer> compactDimens = new ConcurrentHashMap<>();

    private void hookCompactDimensions() {
        XposedBridge.hookAllMethods(Resources.class, "getDimensionPixelSize", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.compactToolbar()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                Resources res = (Resources) param.thisObject;
                Integer dp = compactTargetDp(res, (Integer) param.args[0]);
                if (dp != null) param.setResult(Math.round(dp * res.getDisplayMetrics().density));
            }
        });
        XposedBridge.hookAllMethods(Resources.class, "getDimensionPixelOffset", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.compactToolbar()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                Resources res = (Resources) param.thisObject;
                Integer dp = compactTargetDp(res, (Integer) param.args[0]);
                if (dp != null) param.setResult((int) (dp * res.getDisplayMetrics().density));
            }
        });
        XposedBridge.hookAllMethods(Resources.class, "getDimension", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.compactToolbar()) return;
                // Skip the legacy 2-arg (id, TypedValue) overload.
                if (param.args.length != 1 || !(param.args[0] instanceof Integer)) return;
                Resources res = (Resources) param.thisObject;
                Integer dp = compactTargetDp(res, (Integer) param.args[0]);
                if (dp != null) param.setResult(dp * res.getDisplayMetrics().density);
            }
        });
    }

    /** Target dp for a hooked dimen id, or null when the id is not ours. */
    private static Integer compactTargetDp(Resources res, int id) {
        Integer cached = compactDimens.get(id);
        if (cached != null) return cached < 0 ? null : cached;
        int target = -1;
        try {
            Integer t = COMPACT_DIMEN_DPS.get(res.getResourceEntryName(id));
            if (t != null) target = t;
        } catch (Throwable ignored) {
        }
        compactDimens.put(id, target);
        return target < 0 ? null : target;
    }

    // ------------------------------------------------------------------ compact toolbar (Compose)

    /**
     * The visual half of the compact toolbar. The strip's Compose content draws itself
     * at a hardcoded 64dp (a 48dp URL pill inside 8dp vertical padding); those numbers
     * are Dp literals baked into {@code SizeKt.height(Modifier, Dp)} calls made from
     * mozilla.components.compose.browser.toolbar, so no resource hook can reach them.
     * Intercepts the height modifier factory itself and rewrites 48/56dp heights to a
     * 40dp pill when the call comes from the toolbar package — 40 + 16 of padding keeps
     * the recomposed strip at exactly 56dp, matching the remapped engine insets.
     *
     * The stack is only walked after the cheap float match (48/56 are rare outside the
     * toolbar), so the cost per composition is negligible.
     */
    private static final XC_MethodHook compactPillHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!ModulePrefs.compactToolbar()) return;
            if (param.args.length < 2) return;
            Object raw = param.args[1];
            if (!(raw instanceof Float)) return;
            float v = (Float) raw;
            // The 48dp pill family and the 56dp URL-box variant. The 40dp action-pill
            // literals and the 60/48dp tablet navbar heights stay out of the range, so
            // the rewrite below never rewrites something we just wrote.
            if (v < 47.0f || v > 57.0f) return;
            if (!inBrowserToolbarStack()) return;
            param.args[1] = 40.0f;
        }
    };

    /** True when the current composition originates in the browser toolbar package. */
    private static boolean inBrowserToolbarStack() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (e.getClassName().startsWith("mozilla.components.compose.browser.toolbar")) {
                return true;
            }
        }
        return false;
    }

    private void hookCompactCompose(ClassLoader cl) {
        try {
            Class<?> sizeKt = Class.forName("androidx.compose.foundation.layout.SizeKt", false, cl);
            Class<?> modifier = Class.forName("androidx.compose.ui.Modifier", false, cl);
            java.util.Set<String> names = new java.util.HashSet<>();
            for (java.lang.reflect.Method m : sizeKt.getMethods()) {
                if (!m.getName().startsWith("height")) continue;
                Class<?>[] p = m.getParameterTypes();
                // height(Modifier, Dp[, int mask]) — Dp shows up as a raw float param.
                if (p.length < 2 || p[0] != modifier || p[1] != float.class) continue;
                names.add(m.getName());
            }
            for (String name : names) {
                XposedBridge.hookAllMethods(sizeKt, name, compactPillHook);
            }
            XposedBridge.log(TAG + ": compact compose height hooks: " + names);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compact compose hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------ compact toolbar (icon padding)

    /**
     * The security lock (an ordinary Compose IconButton inside the URL pill) hovers in
     * the middle of a 48dp minimum-interaction touch box — that is where its ~12dp gaps
     * to the pill edge and to the URL text come from, (48 − 24 glyph) / 2 on each side.
     * The 48dp lives in the {@code LocalMinimumInteractiveComponentSize} composition
     * local, whose sole consumer is the shared minimum-size modifier node, reading it
     * through {@code CompositionLocalConsumerModifierNodeKt.currentValueOf} at measure
     * time. Serves 40dp there instead while compact mode is on: the touch box then
     * equals the 40dp pill, mirroring the stock geometry (box 48 = pill 48), and the
     * lock gets symmetric 8dp gaps to both the pill edge and the text.
     *
     * Compacts every icon-button touch box in the app by the same 8dp — glyphs are
     * unaffected, only the invisible minimum padding shrinks, which keeps the general
     * spacing consistent with the compact chrome. Runs at measure time, so the fast
     * path is a flag read plus a reference compare.
     */
    private void hookCompactTouchTargets(ClassLoader cl) {
        try {
            Class<?> nodeKt = Class.forName(
                    "androidx.compose.ui.node.CompositionLocalConsumerModifierNodeKt", false, cl);
            Class<?> interKt = Class.forName(
                    "androidx.compose.material3.InteractiveComponentSizeKt", false, cl);
            final Object local = staticField(interKt, "LocalMinimumInteractiveComponentSize");
            Class<?> dpClass = Class.forName("androidx.compose.ui.unit.Dp", false, cl);
            java.lang.reflect.Constructor<?> dpCtor = dpClass.getDeclaredConstructor(float.class);
            dpCtor.setAccessible(true);
            final Object compactDp = dpCtor.newInstance(40.0f);
            XposedBridge.hookAllMethods(nodeKt, "currentValueOf", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.compactToolbar()) return;
                    if (param.args.length != 2 || param.args[1] != local) return;
                    param.setResult(compactDp);
                }
            });
            XposedBridge.log(TAG + ": compact touch-target hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compact touch-target hook failed: " + t);
        }
    }

    // ------------------------------------------------------------- compact toolbar (menu end padding)

    /**
     * Stock glues the overflow (three-dot) button flush to the screen edge: the
     * toolbar row runs full-bleed on phones (its custom row layout only insets
     * wide screens), so the menu container's touch box ends exactly at the
     * bezel and the ripple clips against it. Gives that container 8dp of end
     * padding while the compact toggle is on; the URL box (weight 1f) absorbs
     * the width, so only the menu button shifts and clears the bezel.
     *
     * Detection is by content, not callsite: the padding is applied only to
     * the ActionContainer whose action list holds the three-dot ActionButtonRes
     * (identified by its drawable resource), so every other container — edit
     * actions, start actions — keeps stock geometry.
     */
    private void hookMenuEndPadding(ClassLoader cl) {
        try {
            final Class<?> actionRes = Class.forName(
                    "mozilla.components.compose.browser.toolbar.concept.Action$ActionButtonRes", false, cl);
            final java.lang.reflect.Field resId = actionRes.getField("drawableResId");
            Class<?> iconsR = Class.forName("mozilla.components.ui.icons.R$drawable", false, cl);
            final int menuIcon = iconsR.getField("mozac_ic_ellipsis_vertical_24").getInt(null);

            Class<?> padKt = Class.forName("androidx.compose.foundation.layout.PaddingKt", false, cl);
            Class<?> modifierCls = Class.forName("androidx.compose.ui.Modifier", false, cl);
            java.lang.reflect.Method padFound = null;
            for (java.lang.reflect.Method m : padKt.getMethods()) {
                if (!m.getName().startsWith("padding")) continue;
                Class<?>[] p = m.getParameterTypes();
                // padding(Modifier, start, top, end, bottom) — Dp args as raw floats.
                if (p.length == 5 && p[0] == modifierCls && p[1] == float.class
                        && p[2] == float.class && p[3] == float.class && p[4] == float.class) {
                    padFound = m;
                    break;
                }
            }
            if (padFound == null) throw new NoSuchMethodException("padding(Modifier, Dp, Dp, Dp, Dp)");
            final java.lang.reflect.Method padding = padFound;
            padding.setAccessible(true);

            Class<?> containerKt = Class.forName(
                    "mozilla.components.compose.browser.toolbar.ActionContainerKt", false, cl);
            XposedBridge.hookAllMethods(containerKt, "ActionContainer", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.compactToolbar()) return;
                    if (param.args.length < 3) return;
                    Object list = param.args[0];
                    Object modifier = param.args[2];
                    if (!(list instanceof java.util.List) || !modifierCls.isInstance(modifier)) return;
                    boolean hasMenu = false;
                    try {
                        for (Object a : (java.util.List<?>) list) {
                            if (actionRes.isInstance(a) && resId.getInt(a) == menuIcon) {
                                hasMenu = true;
                                break;
                            }
                        }
                    } catch (IllegalAccessException ignored) {
                        return;
                    }
                    if (!hasMenu) return;
                    try {
                        param.args[2] = padding.invoke(null, modifier,
                                Float.valueOf(0f), Float.valueOf(0f),
                                Float.valueOf(8.0f), Float.valueOf(0f));
                    } catch (IllegalAccessException ignored) {
                    } catch (java.lang.reflect.InvocationTargetException ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": menu end-padding hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": menu end-padding hook failed: " + t);
        }
    }

    // --------------------------------------------------------- compact toolbar (search selector)

    /**
     * The search selector card (engine logo + chevron, 40dp) is an action inside
     * the URL box. Stock floats it on 4dp horizontal padding, which reads as a
     * symmetric inset while the pill is 48dp tall. The compact pill is exactly
     * 40dp, so the card ends up flush top and bottom and the leftover 4dp reads
     * as a one-sided gap at the left. Strips that padding while compact mode is
     * on: the card then starts where the pill's own content starts.
     *
     * The strip only applies to the selector's own chain: a flag is raised for
     * the duration of the SearchSelector composable and the first matching
     * padding(horizontal = 4, vertical = 0) call inside it is replaced with a
     * trailing-only variant — start = 0 keeps the card flush with the pill's
     * content, end = 6dp gives the text field a touch more room than stock.
     */
    private static boolean inSearchSelector;
    private static boolean selectorPadStripped;

    private void hookSearchSelectorPadding(ClassLoader cl) {
        try {
            Class<?> selectorKt = Class.forName(
                    "mozilla.components.compose.browser.toolbar.ui.SearchSelectorKt", false, cl);
            final XC_MethodHook flagHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.compactToolbar()) return;
                    inSearchSelector = true;
                    selectorPadStripped = false;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    inSearchSelector = false;
                }
            };
            int flagged = 0;
            for (java.lang.reflect.Method m : selectorKt.getMethods()) {
                if (!m.getName().startsWith("SearchSelector")) continue;
                XposedBridge.hookAllMethods(selectorKt, m.getName(), flagHook);
                flagged++;
            }
            if (flagged == 0) throw new NoSuchMethodException("SearchSelector");

            Class<?> padKt = Class.forName("androidx.compose.foundation.layout.PaddingKt", false, cl);
            Class<?> modifierCls = Class.forName("androidx.compose.ui.Modifier", false, cl);
            // padding-qDBjuR0(Modifier, start, top, end, bottom) for the replacement.
            java.lang.reflect.Method endPad = null;
            for (java.lang.reflect.Method m : padKt.getMethods()) {
                if (!m.getName().startsWith("padding-qDBjuR0")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[0] == modifierCls && p[1] == float.class
                        && p[2] == float.class && p[3] == float.class && p[4] == float.class) {
                    endPad = m;
                    break;
                }
            }
            if (endPad == null) throw new NoSuchMethodException("padding-qDBjuR0");
            endPad.setAccessible(true);
            final java.lang.reflect.Method endPadding = endPad;

            int hooked = 0;
            for (java.lang.reflect.Method m : padKt.getMethods()) {
                if (!m.getName().startsWith("padding-VpY3zN4")) continue;
                Class<?>[] p = m.getParameterTypes();
                // padding-VpY3zN4(Modifier, horizontal, vertical[, default mask])
                if (p.length < 3 || p[0] != modifierCls || p[1] != float.class || p[2] != float.class) continue;
                XposedBridge.hookAllMethods(padKt, m.getName(), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!inSearchSelector || selectorPadStripped) return;
                        if (!(param.args[1] instanceof Float) || !(param.args[2] instanceof Float)) return;
                        if (Math.abs((Float) param.args[1] - 4.0f) > 0.01f) return;
                        if (Math.abs((Float) param.args[2]) > 0.01f) return;
                        selectorPadStripped = true;
                        try {
                            param.setResult(endPadding.invoke(null, param.args[0],
                                    Float.valueOf(0f), Float.valueOf(0f),
                                    Float.valueOf(6.0f), Float.valueOf(0f)));
                        } catch (IllegalAccessException ignored) {
                            param.setResult(param.args[0]);
                        } catch (java.lang.reflect.InvocationTargetException ignored) {
                            param.setResult(param.args[0]);
                        }
                    }
                });
                hooked++;
            }
            if (hooked == 0) throw new NoSuchMethodException("padding-VpY3zN4");
            XposedBridge.log(TAG + ": search selector padding hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": search selector padding hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------ bare tab counter

    /**
     * The tab counter draws a stroked rounded box with the count inside
     * (ui.tabcounter.TabCounter -> Box { TabCounterText }). When enabled, the
     * box composable is skipped entirely and its TabCounterText child is
     * composed in its place, so only the number remains — colored with the
     * button's inherited content color. The number is scaled up through the
     * same knob stock uses: its size is factor * mozac_tab_counter_box_width_height
     * (24dp, factor 0.5/0.4), served as 36dp here, so the count grows from
     * 12sp to 18sp (14.4sp for two digits).
     *
     * Default-off feature. The count's contentDescription lives on the skipped
     * box node, so TalkBack stops announcing the tab count while this is on.
     */
    private void hookBareTabCounter(ClassLoader cl) {
        try {
            Class<?> counterKt = Class.forName("mozilla.components.ui.tabcounter.TabCounterKt", false, cl);
            Class<?> composerCls = Class.forName("androidx.compose.runtime.Composer", false, cl);

            // TabCounter(count, isVisible, isPrivate, textColor, Composer, changed, defaults)
            // TabCounterText(count, text, isVisible, textColor, Composer, changed)
            java.lang.reflect.Method main = null;
            java.lang.reflect.Method text = null;
            for (java.lang.reflect.Method m : counterKt.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().startsWith("TabCounterText")) {
                    if (p.length == 6 && p[0] == int.class && p[1] == String.class
                            && p[2] == boolean.class && p[3] == long.class && p[4] == composerCls) {
                        text = m;
                    }
                } else if (m.getName().startsWith("TabCounter")) {
                    if (p.length == 7 && p[0] == int.class && p[1] == boolean.class
                            && p[2] == boolean.class && p[3] == long.class && p[4] == composerCls) {
                        main = m;
                    }
                }
            }
            if (main == null || text == null) throw new NoSuchMethodException("TabCounter composables");
            text.setAccessible(true);
            final java.lang.reflect.Method mainComposable = main;
            final java.lang.reflect.Method textComposable = text;

            // Text-size knob: dimensionResource(id) -> Dp as a raw float.
            Class<?> resKt = Class.forName("androidx.compose.ui.res.PrimitiveResources_androidKt", false, cl);
            java.lang.reflect.Method dimRes = null;
            for (java.lang.reflect.Method m : resKt.getMethods()) {
                if (!m.getName().startsWith("dimensionResource")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == int.class && p[1] == composerCls) {
                    dimRes = m;
                    break;
                }
            }
            if (dimRes == null) throw new NoSuchMethodException("dimensionResource");
            final java.lang.reflect.Method dimensionResource = dimRes;

            Class<?> dimenR = Class.forName("mozilla.components.ui.tabcounter.R$dimen", false, cl);
            final int boxDimen = dimenR.getField("mozac_tab_counter_box_width_height").getInt(null);

            // Content color for the bare number, read from the composer's locals.
            Class<?> contentColorKt = Class.forName("androidx.compose.material3.ContentColorKt", false, cl);
            final Object localContentColor = staticField(contentColorKt, "LocalContentColor");
            java.lang.reflect.Method consume = null;
            for (java.lang.reflect.Method m : composerCls.getMethods()) {
                if (m.getName().equals("consume") && m.getParameterTypes().length == 1) {
                    consume = m;
                    break;
                }
            }
            if (consume == null) throw new NoSuchMethodException("Composer.consume");
            final java.lang.reflect.Method consumeMethod = consume;
            final Class<?> colorCls = Class.forName("androidx.compose.ui.graphics.Color", false, cl);
            final java.lang.reflect.Field colorValue = colorCls.getField("value");

            XposedBridge.hookAllMethods(counterKt, mainComposable.getName(), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.bareTabCounter()) return;
                    int count = (Integer) param.args[0];
                    // (count, isPrivate, isVisible, textColor, Composer, changed, defaults);
                    // the toolbar leaves isVisible defaulted (mask bit 2 -> true), the
                    // tabs tray banner passes it explicitly as arg 2.
                    int defaults = (Integer) param.args[6];
                    boolean visible = (defaults & 4) != 0 || (Boolean) param.args[2];
                    Object composer = param.args[4];
                    long color;
                    try {
                        Object c = consumeMethod.invoke(composer, localContentColor);
                        if (c instanceof Long) {
                            color = (Long) c;
                        } else if (colorCls.isInstance(c)) {
                            color = colorValue.getLong(c);
                        } else {
                            return; // no color resolved: keep the stock box
                        }
                    } catch (Throwable t) {
                        return; // keep the stock box rather than a transparent number
                    }
                    String value = count > 99 ? "" : String.valueOf(count);
                    try {
                        textComposable.invoke(null, count, value, visible, color, composer, 0);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": bare counter compose failed: " + t);
                        return;
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.hookAllMethods(resKt, dimensionResource.getName(), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.bareTabCounter()) return;
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) return;
                    if ((Integer) param.args[0] != boxDimen) return;
                    param.setResult(Float.valueOf(36.0f));
                }
            });
            XposedBridge.log(TAG + ": bare tab-counter hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bare tab-counter hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------ see-through system bars

    /**
     * Chrome-style edge-to-edge: with the toolbar collapsed, pages show through the
     * transparent system status bar. Firefox already runs edge-to-edge (transparent
     * bars, decorFitsSystemWindows false), but the persistent-insets listener in
     * mozilla.support.ktx pads android.id/content with the status bar inset for
     * browser fragments, which clamps the whole browser pane — Gecko included —
     * below the status bar. Three coordinated hooks lift that clamp bottom-up:
     *
     * 1. While the latest attached padded fragment of a window is the browser, the
     *    id/content system bar paddings stay zero, so the browser pane spans the
     *    full window: pages render under the status bar when the toolbar is
     *    collapsed and under the gesture bar at the bottom, like Chrome. The same
     *    padding listener also serves other padded fragments (bookmarks,
     *    downloads, trust panel); the mode map mirrors Firefox's own logic —
     *    whichever SystemInsetsPaddedFragment attached last owns the window —
     *    so those screens keep their stock insets. Dialogs and menus do not
     *    touch the mode. IME-sized paddings keep flowing so the keyboard still
     *    resizes the page.
     * 2. The strip compensates with its own top padding so it keeps drawing where
     *    stock draws it, and its measured height (status + strip) becomes the
     *    collapse travel. For the bottom toolbar placement the strip gets the
     *    gesture bar inset as bottom padding and that padding zone is painted
     *    with the window surface color: the bar floats above the gesture zone
     *    instead of gluing to the screen edge, and the area under it reads
     *    solid like stock; the page only shows in the gesture zone once the
     *    bar collapses away.
     * 3. BaseBrowserFragment.initializeEngineView feeds the toolbar heights to
     *    the engine clipping behavior and to Gecko's dynamic-toolbar max height;
     *    adding the removed inset there lets the engine slide fully up (page at
     *    y=0 with the toolbar collapsed) and tells Gecko the true cover size.
     *    The addition is placement aware: status bar for the top bar, gesture
     *    bar for the bottom one.
     * 4. The status bar polices itself the way the gesture bar already does.
     *    While page content shows directly behind the transparent status bar
     *    (collapsed top bar, or any state with the bottom placement) the
     *    window band is pixel-sampled and the system icons flip to the color
     *    it asks for: dark icons over light pages, light icons over dark
     *    ones. Everywhere else Firefox's own icon choice is restored intact.
     *
     * Toggle semantics like elsewhere: the hooks are installed unconditionally,
     * every callback re-checks this process' copy of the flag; a change needs a
     * Firefox restart.
     */
    private void hookSeeThroughBars(ClassLoader cl) {
        // (1) while a browser fragment is live, keep the content frame's system
        // bar paddings at zero. Other padded fragments keep stock insets via
        // the live-browser counter below.
        XposedBridge.hookAllMethods(android.view.View.class, "setPadding", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!ModulePrefs.seeThroughBars()) return;
                if (param.args.length < 4) return;
                Object o = param.thisObject;
                if (!(o instanceof android.widget.FrameLayout)) return;
                android.view.View v = (android.view.View) o;
                if (v.getId() != android.R.id.content) return;
                boolean[] mode = BROWSER_MODE.get(v);
                if (mode == null || !mode[0]) return;
                int top = (Integer) param.args[1];
                int bottom = (Integer) param.args[3];
                // Any top inset comes from the status bar; zero it.
                if (top > 0) param.args[1] = 0;
                // Nav/gesture bar insets are small; remember and zero them. IME
                // paddings keep flowing so the keyboard still resizes the page.
                if (bottom > 40 && bottom < 250) {
                    cachedNavBarInset = bottom;
                    param.args[3] = 0;
                }
            }
        });

        // (1b) window mode: remember whether the latest attached padded fragment
        // is the browser. Mirrors Firefox's own dispatch in
        // EdgeToEdgeFragmentLifecycleCallbacks: dialogs, QR and nav hosts are
        // skipped, non-padded fragments clear their own insets.
        try {
            Class<?> callbacks = Class.forName(
                    "org.mozilla.fenix.e2e.EdgeToEdgeFragmentLifecycleCallbacks", false, cl);
            final Class<?> padded = Class.forName(
                    "org.mozilla.fenix.e2e.SystemInsetsPaddedFragment", false, cl);
            final Class<?> browser = Class.forName(
                    "org.mozilla.fenix.browser.BrowserFragment", false, cl);
            final Class<?> dialog = Class.forName(
                    "androidx.fragment.app.DialogFragment", false, cl);
            final Class<?> qr = Class.forName(
                    "mozilla.components.feature.qr.QrFragment", false, cl);
            final Class<?> navHost = Class.forName(
                    "androidx.navigation.fragment.NavHostFragment", false, cl);
            XposedBridge.hookAllMethods(callbacks, "onFragmentViewCreated", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.seeThroughBars()) return;
                    Object fragment = param.args.length > 1 ? param.args[1] : null;
                    Object view = param.args.length > 2 ? param.args[2] : null;
                    if (fragment == null || !(view instanceof android.view.View)) return;
                    if (dialog.isInstance(fragment) || qr.isInstance(fragment)
                            || navHost.isInstance(fragment)) {
                        return;
                    }
                    Object parent = null;
                    try {
                        parent = fragment.getClass().getMethod("getParentFragment").invoke(fragment);
                    } catch (Throwable ignored) {
                    }
                    if (parent != null && dialog.isInstance(parent)) return;
                    if (!padded.isInstance(fragment)) return;
                    android.view.View content = contentViewOf((android.view.View) view);
                    if (content == null) return;
                    BROWSER_MODE.put(content, new boolean[]{browser.isInstance(fragment)});
                    updateStatusBar();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": window mode hook failed: " + t);
        }

        // (1c) when the browser resumes in a window where another padded screen
        // was shown (returning from bookmarks), it owns the window again; the
        // padding is already applied, so re-run the insets dispatch to re-zero
        // it. The pause twin switches the mode and status adaptation off
        // when the window passes to another screen, since the strip view can
        // stay attached in the back stack and never fire the detach path.
        try {
            Class<?> browserFrag = Class.forName(
                    "org.mozilla.fenix.browser.BrowserFragment", false, cl);
            XposedBridge.hookAllMethods(browserFrag, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.seeThroughBars()) return;
                    Object view = null;
                    try {
                        view = param.thisObject.getClass().getMethod("getView").invoke(param.thisObject);
                    } catch (Throwable ignored) {
                    }
                    if (!(view instanceof android.view.View)) return;
                    android.view.View content = contentViewOf((android.view.View) view);
                    if (content == null) return;
                    BROWSER_MODE.put(content, new boolean[]{true});
                    content.requestApplyInsets();
                    updateStatusBar();
                }
            });
            // Pausing leaves the window to another screen (home fragment, a
            // padded screen, a dialog) — the browser no longer shows behind the
            // bars, so its mode and status adaptation must switch off right
            // then; the strip view can stay attached in the back stack and
            // never re-attach.
            XposedBridge.hookAllMethods(browserFrag, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object view = null;
                    try {
                        view = param.thisObject.getClass().getMethod("getView").invoke(param.thisObject);
                    } catch (Throwable ignored) {
                    }
                    if (!(view instanceof android.view.View)) return;
                    android.view.View content = contentViewOf((android.view.View) view);
                    if (content == null) return;
                    BROWSER_MODE.put(content, new boolean[]{false});
                    updateStatusBar();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": browser resume hook failed: " + t);
        }

        // (2) placement-aware strip padding, applied from the post attach frame
        // when the strip's layout gravity is set and live insets are known.
        // The strip's translation also drives the status icon adaptation.
        try {
            final Class<?> strip = Class.forName(
                    "org.mozilla.fenix.components.toolbar.ScrollableToolbarComposeView", false, cl);
            XposedBridge.hookAllMethods(android.view.View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!strip.isInstance(param.thisObject)) return;
                    final android.view.View v = (android.view.View) param.thisObject;
                    // The strip expands into the full-screen edit sheet and
                    // shrinks back without any translation event; a size
                    // change listener re-kicks the status adaptation then.
                    // Registered once per strip instance across re-attaches.
                    if (LAYOUT_TRACKED.put(v, Boolean.TRUE) == null) {
                        v.addOnLayoutChangeListener(
                                new android.view.View.OnLayoutChangeListener() {
                            @Override
                            public void onLayoutChange(android.view.View view,
                                    int left, int top, int right, int bottom,
                                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                                if (right - left != oldRight - oldLeft
                                        || bottom - top != oldBottom - oldTop) {
                                    updateStatusBar();
                                }
                            }
                        });
                    }
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            applyStripPlacement(v);
                        }
                    });
                }
            });
            XposedBridge.hookAllMethods(android.view.View.class, "onDetachedFromWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!strip.isInstance(param.thisObject)) return;
                    STRIP_REF.clear();
                    updateStatusBar();
                }
            });
            XposedBridge.hookAllMethods(android.view.View.class, "setTranslationY", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!strip.isInstance(param.thisObject)) return;
                    android.view.View v = (android.view.View) param.thisObject;
                    updateStatusBar();
                    // Top placement: the collapse is pure translation, so
                    // once it settles gecko still holds the origin and
                    // safe-area insets from the last Android layout pass and
                    // env()-aware fixed headers never regain their status
                    // bar offset. When the strip goes quiet, poke one layout
                    // pass so GeckoView re-reads both.
                    if (ModulePrefs.seeThroughBars() && !BOTTOM_STRIP) {
                        lastStripMove = android.os.SystemClock.uptimeMillis();
                        scheduleSettleEnvRefresh(v);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": strip placement hook failed: " + t);
        }

        // (3) engine viewport and clipping see the removed insets as toolbar
        // cover, split by placement: status bar for the top toolbar, gesture
        // bar for the bottom one
        try {
            Class<?> fragment = Class.forName(
                    "org.mozilla.fenix.browser.BaseBrowserFragment", false, cl);
            final java.util.WeakHashMap<Object, Boolean> reinit = new java.util.WeakHashMap<>();
            XposedBridge.hookAllMethods(fragment, "initializeEngineView$app", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!ModulePrefs.seeThroughBars()) return;
                    if (param.args.length < 2 || !(param.args[0] instanceof Integer)
                            || !(param.args[1] instanceof Integer)) return;
                    int top = (Integer) param.args[0];
                    int bottom = (Integer) param.args[1];
                    if (top > 0) {
                        param.args[0] = top + statusBarInsetPx();
                    } else if (bottom > 0) {
                        int nav = liveBottomInsetOf(param.thisObject);
                        if (nav <= 0) nav = cachedNavBarInset;
                        if (nav > 0) {
                            param.args[1] = bottom + nav;
                        } else if (!reinit.containsKey(param.thisObject)) {
                            // cold start with the bottom placement: window
                            // insets are not dispatched yet — retry after the
                            // first layout so the engine learns the real cover
                            reinit.put(param.thisObject, Boolean.TRUE);
                            final Object frag = param.thisObject;
                            final Object view =
                                    frag.getClass().getMethod("getView").invoke(frag);
                            final java.lang.reflect.Method m =
                                    (java.lang.reflect.Method) param.method;
                            ((android.view.View) view).post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (ModulePrefs.seeThroughBars()) {
                                            m.invoke(frag, top, bottom);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": engine view hook failed: " + t);
        }

        // Firefox re-asserts its own status icon appearance on every UI
        // event, which would out-race any periodic rewrite of the bit. While
        // the adaptive status bar owns the bit, Firefox's own writes are
        // rewritten in flight to the sampled choice, so both channels agree
        // and nothing flickers.
        try {
            // InsetsController is the hidden concrete class behind the public
            // WindowInsetsController interface, so it is resolved by name
            Class<?> insetsController = Class.forName(
                    "android.view.InsetsController", false, cl);
            XposedBridge.hookAllMethods(insetsController,
                    "setSystemBarsAppearance", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!statusIconsModified || param.args.length < 2) return;
                    if (!(param.args[0] instanceof Integer)
                            || !(param.args[1] instanceof Integer)) return;
                    int mask = (Integer) param.args[1];
                    if ((mask & LIGHT_STATUS_BIT) == 0) return; // status bit untouched
                    int appearance = (Integer) param.args[0];
                    param.args[0] = (appearance & ~LIGHT_STATUS_BIT)
                            | (contentIsLight ? LIGHT_STATUS_BIT : 0);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": insets appearance hook failed: " + t);
        }

        // (4) Gecko's page-facing safe areas assume the stock engine viewport.
        // With the bottom toolbar see-through, the engine view spans the full
        // window, so the raw root gesture-bar inset leaks into
        // env(safe-area-inset-bottom) even while the toolbar and its solid
        // pad zone cover that area: env()-padded fixed footers keep a gap
        // above the toolbar. Zero the bottom inset for the bottom placement
        // so footers sit flush on the toolbar. The top inset stays raw in
        // both placements: at the top the engine really does extend under
        // the status bar, and pages expect env(safe-area-inset-top) there.
        try {
            Class<?> display = find(cl, "org.mozilla.geckoview.GeckoDisplay");
            // Args order is (top, right, bottom, left), see GeckoView's
            // onGlobalLayout dispatch of the root system bar insets.
            XposedBridge.hookAllMethods(display, "safeAreaInsetsChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ModulePrefs.seeThroughBars()) return;
                    if (STRIP_REF.get() == null) return; // browser strip not placed
                    if (!BOTTOM_STRIP) return;
                    if (param.args.length < 4
                            || !(param.args[2] instanceof Integer)) return;
                    param.args[2] = 0;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": gecko safe-area hook failed: " + t);
        }
    }

    /** Main thread only: content view -> latest padded fragment is the browser. */
    private static final java.util.WeakHashMap<android.view.View, boolean[]> BROWSER_MODE =
            new java.util.WeakHashMap<>();

    /** Resolves the window content view that owns the given fragment view. */
    private static android.view.View contentViewOf(android.view.View anyViewInWindow) {
        try {
            android.content.Context ctx = anyViewInWindow.getContext();
            while (!(ctx instanceof android.app.Activity)) {
                if (!(ctx instanceof android.content.ContextWrapper)) return null;
                ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
            }
            return ((android.app.Activity) ctx).getWindow().getDecorView()
                    .findViewById(android.R.id.content);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Live strip view of the foreground browser window plus its placement. */
    private static final java.lang.ref.WeakReference<android.view.View> EMPTY_REF =
            new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<android.view.View> STRIP_REF = EMPTY_REF;
    private static boolean BOTTOM_STRIP;

    /** Strip instances that already carry our layout change listener. */
    private static final java.util.WeakHashMap<android.view.View, Boolean> LAYOUT_TRACKED =
            new java.util.WeakHashMap<>();

    /** Last system bottom inset (gesture or 3-button) seen in the browser window. */
    private static int cachedNavBarInset;

    /**
     * Fixes the toolbar geometry for the active placement. The strip is a
     * direct child of the browser coordinator whose layout gravity encodes the
     * placement. Top: the strip gets the status inset as top padding so the
     * page can slide under the status bar while the bar keeps its stock look.
     * Bottom: the strip gets the gesture bar inset as bottom padding and its
     * padding zone painted with the window surface color, so the bar floats
     * above the gesture zone instead of gluing to the screen edge while the
     * area under it still reads solid — the page only enters the gesture zone
     * once the bar collapses away.
     */
    private static void applyStripPlacement(android.view.View strip) {
        try {
            if (!ModulePrefs.seeThroughBars()) return;
            STRIP_REF = new java.lang.ref.WeakReference<>(strip);
            boolean bottom = false;
            try {
                Object lp = strip.getLayoutParams();
                int g = lp.getClass().getField("gravity").getInt(lp);
                bottom = (g & android.view.Gravity.VERTICAL_GRAVITY_MASK)
                        == android.view.Gravity.BOTTOM;
            } catch (Throwable ignored) {
            }
            if (!bottom && strip.getHeight() > 0) {
                int[] loc = new int[2];
                strip.getLocationOnScreen(loc);
                int sh = strip.getResources().getDisplayMetrics().heightPixels;
                bottom = loc[1] > sh / 2;
            }
            BOTTOM_STRIP = bottom;
            int nav = 0;
            if (!bottom) {
                int st = statusBarInsetPx();
                if (st > 0 && (strip.getPaddingTop() != st || strip.getPaddingBottom() != 0)) {
                    strip.setPadding(0, st, 0, 0);
                    strip.requestLayout();
                }
            } else {
                android.view.WindowInsets wi = strip.getRootWindowInsets();
                nav = wi == null ? 0 : wi.getSystemWindowInsetBottom();
                if (nav <= 0) nav = cachedNavBarInset;
                if (nav > 0 && (strip.getPaddingBottom() != nav || strip.getPaddingTop() != 0)) {
                    strip.setPadding(0, 0, 0, nav);
                    strip.requestLayout();
                }
                // The padding zone below the bar is part of the toolbar
                // visually: fill it with the window surface color so the
                // gesture area under the bar reads solid instead of letting
                // raw page pixels peek through the gap. A band-only drawable,
                // not a plain background: in edit mode the strip view expands
                // into the full-screen edit sheet, and a flat background
                // would paint an opaque backdrop over the page.
                if (nav > 0 && strip.getBackground() == null) {
                    android.graphics.drawable.Drawable bg = strip.getRootView().getBackground();
                    if (bg instanceof android.graphics.drawable.ColorDrawable) {
                        strip.setBackground(new PadFill(
                                ((android.graphics.drawable.ColorDrawable) bg).getColor(), nav));
                    }
                }
            }
            updateStatusBar();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": strip placement failed: " + t);
        }
    }

    /**
     * Adapts the status bar to whatever currently shows behind it. While page
     * content sits directly behind the transparent status bar (collapsed top
     * bar, or any state of the bottom placement) the window band gets sampled
     * and the system icons take the color it asks for: dark icons over light
     * content, light icons over dark content, the same self-policing the
     * gesture pill already does. Every other state - home, other screens, the
     * open top toolbar - keeps the icon appearance Firefox picked for its own
     * chrome.
     */
    private static void updateStatusBar() {
        try {
            android.view.View strip = STRIP_REF.get();
            if (statusBandActive(strip)) {
                scheduleStatusSampler(strip, 0L);
            } else {
                restoreStatusIcons();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status update failed: " + t);
        }
    }

    /**
     * True while the page is what the transparent status bar looks through:
     * always with the bottom placement, and for the top placement only once
     * the strip is fully collapsed away, because the page fills the status
     * zone only then; before that the zone shows the window surface and
     * Firefox's own icon choice already fits it.
     */
    private static boolean statusBandActive(android.view.View strip) {
        if (strip == null || !strip.isAttachedToWindow()
                || !ModulePrefs.seeThroughBars()) {
            return false;
        }
        android.view.View content = contentViewOf(strip);
        boolean[] mode = content == null ? null : BROWSER_MODE.get(content);
        if (mode == null || !mode[0]) return false;
        // bottom placement: the page is always behind the status bar; top
        // placement: only at full collapse, when the page fills the band.
        // A strip grown past any toolbar geometry is the full-screen edit
        // sheet: an opaque Firefox surface, so its own icon choice is right.
        if (strip.getHeight() <= 40 || strip.getHeight() > 480) return false;
        return BOTTOM_STRIP
                || -strip.getTranslationY() >= strip.getHeight() - 8f;
    }

    // --- adaptive status icons ------------------------------------------------

    /**
     * Timestamp of the last top strip movement, plus the pending flag of the
     * quiet-period detector that refreshes gecko's env() once the toolbar
     * animation settles. Main thread only.
     */
    private static long lastStripMove;
    private static boolean settleRefreshScheduled;

    /**
     * Arms a check ~220 ms out; while the strip keeps moving the check
     * re-arms itself, so the single requestLayout lands only after the
     * animation is quiet and reads the settled translation in
     * GeckoView's onGlobalLayout.
     */
    private static void scheduleSettleEnvRefresh(final android.view.View strip) {
        if (settleRefreshScheduled) return;
        settleRefreshScheduled = true;
        strip.postDelayed(new Runnable() {
            @Override
            public void run() {
                settleRefreshScheduled = false;
                if (android.os.SystemClock.uptimeMillis() - lastStripMove < 200) {
                    scheduleSettleEnvRefresh(strip);
                    return;
                }
                android.view.SurfaceView gv = findSurfaceView(strip.getRootView());
                if (gv != null) gv.requestLayout();
            }
        }, 220);
    }

    private static final int LIGHT_STATUS_BIT =
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;

    /**
     * Fresh main-thread handler per probe. Never a static field: module
     * classes are instantiated before the process prepares its main looper,
     * so eager creation crashes the whole class init.
     */
    private static android.os.Handler mainHandler() {
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    /** True while our icon color deviates from Firefox's own choice. */
    private static boolean statusIconsModified;
    private static int savedStatusAppearance;

    private static boolean statusSamplerScheduled;
    private static long lastStatusSample;
    private static android.graphics.Bitmap statusSample;
    private static boolean contentIsLight;
    private static boolean contentLightKnown;

    /**
     * One shot of the sampling loop, posted on the strip: fires a luminance
     * probe at most every 120 ms, then re-arms itself - fast enough to follow
     * scrolling, cheap enough to never fight the frame budget. Event hooks
     * poke it with zero delay; the loop also ticks about once a second so
     * page loads that never move the strip still get noticed.
     */
    private static final Runnable STATUS_SAMPLER = new Runnable() {
        @Override
        public void run() {
            statusSamplerScheduled = false;
            android.view.View strip = STRIP_REF.get();
            if (!statusBandActive(strip)) {
                restoreStatusIcons();
                return;
            }
            long idle = android.os.SystemClock.uptimeMillis() - lastStatusSample;
            if (idle >= 120L) {
                sampleStatusBand(strip);
                scheduleStatusSampler(strip, 800L);
            } else {
                scheduleStatusSampler(strip, 120L - idle);
            }
        }
    };

    private static void scheduleStatusSampler(android.view.View strip, long delay) {
        if (statusSamplerScheduled) return;
        statusSamplerScheduled = true;
        strip.postDelayed(STATUS_SAMPLER, delay);
    }

    /**
     * Luminance probe of the status band: a PixelCopy of the page surface
     * (the browser's GeckoView) region behind the status bar, into a small
     * reusable bitmap, strided so the scan touches a few hundred pixels only.
     * Sampling the surface instead of the window keeps the copy local to the
     * page pixels and dodges ROM quirks in the window path.
     */
    private static void sampleStatusBand(final android.view.View strip) {
        try {
            android.view.View content = contentViewOf(strip);
            android.view.SurfaceView page = content == null ? null : findSurfaceView(content);
            int w = strip.getWidth();
            int h = Math.min(statusBarInsetPx(), strip.getRootView().getHeight());
            if (page == null || w <= 0 || h <= 0) return;
            if (page.getHolder() == null || page.getHolder().getSurface() == null
                    || !page.getHolder().getSurface().isValid()) {
                return;
            }
            if (statusSample == null || statusSample.getWidth() != w
                    || statusSample.getHeight() != h) {
                statusSample = android.graphics.Bitmap.createBitmap(w, h,
                        android.graphics.Bitmap.Config.ARGB_8888);
            }
            android.view.PixelCopy.request(page, new android.graphics.Rect(0, 0, w, h),
                    statusSample, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                        @Override
                        public void onPixelCopyFinished(int result) {
                            if (result != android.view.PixelCopy.SUCCESS) return;
                            if (!statusBandActive(strip)) return;
                            applyStatusIcons(strip, bandLuminance(statusSample));
                        }
                    }, mainHandler());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status sample failed: " + t);
        }
    }

    /** First SurfaceView under the given root; in the browser that is GeckoView. */
    private static android.view.SurfaceView findSurfaceView(android.view.View root) {
        if (root instanceof android.view.SurfaceView) return (android.view.SurfaceView) root;
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.SurfaceView found = findSurfaceView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Average luminance 0..255 of the sampled band, stride-scanned. */
    private static int bandLuminance(android.graphics.Bitmap b) {
        long sum = 0L;
        int n = 0;
        int stepX = Math.max(1, b.getWidth() / 24);
        int stepY = Math.max(1, b.getHeight() / 8);
        for (int y = 0; y < b.getHeight(); y += stepY) {
            for (int x = 0; x < b.getWidth(); x += stepX) {
                int p = b.getPixel(x, y);
                sum += ((p >> 16) & 255) * 299L + ((p >> 8) & 255) * 587L + (p & 255) * 114L;
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / (n * 1000L));
    }

    /**
     * Dark icons over light content, light icons over dark. The flip decision
     * uses hysteresis so scrolling near the threshold cannot strobe, but the
     * chosen appearance is re-asserted on every probe: Firefox rewrites the
     * system bar appearance on its own UI events, and a silent skip would
     * leave that stomped value in charge.
     */
    private static void applyStatusIcons(android.view.View strip, int lum) {
        if (!contentLightKnown) {
            contentIsLight = lum > 135;
            contentLightKnown = true;
        } else if (lum > 155) {
            contentIsLight = true;
        } else if (lum < 115) {
            contentIsLight = false;
        } // else: inside the hysteresis band, keep the current decision
        android.view.WindowInsetsController c = strip.getWindowInsetsController();
        if (c == null) return;
        try {
            if (!statusIconsModified) {
                statusIconsModified = true;
                savedStatusAppearance = c.getSystemBarsAppearance();
            }
            c.setSystemBarsAppearance(contentIsLight ? LIGHT_STATUS_BIT : 0, LIGHT_STATUS_BIT);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status appearance failed: " + t);
        }
    }

    /** Hands the icon color back to whatever Firefox chose for its chrome. */
    private static void restoreStatusIcons() {
        android.view.View strip = STRIP_REF.get();
        if (strip != null) {
            strip.removeCallbacks(STATUS_SAMPLER);
            statusSamplerScheduled = false;
        }
        contentLightKnown = false;
        if (!statusIconsModified) return;
        statusIconsModified = false;
        if (strip == null) return;
        android.view.WindowInsetsController c = strip.getWindowInsetsController();
        if (c == null) return;
        try {
            c.setSystemBarsAppearance(savedStatusAppearance, LIGHT_STATUS_BIT);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": status restore failed: " + t);
        }
    }

    /**
     * Drawable that fills only the bottom band of its bounds. Used as the
     * strip background: the bottom padding zone under the toolbar reads as
     * solid surface, while everything above keeps the strip's own chrome -
     * and when the strip expands into the full-screen edit sheet, no opaque
     * backdrop ever covers the page.
     */
    private static final class PadFill extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final int band;

        PadFill(int color, int bandPx) {
            paint.setColor(color);
            band = bandPx;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect r = getBounds();
            int top = Math.max(r.top, r.bottom - band);
            canvas.drawRect(r.left, top, r.right, r.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @SuppressWarnings("deprecation")
        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Live gesture/nav inset from the fragment's activity window, if known. */
    private static int liveBottomInsetOf(Object fragment) {
        try {
            Object activity = fragment.getClass().getMethod("getActivity").invoke(fragment);
            if (!(activity instanceof android.app.Activity)) return 0;
            android.view.Window w = ((android.app.Activity) activity).getWindow();
            if (w == null) return 0;
            android.view.WindowInsets wi = w.getDecorView().getRootWindowInsets();
            return wi == null ? 0 : wi.getSystemWindowInsetBottom();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Status bar height from the platform resource; stable for the process. */
    private static int cachedStatusBarInset = -1;

    private static int statusBarInsetPx() {
        if (cachedStatusBarInset > 0) return cachedStatusBarInset;
        try {
            android.content.res.Resources r = android.content.res.Resources.getSystem();
            int id = r.getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) cachedStatusBarInset = r.getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        if (cachedStatusBarInset < 0) cachedStatusBarInset = 0;
        return cachedStatusBarInset;
    }

    // ------------------------------------------------------------------ utils

    private static Class<?> find(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    private static Object staticField(Class<?> clazz, String field) throws Exception {
        java.lang.reflect.Field f = clazz.getField(field);
        return f.get(null);
    }
}
