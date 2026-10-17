package com.wxz.hnlauncherport.returnhome;

import android.graphics.Point;
import android.graphics.RectF;

import android.view.View;

import com.wxz.hnlauncherport.Port;
import com.wxz.hnlauncherport.motion.MotionParams;
import com.wxz.hnlauncherport.motion.SpringFactory;
import com.wxz.hnlauncherport.swipe.WindowFollowScale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * MotionEngine v0.7 for HnLauncher 14 (FNE). Task 24 landscape rework.
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
 *  (1) screenSpeed (user-view -> pipeline):
 *        LIVE rot 1 (port right):  ( vx, vy ) -> ( -vy,  vx )
 *        LIVE rot 3 (port left):   ( vx, vy ) -> (  vy, -vx )
 *        LIVE rot 0 (portrait):                 raw
 *      An up-swipe (vy < 0) then drives the X spring: rot 1 -> +x (toward
 *      0.7*W), rot 3 -> -x (toward 0.3*W) - matching the target below.
 *
 *  (2) No-icon default target (getDefaultToRectF): visual TOP-0.3 /
 *      horizontal-center point for BOTH holdings, expressed in portrait
 *      pipeline coords:
 *        rot 1 -> (0.7 * 1080, 0.5 * 2400) = (756, 1200)
 *        rot 3 -> (0.3 * 1080, 0.5 * 2400) = (324, 1200)
 *      Portrait keeps the stock degenerate point.
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
                                    + " from=" + str((RectF) param.args[1])
                                    + " to=" + str(to));
                            // (4) rot=3 frame fix: FROM arrives in the visual
                            // landscape frame (sz.y x sz.x). The stock :954
                            // transpose equals the visual->panel inverse only
                            // when the rect is centered on the visual x-axis
                            // midpoint (l+r == sz.y); rot=3 scrub rects are
                            // not (measured l+r 929..3720), so the released
                            // snapshot starts up to 1320px off in panel-y -
                            // visually the mirror position. Mirror FROM about
                            // x = sz.y/2 so the stock transpose yields the
                            // exact panel rect. rot=1 rects are measured
                            // exactly visual-y centered, stock stays correct.
                            RectF from = (RectF) param.args[1];
                            if (rot == 3 && from != null && !from.isEmpty()) {
                                RectF mirrored = new RectF(
                                        sz.y - from.right, from.top,
                                        sz.y - from.left, from.bottom);
                                param.args[1] = mirrored;
                                Port.log("MotionCalcFromFix rot=3 "
                                        + str(from) + " -> " + str(mirrored));
                            }
                            if (to == null) {
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
        Port.log("MotionReturnHome installed");
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
     * (1) Gesture velocity remapped from the USER-VIEW space (where an
     * up-swipe is always -Y) into the device-portrait pipeline space:
     *   LIVE rot 1:  ( vx, vy ) -> ( -vy,  vx )
     *   LIVE rot 3:  ( vx, vy ) -> (  vy, -vx )
     *   LIVE rot 0:  raw (portrait behavior preserved on any failure).
     */
    private static float screenSpeed(Object h, boolean flag, boolean isX, int rot) {
        float vx = (Float) XposedHelpers.callMethod(h, "getTransSpeed", flag, Boolean.TRUE);
        float vy = (Float) XposedHelpers.callMethod(h, "getTransSpeed", flag, Boolean.FALSE);
        float sx;
        float sy;
        if (rot == 1) {
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
     * Rotation-aware no-icon default target (degenerate point) in the
     * portrait pipeline space: visual top-0.3/center for both holdings.
     *   rot 1 (port right) -> (0.7 * W, 0.5 * H) = (756, 1200)
     *   rot 3 (port left)  -> (0.3 * W, 0.5 * H) = (324, 1200)
     *   rot 0 (portrait)   -> stock (W/2, 0.3 * H) = (540, 720)
     */
    private static RectF defaultTo(int rot, Point sz) {
        float x;
        float y;
        if (rot == 1) {
            x = sz.x * 0.7f;
            y = sz.y * 0.5f;
        } else if (rot == 3) {
            x = sz.x * 0.3f;
            y = sz.y * 0.5f;
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
                // card's VISUAL center velocity - already in the spring
                // value space (from->to center delta, pipeline space). The
                // spring v0 IS that velocity directly: no finger->visual
                // conversion, no rotation remap, no endPos-based math.
                boolean curveLive = !capsule && WindowFollowScale.curveVelFresh();
                float v0;
                int rot;
                if (curveLive) {
                    rot = -1;
                    v0 = isX ? WindowFollowScale.curveVelX()
                            : WindowFollowScale.curveVelY();
                } else {
                    rot = liveRot(h);
                    float speed = screenSpeed(h, isReturnHome, isX, rot);
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
                        + (curveLive ? " wfs" : "")
                        + (capsule ? " capsule" : ""));

                Object spring = SpringFactory.make(cl, stiffness, damping,
                        endPos, v0, threshold);
                if (spring != null) {
                    param.setResult(spring);
                }
            }
        };
    }

    private static XC_MethodHook scaleHook(final ClassLoader cl, final boolean isX) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
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
                int rot;
                float v0;
                if (curveLive) {
                    rot = -1;
                    // wfs scale initial velocity gain x1.4, clamp endPos*18
                    v0 = Math.min(Math.abs(WindowFollowScale.curveVelSig()) * 1.4f,
                            endPos * 18f);
                } else {
                    // V14 velocity-coupled scale formula (kept - this is the
                    // "scale carries linear velocity" behavior); the speed is
                    // remapped into the portrait pipeline space first
                    // (landscape fix, task 24).
                    rot = liveRot(h);
                    float speed = screenSpeed(h, isIcon, isX, rot);
                    float bounded = Math.max(-11000f, Math.min(11000f, speed));
                    float v = (bounded * -1.848f
                            * (float) Math.pow(Math.E, (disp / span) * -1.848f)) / span;
                    // v0.4 stock-trajectory gain 1.28 (cap endPos*37). The
                    // old 1.792 (=1.28*7/5) / 51.8 was WFS-curve tuning left
                    // in the non-curveLive fallback - wrong when 跟手 is off
                    // (same freeze class as the trans 0.375*att bug).
                    v0 = Math.min(Math.abs(v) * 1.28f, endPos * 37f) * 0.5f;
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
                    damping = MotionParams.SCALE_DAMPING;
                    threshold = 5.0e-4f;
                }

                Port.log("MotionScale" + (isX ? "X" : "Y")
                        + " endPos=" + endPos + " v0=" + v0
                        + " rot=" + rot
                        + (curveLive ? " wfs" : "")
                        + (capsule ? " capsule" : ""));

                Object spring = SpringFactory.make(cl, stiffness, damping,
                        endPos, v0, threshold);
                if (spring != null) {
                    param.setResult(spring);
                }
            }
        };
    }

    private MotionReturnHome() {}
}
