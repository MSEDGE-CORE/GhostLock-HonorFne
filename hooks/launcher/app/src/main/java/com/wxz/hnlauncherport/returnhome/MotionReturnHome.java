package com.wxz.hnlauncherport.returnhome;

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;

import android.view.View;
import android.view.animation.Interpolator;

import com.wxz.hnlauncherport.Port;
import com.wxz.hnlauncherport.motion.MotionParams;
import com.wxz.hnlauncherport.motion.SpringFactory;
import com.wxz.hnlauncherport.swipe.WindowFollowScale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * MotionEngine v0.9 for HnLauncher 14 (FNE). 锚点优化 (KEY_WIN_SCALE)
 * landscape: with WindowFollowScale v7.5 the trajectory now renders in
 * landscape, so (a) the curveLive icon (z=true) spring v0 is remapped
 * visual->panel (same table as screenSpeed z=true) - the icon consumer
 * is the panel (portrait pipeline) frame while curveVelX/Y are visual;
 * (b) the z=true FROM fix gains the rot=1 mirror about y=sz.x/2 - OUR
 * curve rect is NOT visual-y centered (cross-axis finger follow), so
 * the stock :954 transpose no longer equals the visual->panel inverse
 * for rot=1 either (the mirror is an identity for the stock centered
 * rects, exact for ours).
 * v0.8.1: z=false (no icon) landscape consumer fix. The consumer renders
 * the calc translation DIRECTLY in the visual landscape frame (v0.7.1
 * evidence: to=(1200,756) landed at visual y=0.7, (1200,324) at top-0.3,
 * both x-centered; (324,1200) off-screen). Therefore: FROM is
 * PRE-TRANSPOSED (the stock :954 transpose then restores the raw visual
 * rect - the snapshot starts at the true window position), the default
 * target is the direct-visual (1200, 324) for BOTH holdings (rot=1
 * previously landed at y=0.7), and the z=false spring v0 uses the RAW
 * user-view speed (fixes the mirrored/axis-swapped landscape inertia).
 * Icon (z=true) paths unchanged - the v0.7 panel-frame fixes are kept.
 * v0.8: 跟手 (KEY_WIN_SCALE) ON but the curve never rendered (released
 * before the live takeover had velocity samples): the non-curveLive
 * fallback now synthesizes the velocity with the NEW curve mapping
 * instead of the stock one - trans Y slope 0.375 ((0.5-F)/(1-F), F=0.2
 * anchor; X keeps 0.25; finger speed capped +/-2000 first), scale sigma
 * slope 1.25/span with the curveLive gain x1.4 / endPos*18 clamp.
 * v0.7: sink path is DISABLED on this device (ro.vendor.display.
 * local_anim.config unset -> IS_LOCAL_ANIM_ENABLE false), so the release
 * animation is the VIEW path (RoundImageView snapshot). FROM is measured
 * to arrive in the VISUAL landscape frame (2400x1080): the stock :954
 * transpose equals the visual->panel inverse only for visual-y centered
 * rects (rot=1 scrub rects measured exactly t+b=1080 -> correct), while
 * rot=3 rects have l+r = 929..3720 -> transposed panel-y off by
 * (l+r-2400), up to 1320px: the snapshot appears at the mirror position
 * at release. Fix (4): for rot=3 mirror FROM about x=1200 so the stock
 * transpose produces the exact panel rect. v0.6: non-curveLive fallback
 * restored to v0.4 stock-trajectory velocity
 * semantics (trans 0.25 both axes CLAMPED to v0 +/-500 = the z=false 2000
 * cap, scale gain 1.28/cap 37) - fixes the 跟手-off return-home freeze
 * (oversized spring v0, window stuck mid-air).
 *
 * CONFIRMED pipeline model (v0.4 log evidence, 1080x2400 device):
 *  - The launcher stays PORTRAIT during the whole return animation; the
 *    classic pipeline space is device-portrait coords. mScreenSize is
 *    (1080, 2400) and is NEVER swapped (constructor-time handler rotation
 *    is always 0 - the v0.3/v0.4 constructor approaches are dead ends).
 *  - calcReturnToHomeAnimationParams (:952-967) transposes the FROM rect
 *    with the LIVE rotation (1/3), so after the transpose the geometry is
 *    plain portrait (fullscreen -> icon / default point). Both portrait
 *    and landscape gestures are geometrically IDENTICAL post-transpose.
 *  - VelocityTracker speeds (mTransSpeedX/Y, OtherActivityInputConsumer
 *    :506-518) are in the USER-VIEW (gesture-portraitized) space: an
 *    up-swipe gives (small, -big) for BOTH holdings (logged: v0_y pinned
 *    at -500 = the +/-2000 cap, v0_x ~ 10-30, rot 1 and 3 alike).
 *  - Therefore the ONLY landscape fix needed is a rotation-aware velocity
 *    remap from user-view into the device-portrait pipeline space, plus a
 *    rotation-aware no-icon target. All reads use the LIVE rotation at
 *    hook time (the constructor hook and initRot weak map are REMOVED;
 *    the v0.4 stub setAdditionalInstanceField returned void and crashed
 *    with NoSuchMethodError every gesture anyway).
 *
 *  (1) screenSpeed (spring value space; see the method comment):
 *        z=false (no icon): RAW user-view speed, every rotation (the
 *          consumer renders directly in the user-view visual frame).
 *        z=true (icon, user-view -> panel pipeline):
 *          LIVE rot 1 (port right):  ( vx, vy ) -> ( -vy,  vx )
 *          LIVE rot 3 (port left):   ( vx, vy ) -> (  vy, -vx )
 *          LIVE rot 0 (portrait):                 raw
 *
 *  (2) No-icon default target (getDefaultToRectF): the z=false
 *      consumer renders the translation DIRECTLY in the visual
 *      landscape frame (v0.7.1 evidence above), so both holdings take
 *      the SAME direct-visual TOP-0.3/center point:
 *        rot 1/3 -> (0.5 * 2400, 0.3 * 1080) = (1200, 324)
 *      Portrait keeps the stock degenerate point. The z=false landscape
 *      to skips the portrait-bounds validation (direct-visual values).
 *
 *  (3) v0.6 scrub-release safety net: after swipe-hold (live overview)
 *      ExitAppAnimationMananger can hold a stale card/drawer info, so even
 *      no-icon apps take the ICON path with an off-screen mIconToRectF
 *      (logged: 195.6-tall rect, toCY ~2411) and the window flies
 *      off-screen. calcReturnToHomeAnimationParams is validated at entry:
 *      an off-screen effective target (center outside the portrait screen
 *      with a 50px margin) is replaced by the rotation-aware default
 *      point; capsule flows are untouched.
 */
