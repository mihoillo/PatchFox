package slop.miho.patchfox;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Material 3 switch drawn by hand. The module APK cannot ship the Compose or
 * Material libraries, so the component follows the MD3 token spec directly:
 *
 *  - shape-full 52x32dp pill track, 16/24dp thumb (off/on)
 *  - tonal colors from Monet roles (off: surface_container_highest track with a
 *    2dp outline border and outline thumb; on: primary track, on-primary thumb,
 *    no border)
 *  - Expressive motion: pressed state grows the track and the thumb with a
 *    spring-like overshoot; the checked slide uses the emphasized decelerate
 *    curve from the MD3 motion spec.
 */
public class SwitchM3 extends View {

    /** Not reusing CompoundButton's listener type since this is not a CompoundButton. */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchM3 view, boolean checked);
    }

    private static final float TRACK_W = 52f;
    private static final float TRACK_H = 32f;
    private static final float TRACK_W_PRESSED = 64f;
    private static final float THUMB_OFF = 16f;
    private static final float THUMB_ON = 24f;
    private static final long MOVE_MS = 350;
    private static final long PRESS_MS = 150;
    private static final long RELEASE_MS = 450;

    private static final OvershootInterpolator SPRING = new OvershootInterpolator(1.1f);
    // MD3 emphasized decelerate easing.
    private static final PathInterpolator EMPHASIZED_DECELERATE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    // Baseline M3 fallbacks for roles missing on older frameworks (pre-33 has no
    // surface_container_* system resources).
    private static final int FALLBACK_TRACK_OFF = 0xFFE6E0E9;  // surface_container_highest-ish
    private static final int FALLBACK_THUMB_OFF = 0xFF74757F;  // outline-ish
    private static final int FALLBACK_PRIMARY = 0xFF6750A4;
    private static final int FALLBACK_ON_PRIMARY = 0xFFFFFFFF;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private OnCheckedChangeListener listener;
    private boolean checked;

    /** 0=off .. 1=on (thumb position/scale blend). */
    private float pos;
    /** 0=rest .. 1=pressed (track+thumb grow). */
    private float press;
    private float trackW = TRACK_W;

    private ValueAnimator posAnim;
    private ValueAnimator pressAnim;

    public SwitchM3(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setContentDescription("Switch");
    }

    public void setChecked(boolean value, boolean animate) {
        if (checked == value) return;
        checked = value;
        if (animate) {
            animatePos(value ? 1f : 0f);
        } else {
            pos = value ? 1f : 0f;
            invalidate();
        }
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    public void toggle() {
        setChecked(!checked, true);
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    // ---------------------------------------------------------------- measuring

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // A few dp of headroom so the 2dp outline stroke (drawn centered on the
        // track edge) and the pressed track growth never touch the view bounds —
        // the settings rows wrap this view tightly, with no extra height reserved.
        int w = (int) Math.ceil(dp(TRACK_W_PRESSED + 4));
        int h = (int) Math.ceil(dp(TRACK_H + 4));
        setMeasuredDimension(
                Math.max(w, getSuggestedMinimumWidth()),
                Math.max(h, getSuggestedMinimumHeight()));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        boolean night = Monet.isNight(getResources());
        float move = pos;

        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float halfW = dp(trackW) / 2f;
        final float halfH = dp(TRACK_H) / 2f;

        int trackFrom = role("surface_container_highest", night, FALLBACK_TRACK_OFF);
        int trackTo = role("primary", night, FALLBACK_PRIMARY);
        int thumbFrom = role("outline", night, FALLBACK_THUMB_OFF);
        int thumbTo = role("on_primary", night, FALLBACK_ON_PRIMARY);

        // Track: pill with a 2dp outline border that fades out as it turns on.
        rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(trackFrom, trackTo, move));
        float r = halfH;
        canvas.drawRoundRect(rect, r, r, paint);
        float borderAlpha = 1f - move;
        if (borderAlpha > 0.02f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(withAlpha(thumbFrom, (int) (borderAlpha * 255)));
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // Thumb: grows 16 -> 24 with position, extra grow while pressed.
        float rOff = dp(THUMB_OFF) / 2f;
        float rOn = dp(THUMB_ON) / 2f;
        float inOff = halfH - rOff;
        float inOn = halfH - rOn;
        float xFrom = cx - halfW + inOff + rOff;
        float xTo = cx + halfW - inOn - rOn;
        float x = lerp(xFrom, xTo, move);
        float rad = lerp(rOff, rOn, move) * (1f + 0.30f * press);

        paint.setColor(blend(thumbFrom, thumbTo, move));
        canvas.drawCircle(x, cy, rad, paint);
    }

    private int role(String name, boolean night, int fallback) {
        int v = Monet.role(name, night);
        return v != 0 ? v : fallback;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animatePress(1f, PRESS_MS);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animatePress(0f, RELEASE_MS);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        toggle();
        return true;
    }

    // ---------------------------------------------------------------- animators

    private void animatePos(float target) {
        if (posAnim != null) posAnim.cancel();
        posAnim = ValueAnimator.ofFloat(pos, target);
        posAnim.setDuration(MOVE_MS);
        posAnim.setInterpolator(EMPHASIZED_DECELERATE);
        posAnim.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            invalidate();
        });
        posAnim.start();
    }

    private void animatePress(float target, long ms) {
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(press, target);
        pressAnim.setDuration(ms);
        pressAnim.setInterpolator(SPRING);
        pressAnim.addUpdateListener(a -> {
            press = (Float) a.getAnimatedValue();
            trackW = lerp(TRACK_W, TRACK_W_PRESSED, press);
            invalidate();
        });
        pressAnim.start();
    }

    // ---------------------------------------------------------------- utils

    private float dp(float v) {
        return v * density;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int blend(int from, int to, float t) {
        if (t <= 0f) return from;
        if (t >= 1f) return to;
        int a = Math.round(lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t));
        int r = Math.round(lerp((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, t));
        int g = Math.round(lerp((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, t));
        int b = Math.round(lerp(from & 0xFF, to & 0xFF, t));
        return Color.argb(a, r, g, b);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
