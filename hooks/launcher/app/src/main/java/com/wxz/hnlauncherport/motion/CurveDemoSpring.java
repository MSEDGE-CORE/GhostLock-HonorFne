package com.wxz.hnlauncherport.motion;

import com.wxz.hnlauncherport.Port;

/**
 * CurveDemo (UWP, ~/Downloads/124/CurveDemo-1.3.55) analytic spring,
 * slot-0 curve ("轻快") with the default duration, ported onto honor's
 * SpringInterpolator:
 *
 *   w = 4*PI / T  (T = 回桌分支基线: 轻快 1.1s / 漂浮 1.0s /
 *                  自定义 = 时长滑条; MainPage.xaml.cs:1207/:1220/:1232,
 *                  TransitionDurationTime default 1.0)
 *   k = w^2, c = 2*zeta*w
 *   (honor SpringModelBase: x'' + c*x' + k*x = 0, m = 1)
 *
 * Per-axis zeta (slot-0; rotation excluded from the port):
 *   X / Y / Scale   0.86
 *   Width / Height  0.86
 *   Corner          1.0 (CurveDemo CornerRadiusDamping default)
 *   Opacity         1.0 (begin 0.1T / duration 0.3T, not spring-ported)
 * Launch v0 = 0; gesture-handoff v0 is supplied by the caller.
 * Stop criterion = SpringInterpolator's own valueThreshold duration
 * search, equivalent to CurveDemo GetSpringAnimationVisibleTick(0.001).
 */
public final class CurveDemoSpring {

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

    /**
     * CD 回桌分支 (RvScale != 0, MainPage.xaml.cs:1207/:1220/:1232) 的
     * durationTick 基线: CurveStyle 0 (轻快) = 1.1 x TDT, CurveStyle 1
     * (漂浮) = 1.0 x TDT, CurveStyle 2 (自定义) = DurationTime x TDT
     * (滑条即 T)。
     */
    private static float baseT() {
        switch (style()) {
            case 0:
                return 1.1f;
            case 1:
                return 1.0f;
            default:
                return 1.0f;
        }
    }

    /** T = 风格基线 (1.1/1.0) x 时长系数 (0.8-1.5, 默认 1.0)。path 开即生效。 */
    public static float tSeconds() {
        return baseT() * (Port.on(Port.KEY_MOTION_PATH)
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

    /**
     * 惯性系数 (0-10, 默认 1): 乘在 transV0/scaleV0 上。仅 "自定义弹簧"
     * 风格生效 (CD XVelocity/YVelocity/ScaleVelocity, MainPage.xaml.cs:
     * 1073-1075, 仅 CurveStyle==2); 轻快/漂浮恒 1。
     */
    public static float inertia() {
        return custom() ? Port.getFloat(Port.KEY_MP_INERTIA, 1.0f) : 1.0f;
    }

    /**
     * 回桌动画统一时长 (ms): 900 基线 x 时长系数 (随滑条, CD 定长语义,
     * 与目标/速度无关; 弹簧按 fraction 求值, 变长只整体重定时).
     */
    public static long homeAnimDurationMs() {
        return Math.round(900f * (Port.on(Port.KEY_MOTION_PATH)
                ? Port.getFloat(Port.KEY_MP_DURATION, 1.0f) : 1.0f));
    }

    /**
     * 背景层 (workspace/hotseat) 回桌时长 (ms): 800ms 基线 x 时长系数
     * (path 开), 与前景窗口层的 max-settle 时长解耦 - getScaleDuration /
     * getStartOffset 按比例重映射, 背景不再被 MotionDur 的 max settle
     * 拉长.
     */
    public static long bgAnimDurationMs() {
        return Math.round(800f * (Port.on(Port.KEY_MOTION_PATH)
                ? Port.getFloat(Port.KEY_MP_DURATION, 1.0f) : 1.0f));
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
        return (float) (Math.abs(vyPxPerSec) * (1.0 - FAR_POINT) * 8.0
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
                ? v * 3.0 * FLY_FAR / (20.0 * t)
                : v * 6.0 * (0.5 - FAR_POINT) / (1.0 - FAR_POINT) / 30.0 * FLY_FAR / t * 1.3) * inertia();
    }

    /**
     * CD target=-1 (无图标) fade (MainPage.xaml.cs, RvScale != 0 分支):
     * opacity 在 0.1*coeff 后开始, 淡出窗 0.6*coeff (per tuning),
     * OpacityDamping 1.0 临界阻尼 1 -> 0 (帧应用 :425):
     *   alpha(t) = (1 + w t) e^{-w t},  w = 4*PI / (0.6*coeff)。
     */
    public static final float OPACITY_BEGIN_S = 0.1f * TRANSITION_DURATION;

    public static float fadeOutAlpha(float elapsedSec) {
        float coeff = Port.on(Port.KEY_MOTION_PATH)
                ? Port.getFloat(Port.KEY_MP_DURATION, 1.0f) : 1.0f;
        float t = elapsedSec - 0.1f * coeff;
        if (t <= 0f) {
            return 1f;
        }
        double dur = Math.max(0.05, 0.5 * coeff);
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
     * MainPage.xaml.cs:154-200, 0.1% threshold), ANY damping regime.
     * CD 欠阻尼分支算出含 v0 的包络 A0 后直接丢弃 ("为了简洁, 直接返回
     * tVisible"): settle = -ln(0.001)/(zeta*w) 是与振幅/速度无关的常量
     * (zeta 0.86, T 0.85 -> 543ms), 早松手大 v0 不会拉长时长; 之前的物理
     * 包络版 (ln(amp/eps)/rate) 会随 v0 涨到 1.4s+ - 非 CD 语义.
     */
    public static float springSettle(float from, float to,
            float zeta, float v0) {
        float a = from - to;
        if (Math.abs(a) < 1.0e-12f && Math.abs(v0) < 1.0e-12f) {
            return 0f;
        }
        double ln = Math.log(1000.0);
        double zz = (double) zeta * zeta;
        double t;
        if (zz < 1.0) {
            double dampingW = (double) zeta * omega();
            if (dampingW < 1.0e-12) {
                return tSeconds() * 10f;
            }
            t = ln / dampingW;
        } else {
            double effectiveW = zz > 1.0001
                    ? omega() * (zeta - Math.sqrt(zz - 1.0))
                    : omega();
            if (effectiveW < 1.0e-12) {
                return tSeconds() * 2f;
            }
            t = ln / effectiveW * 1.5;
        }
        return (float) Math.max(t, 0.05);
    }

    public static Object make(ClassLoader cl, float zeta, float endPos,
            float v0, float minVisibleChange) {
        return SpringFactory.make(cl, stiffness(), damping(zeta),
                endPos, v0, minVisibleChange);
    }

    private CurveDemoSpring() {}
}
