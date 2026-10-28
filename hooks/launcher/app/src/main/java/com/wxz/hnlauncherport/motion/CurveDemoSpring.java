package com.wxz.hnlauncherport.motion;

/**
 * CurveDemo (UWP, ~/Downloads/124/CurveDemo-1.3.55) analytic spring,
 * slot-0 curve ("轻快") with the default duration, ported onto honor's
 * SpringInterpolator:
 *
 *   w = 4*PI / T  (T = 1.1s, TransitionDurationTime default 1.0)
 *   k = w^2, c = 2*zeta*w
 *   (honor SpringModelBase: x'' + c*x' + k*x = 0, m = 1)
 *
 * Per-axis zeta (slot-0; rotation excluded from the port):
 *   X / Y / Scale   0.82
 *   Width / Height  0.9
 *   Corner          1.0 (CurveDemo CornerRadiusDamping default)
 *   Opacity         1.0 (begin 0.1T / duration 0.3T, not spring-ported)
 * Launch v0 = 0; gesture-handoff v0 is supplied by the caller.
 * Stop criterion = SpringInterpolator's own valueThreshold duration
 * search, equivalent to CurveDemo GetSpringAnimationVisibleTick(0.001).
 */
public final class CurveDemoSpring {

    public static final float T_SECONDS = 0.85f;
    public static final float W = (float) (4.0 * Math.PI / T_SECONDS);
    public static final float ZETA_XY = 0.86f;
    public static final float ZETA_SCALE = 0.86f;
    public static final float ZETA_WH = 0.9f;
    public static final float ZETA_CORNER = 1.0f;

    // Gesture-handoff transfer (MainPage.xaml.cs:2384-2386): finger speed
    // in UWP DIP/ms divided by 1e4, result in value-per-tick (1 tick = 1e-7
    // s). Converting DIP/ms -> px/s and per-tick -> per-second cancels the
    // 1e4 * 1e3 vs 1e7 pair, leaving the px/s form below.
    public static final float FAR_POINT = 0.2f;
    public static final float FLY_FAR = 5.0f;
    public static final float TRANSITION_DURATION = 1.0f;

    /**
     * Scale-spring v0 close-handoff (CurveDemo slot-0, MainPage.xaml.cs:2386):
     *   RvScale = vy * (1 - FarPoint) / (screenHeight * scaleCur) / 30
     *             * FlyFar / sqrt(TransitionDuration)
     * vy = vertical finger speed in px/s, scaleCur = current visual scale.
     */
    public static float scaleV0(float vyPxPerSec, float screenSpan, float scaleCur) {
        float s = Math.max(scaleCur, 0.05f);
        return (float) (Math.abs(vyPxPerSec) * (1.0 - FAR_POINT)
                / (screenSpan * s) / 30.0 * FLY_FAR
                / Math.sqrt(TRANSITION_DURATION));
    }

    /**
     * Translation-spring v0 close-handoff (MainPage.xaml.cs:2384-2385):
     *   RvX = vx * FlyFar / (20 * sqrt(T))            (= vx * 0.25 at defaults)
     *   RvY = vy * (0.5 - F) / (1 - F) / 30 * FlyFar / sqrt(T)
     *                                             (= vy * 0.0625 at defaults)
     * Signed: follows the finger direction, any endPos sign.
     */
    public static float transV0(float vPxPerSec, boolean isX) {
        double t = Math.sqrt(TRANSITION_DURATION);
        double v = vPxPerSec;
        return (float) (isX
                ? v * FLY_FAR / (20.0 * t)
                : v * (0.5 - FAR_POINT) / (1.0 - FAR_POINT) / 30.0 * FLY_FAR / t * 1.3);
    }

    /**
     * CD isLaunching=false (StartWindowAnimation isOnLaunching==0),
     * target=-1 fade (MainPage.xaml.cs:1283+, slot-0): opacity begins at
     * 0.1*TDT and springs (OpacityDamping 1.0, critically damped) 1 -> 0,
     * frame application :425. Per tuning the fade is extended to the
     * animation end t = T_SECONDS (0.9s): the spring spans
     * [0.1*TDT, T_SECONDS] and settles exactly at the end.
     */
    public static final float OPACITY_BEGIN_S = 0.1f * TRANSITION_DURATION;
    public static final float OPACITY_DURATION_S = T_SECONDS - OPACITY_BEGIN_S;

    /** CD target=-1 fade: zeta-1.0 spring 1 -> 0 after OPACITY_BEGIN_S. */
    public static float fadeOutAlpha(float elapsedSec) {
        float t = elapsedSec - OPACITY_BEGIN_S;
        if (t <= 0f) {
            return 1f;
        }
        double w = 4.0 * Math.PI / OPACITY_DURATION_S;
        double v = (1.0 + w * t) * Math.exp(-w * t);
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    public static float stiffness() {
        return W * W;
    }

    public static float damping(float zeta) {
        return 2.0f * zeta * W;
    }

    public static Object make(ClassLoader cl, float zeta, float endPos,
            float v0, float minVisibleChange) {
        return SpringFactory.make(cl, stiffness(), damping(zeta),
                endPos, v0, minVisibleChange);
    }

    private CurveDemoSpring() {}
}
