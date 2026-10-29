package com.wxz.hnlauncherport.motion;

import com.wxz.hnlauncherport.Port;

/**
 * CurveDemo (UWP, ~/Downloads/124/CurveDemo-1.3.55) analytic spring,
 * slot-0 curve ("轻快") with the default duration, ported onto honor's
 * SpringInterpolator:
 *
 *   w = 4*PI / T  (T = 0.85s, TransitionDurationTime default 1.0)
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
    public static final float ZETA_WH = 0.86f;
    public static final float ZETA_CORNER = 1.0f;

    // ---- 曲线风格 (MotionPathDialog radio, Settings.Global) ----

    /** 0 = 轻快 (path 默认), 1 = 漂浮, 2 = 自定义弹簧 (劲度 = path 关). */
    public static int style() {
        return Math.max(0, Math.min(2, Port.getInt(Port.KEY_MP_STYLE, 0)));
    }

    /** 滑条参数 (KEY_MP_*) 只在 "自定义弹簧" 风格下生效, 预设用内置默认. */
    private static boolean custom() {
        return style() == 2;
    }

    // ---- 曲线自定义 (仅 "自定义弹簧" 风格, MotionPathDialog, Settings.Global) ----

    /** T = 基线时长 * 时长系数 (0.8-1.5, 默认 1.0)。path 开即生效 (含预设)。 */
    public static float tSeconds() {
        return T_SECONDS * (Port.on(Port.KEY_MOTION_PATH)
                ? Port.getFloat(Port.KEY_MP_DURATION, 1.0f) : 1.0f);
    }

    /** w = 4*PI / T, 随时长系数动态变化。 */
    public static float omega() {
        return (float) (4.0 * Math.PI / tSeconds());
    }

    private static float clampZeta(float z) {
        return Math.max(0.5f, Math.min(1.5f, z));
    }

    public static float zetaXY() {
        return custom() ? clampZeta(Port.getFloat(Port.KEY_MP_ZETA_XY, ZETA_XY)) : ZETA_XY;
    }

    public static float zetaScale() {
        return custom() ? clampZeta(Port.getFloat(Port.KEY_MP_ZETA_SCALE, ZETA_SCALE)) : ZETA_SCALE;
    }

    public static float zetaWH() {
        return custom() ? clampZeta(Port.getFloat(Port.KEY_MP_ZETA_WH, ZETA_WH)) : ZETA_WH;
    }

    /**
     * 漂浮风格 (CD CurveStyle==1, MainPage.xaml.cs:1223/:1367):
     * ScaleDamping = 1.0 - ToY / ActualHeight * 0.3 - scale 弹簧 zeta 随
     * 目标 Y 坐标插值: 目标在顶部 (ToY=0) -> zeta 1.0 无过冲, 在底部 ->
     * zeta 0.7 漂浮回弹。其余风格退回 zetaScale()。
     */
    public static float zetaScaleTargeted(float targetTopY, float screenH) {
        if (style() == 1 && screenH > 1f) {
            return clampZeta(1.0f - (targetTopY / screenH) * 0.3f);
        }
        return zetaScale();
    }

    /** 惯性系数 (0-10, 默认 1): 乘在 transV0/scaleV0 上。path 开即生效 (含预设)。 */
    public static float inertia() {
        return Port.on(Port.KEY_MOTION_PATH)
                ? Port.getFloat(Port.KEY_MP_INERTIA, 1.0f) : 1.0f;
    }

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
                / Math.sqrt(TRANSITION_DURATION)) * inertia();
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
                : v * (0.5 - FAR_POINT) / (1.0 - FAR_POINT) / 30.0 * FLY_FAR / t * 1.3) * inertia();
    }

    /**
     * CD isLaunching=false (StartWindowAnimation isOnLaunching==0),
     * target=-1 fade (MainPage.xaml.cs:1283+, slot-0): opacity begins at
     * 0.1*TDT and springs (OpacityDamping 1.0, critically damped) 1 -> 0,
     * frame application :425. Per tuning the fade is extended to the
     * animation end t = T_SECONDS (0.85s): the spring spans
     * [0.1*TDT, T_SECONDS] and settles exactly at the end.
     */
    public static final float OPACITY_BEGIN_S = 0.1f * TRANSITION_DURATION;

    /** CD target=-1 fade: zeta-1.0 spring 1 -> 0 after OPACITY_BEGIN_S. */
    public static float fadeOutAlpha(float elapsedSec) {
        float t = elapsedSec - OPACITY_BEGIN_S;
        if (t <= 0f) {
            return 1f;
        }
        double dur = Math.max(0.05f, tSeconds() - OPACITY_BEGIN_S);
        double w = 4.0 * Math.PI / dur;
        double v = (1.0 + w * t) * Math.exp(-w * t);
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    public static float stiffness() {
        return omega() * omega();
    }

    /**
     * Damping ratio fed to the HOST SpringInterpolator. The host math
     * (SpringModelBase) only handles the under-damped regime
     * (sqrt(1 - zeta^2) -> NaN at zeta >= 1), so clamp just below
     * critical: zeta >= 1 means "no overshoot", which ~critical
     * damping delivers on the host path.
     */
    public static float damping(float zeta) {
        return 2.0f * Math.min(zeta, 0.995f) * omega();
    }

    /**
     * Closed-form spring value for ANY damping regime (our own eval,
     * e.g. ScaleComposer): under-damped / critically-damped /
     * over-damped. Same basis as CurveDemo SpringAnimationInterpolator.
     */
    public static float springValue(float t, float from, float to,
            float zeta, float v0) {
        float omega = omega();
        float a = from - to;
        double zz = (double) zeta * zeta;
        if (zz < 1.0) {
            // under-damped
            float omegaD = omega * (float) Math.sqrt(1.0 - zz);
            float b = (v0 + zeta * omega * a) / omegaD;
            double decay = Math.exp(-zeta * omega * t);
            return (float) (to + decay * (a * Math.cos(omegaD * t)
                    + b * Math.sin(omegaD * t)));
        }
        if (zz <= 1.0001) {
            // critically damped: x = to + e^{-w t} (a + (v0 + w a) t)
            double decay = Math.exp(-omega * t);
            return (float) (to + decay * (a + (v0 + omega * a) * t));
        }
        // over-damped: roots r1/r2 = -w (zeta -/+ sqrt(zeta^2 - 1))
        double s = Math.sqrt(zz - 1.0);
        double r1 = -omega * (zeta - s);
        double r2 = -omega * (zeta + s);
        double c1 = (v0 - a * r2) / (r1 - r2);
        double c2 = a - c1;
        return (float) (to + c1 * Math.exp(r1 * t) + c2 * Math.exp(r2 * t));
    }

    /**
     * Visible-settle estimate (CurveDemo GetSpringAnimationVisibleTick,
     * 0.1% threshold) for ANY damping regime.
     */
    public static float springSettle(float from, float to,
            float zeta, float v0) {
        float omega = omega();
        float a = from - to;
        if (Math.abs(a) < 1.0e-4f && Math.abs(v0) < 1.0e-4f) {
            return 0f;
        }
        double eps = 0.001 * Math.max(Math.abs(a), 0.01);
        double zz = (double) zeta * zeta;
        double amp;
        double rate;
        if (zz < 1.0) {
            float omegaD = omega * (float) Math.sqrt(1.0 - zz);
            float b = (v0 + zeta * omega * a) / omegaD;
            amp = Math.sqrt((double) a * a + (double) b * b);
            rate = (double) zeta * omega;
        } else if (zz <= 1.0001) {
            amp = Math.max(Math.abs(a), Math.abs(v0 + omega * a) / (double) omega);
            rate = omega;
        } else {
            double s = Math.sqrt(zz - 1.0);
            double r1 = -omega * (zeta - s);
            double r2 = -omega * (zeta + s);
            double c1 = (v0 - a * r2) / (r1 - r2);
            double c2 = a - c1;
            amp = Math.max(Math.abs(c1), Math.abs(c2));
            rate = -r1; // slow root governs the envelope
        }
        double t = Math.log(amp / eps) / rate;
        return (float) Math.max(t, 0.05);
    }

    public static Object make(ClassLoader cl, float zeta, float endPos,
            float v0, float minVisibleChange) {
        return SpringFactory.make(cl, stiffness(), damping(zeta),
                endPos, v0, minVisibleChange);
    }

    private CurveDemoSpring() {}
}
