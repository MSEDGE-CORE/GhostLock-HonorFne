package com.wxz.hnlauncherport.motion;

/**
 * CurveDemo swipe-home scale composition (MainPage.xaml.cs:1126-1151 icon
 * init, :428-429 frame application): three INDEPENDENT analytic springs
 * evaluated per frame and multiplied at the screen-application step:
 *
 *   host_scaleX(t) = cdWidth(t)  / screenW * cdScale(t)
 *   host_scaleY(t) = cdHeight(t) / screenH * cdScale(t)
 *
 * During the hand swipe Width/Height hold the full-screen size (W/H ratio
 * constant); after release they spring (zeta 0.9, v0 = VWidth/VHeight = 0,
 * both never assigned in CurveDemo) to the aspect-corrected rect, while
 * Scale (zeta 0.82, v0 = RvScale from the finger speed) carries the whole
 * shrink plus the velocity kick.
 *
 * Target split follows the CD icon init, case A/B by target-vs-screen
 * aspect (MainPage.xaml.cs:1126):
 *   A (targetW/targetH <= screenW/screenH):
 *       W: screenW -> targetAspect * screenH
 *       H: screenH -> screenH                 (no motion)
 *       S: s0     -> targetH / screenH
 *   B:
 *       W: screenW -> screenW                 (no motion)
 *       H: screenH -> (targetH/targetW) * screenW
 *       S: s0     -> targetW / screenW
 * Start state: composed(0) = actual per-axis release scales (s0x, s0y) -
 * see the ctor comment; the end state is the exact per-axis target scale
 * (case B): sx_end = targetW/screenW, sy_end = targetH/screenH.
 *
 * Time base: the composer is evaluated in NORMALIZED time x in [0, 1];
 * t = x * settleSec, so every spring is exactly settled at the last frame
 * (the animator duration comes from the trans springs' settle, ~771 ms).
 */
public final class ScaleComposer {

    private final float screenW;
    private final float screenH;
    private final boolean caseB;

    private final float wFrom;
    private final float wTo;
    private final float hFrom;
    private final float hTo;
    private final float sFrom;
    private final float sTo;
    private final float v0S;

    private final float settleSec;

    // Per-frame stash, consumed by the getScaleAndFasterValue hook.
    private boolean hasStash;
    private float lastSx;
    private float lastSy;
    private float lastProgress;

    public ScaleComposer(float targetW, float targetH,
            float screenW, float screenH, float s0x, float s0y, float scaleV0) {
        this.screenW = screenW;
        this.screenH = screenH;
        float targetAspect = targetW / Math.max(targetH, 1.0f);
        float screenAspect = screenW / screenH;
        this.caseB = targetAspect > screenAspect;
        this.sFrom = clamp(s0y, 0.05f, 1.0f);
        // Release-state W/H start (CD StartWindowSpringAnimation :245+,
        // From *= current): a release can land while the window is STILL
        // DEFORMED - the interrupted-open case (the open anim springs W/H
        // back toward screen aspect and a new swipe-up releases before it
        // finishes, s0x != s0y). Hard-coding wFrom = screenW forces the
        // composed(0) to a uniform SCREEN-ASPECT scale (sx = sy = s0y) -
        // the window flashes to screen aspect at release. Decompose the
        // actual per-axis release ratios instead: Scale carries s0y (the
        // H-basis, RvScale's scaleCur), so hFrom stays screenH and wFrom
        // = s0x/s0y * screenW. Normal live-swipe release (s0x == s0y,
        // screen aspect kept during the swipe) is unchanged.
        float ratioX = clamp(s0x / this.sFrom, 0.2f, 5.0f);
        this.wFrom = ratioX * screenW;
        this.hFrom = screenH;
        if (caseB) {
            this.wTo = screenW;
            this.hTo = (targetH / Math.max(targetW, 1.0f)) * screenW;
            this.sTo = targetW / screenW;
        } else {
            this.wTo = targetAspect * screenH;
            this.hTo = screenH;
            this.sTo = targetH / screenH;
        }
        this.v0S = scaleV0;
        float sw = settleOf(this.wFrom, this.wTo, CurveDemoSpring.ZETA_WH, 0f);
        float sh = settleOf(this.hFrom, this.hTo, CurveDemoSpring.ZETA_WH, 0f);
        float ss = settleOf(this.sFrom, this.sTo, CurveDemoSpring.ZETA_SCALE, scaleV0);
        this.settleSec = Math.max(sw, Math.max(sh, ss));
    }

