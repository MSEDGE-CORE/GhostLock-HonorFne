package com.wxz.hnlauncherport.swipe;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.wxz.hnlauncherport.Port;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * V17-style "window follows finger" swipe-up trajectory (窗口跟手缩放).
 *
 * v7: carousel follow via canvas translate (no view state), landscape via
 * rotation-consistency, interrupt anchor blend, return-home injection.
 *
 * v7 changes (user issues on v6):
 *
 *  1. Recents page Y offset / follow magnitude ("recent页整体偏下/跟随幅度不对"):
 *     the v6 overlay wrote RecentsView translationY with a remove-last/add-new
 *     scheme. Two failure classes: (a) stock rewrites the translation
 *     absolutely (state settle) while our bookkeeping still holds the old
 *     offset - the next gesture re-added the stale value, pushing the page
 *     DOWN by the previous swipe amplitude; (b) TVS.apply stopping mid
 *     release-blend left the translation frozen. v7 never touches view
 *     properties: it wraps RecentsView.dispatchDraw (overridden at :792) with
 *     canvas.save/translate/restore. The translate is recomputed each draw
 *     from (des.center - taskRect.center) / viewScale (exact follow while
 *     fresh, exponential decay to zero once TVS frames stop) - zero persistent
 *     state, self-healing, stock absolute writes untouched.
 *
 *  2. Landscape ("横屏跟手位置不对"): TaskViewSimulator.apply ends with
 *     applyWindowToHomeRotation (:1428) which appends a rotation only when
 *     recentsActivityRotation != displayRotation. When the two rotations
 *     match (portrait AND landscape-launcher), the matrix is plain screen
 *     space and the reference formula - which is dimension-generic - runs
 *     unchanged with landscape w/h. When they differ (launcher portrait vs
 *     landscape display), stock geometry is kept.
 *
 *  3. Return-home icons animation ("返回桌面图标动画没有新版初始位置"):
 *     OICEx.returnHomeWithAnimation (:677) builds the whole pipeline from
 *     HnLauncherSwipeHandlerV2.getCurrentRectF(float) (:642 - TVS stock rect)
 *     plus mTransSpeedX/Y. v7 injects, while our curve is live:
 *       - getCurrentRectF(float)      -> our last curve rect (from-rect,
 *                                        sourceScale, spring endPos anchors)
 *       - setTransSpeedX/Y + getTransSpeed -> our card visual velocity
 *                                        (px/s, X/Y rates into spring v0)
 *       - getResetCurrentRectHeight   -> 0 (no bogus inset offset on our rect)
 *
 *  4. Interrupt continuity ("打断动画保留从动画当前位置和尺寸到手指当前位置
 *     和尺寸的衔接"): at the first active frame of a gesture the stock rect
 *     (cur) may be mid-animation (interrupted launch/settle), not fullscreen.
 *     The reference trajectory would snap to fullscreen. v7 anchors: offset =
 *     cur - referenceRect at that frame, then the offset decays to zero over
 *     ANCHOR_BLEND_MS (smoothstep, position AND size). Normal gestures have
 *     offset ~= 0 and are unaffected.
 *
 *  5. Interrupt takeover, v7.4 ("打断不停顿也走新跟手"): the stock interrupt
 *     spring (mRecentTransitAnim, "RT") is a durationless SpringAnimation
 *     that tracks the finger every frame (breakAnimationStartOrRunning:1597)
 *     and only converges once the finger pauses. While it ran, the
 *     winAnimActive gate kept our curve off for the whole interrupt gesture
 *     (stock trajectory unless the user paused first). Now an RT-only
 *     window with a live finger keeps the spring as the renderer while the
 *     matrix surgery warps its frames to our trajectory.
 *
 *  6. Landscape anchor trajectory (v7.5, 锚点优化 landscape): with a
 *     landscape app over the portrait launcher the TVS matrix ends with
 *     applyWindowToHomeRotation = postDisplayRotation(deltaRotation(rec,
 *     disp)) - its output (cur, and our injected from-rect sLastDes) is
 *     the VISUAL landscape frame (2400x1080) while the finger (OIC
 *     events), the reference formula and taskRect stay PANEL (1080x2400;
 *     gesture nav keeps getDisplacement on the panel y-axis, so stock
 *     landscape tracking is panel-driven). The whole trajectory math runs
 *     in panel - referenceRect unchanged - with cur inverse-rotated to
 *     panel (curS) for the anchor/grab/blend, the panel result (desS)
 *     rotated to visual for the matrix surgery / sLastDes / the visual
 *     velocity sample, and (C) writing the panel-remapped velocity into
 *     mTransSpeedX/Y (panel consumers). delta 2 or reflection failure
 *     keeps stock geometry.
 *
 *  7. Landscape anchor position (v7.8, 锚点优化 landscape): the v7.5
 *     rotated-portrait anchor kept the portrait convergence (top ->
 *     FAR*h = 0.2h panel), which rotated to visual 0.2W_v from the FAR
 *     edge - the anchor sat at the wrong end of the landscape swipe
 *     ("似乎是根据竖屏的坐标", 横向映射错). User spec (long/short edge):
 *     the SHORT edge is anchored at 0.3 against gravity (visual y 324,
 *     same physical point both directions; panel x 0.7w rot=1 / 0.3w
 *     rot=3); the LONG edge is finger-following - the portrait-X
 *     analog (top = fy - sigma*downY) - the card tracks the swipe and
 *     rests at the finger's position (user: "缺少长边方向的位移"). The
 *     landscape swipe distance maps into mY x2 (user spec) so sigma
 *     hits the floor at roughly half the portrait travel. The portrait
 *     formula is byte-identical.
 *
 * Trajectory (reference-faithful, F = FarPoint = 0.2, raw-finger driven):
 *   dy = fingerY - downY; mY = H + dy
 *   mH = (mY - F*H) * (H / (H - F*H))
 *   mH < 0.4H: a=0.3H soft resistance; mH <= 0.05H floor
 *   mH > 1.0H: a=0.1H soft overshoot; mH >= 1.1H cap
 *   sigma = mH/H; left = fingerX - sigma*downX; top = F*H*(1-sigma)
 *   landscape (rot!=0): mY = H + 2*dy; cx = ax + (0.5W-ax)*sigma,
 *     ax=0.7W/0.3W by rot; left = cx - sigma*W/2; top = fy - sigma*downY
 */
public final class WindowFollowScale {
    private static final String TAG = "WindowFollowScale";
    private static final String HANDLER = "com.android.quickstep.AbsSwipeUpHandler";
    private static final String LOGIC = "com.android.quickstep.SwipeUpAnimationLogic";
    private static final String TVS = "com.android.quickstep.util.TaskViewSimulator";
    private static final String LAUNCHER = "com.hihonor.android.launcher.Launcher";
    private static final String OIC =
            "com.android.quickstep.inputconsumers.OtherActivityInputConsumer";
    private static final String HN_HANDLER =
            "com.hihonor.android.launcher.extension.quickstep.HnLauncherSwipeHandlerV2";
    private static final String RECENTS_VIEW =
            "com.android.quickstep.views.RecentsView";

    /** Reference FarPoint F = 0.2. */
    private static final float FAR = 0.20f;
    /** Soft resistance knee (mH < 0.4H). */
    private static final float KNEE = 0.40f;
    /** Resistance coefficient below the knee (a = 0.3H). */
    private static final float RESIST_A = 0.30f;
    /** Overshoot coefficient above fullscreen (a = 0.1H). */
    private static final float OVERSHOOT_A = 0.10f;
    /** Hard floor / cap for mH. */
    private static final float FLOOR = 0.05f;
    private static final float CAP = 1.10f;
    /** Raw finger movement younger than this counts as "gesture active". */
    private static final long UD_ACTIVE_MS = 400L;
    /** Release blend duration into the stock settle. */
    private static final long END_BLEND_MS = 300L;
    /** Our window offset must be fresher than this for break suppression. */
    private static final long APPLIED_FRESH_MS = 400L;
    /** Interrupt anchor blend duration (anim rect -> finger trajectory). */
    private static final long ANCHOR_BLEND_MS = 250L;
    /** RT grab offset decay time constant; 3*tau ~= 120ms full convergence. */
    private static final long GRAB_TAU_MS = 40L;
    /** Velocity sample window (ms) for the release velocity tracker. */
    private static final long VEL_WINDOW_MS = 100L;
    /** Release velocity considered valid this long after the last sample. */
    private static final long VEL_FRESH_MS = 400L;
    /** Our curve rect considered injectable this long after the last update. */
    private static final long DES_FRESH_MS = 800L;

    // raw finger capture (OtherActivityInputConsumer)
    private static volatile float sDownX;
    private static volatile float sDownY;
    private static volatile float sFingerX;
    private static volatile float sFingerY;
    private static volatile boolean sDownSeen;
    private static volatile boolean sFingerDown;
    // updateDisplacement capture (freshness + diag)
    private static volatile long sLastUdTime;
    private static volatile float sUdShift;
    // applyScrollAndTransform capture
    private static volatile long sLastScrollApply;
    private static volatile float sLastShift;
    private static volatile long sLastAppliedTime;
    // release detection (endTarget != null)
    private static volatile boolean sEndSet;
    private static volatile long sEndTime;
    private static final RectF sLastDes = new RectF();
    private static volatile boolean sLastDesValid;
    private static volatile long sLastDesTime;
    private static final RectF sRelDes = new RectF();
    private static volatile boolean sRelDesValid;
    /** Live trajectory rotation (rotOf): 0 panel==visual, 1/3 landscape delta. */
    private static volatile int sTrajRot;
    /** Release endTarget was RECENTS (enter recents): skip our release blend. */
    private static volatile boolean sEndRecents;
    /** Release endTarget was NEW_TASK/LAST_TASK (quick-switch or back to
     *  fullscreen app): use the stock settle (same as when 跟手 is off). */
    private static volatile boolean sEndTask;
    /** Task-release position pass-through: per-edge offset from our curve
     *  rect to the stock rect, captured once at release. The offset decays
     *  with the stock settle's OWN progress toward fullscreen (rate curve
     *  identical to the settle), so the composite trajectory is the stock
     *  easing played from our position. */
    private static boolean sTaskOffValid;
    private static float sTaskOffL, sTaskOffT, sTaskOffR, sTaskOffB;
    private static final RectF sTaskS0 = new RectF();
    // interrupt anchor (v7 #4) + RT grab mode (v7.4.1)
    private static boolean sAncValid;
    private static long sAncTime;
    private static float sAncL;
    private static float sAncT;
    private static float sAncR;
    private static float sAncB;
    private static boolean sAncGrab;
    private static float sAncDx;
    private static float sAncDy;
    private static float sAncDsig;
    private static float sAncDsigW;
    /** Mid quick-switch: keep the captured horizontal CENTER offset for the
     *  whole gesture (stock keeps the card's X offset; only Y/scale follow). */
    private static boolean sAncKeepX;
    /** Reference height at the keepX capture: the persisted X offset scales
     *  with the main card (r.height()/sAncKeepXH as the card shrinks/grows). */
    private static float sAncKeepXH;
    // release velocity tracker (v7 #3)
    private static long sVelT0;
    private static long sVelT;
    private static float sVelX0;
    private static float sVelY0;
    private static float sVelSig0;
    private static float sVelX;
    private static float sVelY;
    private static float sVelSig;
    // carousel follow REMOVED (v17): all v16 RecentsView manipulations
    // (dispatchDraw canvas wrap, view-prop override, property redirect)
    // are gone - recent cards are fully stock; only the main card keeps
    // the 0.2-anchored single-stage trajectory.

    private static boolean sLoggedOnce;

    // SwipeUpAnimationLogic / OtherActivityInputConsumer fields
    private static Field fShiftAnim;         // mCurrentShift (AnimatedFloat)
    private static Field fAnimValue;         // AnimatedFloat.value
    private static Field fDownPos;           // OIC.mDownPos (PointF)
    private static Field fLastPos;           // OIC.mLastPos (PointF)
    // handler fields
    private static Field fRecentsView;       // ASH.mRecentsView
    // TaskViewSimulator fields
    private static Field fDp;
    private static Field fSplitBounds;
    private static Field fTaskRect;
    private static Field fMatrix;
    private static Field fTmpCropRect;
    private static Method mDpWidth;
    private static Method mDpHeight;
    private static Field fOrientationState;  // TVS.mOrientationState
    private static Method mDispRot;          // RecentsOrientedState.getDisplayRotation
    private static Method mRecRot;           // RecentsOrientedState.getRecentsActivityRotation
    // diag / release detect
    private static Field fGestureState;
    private static Method mGetEndTarget;
    private static Method mGetLauncher;
    private static Method mGetGac;
    private static Method mGacRunning;
    private static Method mGacLaunchContinue;
    private static Method mGacBreakRunning;
    private static Field fGacRt;
    private static Field fGacLc;
    private static Field fGacRv;
    private static Field fGacRh;
    private static Field fGacRr;
    private static boolean sEndDetectOk = true;
    private static boolean sDiagErrored;

    public static void install(ClassLoader cl) throws Exception {
        // Resolved with the HOST classloader: the module classloader cannot
        // see launcher classes (v5 gacBusy bug - interrupt stuck at frame 1).
        mGetLauncher = XposedHelpers.findClass(LAUNCHER, cl).getMethod("getLauncher");

        // ---- Hook 0: raw finger position from the input consumer ----
        Class<?> oic = XposedHelpers.findClass(OIC, cl);
        fDownPos = field(oic, "mDownPos");
        fLastPos = field(oic, "mLastPos");
        XposedHelpers.findAndHookMethod(oic, "onMotionEvent", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // BEFORE: at the first MOVE of a new gesture mDownPos
                        // already holds the fresh DOWN position (set during
                        // the DOWN event), so the first cascade frame sees
                        // dy = 0 (fullscreen, no offset). Capturing AFTER
                        // instead made the first frame use the PREVIOUS
                        // gesture's down/finger - a one-frame jump.
                        MotionEvent ev = (MotionEvent) param.args[0];
                        int action = ev.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            sFingerDown = true;
                            resetGestureState();
                            // New touch ends the previous release handoff: a
                            // new finger is a new gesture (chained quick-switch).
                            sEndSet = false;
                            sEndTime = 0L;
                            sRelDesValid = false;
                            sEndRecents = false;
                            sEndTask = false;
                            sTaskOffValid = false;
                            // DOWN arrives BEFORE the consumer updates
                            // mDownPos/mLastPos: reading them now yields the
                            // PREVIOUS gesture's positions, and the still-
                            // rendering settle frames would warp the window
                            // back to the old gesture's rect (chained quick-
                            // switch "move back then flash"). Block our warp
                            // until the first MOVE carries fresh positions.
                            sDownSeen = false;
                            return;
                        } else if (action == MotionEvent.ACTION_UP
                                || action == MotionEvent.ACTION_CANCEL) {
                            sFingerDown = false;
                        }
                        try {
                            Object down = fDownPos.get(param.thisObject);
                            Object last = fLastPos.get(param.thisObject);
                            if (down == null || last == null) {
                                return;
                            }
                            sDownX = xOf(down);
                            sDownY = yOf(down);
                            sFingerX = xOf(last);
                            sFingerY = yOf(last);
                            sDownSeen = true;
                        } catch (Throwable ignore) {
                        }
                    }
                });

        // ---- Hook 1: gesture freshness + shift diag ----
        Class<?> logic = XposedHelpers.findClass(LOGIC, cl);
        fShiftAnim = field(logic, "mCurrentShift");
        for (Method m : logic.getDeclaredMethods()) {
            if ("updateDisplacement".equals(m.getName()) && m.getParameterTypes().length == 3) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sLastUdTime = SystemClock.uptimeMillis();
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object shift = fShiftAnim.get(param.thisObject);
                            if (shift != null) {
                                if (fAnimValue == null) {
                                    fAnimValue = field(shift.getClass(), "value");
                                }
                                sUdShift = (Float) fAnimValue.get(shift);
                            }
                        } catch (Throwable ignore) {
                        }
                        dbgUd("UD f=" + param.args[0] + " y=" + param.args[2]
                                + " sh=" + sUdShift + " end=" + (sEndSet ? "Y" : "N"));
                    }
                });
                break;
            }
        }

        // ---- Hook 2: release detection + recents view cache + diag ----
        Class<?> handler = XposedHelpers.findClass(HANDLER, cl);
        fRecentsView = field(handler, "mRecentsView");
        XposedHelpers.findAndHookMethod(handler, "applyScrollAndTransform", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                long now = SystemClock.uptimeMillis();
                sLastScrollApply = now;
                try {
                    Object shift = fShiftAnim.get(param.thisObject);
                    if (shift != null) {
                        if (fAnimValue == null) {
                            fAnimValue = field(shift.getClass(), "value");
                        }
                        sLastShift = (Float) fAnimValue.get(shift);
                    }
                } catch (Throwable ignore) {
                }
                if (sEndDetectOk) {
                    detectRelease(param.thisObject, now);
                }
                if (Port.on(Port.KEY_WIN_DEBUG)) {
                    diagAsh(param.thisObject, now);
                }
            }
        });

        // ---- Hook 3: suppress the post-haptic break takeover ----
        // v7.1: gate on GESTURE ACTIVITY, not on our last apply. The old
        // sLastAppliedTime gate deadlocked: one gacBusy frame blocked the
        // apply -> freshness latched off -> suppression dropped -> stock
        // breakAnimationStartOrRunning returned true via the
        // shouldContinueAppLaunch early-return (which starts NOTHING and
        // renders NOTHING) -> applyScrollAndTransform stayed blocked for
        // the rest of the gesture: window frozen until finger lift.
        // shouldContinueAppLaunch is a non-rendering placeholder: only a
        // REAL running break spring (isBreakAnimRunning) owns the window.
        //
        // v7.3: pass through to stock whenever gac owns the window
        // (isWindowAnimRunning: launch / reverse / return-home / break
        // anims). While it is true, applyScrollAndTransform's render body
        // is SKIPPED entirely (AbsSwipeUpHandler:3196), so suppression is
        // a no-op for rendering - its only effect was blocking stock's
        // designed launch->break handoff, and a deep interrupt fling then
        // wedged the commit path (14s frozen window, 08:14:43 event).
        // Restoring stock behavior in the gac-owned window fixes the
        // interrupt freeze; the v7.1 deep-fling deadlock fix is kept for
        // the run=0 case (there suppression forces our TVS render path).
        try {
            Class<?> hnHandler = XposedHelpers.findClass(HN_HANDLER, cl);
            Method breakGate = null;
            for (Method m : hnHandler.getDeclaredMethods()) {
                if ("breakAnimationStartOrRunning".equals(m.getName())
                        && m.getParameterTypes().length == 0) {
                    breakGate = m;
                    break;
                }
            }
            if (breakGate == null) {
                throw new NoSuchMethodException("breakAnimationStartOrRunning");
            }
            XposedBridge.hookMethod(breakGate, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Port.on(Port.KEY_WIN_SCALE)) {
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    boolean active;
                    if (sEndSet) {
                        active = now - sEndTime < END_BLEND_MS + 150L;
                    } else {
                        active = sFingerDown
                                || now - sLastUdTime < UD_ACTIVE_MS;
                    }
                    if (!active) {
                        return; // outside our gesture/blend window: stock
                    }
                    if (winAnimActive()) {
                        // gac owns the window (launch/reverse/return-home/
                        // break anim): stock gate logic - the deep interrupt
                        // fling must reach stock's break handoff.
                        dbgBrk("BRK passthrough win-anim " + winAnimWhich()
                                + (launchContinue() ? " lc=1" : ""));
                        return;
                    }
                    if (breakSpringRunning()) {
                        return; // a real break spring is rendering
                    }
                    param.setResult(Boolean.FALSE);
                    dbgBrk("BRK suppressed" + (launchContinue() ? " lc=1" : ""));
                }
            });
        } catch (Throwable t) {
            Port.log(TAG + " break-gate hook unavailable (stock stage 2 remains)", t);
        }

         // ---- Hook 4 (carousel follow) REMOVED in v17: no RecentsView
         //      manipulation at all - recent cards are fully stock. ----

         // ---- Hook 5: return-home pipeline injection (v7 #3) ----
        installReturnHomeInjection(cl);

        // ---- Hook 6: matrix surgery + carousel target ----
        Class<?> tvs = XposedHelpers.findClass(TVS, cl);
        fDp = field(tvs, "mDp");
        fSplitBounds = field(tvs, "mSplitBounds");
        fTaskRect = field(tvs, "mTaskRect");
        fMatrix = field(tvs, "mMatrix");
        fTmpCropRect = field(tvs, "mTmpCropRect");
        fOrientationState = field(tvs, "mOrientationState");

        Method buildParams = null;
        for (Method m : tvs.getDeclaredMethods()) {
            if ("onBuildTargetParams".equals(m.getName())) {
                buildParams = m;
                break;
            }
        }
        if (buildParams == null) {
            throw new IllegalStateException("onBuildTargetParams not found on TaskViewSimulator");
        }
        XposedBridge.hookMethod(buildParams, new XC_MethodHook() {
            private boolean applied;
            private boolean erroredOnce;
            private float rA;
            private float rOcx;
            private float rOcy;
            private float rDx;
            private float rDy;

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                applied = false;
                try {
                    applyOffset(param.thisObject);
                } catch (Throwable t) {
                    // Log once, but do NOT disable: the matrix is untouched
                    // on this path, retrying next frame is safe and keeps the
                    // feature alive through transient errors.
                    if (!erroredOnce) {
                        erroredOnce = true;
                        Port.log(TAG + " offset failed (once)", t);
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!applied) {
                    return;
                }
                applied = false;
                Matrix matrix = (Matrix) fMatrix.get(param.thisObject);
                // inverse of (postScale about center + postTranslate)
                matrix.postTranslate(-rDx, -rDy);
                matrix.postScale(1f / rA, 1f / rA, rOcx, rOcy);
            }

            private void applyOffset(Object tvs) throws Throwable {
                long now = SystemClock.uptimeMillis();
                long udAge = now - sLastUdTime;
                // v7.3: while gac owns the window (stock break renders via
                // applyBreak -> createSurfaceParams -> here), leave the
                // params untouched - surgery from our gesture state would
                // corrupt the stock break's own surface params.
                //
                // v7.4 fix A: EXCEPT when ONLY the interrupt break spring
                // owns the window (winAnimWhich()=="RT" - mRecentTransitAnim,
                // a durationless androidx SpringAnimation that tracks the
                // finger and only converges once the finger PAUSES). While
                // it ran, this gate kept our curve off for the whole
                // interrupt gesture (stock trajectory until the user paused
                // - the 打断 pause bug). Now, with a live finger gesture,
                // the spring stays as stock's renderer and the matrix
                // surgery below warps each rendered frame to OUR reference
                // trajectory (spring = renderer, trajectory = ours).
                boolean rtTakeover = false;
                if (winAnimActive()) {
                    rtTakeover = sFingerDown && sDownSeen && !sEndSet
                            && "RT".equals(winAnimWhich());
                    if (!rtTakeover) {
                        // Protect a live grab anchor from transient gate
                        // flaps (winAnimWhich "?" / mixed state for one
                        // frame): re-capturing mid-gesture would snap the
                        // window back to the spring rect.
                        if (!(sAncGrab && sFingerDown)) {
                            resetAnchor();
                        }
                        dbgWfs("gate win-anim " + winAnimWhich()
                                + " fd=" + sFingerDown + " udAge=" + udAge);
                        return;
                    }
                    dbgWfs("RT takeover udAge=" + udAge);
                }
                boolean endBlend = sEndSet && sRelDesValid
                        && now - sEndTime < END_BLEND_MS;
                // After release: ONLY end-blend frames may apply our rect.
                // Everything else renders stock (fixes the post-blend
                // stale-finger jump back to the old drag position).
                if (sEndSet && !endBlend) {
                    resetAnchor();
                    dbgWfs("post-end udAge=" + udAge);
                    return;
                }
                // A held finger keeps the curve alive even without MOVE
                // events; after lift the udAge grace covers the handoff.
                boolean active = !sEndSet
                        && (sFingerDown || udAge < UD_ACTIVE_MS);
                if (!active && !endBlend) {
                    resetAnchor();
                    dbgWfs("gate udAge=" + udAge + " end=" + (sEndSet ? "Y" : "N"));
                    return;
                }
                if (!Port.on(Port.KEY_WIN_SCALE)) {
                    return;
                }
                Object dp = fDp.get(tvs);
                if (dp == null) {
                    return;
                }
                if (mDpWidth == null) {
                    mDpWidth = dp.getClass().getMethod("getWidthPx");
                    mDpHeight = dp.getClass().getMethod("getHeightPx");
                }
                int w = (Integer) mDpWidth.invoke(dp);
                int h = (Integer) mDpHeight.invoke(dp);
                if (w <= 0 || h <= 0) {
                    return;
                }
                // Landscape (v7.5): the TVS matrix ends with
                // applyWindowToHomeRotation = postDisplayRotation(delta-
                // Rotation(recentsActivityRotation, displayRotation)).
                // delta 0: plain panel space (portrait) - the reference
                // formula runs unchanged. delta 1/3 (landscape app over
                // the portrait launcher): the matrix output is the VISUAL
                // landscape frame while the finger/reference/taskRect stay
                // PANEL - the trajectory runs in panel and is rotated to
                // visual at the boundaries (curS / toVisual below). delta
                // 2 or reflection failure keeps stock geometry.
                int rot = rotOf(tvs);
                if (rot < 0) {
                    resetAnchor();
                    dbgWfs("rot-unsupported stock");
                    return;
                }
                sTrajRot = rot;
                if (fSplitBounds.get(tvs) != null) {
                    return; // split screen keeps stock geometry
                }
                Rect taskRect = (Rect) fTaskRect.get(tvs);
                if (taskRect == null || taskRect.isEmpty()) {
                    return;
                }
                Matrix matrix = (Matrix) fMatrix.get(tvs);
                Rect crop = (Rect) fTmpCropRect.get(tvs);
                if (matrix == null || crop == null || crop.isEmpty()) {
                    return;
                }
                RectF cur = new RectF(crop);
                matrix.mapRect(cur);
                if (cur.width() < 1f || cur.height() < 1f) {
                    return;
                }
                // panel-space twin of cur (identity when rot == 0): the
                // anchor/grab/blend math below is panel-native.
                RectF curS = rot == 0 ? cur : toPanel(cur, rot, w, h);

                RectF desS;
                float t = 1f;
                if (endBlend) {
                    if (sEndRecents) {
                        // RECENTS release (enter recents): the stock settle is
                        // already instant (DisableEnterRecentsAnim zeroes the
                        // shift duration), so our 250ms release blend would be
                        // the only thing still gliding the main card. Skip it:
                        // snap straight to the stock rect (jump to end).
                        t = 0f;
                        desS = new RectF(curS);
                        dbgWfs("REL instant (recents) udAge=" + udAge);
                    } else if (sEndTask) {
                        // Quick-switch (NEW_TASK) / swipe-down back to the
                        // fullscreen app (LAST_TASK): play the stock settle,
                        // exactly as when 跟手 (KEY_WIN_SCALE) is off -- but
                        // PASS OUR POSITION: capture the per-edge offset from
                        // our curve rect (where the window is visually) to
                        // the stock rect once at release. The offset decays
                        // with the settle's OWN progress toward fullscreen
                        // (k = 1 - p), so the composite is V = W0 + p*(E-W0):
                        // the stock easing curve, played from our position.
                        RectF relS = rot == 0 ? sRelDes : toPanel(sRelDes, rot, w, h);
                        if (!sTaskOffValid) {
                            sTaskOffValid = true;
                            sTaskS0.set(curS);
                            sTaskOffL = relS.left - curS.left;
                            sTaskOffT = relS.top - curS.top;
                            sTaskOffR = relS.right - curS.right;
                            sTaskOffB = relS.bottom - curS.bottom;
                        }
                        float p = stockSettleProgress(curS, w, h);
                        float kt = 1f - (float) (now - sEndTime)
                                / (float) END_BLEND_MS;
                        float k = Math.min(clamp01(1f - p), clamp01(kt));
                        t = 0f;
                        desS = new RectF(
                                curS.left + sTaskOffL * k,
                                curS.top + sTaskOffT * k,
                                curS.right + sTaskOffR * k,
                                curS.bottom + sTaskOffB * k);
                        dbgWfs("REL task p=" + p + " k=" + k
                                + " off=" + ((int) sTaskOffL) + "," + ((int) sTaskOffT)
                                + "," + ((int) sTaskOffR) + "," + ((int) sTaskOffB)
                                + " udAge=" + udAge);
                    } else {
                        // Release: blend our snapshotted rect into the stock
                        // settle path (position AND size), smoothstepped.
                        t = 1f - (float) (now - sEndTime) / (float) END_BLEND_MS;
                        t = clamp01(t);
                        RectF relS = rot == 0 ? sRelDes : toPanel(sRelDes, rot, w, h);
                        desS = lerp(curS, relS, t * t * (3f - 2f * t));
                        dbgWfs("REL t=" + t + " udAge=" + udAge);
                    }
                } else {
                    if (!sDownSeen) {
                        return;
                    }
                    RectF r = referenceRect(w, h, rot);
                    // Interrupt anchor (v7 #4): blend from the animation's
                    // current rect (cur - may be mid-flight on interrupted
                    // launch/settle) into the finger-driven trajectory.
                    // v7.4.3: an RT interrupt uses GRAB semantics - the
                    // center/height/width offsets captured at the grab decay
                    // EXPONENTIALLY toward the finger-driven trajectory
                    // (tau = GRAB_TAU_MS, full convergence ~200ms). The
                    // width sigma offset decays too, so the grabbed rect's
                    // ASPECT RATIO eases into the reference ratio instead
                    // of snapping on the first takeover frame (ratio jump).
                    if (!sAncValid) {
                        sAncValid = true;
                        sAncTime = now;
                        sAncGrab = rtTakeover;
                        // Quick-switch recognition (v8): a fresh swipe-up
                        // starts with the window horizontally centered on
                        // our reference; touching the swipe-up area mid
                        // horizontal-switch (chained quick-switch) starts
                        // with the card offset in X (carousel slot / settle
                        // in flight). Stock keeps that X offset while the
                        // finger drives Y/scale - so must we: the captured
                        // CENTER-X offset persists for the whole gesture,
                        // only Y/scale blend toward the trajectory.
                        sAncKeepX = Math.abs(curS.centerX() - r.centerX())
                                > (float) w * 0.08f;
                        sAncKeepXH = r.height() > 1f ? r.height() : 1f;
                        // v9: unified grab-style capture for BOTH paths -
                        // the non-grab anchor uses the same sigma/center
                        // decomposition (single-stage convergence into the
                        // 0.2-anchored reference curve), replacing the old
                        // two-stage edge-offset blend (offset glide, then
                        // reference following).
                        sAncDx = curS.centerX() - r.centerX();
                        sAncDy = curS.centerY() - r.centerY();
                        sAncDsig = (curS.height() - r.height()) / (float) h;
                        sAncDsigW = (curS.width() - r.width()) / (float) w;
                        dbgWfs("ANC dx=" + ((int) sAncDx)
                                + " dy=" + ((int) sAncDy)
                                + " dsig=" + sAncDsig
                                + " dsigW=" + sAncDsigW
                                + " keepX=" + sAncKeepX
                                + " grab=" + sAncGrab);
                    }
                    // v9: unified grab-style apply - offsets decay
                    // exponentially (tau = GRAB_TAU_MS) into the 0.2-anchored
                    // reference trajectory; both sigmas clamp to the
                    // reference floor/cap; keepX persists the X center offset
                    // scaled by the reference height ratio.
                    {
                        float kg = (float) Math.exp(
                                -(double) (now - sAncTime) / (double) GRAB_TAU_MS);
                        float sig = r.height() / (float) h + sAncDsig * kg;
                        sig = Math.max(FLOOR, Math.min(CAP, sig));
                        float sigW = r.width() / (float) w + sAncDsigW * kg;
                        sigW = Math.max(FLOOR, Math.min(CAP, sigW));
                        float halfW = sigW * w / 2f;
                        float halfH = sig * h / 2f;
                        float cx = r.centerX() + sAncDx
                                * (sAncKeepX ? r.height() / sAncKeepXH : kg);
                        float cy = r.centerY() + sAncDy * kg;
                        desS = new RectF(cx - halfW, cy - halfH,
                                cx + halfW, cy + halfH);
                    }
                    // visual twin: what the matrix surgery, the injected
                    // from-rect (sLastDes) and the visual velocity see
                    RectF desV = rot == 0 ? desS : toVisual(desS, rot, w, h);
                    sLastDes.set(desV);
                    sLastDesValid = true;
                    sLastDesTime = now;
                    // Release velocity tracker (v7 #3): rolling VEL_WINDOW_MS
                    // samples of the card's visual center + scale.
                    if (now - sVelT0 >= VEL_WINDOW_MS) {
                        if (sVelT0 > 0L) {
                            float dt = (float) (now - sVelT0);
                            sVelX = (desV.centerX() - sVelX0) * 1000f / dt;
                            sVelY = (desV.centerY() - sVelY0) * 1000f / dt;
                            sVelSig = (desS.height() / (float) h - sVelSig0) * 1000f / dt;
                            sVelT = now;
                        }
                        sVelT0 = now;
                        sVelX0 = desV.centerX();
                        sVelY0 = desV.centerY();
                        sVelSig0 = desS.height() / (float) h;
                    }
                }

                RectF des = rot == 0 ? desS : toVisual(desS, rot, w, h);
                float a = des.width() / cur.width();
                if (!(a > 0.05f) || !(a < 20f)) {
                    return; // degenerate / NaN guard
                }
                float ocx = cur.centerX();
                float ocy = cur.centerY();
                float dx = des.centerX() - ocx;
                float dy = des.centerY() - ocy;
                if (Math.abs(a - 1f) < 0.001f && Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f) {
                    dbgWfs("skip a=1");
                    return;
                }
                matrix.postScale(a, a, ocx, ocy);
                matrix.postTranslate(dx, dy);
                applied = true;
                sLastAppliedTime = now;
                rA = a;
                rOcx = ocx;
                rOcy = ocy;
                rDx = dx;
                rDy = dy;
                if (!sLoggedOnce) {
                    sLoggedOnce = true;
                    Port.log(TAG + " first v7.5 offset a=" + a + " dx=" + dx + " dy=" + dy);
                }
                dbgWfs("WFS rot=" + rot + " sigH=" + (desS.height() / h)
                        + " dy=" + (sFingerY - sDownY)
                        + " udAge=" + udAge + " end=" + (sEndSet ? "Y" : "N")
                        + " t=" + t + " a=" + a);
            }
        });

        Port.log(TAG + " v7.8 installed");
    }

    /**
     * Return-home pipeline injection (v7 #3): while our curve is live, the
     * from-rect and the X/Y velocity of the whole return-to-icon animation
     * come from OUR card state instead of the stock TVS rect / stock speed.
     */
    private static void installReturnHomeInjection(ClassLoader cl) {
        final Class<?> hsv2 = XposedHelpers.findClass(HN_HANDLER, cl);

        // (A) from-rect: OICEx.returnHomeWithAnimation:677 -> :796 feeds the
        // whole pipeline (sourceScale, spring anchors, icon morph start).
        XposedHelpers.findAndHookMethod(hsv2, "getCurrentRectF", float.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_WIN_SCALE) || !injectFresh()) {
                            return;
                        }
                        try {
                            Object handles = lazy(param.thisObject, "mRemoteTargetHandles");
                            if (handles instanceof Object[]
                                    && ((Object[]) handles).length != 1) {
                                return; // multi-window keeps stock
                            }
                        } catch (Throwable ignore) {
                            return;
                        }
                        param.setResult(new RectF[]{new RectF(sLastDes)});
                        dbgWfs("INJ from=" + sLastDes
                                + " vel=" + ((int) sVelX) + "," + ((int) sVelY)
                                + " sigV=" + sVelSig);
                    }
                });

        // (B) X/Y velocity read by the stock pipeline and MotionReturnHome
        // - our card's visual velocity in px/s. Landscape (v7.5): the icon
        // (z) consumers are the PANEL frame, the no-icon consumer is the
        // visual frame - remap per consumer via the live trajectory rot.
        XposedHelpers.findAndHookMethod(hsv2, "getTransSpeed",
                boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_WIN_SCALE) || !velocityFresh()) {
                            return;
                        }
                        boolean icon = (Boolean) param.args[0];
                        boolean isX = (Boolean) param.args[1];
                        param.setResult(isX
                                ? (icon ? trajPanelVelX() : sVelX)
                                : (icon ? trajPanelVelY() : sVelY));
                    }
                });

        // (C) keep the raw fields in sync (RoundImageView.setAnimationAttrs
        // reads mTransSpeedX/Y directly - PANEL space, v7.5 landscape).
        XposedHelpers.findAndHookMethod(hsv2, "setTransSpeedX", float.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_WIN_SCALE) || !velocityFresh()) {
                            return;
                        }
                        XposedHelpers.setFloatField(param.thisObject, "mTransSpeedX", trajPanelVelX());
                    }
                });
        XposedHelpers.findAndHookMethod(hsv2, "setTransSpeedY", float.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_WIN_SCALE) || !velocityFresh()) {
                            return;
                        }
                        XposedHelpers.setFloatField(param.thisObject, "mTransSpeedY", trajPanelVelY());
                    }
                });

        // (D) our rect is already true window space: skip the stock
        // inset-offset adjustment on it.
        XposedHelpers.findAndHookMethod(hsv2, "getResetCurrentRectHeight",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_WIN_SCALE) || !injectFresh()) {
                            return;
                        }
                        param.setResult(0f);
                    }
                });
    }

    /** True when our curve rect may replace the stock from-rect. */
    private static boolean injectFresh() {
        long now = SystemClock.uptimeMillis();
        return sLastDesValid && !sLastDes.isEmpty()
                && (sFingerDown || now - sLastDesTime < DES_FRESH_MS);
    }

    /** True when our release velocity may replace the stock speeds. */
    private static boolean velocityFresh() {
        long now = SystemClock.uptimeMillis();
        return sVelT > 0L && (sFingerDown || now - sVelT < VEL_FRESH_MS);
    }

    /**
     * Curve-live velocity accessors for MotionReturnHome: the card's true
     * visual rates measured off OUR trajectory - center X/Y in px/s
     * (pipeline space) and sigma (height/span) per second.
     */
    public static boolean curveVelFresh() {
        return Port.on(Port.KEY_WIN_SCALE) && velocityFresh();
    }

    public static float curveVelX() {
        return sVelX;
    }

    public static float curveVelY() {
        return sVelY;
    }

    public static float curveVelSig() {
        return sVelSig;
    }

    /** Per-gesture state reset on ACTION_DOWN. */
    private static void resetGestureState() {
        sAncValid = false;
        sAncL = 0f;
        sAncT = 0f;
        sAncR = 0f;
        sAncB = 0f;
        sAncGrab = false;
        sAncDx = 0f;
        sAncDy = 0f;
        sAncDsig = 0f;
        sAncDsigW = 0f;
        sAncKeepX = false;
        sVelT = 0L;
        sVelT0 = 0L;
        sVelX = 0f;
        sVelY = 0f;
        sVelSig = 0f;
        sVelX0 = 0f;
        sVelY0 = 0f;
        sVelSig0 = 0f;
        sLastDesValid = false;
        sLastDesTime = 0L;
        sTrajRot = 0;
    }

    private static void resetAnchor() {
        sAncValid = false;
        sAncL = 0f;
        sAncT = 0f;
        sAncR = 0f;
        sAncB = 0f;
        sAncGrab = false;
        sAncDx = 0f;
        sAncDy = 0f;
        sAncDsig = 0f;
        sAncDsigW = 0f;
        sAncKeepX = false;
    }

    /**
     * TVS matrix rotation delta (v7.5): the matrix ends with
     * applyWindowToHomeRotation which appends postDisplayRotation(delta,
     * dp.w, dp.h) with delta = deltaRotation(recentsActivityRotation,
     * displayRotation) - 0 means plain panel space (portrait), 1/3 the
     * panel->visual rotation (landscape app over the portrait launcher),
     * -1 means 180/bogus/reflection failure (keep stock geometry). The
     * landscape mappings (postDisplayRotation, RecentsOrientedState:388,
     * with dp 1080x2400):
     *   delta=1: (x_v, y_v) = (y_p, w - x_p)
     *   delta=3: (x_v, y_v) = (h - y_p, x_p)
     */
    /** Stock settle progress toward the fullscreen rect (0,0,w,h), per-edge
     *  averaged from the release-time snapshot sTaskS0. Drives the task
     *  release offset decay so its rate curve matches the settle itself. */
    private static float stockSettleProgress(RectF curS, int w, int h) {
        float sum = 0f;
        int n = 0;
        float d = -sTaskS0.left; // E.left = 0
        if (Math.abs(d) > 1f) {
            sum += (curS.left - sTaskS0.left) / d;
            n++;
        }
        d = -sTaskS0.top; // E.top = 0
        if (Math.abs(d) > 1f) {
            sum += (curS.top - sTaskS0.top) / d;
            n++;
        }
        d = w - sTaskS0.right;
        if (Math.abs(d) > 1f) {
            sum += (curS.right - sTaskS0.right) / d;
            n++;
        }
        d = h - sTaskS0.bottom;
        if (Math.abs(d) > 1f) {
            sum += (curS.bottom - sTaskS0.bottom) / d;
            n++;
        }
        if (n == 0) {
            return 1f;
        }
        return clamp01(sum / (float) n);
    }

    private static int rotOf(Object tvs) {
        try {
            Object os = fOrientationState.get(tvs);
            if (os == null) {
                return 0; // cannot check: keep legacy portrait behavior
            }
            if (mDispRot == null) {
                mDispRot = os.getClass().getMethod("getDisplayRotation");
                mRecRot = os.getClass().getMethod("getRecentsActivityRotation");
            }
            int disp = (Integer) mDispRot.invoke(os);
            int rec = (Integer) mRecRot.invoke(os);
            int delta = disp - rec;
            if (delta < 0) {
                delta += 4;
            }
            if (delta == 0 || delta == 1 || delta == 3) {
                return delta;
            }
            return -1; // 180 or bogus: keep stock geometry
        } catch (Throwable t) {
            return -1;
        }
    }

    /** panel rect -> visual rect (v7.5 landscape). */
    private static RectF toVisual(RectF p, int rot, int w, int h) {
        if (rot == 1) {
            return new RectF(p.top, w - p.right, p.bottom, w - p.left);
        }
        return new RectF(h - p.bottom, p.left, h - p.top, p.right); // rot == 3
    }

    /** visual rect -> panel rect (inverse of {@link #toVisual}). */
    private static RectF toPanel(RectF v, int rot, int w, int h) {
        if (rot == 1) {
            return new RectF(w - v.bottom, v.left, w - v.top, v.right);
        }
        return new RectF(v.top, h - v.right, v.bottom, h - v.left); // rot == 3
    }

    /**
     * Trajectory velocity in the PANEL (portrait pipeline) space (v7.5):
     * visual->panel remap of sVelX/sVelY by the live trajectory rot -
     * identity in portrait. rot=3: (vx_p, vy_p) = (vy_v, -vx_v);
     * rot=1: (vx_p, vy_p) = (-vy_v, vx_v).
     */
    private static float trajPanelVelX() {
        int rot = sTrajRot;
        if (rot == 1) {
            return -sVelY;
        }
        if (rot == 3) {
            return sVelY;
        }
        return sVelX;
    }

    private static float trajPanelVelY() {
        int rot = sTrajRot;
        if (rot == 1) {
            return sVelX;
        }
        if (rot == 3) {
            return -sVelX;
        }
        return sVelY;
    }

    /** Reference GstBut_ManipulationDelta mapping, raw-finger driven. */
    /**
     * Reference trajectory rect.
     *
     * Portrait (rot == 0, unchanged): top = FAR*h*(1-sigma) - the card
     * converges toward FAR*h = 0.2h (top-anchored, iOS-style).
     *
     * Landscape (rot != 0, v7.8 direct rot anchor): user spec in
     * long/short edges - the SHORT edge is anchored at 0.3 against
     * gravity (panel x: ax = 0.7w rot=1 / 0.3w rot=3; visual 0.3 of
     * the short edge, same physical point both rots); the LONG edge
     * is finger-following - the portrait-X analog: top = fy -
     * sigma*downY, so the card tracks the swipe along the long edge
     * and rests at the finger's position. The landscape swipe
     * distance maps into mY x2 (user spec), so sigma reaches the
     * floor at roughly half the portrait travel. At sigma=1:
     * left=0, top=0 - identical to the portrait fullscreen start, so
     * the anchor-capture blend has no snap.
     */
    private static RectF referenceRect(int w, int h, int rot) {
        float downX = sDownX;
        float downY = sDownY;
        float fx = sFingerX;
        float fy = sFingerY;
        // mY = H + dy; mH = (mY - F*H) * (H / (H - F*H))
        // v7.8 landscape: the swipe runs along panel -y from the bar;
        // its distance maps into mY x2 (user spec) so sigma reaches
        // the floor at roughly half the portrait travel.
        float mY = h + (fy - downY) * (rot == 0 ? 1f : 2f);
        float mH = (mY - FAR * h) * (h / (h - FAR * h));
        if (mH < KNEE * h) {
            float a = RESIST_A * h;
            mH = -(a * (-1f / (Math.abs(mH - KNEE * h) / a + 1f) + 1f)) + KNEE * h;
        }
        if (mH <= FLOOR * h) {
            mH = FLOOR * h;
        }
        if (mH > h) {
            float a = OVERSHOOT_A * h;
            mH = a * (-1f / (Math.abs(mH - h) / a + 1f) + 1f) + h;
        }
        if (mH >= CAP * h) {
            mH = CAP * h;
        }
        float sigma = mH / h;
        float left;
        float top;
        if (rot != 0) {
            // v7.8: short edge (panel x) anchored - card center eases
            // from fullscreen mid to ax (rot=1: 0.7w; rot=3: 0.3w; visual
            // 0.3 of the short edge, against gravity). Long edge (panel y)
            // is finger-following - the portrait-X analog: top = fy -
            // sigma*downY, so the card tracks the swipe along the long
            // edge and rests at the finger's position (sigma=1 start:
            // top = 0, fullscreen seam is identical to portrait).
            float ax = (rot == 1 ? 0.70f : 0.30f) * w;
            float cxs = ax + (0.5f * w - ax) * sigma;
            left = cxs - sigma * w / 2f;
            top = (fx - sigma * downX); //(fx - (sigma) * (downX - 0.5 * h) - 0.5 * h) * sigma
            if(rot == 3)
            {
                top = h - ((fx - sigma * downX) + sigma * h); 
            }
        } else {
            left = fx - sigma * downX;
            top = FAR * h * (1f - sigma);
        }
        return new RectF(left, top, left + sigma * w, top + sigma * h);
    }

    /** endTarget-based release detect; snapshots our rect at the transition. */
    private static void detectRelease(Object ash, long now) {
        try {
            if (fGestureState == null) {
                fGestureState = field(ash.getClass(), "mGestureState");
            }
            Object gs = fGestureState.get(ash);
            if (gs == null) {
                return;
            }
            if (mGetEndTarget == null) {
                mGetEndTarget = gs.getClass().getMethod("getEndTarget");
            }
            Object endTarget = mGetEndTarget.invoke(gs);
            if (endTarget == null) {
                    if (sEndSet) {
                        sEndSet = false; // fresh GestureState -> new gesture
                        sEndTime = 0L;
                        sRelDesValid = false;
                        sEndRecents = false;
                        sEndTask = false;
                        sTaskOffValid = false;
                        resetAnchor();
                    }
            } else if (!sEndSet) {
                sEndSet = true;
                sEndTime = now;
                String et = endTarget.toString();
                sEndRecents = "RECENTS".equals(et);
                sEndTask = "NEW_TASK".equals(et) || "LAST_TASK".equals(et);
                sTaskOffValid = false;
                sRelDes.set(sLastDes);
                sRelDesValid = sLastDesValid && !sLastDes.isEmpty();
            }
        } catch (Throwable t) {
            sEndDetectOk = false;
            Port.log(TAG + " release detect disabled", t);
        }
    }

    /** True only while a break spring is ACTUALLY running (it renders). */
    private static boolean breakSpringRunning() {
        try {
            if (mGetLauncher == null) {
                return false;
            }
            Object launcher = mGetLauncher.invoke(null);
            if (launcher == null) {
                return false;
            }
            if (mGetGac == null) {
                mGetGac = launcher.getClass().getMethod("getGestureAnimationController");
            }
            Object gac = mGetGac.invoke(launcher);
            if (gac == null) {
                return false;
            }
            if (mGacBreakRunning == null) {
                mGacBreakRunning = gac.getClass().getMethod("isBreakAnimRunning");
            }
            return Boolean.TRUE.equals(mGacBreakRunning.invoke(gac));
        } catch (Throwable t) {
            return false;
        }
    }

    /** shouldContinueAppLaunch: a NON-RENDERING placeholder claim (diag). */
    private static boolean launchContinue() {
        try {
            if (mGetLauncher == null) {
                return false;
            }
            Object launcher = mGetLauncher.invoke(null);
            if (launcher == null) {
                return false;
            }
            if (mGetGac == null) {
                mGetGac = launcher.getClass().getMethod("getGestureAnimationController");
            }
            Object gac = mGetGac.invoke(launcher);
            if (gac == null) {
                return false;
            }
            if (mGacLaunchContinue == null) {
                mGacLaunchContinue = gac.getClass().getMethod("shouldContinueAppLaunch");
            }
            return Boolean.TRUE.equals(mGacLaunchContinue.invoke(gac));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Per-call diagnosis of the applyScrollAndTransform guard/branches. */
    private static void diagAsh(Object ash, long now) {
        try {
            Object gs = fGestureState != null ? fGestureState.get(ash) : null;
            Object endTarget = null;
            if (gs != null && mGetEndTarget != null) {
                endTarget = mGetEndTarget.invoke(gs);
            }
            Object targets = lazy(ash, "mRecentsAnimationTargets");
            Object handles = lazy(ash, "mRemoteTargetHandles");
            int n = -1;
            if (handles instanceof Object[]) {
                n = ((Object[]) handles).length;
            }
            String run = gacRunning();
            dbgAsh("AST run=" + run
                    + ("1".equals(run) ? "(" + winAnimWhich() + ")" : "")
                    + " tgtNull=" + (targets == null ? "Y" : "N")
                    + " end=" + (endTarget == null ? "null" : endTarget.toString())
                    + " n=" + n + " sh=" + sLastShift
                    + " udAge=" + (now - sLastUdTime));
        } catch (Throwable t) {
            if (!sDiagErrored) {
                sDiagErrored = true;
                Port.log(TAG + " diag failed (once)", t);
            }
        }
    }

    /** gac owns the window: break/launch/reverse/return-home anim running. */
    private static boolean winAnimActive() {
        try {
            Object gac = gacInstance();
            if (gac == null) {
                return false;
            }
            if (mGacRunning == null) {
                try {
                    mGacRunning = gac.getClass().getMethod("isWindowAnimRunning");
                } catch (NoSuchMethodException e) {
                    mGacRunning = gac.getClass().getMethod("ln");
                }
            }
            return Boolean.TRUE.equals(mGacRunning.invoke(gac));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cached launcher -> getGestureAnimationController lookup. */
    private static Object gacInstance() throws Exception {
        if (mGetLauncher == null) {
            return null;
        }
        Object launcher = mGetLauncher.invoke(null);
        if (launcher == null) {
            return null;
        }
        if (mGetGac == null) {
            mGetGac = launcher.getClass().getMethod("getGestureAnimationController");
        }
        return mGetGac.invoke(launcher);
    }

    /**
     * Which gac window-anim components report running (diag for the
     * 原版-跟手 flash): RT=mRecentTransitAnim LC=launch-continue
     * RV=mReverseAnimator RH=mReturnHomeAnim RR=mIsRecentStartInReverse.
     * "-" = none, "?" = reflection failure.
     */
    private static String winAnimWhich() {
        try {
            Object gac = gacInstance();
            if (gac == null) {
                return "?";
            }
            if (fGacRt == null) {
                Class<?> c = gac.getClass();
                fGacRt = field(c, "mRecentTransitAnim");
                fGacLc = field(c, "mLaunchAnimation");
                fGacRv = field(c, "mReverseAnimator");
                fGacRh = field(c, "mReturnHomeAnim");
                fGacRr = field(c, "mIsRecentStartInReverse");
            }
            StringBuilder sb = new StringBuilder();
            Object v = fGacRt.get(gac);
            if (v != null && Boolean.TRUE.equals(
                    v.getClass().getMethod("isRunning").invoke(v))) {
                sb.append("RT");
            }
            v = fGacLc.get(gac);
            if (v != null
                    && Boolean.TRUE.equals(
                            v.getClass().getMethod("getNeedContinuePlay").invoke(v))
                    && Boolean.TRUE.equals(
                            v.getClass().getMethod("isCanBreak").invoke(v))) {
                sb.append("LC");
            }
            v = fGacRv.get(gac);
            if (v != null && Boolean.TRUE.equals(
                    v.getClass().getMethod("isRunning").invoke(v))) {
                sb.append("RV");
            }
            v = fGacRh.get(gac);
            if (v != null && Boolean.TRUE.equals(
                    v.getClass().getMethod("isRunning").invoke(v))) {
                sb.append("RH");
            }
            if (fGacRr.getBoolean(gac)) {
                sb.append("RR");
            }
            return sb.length() == 0 ? "-" : sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** "1"/"0" from GestureAnimationController.isWindowAnimRunning (or ln). */
    private static String gacRunning() {
        return winAnimActive() ? "1" : "0";
    }

    private static Object lazy(Object obj, String name) throws Exception {
        Field f = "mRecentsAnimationTargets".equals(name) ? fTargets : fHandles;
        if (f == null) {
            f = field(obj.getClass(), name);
            if ("mRecentsAnimationTargets".equals(name)) {
                fTargets = f;
            } else {
                fHandles = f;
            }
        }
        return f.get(obj);
    }

    private static float xOf(Object pointF) throws Exception {
        return (Float) pointF.getClass().getField("x").get(pointF);
    }

    private static float yOf(Object pointF) throws Exception {
        return (Float) pointF.getClass().getField("y").get(pointF);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static RectF lerp(RectF from, RectF to, float t) {
        return new RectF(
                from.left + (to.left - from.left) * t,
                from.top + (to.top - from.top) * t,
                from.right + (to.right - from.right) * t,
                from.bottom + (to.bottom - from.bottom) * t);
    }

    // ---- throttled logcat-only debug (Settings hn_port_win_debug=1) ----
    private static long sLastUdDbg;
    private static long sLastAshDbg;
    private static long sLastWfsDbg;
    private static long sLastBrkDbg;
    private static Field fTargets;
    private static Field fHandles;

    private static void dbgUd(String info) {
        if (!Port.on(Port.KEY_WIN_DEBUG) || SystemClock.uptimeMillis() - sLastUdDbg < 120L) {
            return;
        }
        sLastUdDbg = SystemClock.uptimeMillis();
        Log.d("HnWFS", info);
    }

    private static void dbgAsh(String info) {
        if (SystemClock.uptimeMillis() - sLastAshDbg < 150L) {
            return;
        }
        sLastAshDbg = SystemClock.uptimeMillis();
        Log.d("HnWFS", info);
    }

    private static void dbgWfs(String info) {
        if (!Port.on(Port.KEY_WIN_DEBUG) || SystemClock.uptimeMillis() - sLastWfsDbg < 100L) {
            return;
        }
        sLastWfsDbg = SystemClock.uptimeMillis();
        Log.d("HnWFS", info);
    }

    private static boolean dbgOn() {
        return Port.on(Port.KEY_WIN_DEBUG);
    }

    private static void dbgBrk(String info) {
        if (!Port.on(Port.KEY_WIN_DEBUG) || SystemClock.uptimeMillis() - sLastBrkDbg < 200L) {
            return;
        }
        sLastBrkDbg = SystemClock.uptimeMillis();
        Log.d("HnWFS", info);
    }

    /** Declared-field lookup that walks up the class hierarchy. */
    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
                // try superclass
            }
        }
        throw new NoSuchFieldException(name + " not found in " + c.getName());
    }

    private WindowFollowScale() {}
}