public final class MotionReturnHome {

    private static final String HSV2 =
            "com.hihonor.android.launcher.extension.quickstep.HnLauncherSwipeHandlerV2";
    private static final String LAU =
            "com.hihonor.android.launcher.animutils.LauncherAnimUtils";
    // Task 24 快滑 diagnostics: consumer-side per-frame state capture.
    private static final String GAC =
            "com.hihonor.android.launcher.quickstep.breakanim.GestureAnimationController";
    private static final String CAH =
            "com.hihonor.android.launcher.quickstep.util.ClipAnimationHelper";

    private static Class<?> lauClass;

    public static void install(ClassLoader cl) {
        Class<?> hsv2 = XposedHelpers.findClass(HSV2, cl);
        lauClass = XposedHelpers.findClassIfExists(LAU, cl);

        // (2) no-icon default target: rotation-aware top-0.3/center point
        // in the portrait pipeline space (see class comment).
        XposedHelpers.findAndHookMethod(hsv2, "getDefaultToRectF", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!motionEnabled()) {
                    return;
                }
                try {
                    int rot = liveRot(param.thisObject);
                    if (rot != 1 && rot != 3) {
                        return; // portrait keeps stock
                    }
                    Point sz = (Point) XposedHelpers.getObjectField(
                            param.thisObject, "mScreenSize");
                    if (sz == null) {
                        return;
                    }
                    param.setResult(defaultTo(rot, sz));
                    Port.log("MotionDefaultRect rot=" + rot
                            + " -> " + str((RectF) param.getResult()));
                } catch (Throwable t) {
                    Port.log("MotionDefaultRect failed", t);
                }
            }
        });

        // (3) scrub-release safety net: after a swipe-hold (live overview
        // appears) ExitAppAnimationMananger can hold a stale card/drawer
        // info, which routes no-icon apps through the ICON path with an
        // off-screen mIconToRectF (or an off-screen explicit to) - the
        // window then flies off-screen. Validate the EFFECTIVE target at
        // calc entry (z ? mIconToRectF : args[2]); if its center is off
        // the portrait screen (50px margin), replace it with the
        // rotation-aware default point. Capsule flows are left untouched.
        XposedHelpers.findAndHookMethod(hsv2, "calcReturnToHomeAnimationParams",
                View.class, RectF.class, RectF.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!motionEnabled()) {
                            return;
                        }
                        try {
                            if (isCapsule()) {
                                return;
                            }
                            Object h = param.thisObject;
                            boolean z = (Boolean) param.args[3];
                            int rot = liveRot(h);
                            Point sz = (Point) XposedHelpers.getObjectField(h, "mScreenSize");
                            if (sz == null) {
                                return;
                            }
                            RectF to = z
                                    ? (RectF) XposedHelpers.getObjectField(h, "mIconToRectF")
                                    : (RectF) param.args[2];
                            Port.log("MotionCalc z=" + z + " rot=" + rot
                                    + " sz=" + sz.x + "x" + sz.y
                                    + " from=" + str((RectF) param.args[1])
                                    + " to=" + str(to));
                            // (4) FROM frame fix (rotation != 0): FROM
                            // arrives in the VISUAL landscape frame
                            // (sz.y x sz.x); the stock :954 transpose
                            // turns it into the portrait pipeline rect.
                            //  - z=true (icon): consumer is the panel
                            //    frame. The transpose equals the
                            //    visual->panel inverse only when the
                            //    rect is centered on the visual x-axis
                            //    midpoint (l+r == sz.y): rot=1 stock
                            //    scrub rects are (measured t+b=1080),
                            //    rot=3 are not (l+r 929..3720) - mirror
                            //    FROM about x = sz.y/2 so the stock
                            //    transpose yields the exact panel rect
                            //    (fixes the rot=3 release mirror jump).
                            //    v0.9: OUR curve rect (v7.5 landscape)
                            //    is NOT visual-y centered either, so
                            //    rot=1 gains the same mirror about
                            //    y = sz.x/2 (identity for the stock
                            //    centered rects, exact for ours).
                            //  - z=false (no icon): consumer renders the
                            //    calc translation DIRECTLY in the visual
                            //    frame (v0.7.1: to=(1200,756) landed at
                            //    visual y=0.7). PRE-TRANSPOSE FROM so the
                            //    stock transpose restores the raw visual
                            //    rect: the snapshot then starts at the
                            //    true window position and springs to the
                            //    direct-visual target in one frame.
                            RectF from = (RectF) param.args[1];
                            if (rot != 0 && from != null && !from.isEmpty()) {
                                if (z) {
                                if (rot == 3) {
                                    RectF mirrored = new RectF(
                                            sz.y - from.right, from.top,
                                            sz.y - from.left, from.bottom);

                                    param.args[1] = mirrored;
                                    Port.log("MotionCalcFromFix z rot=3 "
                                            + str(from) + " -> " + str(mirrored));
                                } else if (rot == 1) {
                                    RectF mirrored = new RectF(
                                            from.left, sz.x - from.bottom,
                                            from.right, sz.x - from.top);

                                    param.args[1] = mirrored;
                                    Port.log("MotionCalcFromFix z rot=1 "
                                            + str(from) + " -> " + str(mirrored));
                                }
                                } else {
                                    // z=false landscape FROM pre-transpose:
                                    // abandoned (v0.9.2) - the empty-rect
                                    // placeholder degenerated the window
                                    // start (0-size snapshot -> stuck/black
                                    // window). Keep the stock FROM rect.
                                    RectF pre = new RectF(
                                            from.top, from.left,
                                            from.bottom, from.right);
                                    pre = new RectF(
                                            0, 0, 0, 0); // MSEDGE: difficult, complex, abandon.........
                                    param.args[1] = pre;
                                    Port.log("MotionCalcFromFix noicon skip");
                                }
                            }
                            if (to == null) {
                                return;
                            }
                            // z=false landscape: the effective target is the
                            // direct-visual default above (or a stock
                            // explicit rect) - the portrait-bounds check
                            // below would misread (1200, 324) as
                            // off-screen. Keep it as-is.
                            if (!z && rot != 0) {
                                return;
                            }
                            float cx = to.centerX();
                            float cy = to.centerY();
                            if (cx >= 50f && cx <= sz.x - 50f
                                    && cy >= 50f && cy <= sz.y - 50f) {
                                return; // on-screen target - keep it
                            }
                            RectF fixed = defaultTo(rot, sz);
                            if (z) {
                                XposedHelpers.callMethod(h, "setIconToRectF", fixed);
                            } else {
                                param.args[2] = fixed;
                            }
                            Port.log("MotionCalcFix rot=" + rot
                                    + " bad=" + str(to) + " -> " + str(fixed));
                        } catch (Throwable t) {
                            Port.log("MotionCalc failed", t);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(hsv2, "getTransSpringInterpolatorX",
                RectF.class, RectF.class, boolean.class, transHook(cl, true));
        XposedHelpers.findAndHookMethod(hsv2, "getTransSpringInterpolatorY",
                RectF.class, RectF.class, boolean.class, transHook(cl, false));
        XposedHelpers.findAndHookMethod(hsv2, "getScaleSpringInterpolatorX",
                RectF.class, boolean.class, scaleHook(cl, true));
        XposedHelpers.findAndHookMethod(hsv2, "getScaleSpringInterpolatorY",
                RectF.class, boolean.class, scaleHook(cl, false));
        installDiag(cl);
        Port.log("MotionReturnHome v0.9 installed");
    }

    /**
     * Task 24 快滑 diagnostics. The consumer (lambda$3 in
     * HnLauncherSwipeHandlerV2) math is speed-independent on paper - the
     * landing differs between 慢滑 (normal) and 快滑 (wrong target) only
     * through runtime state the module logs do not cover. Capture:
     *  - MotionQuickEnd: z4 = GestureAnimationController
     *    .isQuickEndAnimatorRunning() state EDGES (the quick-end branch
     *    selector at :1364/:1435).
     *  - MotionReverse: updateReverseStartInfo args = the ACTUAL per-frame
     *    leash trans (post rot-branch mapping) + scale + animator fraction
     *    (fired from lambda$3 :1534 only when !z4).
     *  - MotionLeash: applyTransformReturnHome TransformParams dump - the
     *    final leash matrix inputs, every frame, z4 or not.
     *  - MotionDur: getDuration4HomeAnim result (animator duration vs the
     *    spring settle time).
     */
    private static void installDiag(ClassLoader cl) {
        try {
            Class<?> gac = XposedHelpers.findClassIfExists(GAC, cl);
            if (gac != null) {
                XposedHelpers.findAndHookMethod(gac, "isQuickEndAnimatorRunning",
                        new XC_MethodHook() {
                            private boolean seen = false;
                            private boolean last = false;
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!motionEnabled()) {
                                    return;
                                }
                                boolean cur = Boolean.TRUE.equals(param.getResult());
                                if (!seen || cur != last) {
                                    seen = true;
                                    last = cur;
                                    Port.log("MotionQuickEnd z4=" + cur);
                                }
                            }
                        });
                XposedHelpers.findAndHookMethod(gac, "updateReverseStartInfo",
                        float.class, float.class, float.class, float.class,
                        Rect.class, float.class, float.class, float.class, float.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!motionEnabled() || !Port.on(Port.KEY_WIN_DEBUG)) {
                                    return; // per-frame 探针仅调试时开, 避免帧路径日志阻塞主线程
                                }
                            // Port.log("MotionReverse tx=" + param.args[0]
                            //         + " ty=" + param.args[1]
                            //         + " s=" + param.args[2]
                            //         + " frac=" + param.args[5]
                            //         + " pv=" + param.args[6] + "," + param.args[7]);
                            }
                        });
            }
        } catch (Throwable t) {
            Port.log("MotionDiag GAC failed", t);
        }
        try {
            Class<?> cah = XposedHelpers.findClassIfExists(CAH, cl);
            if (cah != null) {
                XposedBridge.hookAllMethods(cah, "applyTransformReturnHome", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!motionEnabled() || !Port.on(Port.KEY_WIN_DEBUG)) {
                            return; // per-frame 探针仅调试时开, 避免帧路径日志阻塞主线程
                        }
                        Object p = param.args.length > 1 ? param.args[param.args.length - 1] : null;
                        if (p == null) {
                            return;
                        }
                        try {
                            // Port.log("MotionLeash tx=" + XposedHelpers.getFloatField(p, "mTransX")
                            //         + " ty=" + XposedHelpers.getFloatField(p, "mTransY")
                            //         + " sx=" + XposedHelpers.getFloatField(p, "mScaleX")
                            //         + " sy=" + XposedHelpers.getFloatField(p, "mScaleY")
                            //         + " pv=" + XposedHelpers.getFloatField(p, "mPivotX")
                            //         + "," + XposedHelpers.getFloatField(p, "mPivotY")
                            //         + " a=" + XposedHelpers.getFloatField(p, "mCurAlpha"));
                        } catch (Throwable t) {
                            // Port.log("MotionLeash dump failed", t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Port.log("MotionDiag CAH failed", t);
        }
        try {
            Class<?> hsv2 = XposedHelpers.findClassIfExists(HSV2, cl);
            if (hsv2 != null) {
                XposedHelpers.findAndHookMethod(hsv2, "getDuration4HomeAnim",
                        Interpolator.class, Interpolator.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!motionEnabled()) {
                                    return;
                                }
                                Port.log("MotionDur dur=" + param.getResult()
                                        + " sx=" + param.args[0]
                                        + " sy=" + param.args[1]
                                        + " z=" + param.args[2]);
                            }
                        });
            }
        } catch (Throwable t) {
            Port.log("MotionDiag dur failed", t);
        }
    }

    private static boolean motionEnabled() {
        return Port.on(Port.KEY_MOTION);
    }

    /**
     * LIVE swipe-handler rotation at hook time: 0 portrait / 1 landscape
     * (port right) / 3 seascape (port left). 0 on any failure, which
     * keeps stock portrait behavior.
     */
    private static int liveRot(Object h) {
        try {
            Object oh = XposedHelpers.callMethod(h, "getOrientationHandler");
            Object r = XposedHelpers.callMethod(oh, "getRotation");
            return r instanceof Integer ? ((Integer) r).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isCapsule() {
        try {
            if (lauClass == null) {
                return false;
            }
            Object r = XposedHelpers.callStaticMethod(lauClass, "isReturnToCapsule");
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Gesture velocity in the spring value space.
     *  - z=false (no icon): the consumer renders the translation DIRECTLY
     *    in the user-view visual frame (v0.7.1 landing evidence), so the
     *    RAW user-view speed already IS the spring velocity - no remap,
     *    any rotation (rot 0 raw is unchanged stock portrait behavior).
     *  - z=true (icon): the consumer is the panel (portrait pipeline)
     *    frame; remap the USER-VIEW velocity into it:
     *    LIVE rot 1:  ( vx, vy ) -> ( -vy,  vx )
     *    LIVE rot 3:  ( vx, vy ) -> (  vy, -vx )
     *    LIVE rot 0:  raw.
     *  v0.9 capsule edge: with a fresh WFS trajectory the getTransSpeed
     *  hook (B, v7.5) already returns the PANEL trajectory velocity for
     *  the icon consumer - do NOT remap again (only reachable via the
     *  capsule spring chain; the non-curveLive trans/scale paths imply
     *  curveVelFresh() == false there, where (B) is inactive).
     */
    private static float screenSpeed(Object h, boolean flag, boolean isX, int rot) {
        float vx = (Float) XposedHelpers.callMethod(h, "getTransSpeed", flag, Boolean.TRUE);
        float vy = (Float) XposedHelpers.callMethod(h, "getTransSpeed", flag, Boolean.FALSE);
        float sx;
        float sy;
        if (!flag) {
            sx = vx;
            sy = vy;
        } else if (WindowFollowScale.curveVelFresh()) {
            sx = vx;
            sy = vy;
        } else if (rot == 1) {
            sx = -vy;
            sy = vx;
        } else if (rot == 3) {
            sx = vy;
            sy = -vx;
        } else {
            sx = vx;
            sy = vy;
        }
        return isX ? sx : sy;
    }

    /**
     * Rotation-aware no-icon default target (degenerate point).
     * v0.7 user-verified: the z=false release consumer renders the
     * translation DIRECTLY on the landscape screen (1200,756) landed at
     * visual 0.7). Both holdings therefore take the SAME visual
     * TOP-0.3/center point:
     *   rot 1 (port right) -> (0.5 * H, 0.3 * W) = (1200, 324)
     *   rot 3 (port left)  -> (0.5 * H, 0.3 * W) = (1200, 324)
     *   rot 0 (portrait)   -> stock (W/2, 0.3 * H) = (540, 720)
     */
    private static RectF defaultTo(int rot, Point sz) {
        float x;
        float y;
        if (rot == 1 || rot == 3) {
            x = sz.y * 0.5f;
            y = sz.x * 0.3f;
        } else {
            x = sz.x / 2f;
            y = sz.y * 0.3f;
        }
        return new RectF(x, y, x, y);
    }

    private static String str(RectF r) {
        if (r == null) {
            return "null";
        }
        return "[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]";
    }

    /**
     * V17 MotionController.buildReturnMotion capsule branch (:1529-1530):
     *   toRect = (centerX - h/2*W/H, top, centerX + h/2*W/H, bottom)
     * i.e. the square anchor is re-fit to the capsule bar aspect before the
     * motion targets it.
     */
    private static RectF widenForCapsule(RectF to, Point screen) {
        float halfW = (to.height() * 0.5f * screen.x) / screen.y;
        float cx = to.centerX();
        return new RectF(cx - halfW, to.top, cx + halfW, to.bottom);
    }

    private static XC_MethodHook transHook(final ClassLoader cl, final boolean isX) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // 整体兜底: 反射异常不可抛回桌面原调用点
                // (会变成 FATAL EXCEPTION -> 桌面崩溃), 保留原结果.
                try {
                    if (!motionEnabled()) {
                        return;
                    }
                    boolean capsule = isCapsule();
                    RectF from = (RectF) param.args[0];
                    RectF to = (RectF) param.args[1];
                    Object h = param.thisObject;
                    Point screen = (Point) XposedHelpers.getObjectField(h, "mScreenSize");
                    float span = isX ? screen.x : screen.y;

                RectF target = capsule ? widenForCapsule(to, screen) : to;

                float endPos;
                if (isX) {
                    endPos = (target.left - (span / 2f - target.width() / 2f))
                            - ((from.left + from.width() / 2f) - span / 2f);
                } else {
                    endPos = (target.top - (span / 2f - target.height() / 2f))
                            - ((from.top + from.height() / 2f) - span / 2f);
                }

                // Classic pipeline velocity scaling: the interpolator-based
                // consumer expects startVelocity = transSpeed * 0.25
                // (sInterpolatorCoefficient, HnLauncherSwipeHandlerV2:336)
                // for BOTH axes - the exact stock semantics. screenSpeed
                // remaps the user-view velocity into the portrait pipeline
                // space (landscape fix, task 24).
                //
                // v0.6 fallback fix (跟手 off freeze): the previous
                // non-curveLive Y path used v0 = speed * 0.375 * att,
                // derived from OUR curve trajectory (d(centerY)/d(finger) =
                // 0.375 below the knee). That is only meaningful while the
                // curve drives the window. With 跟手 off the window follows
                // the STOCK trajectory, the conversion fed the fixed-
                // damping MotionEngine spring a v0 thousands of px/s too
                // large (logged -5985 vs curve-true -2..-900) and the
                // return animation never settled - window stuck mid-air
                // until the next swipe. Restore the v0.4 0.25 scaling.
                boolean isReturnHome = (Boolean) param.args[2];
                // WindowFollowScale (窗口跟手缩放) curve live: the from-rect
                // is OUR curve rect and getTransSpeed is hooked to return the
                // card's VISUAL center velocity (z-aware per consumer frame,
                // (B) v7.5). The spring v0 IS that velocity directly - no
                // finger->visual conversion, no endPos-based math; the icon
                // consumer takes the visual->panel remap below (v0.9).
                // Capsule follows the SAME normal-icon velocity route
                // (curve live -> synthesized -> v0.4 classic); capsule flows
                // never render the curve, so curveVelFresh stays false
                // there and the chain falls through to the classic clamp.
                boolean curveLive = WindowFollowScale.curveVelFresh();
                boolean wfsSyn = false;
                float v0;
                int rot;
                if (curveLive) {
                    // v7.5 landscape: curveVelX/Y are the card's VISUAL
                    // velocity. The icon (z) consumer is the PANEL
                    // (portrait pipeline) frame - remap with the LIVE
                    // rotation (same table as screenSpeed z=true); the
                    // no-icon consumer renders in the visual frame - raw.
                    // Portrait (rot 0): identity both ways.
                    if (isReturnHome) {
                        rot = liveRot(h);
                        float cx = WindowFollowScale.curveVelX();
                        float cy = WindowFollowScale.curveVelY();
                        float sx;
                        float sy;
                        if (rot == 1) {
                            sx = -cy;
                            sy = cx;
                        } else if (rot == 3) {
                            sx = cy;
                            sy = -cx;
                        } else {
                            sx = cx;
                            sy = cy;
                        }
                        // per tuning: Y spring v0 = measured card velocity x3.
                        v0 = isX ? sx * 1f : sy * 3f;
                    } else {
                        rot = -1;
                        // per tuning: Y spring v0 = measured card velocity x3.
                        v0 = isX ? WindowFollowScale.curveVelX() * 1f
                                : WindowFollowScale.curveVelY() * 3f;
                    }
                } else {
                    rot = liveRot(h);
                    float speed = screenSpeed(h, isReturnHome, isX, rot);
                    // NOTE: V17 dynamicAdjustSpeedAndParam feeds RAW px/s
                    // (gain 1.0, clamp +-10419) into the ENGINE
                    // SpringConstraint/MotionObject - that transfer is NOT
                    // portable to the classic SpringInterpolator path: the
                    // interpolator consumer expects 0.25-attenuated,
                    // endPos-scaled velocities (field test v0.9.1: raw
                    // injection = huge misplacement). Capsule follows the
                    // SAME velocity route as the normal icon return
                    // (curve-synthesized when 跟手 is on, v0.4 classic
                    // clamp otherwise); spring params (hertz/damping) stay
                    // the V17 capsule values via MotionParams.
                    if (Port.on(Port.KEY_WIN_SCALE)) {
                        // v0.8: 跟手 ON but the curve never rendered
                        // (released before the live takeover had samples).
                        // Synthesize the card velocity with the NEW curve's
                        // finger->visual mapping instead of the stock 0.25.
                        // Y slope 0.375 ((0.5-F)/(1-F), F=0.2 anchor) x3
                        // per tuning = 1.125; X per the new curve slope
                        // (d(centerX)/d(finger) = 1 - the curve tracks the
                        // finger 1:1 horizontally) instead of the stock
                        // 0.25. Cap the finger speed at the stock +/-2000
                        // first - the raw isReturnHome speed is
                        // -14k..-21k (the v0.6 freeze class).
                        wfsSyn = true;
                        float capped = Math.max(-2000f, Math.min(2000f, speed));
                        v0 = capped * (isX ? 1f : 1.5f);
                    } else {
                        // v0.4 semantics: the observed v0 was PINNED at -500 on
                        // fast flicks = the stock z=false +/-2000 speed cap *
                        // 0.25. isReturnHome (z=true) returns the UNCAPPED raw
                        // speed (logged -14k..-21k), and a v0 of thousands
                        // (10:10 logs: -3490..-5224) never settles in our
                        // fixed-damping MotionEngine spring - window stuck
                        // mid-air. Clamp v0 to the v0.4 range (wfs-path v0 is
                        // -2..919, same order).
                        v0 = Math.max(-500f, Math.min(500f, speed * 0.25f));
                    }
                }

                float stiffness;
                float damping;
                float threshold;
                if (capsule) {
                    stiffness = MotionParams.CAPSULE_MOVEMENT_STIFFNESS;
                    damping = MotionParams.CAPSULE_MOVEMENT_DAMPING;
                    threshold = MotionParams.CAPSULE_VALUE_THRESHOLD;
                } else {
                    stiffness = MotionParams.MOVEMENT_STIFFNESS;
                    damping = MotionParams.MOVEMENT_DAMPING;
                    threshold = 0.3f;
                }

                Port.log("MotionTrans" + (isX ? "X" : "Y")
                        + " endPos=" + endPos + " v0=" + v0
                        + " rot=" + rot
                        + (curveLive ? " wfs" : (wfsSyn ? " wfsSyn" : ""))
                        + (capsule ? " capsule" : ""));

                Object spring = SpringFactory.make(cl, stiffness, damping,
                        endPos, v0, threshold);
                if (spring != null) {
                    param.setResult(spring);
                }
                } catch (Throwable t) {
                    Port.log("MotionTrans hook failed", t);
                }
            }
        };
    }

    private static XC_MethodHook scaleHook(final ClassLoader cl, final boolean isX) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                if (!motionEnabled()) {
                    return;
                }
                boolean capsule = isCapsule();
                RectF rectF = (RectF) param.args[0];
                boolean isIcon = (Boolean) param.args[1];
                Object h = param.thisObject;
                Point screen = (Point) XposedHelpers.getObjectField(h, "mScreenSize");
                float span = isX ? screen.x : screen.y;
                float disp = XposedHelpers.getFloatField(h, "mDisplacement");

                float ratio = rectF.height() / span;
                float endPos = Math.abs(1f - ratio);
                // WindowFollowScale curve live: the scale spring value is
                // (1 - sigma) and the hook tracks the TRUE sigma rate
                // (sVelSig, sigma/s, measured off its own trajectory
                // algorithm) - use it directly instead of the endPos-based
                // stock chain rule below. Safety clamp only (native
                // max-speed semantics: endPos * 37).
                boolean curveLive = !capsule && WindowFollowScale.curveVelFresh();
                boolean wfsSyn = false;
                int rot;
                float v0;
                if (curveLive) {
                    rot = -1;
                    // wfs scale initial velocity gain x1.4, clamp endPos*18
                    v0 = Math.max(Math.min(Math.abs(WindowFollowScale.curveVelSig()) * 1.5f,
                            endPos * 30f),5f);
                } else {
                    rot = liveRot(h);
                    float speed = screenSpeed(h, isIcon, isX, rot);
                    // NOTE: as transHook - V17's raw px/s scale transfer
                    // (setLinearVelocity(-clamp(vY, +-scaleMax))) is engine
                    // -only; the classic interpolator needs the
                    // span-normalized V14-coupled formula below.
                    if (!capsule && Port.on(Port.KEY_WIN_SCALE)) {
                        // v0.8: 跟手 ON but the curve never rendered
                        // (released before the live takeover had samples).
                        // Synthesize the sigma rate from the NEW curve slope
                        // (d(sigma)/d(finger) = 1.25/span below the knee) and
                        // apply the curveLive gain x1.4 / endPos*18 clamp.
                        wfsSyn = true;
                        float bounded = Math.max(-11000f, Math.min(11000f, speed));
                        float sigRate = bounded * 1.25f / span;
                        v0 = Math.max(Math.min(Math.abs(sigRate) * 1.5f, endPos * 30f),5f);
                    } else {
                        // V14 velocity-coupled scale formula (kept - this is the
                        // "scale carries linear velocity" behavior); the speed is
                        // remapped into the portrait pipeline space first
                        // (landscape fix, task 24).
                        float bounded = Math.max(-11000f, Math.min(11000f, speed));
                        float v = (bounded * -1.848f
                                * (float) Math.pow(Math.E, (disp / span) * -1.848f)) / span;
                        // v0.4 stock-trajectory gain 1.28 (cap endPos*37). The
                        // old 1.792 (=1.28*7/5) / 51.8 was WFS-curve tuning left
                        // in the non-curveLive fallback - wrong when 跟手 is off
                        // (same freeze class as the trans 0.375*att bug).
                        v0 = Math.min(Math.abs(v) * 1.28f, endPos * 37f) * 0.5f;
                    }
                }

                float stiffness;
                float damping;
                float threshold;
                if (capsule) {
                    stiffness = MotionParams.CAPSULE_SCALE_STIFFNESS;
                    damping = MotionParams.CAPSULE_SCALE_DAMPING;
                    threshold = MotionParams.CAPSULE_VALUE_THRESHOLD;
                } else {
                    stiffness = MotionParams.SCALE_STIFFNESS;
                    // wfsSyn: bigger synthesized v0 -> critical damping.
                    damping = wfsSyn ? MotionParams.SCALE_DAMPING_SYN
                            : MotionParams.SCALE_DAMPING;
                    threshold = 5.0e-4f;
                }

                Port.log("MotionScale" + (isX ? "X" : "Y")
                        + " endPos=" + endPos + " v0=" + v0
                        + " rot=" + rot
                        + (curveLive ? " wfs" : (wfsSyn ? " wfsSyn" : ""))
                        + (capsule ? " capsule" : ""));

                Object spring = SpringFactory.make(cl, stiffness, damping,
                        endPos, v0, threshold);
                if (spring != null) {
                    param.setResult(spring);
                }
                } catch (Throwable t) {
                    Port.log("MotionScale hook failed", t);
                }
            }
        };
    }

    private MotionReturnHome() {}
}