    /**
     * Evaluates the three springs at normalized time x (already un-warped
     * from the host piecewise input) and stashes the composed per-axis
     * scales. Returns the normalized Scale progress (0..1) for consumers
     * that only need a monotonic fraction (RoundImageView progress etc.).
     */
    public float eval(float x) {
        float xn = clamp(x, 0f, 1f);
        float t = xn * settleSec;
        float cdW = underDamped(t, wFrom, wTo, CurveDemoSpring.ZETA_WH, 0f);
        float cdH = underDamped(t, hFrom, hTo, CurveDemoSpring.ZETA_WH, 0f);
        float cdS = underDamped(t, sFrom, sTo, CurveDemoSpring.ZETA_SCALE, v0S);
        // CD min-scale soft floor (MainPage.xaml.cs:412-415): the Scale
        // spring can undershoot (big RvScale, underdamped) below
        // minScale = min(FromScale, ToScale); CD decays it with a rational
        // asymptote s' = minScale - a*(1 - 1/(|s-minScale|/a + 1)), a =
        // minScale - continuous at minScale, asymptotic floor 0, never
        // negative (a negative composed scale would mirror the window).
        float minScale = Math.min(sFrom, sTo);
        if (cdS < minScale) {
            double a = minScale;
            cdS = (float) (minScale - a * (1.0
                    - 1.0 / (Math.abs(cdS - minScale) / a + 1.0)));
        }
        lastSx = (cdW / screenW) * cdS;
        lastSy = (cdH / screenH) * cdS;
        // Progress = the composed Y-scale travel fraction, NOT the bare S
        // spring. The card consumer (observer) never reads the stash
        // {sx, sy} - it lerps source -> target MultiAnimationObj by this
        // fraction (HSV2:1389-1393). For a wide card (case B) the visual
        // Y travel is dominated by the HEIGHT spring (h 2400 -> 531) while
        // the S spring only spans sFrom -> targetW/screenW (a WIDTH-derived
        // reference): an S-only progress both mis-shapes the card and
        // reverses it when S undershoots below sTo (fraction > 1 -> the
        // lerp overshoots past the target and plays backward). The composed
        // sy travel (cdH x cdS) is monotonic (H's shrink dominates S's
        // dip); case A (H at rest) degenerates algebraically to the S
        // fraction, so icons are unchanged. Residual late wiggle is removed
        // by the monotonic [0,1] clamp (the host itself clamps its own
        // f10 = min(interpolation, 1.0), HSV2:1387 - a lerp fraction).
        float syStart = (hFrom / screenH) * sFrom;
        float syEnd = (hTo / screenH) * sTo;
        float spanY = syStart - syEnd;
        float raw = Math.abs(spanY) < 1.0e-4f
                ? 1f : (syStart - lastSy) / spanY;
        lastProgress = Math.max(lastProgress, clamp(raw, 0f, 1f));
        hasStash = true;
        return lastProgress;
    }

    public boolean hasStash() {
        return hasStash;
    }

    /** True when the last evaluated frame was already at/near the settle. */
    public boolean atEnd() {
        return lastProgress >= 0.999f;
    }

    /**
     * getScaleAndFasterValue replacement. Host output semantics (portrait
     * branch, f2 = screen H/W > 1 - the pipeline space is always device
     * portrait, HnLauncherSwipeHandlerV2:3360-3380): [0] derives from
     * obj.scaleX (WIDTH ratio) and the consumers apply it to BOTH view
     * axes (view.setScaleX/Y(f8), :1455-1456, matrix preScale :2716-2726);
     * [1] derives from obj.scaleY (HEIGHT ratio) and feeds only the icon
     * sub-visual (setProgressAndIconSy). Therefore {sx, sy} - NOT the
     * method's own variable names (scaleY2, scaleX), which are swapped.
     */
    public float[] stashResult() {
        return new float[]{lastSx, lastSy};
    }

    public float settleMillis() {
        return settleSec * 1000f;
    }

    public String describe() {
        return (caseB ? "B" : "A")
                + " w" + wFrom + "->" + wTo
                + " h" + hFrom + "->" + hTo
                + " s" + sFrom + "->" + sTo
                + " v0S=" + v0S
                + " settle=" + (int) settleMillis() + "ms";
    }

    /**
     * Inverse of the host nonLinearTimePiecewise(f, 0.4, 1.7) time warp
     * (HnLauncherSwipeHandlerV2:333-335, defaults; scene overrides not
     * expected on this device). The warp is applied to the animator
     * fraction BEFORE SpringInterpolator.getInterpolation, so the composed
     * springs un-warp it first to recover the linear time CurveDemo uses.
     */
    public static float inversePiecewise(float y) {
        final double b = 0.4;
        final double base = 1.7;
        final double logBase = Math.log(base);
        final double l = logBase + 1.0e-8; // sOffset default
        final double d = b + (Math.pow(base, 1.0 - b) - 1.0) / l;
        double xp = y * d;
        double x = xp <= b
                ? xp
                : b + Math.log(1.0 + (xp - b) * l) / logBase;
        return (float) clamp(x, 0.0, 1.0);
    }

    /**
     * Under-damped analytic spring (CurveDemo SpringAnimationInterpolator,
     * MainPage.xaml.cs:120-151): same omega/zeta basis as CurveDemoSpring,
     * closed form evaluated directly in value space (from -> to).
     */
    private static float underDamped(float t, float from, float to,
            float zeta, float v0) {
        float omega = CurveDemoSpring.W;
        float omegaD = omega * (float) Math.sqrt(1.0 - zeta * zeta);
        float a = from - to;
        float b = (v0 + zeta * omega * a) / omegaD;
        double decay = Math.exp(-zeta * omega * t);
        return (float) (to + decay * (a * Math.cos(omegaD * t)
                + b * Math.sin(omegaD * t)));
    }

    /**
     * Visible-settle estimate (CurveDemo GetSpringAnimationVisibleTick,
     * 0.1% threshold): envelope decay time, per spring.
     */
    private static float settleOf(float from, float to, float zeta, float v0) {
        float omega = CurveDemoSpring.W;
        float omegaD = omega * (float) Math.sqrt(1.0 - zeta * zeta);
        float a = from - to;
        if (Math.abs(a) < 1.0e-4f && Math.abs(v0) < 1.0e-4f) {
            return 0f;
        }
        float b = (v0 + zeta * omega * a) / omegaD;
        double amp = Math.sqrt(a * a + b * b);
        double eps = 0.001 * Math.max(Math.abs(a), 0.01);
        double t = Math.log(amp / eps) / (zeta * omega);
        return (float) Math.max(t, 0.05);
    }

    private static float clamp(double v, double lo, double hi) {
        return (float) (v < lo ? lo : (v > hi ? hi : v));
    }
}
