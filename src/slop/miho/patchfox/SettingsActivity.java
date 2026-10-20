package slop.miho.patchfox;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Module settings styled after Material 3 Expressive. Plain platform widgets
 * only — the module APK intentionally carries no support libraries — so the MD3
 * tokens (tonal surfaces from Monet roles, 28dp extra-large corners, the
 * title/body/label type scale, 8dp spacing grid) are applied by hand. The
 * switch itself is {@link SwitchM3}.
 */
public class SettingsActivity extends Activity {

    /** Must match the names used by ModulePrefs (read inside the hooked apps). */
    private static final String PREFS = "settings";
    private static final String KEY_MONET = "monet_theme";
    private static final String KEY_COMPACT = "compact_toolbar";
    private static final String KEY_SEE_THROUGH = "see_through_bars";
    private static final String KEY_BARE_TAB_COUNTER = "bare_tab_counter";
    private static final String TAG = "PatchFox";

    // Baseline M3 palette for roles that the framework may not expose (pre-33).
    private static final int F_SURFACE = 0xFFF9F9FF;
    private static final int F_ON_SURFACE = 0xFF1A1C1E;
    private static final int F_ON_SURF_VAR = 0xFF44474E;
    private static final int F_SURF_CONTAINER = 0xFFEEEAF0;

    private SwitchM3 monet;
    private boolean night;
    /** Screen column, kept as a field so the row builder can attach its cards. */
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = prefs();
        if (!prefs.contains(KEY_MONET)) {
            // Make sure the file exists with the defaults so the hooked app can read them.
            prefs.edit().putBoolean(KEY_MONET, true).apply();
        }
        if (!prefs.contains(KEY_COMPACT)) {
            prefs.edit().putBoolean(KEY_COMPACT, true).apply();
        }
        if (!prefs.contains(KEY_SEE_THROUGH)) {
            prefs.edit().putBoolean(KEY_SEE_THROUGH, true).apply();
        }

        Monet.provide(getResources());
        night = Monet.isNight(getResources());

        // Light/dark system bar icons to match the surface tone.
        try {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                int appearance = night ? 0
                        : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } catch (Throwable ignored) {
        }

        // ---------------------------------------------------------- root + insets
        final int pad = dp(16);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(roleOr("surface", F_SURFACE));
        // edge-to-edge (targetSdk 35): keep the content out of the system bars.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(pad, dp(8) + insets.getSystemWindowInsetTop(),
                        pad, pad + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        // ---------------------------------------------------------- header
        TextView title = new TextView(this);
        title.setText("PatchFox");
        title.setTextSize(28f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(roleOr("on_surface", F_ON_SURFACE));
        root.addView(title, lparams());

        TextView subtitle = new TextView(this);
        subtitle.setText("Module settings");
        subtitle.setTextSize(14f);
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle.setLetterSpacing(0.1f);
        subtitle.setTextColor(roleOr("on_surface_variant", F_ON_SURF_VAR));
        subtitle.setPadding(0, dp(2), 0, 0);
        root.addView(subtitle, lparams());

        // ---------------------------------------------------------- switch rows
        // MD3 Expressive settings list, measured on the phone's own Settings app:
        // every row is its own rounded card (corner large-increased, 20dp) in the
        // surface_container role, a 2dp seam between cards, 16dp padding around the
        // content, and the body-large/body-medium type scale.
        monet = addSwitchRow("Monet theme",
                "Recolor the interface to match the system theme (Material You)",
                KEY_MONET, prefs.getBoolean(KEY_MONET, true), dp(24));
        addSwitchRow("Compact address bar",
                "Reduce the address bar height (text size unchanged)",
                KEY_COMPACT, prefs.getBoolean(KEY_COMPACT, true), dp(2));
        addSwitchRow("See-through system bars",
                "Chrome-style: pages show through the transparent system bars",
                KEY_SEE_THROUGH, prefs.getBoolean(KEY_SEE_THROUGH, true), dp(2));
        addSwitchRow("Bare tab counter",
                "Show only the tab count number, no box around it (larger number)",
                KEY_BARE_TAB_COUNTER, prefs.getBoolean(KEY_BARE_TAB_COUNTER, false), dp(2));

        // monet_toggle intent extra: scripted toggling for tests.
        if (getIntent() != null && getIntent().hasExtra("monet_toggle")) {
            boolean initial = getIntent().getBooleanExtra("monet_toggle", true);
            writeFlag(KEY_MONET, initial); // setChecked may be a no-op, persist explicitly
            monet.setChecked(initial, false);
        }

        // ---------------------------------------------------------- footer (pinned to the bottom)
        // Spacer pushes the hint + version block to the bottom of the screen.
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("Changes apply after restarting Firefox.");
        hint.setTextSize(12f);
        hint.setTextColor(roleOr("on_surface_variant", F_ON_SURF_VAR));
        root.addView(hint, lparams());

        TextView version = new TextView(this);
        version.setText("PatchFox 0.5.7");
        version.setTextSize(11f);
        version.setTextColor(roleOr("on_surface_variant", F_ON_SURF_VAR));
        version.setAlpha(0.7f);
        LinearLayout.LayoutParams versionLp = lparams();
        versionLp.topMargin = dp(2);
        root.addView(version, versionLp);

        setContentView(root);
        Log.i(TAG, "activity created, monet_theme=" + prefs().getBoolean(KEY_MONET, true));
    }

    /**
     * One MD3E settings row as its own rounded card (corner large-increased, 20dp,
     * surface_container fill), 16dp of vertical padding around the content and the
     * ripple following the card shape. Two lines: body-large headline, body-medium
     * supporting text, trailing switch centered vertically.
     */
    private SwitchM3 addSwitchRow(String title, String summary,
                                  final String key, boolean checked, int topMargin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dp(20));
        cardBg.setColor(roleOr("surface_container", F_SURF_CONTAINER));
        // Only vertical padding is needed here; the row content carries 16dp insets.
        card.setPadding(0, dp(16), 0, dp(16));
        card.setClickable(true);
        card.setBackground(ripple(roleOr("on_surface", F_ON_SURFACE), dp(20), cardBg));
        LinearLayout.LayoutParams cardLp = lparams();
        cardLp.topMargin = topMargin;
        root.addView(card, cardLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        // Content inset: 16dp from the card edges on both sides.
        texts.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(texts, textsLp);

        TextView rowTitle = new TextView(this);
        rowTitle.setText(title);
        rowTitle.setTextSize(16f);
        // MD3 list headline: body-large, regular weight.
        rowTitle.setTextColor(roleOr("on_surface", F_ON_SURFACE));
        texts.addView(rowTitle, lparams());

        TextView rowSummary = new TextView(this);
        rowSummary.setText(summary);
        rowSummary.setTextSize(14f);
        rowSummary.setTextColor(roleOr("on_surface_variant", F_ON_SURF_VAR));
        rowSummary.setPadding(0, dp(4), 0, 0);
        texts.addView(rowSummary, lparams());

        final SwitchM3 sw = new SwitchM3(this);
        sw.setContentDescription(title);
        FrameLayout switchTouch = new FrameLayout(this);
        switchTouch.setClickable(false);
        switchTouch.setPadding(0, 0, dp(16), 0);
        switchTouch.addView(sw, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        card.addView(switchTouch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        sw.setChecked(checked, false);
        sw.setOnCheckedChangeListener(new SwitchM3.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchM3 view, boolean checked) {
                writeFlag(key, checked);
            }
        });
        return sw;
    }

    /**
     * Card-shaped ripple: MD3 list rows ripple with their container's corner radius
     * (the seam stays clean), drawn on the neutral on-surface state layer.
     */
    private RippleDrawable ripple(int stateColor, int radiusDp, Drawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radiusDp);
        mask.setColor(0xFFFFFFFF);
        int rippleColor = (stateColor & 0x00FFFFFF) | 0x29000000; // ~16% alpha
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /** Persists a flag; PrefsProvider serves it to the hooked apps. */
    private void writeFlag(String key, boolean checked) {
        try {
            boolean ok = prefs().edit().putBoolean(key, checked).commit();
            Log.i(TAG, "commit " + key + "=" + checked + " ok=" + ok
                    + " readback=" + prefs().getBoolean(key, true));
        } catch (Throwable t) {
            Log.e(TAG, "commit failed", t);
        }
    }

    private SharedPreferences prefs() {
        // Plain private storage; the hook side reads it through PrefsProvider IPC.
        // (MODE_WORLD_READABLE writes were silently dropped on this platform, which
        // once left the provider serving stale values from a long-lived process.)
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- helpers

    private int roleOr(String role, int fallback) {
        int v = Monet.role(role, night);
        return v != 0 ? v : fallback;
    }

    private LinearLayout.LayoutParams lparams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
