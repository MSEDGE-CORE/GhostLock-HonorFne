package com.wxz.hnlauncherport.blur;

import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Force-enable V14's transition blur (open app / return home / enter recents)
 * on every model (解除机型限制).
 *
 * V14 already ships the full real-window-blur chain
 *   LevelAnimationMgr (radius 109) -> DepthController.stateDepth
 *   -> BaseDepthController.applyDepthAndBlur
 *   -> TransactionCompat.setBackgroundBlurRadius (SF background blur)
 * plus a RenderEffect fallback, but it is gated by:
 *   - Utilities.isEnableWindowBlur()            (ro.surface_flinger.supports_background_blur + cloud)
 *   - LowPerfAnimationHelper.isLightWeightBlurEffectEnabled() / isCutBlurEffect() (cloud anim_app_blur_effect)
 * Hooking these two gates on turns the existing chain on for all devices.
 *
 * NOTE: getUseSpaceHierarchyAnim() (cloud param "use_space_hierarchy_anim",
 * false on this device) is REQUIRED for the blur feature - both the master
 * gate (LevelAnimationMgr.resetState:409) and the return-home blur trigger
 * (createWindowAnimationToHome:1253 -> LevelAnimationMgr.changeScene(2,...))
 * live behind it. Removing it killed the blur entirely (user-verified
 * regression). It is re-forced here; the status-bar/nav-bar scope complaint
 * from the earlier build needs a separate surgical fix once re-described.
 * isBlurShowAccessibility() is additionally forced as a second unlock of the
 * resetState gate (its only other consumer is the accessibility branch :488).
 */
public final class ForceBlur {
    private static final String UTILITIES = "com.hihonor.android.launcher.Utilities";
    private static final String LOW_PERF = "com.hihonor.android.launcher.quickstep.LowPerfAnimationHelper";
    private static final String LEVEL_MGR = "com.hihonor.android.launcher.anim.LevelAnimationMgr";
    private static final String SCENE_MGR = "com.hihonor.android.launcher.utils.RecentsAnimSceneManager";
    private static final String LAUNCHER = "com.hihonor.android.launcher.Launcher";
    private static final String HN_STATE = "com.hihonor.android.launcher.quickstep.util.HnLauncherState";
    /** Smooth exit fade replacing instant kills; matches icon path dur/3 (~400/3). */
    private static final long EXIT_FADE_MS = 133L;
    /** Task 25: icon-layer scale the enter-recents settle must land on. */
    private static final float SETTLED_ICON_SCALE = 0.9f;

    public static void install(ClassLoader cl) {
        try {
            Class<?> u = XposedHelpers.findClass(UTILITIES, cl);
            XposedHelpers.findAndHookMethod(u, "isEnableWindowBlur", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (Port.on(Port.KEY_BLUR)) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });

            Class<?> lp = XposedHelpers.findClass(LOW_PERF, cl);
            XposedHelpers.findAndHookMethod(lp, "isLightWeightBlurEffectEnabled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (Port.on(Port.KEY_BLUR)) {
                        param.setResult(Boolean.FALSE);
                    }
                }
            });
            try {
                XposedHelpers.findAndHookMethod(lp, "isCutBlurEffect", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_BLUR)) {
                            param.setResult(Boolean.FALSE);
                        }
                    }
                });
            } catch (Throwable ignore) {
            }

            try {
                Class<?> lm = XposedHelpers.findClass(LEVEL_MGR, cl);
                XposedHelpers.findAndHookMethod(lm, "isBlurShowAccessibility", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_BLUR)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
            } catch (Throwable t) {
                Port.log("ForceBlur level-mgr gate hook failed", t);
            }

            try {
                Class<?> sm = XposedHelpers.findClass(SCENE_MGR, cl);
                XposedHelpers.findAndHookMethod(sm, "getUseSpaceHierarchyAnim", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_BLUR)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
            } catch (Throwable t) {
                Port.log("ForceBlur scene-mgr hook failed", t);
            }

            // V17 Motion blur curve (摘要3 §3): the app-scene blur becomes
            //   enter: CubicBezier(0.32, 0.94, 0.6, 1.0) over 433ms
            //          (V17 LevelAnimationMgr.sStartAppBlurInterpolater +
            //           APP_OPEN_BLUR_DURATION = sStartAppLevelAnimDuration)
            //   exit : LINEAR (V17 Interpolators.LINEAR on the Motion path)
            // V14 changeScene is a public virtual method on the singleton, so
            // hooking it is reliable; the interpolator is applied to the
            // ValueAnimator this call created (identity-checked against the
            // previous mBlurLevelAnimationState.mValueAnimator) before its
            // first frame, which is safe. Folder/recents scenes and the
            // resetBlurFraction correction path stay untouched.
            try {
                Class<?> lmCurve = XposedHelpers.findClass(LEVEL_MGR, cl);
                XposedHelpers.findAndHookMethod(lmCurve, "changeScene",
                        int.class, int.class, boolean.class, long.class, new XC_MethodHook() {
                            private ValueAnimator beforeAnim;

                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                beforeAnim = null;
                                if (!Port.on(Port.KEY_BLUR)) {
                                    return;
                                }
                                int scene = (Integer) param.args[0];
                                int type = (Integer) param.args[1];
                                boolean enter = (Boolean) param.args[2];
                                // Task 23 v2: recents-policy exit smoothing. After the
                                // swipe-up "show task" haptic, showTask() has already
                                // gone to OVERVIEW and engaged the policy blur
                                // ([0][0]=1, mPolicyValue[0]=1), so on release the
                                // :2849 resetBlurFraction no-ops on its OVERVIEW guard
                                // and the REAL kill is the OVERVIEW->NORMAL setState ->
                                // processStateForNormal:375 -> cleanBlurAnimAndBackground
                                // -> clearWindowBlurAndScale -> changeScene(0,0,false,0)
                                // -> 0ms policy fade. Stretch that kill to a 133ms fade
                                // when it would actually turn a visible blur off. The
                                // setPolicyValue sum check keeps true no-ops intact, and
                                // [0][1]==0 keeps an open folder's policy bit stock.
                                if (scene == 0 && type == 0 && !enter
                                        && (Long) param.args[3] == 0L) {
                                    try {
                                        int[][] policy = (int[][]) XposedHelpers.getObjectField(
                                                param.thisObject, "mBlurPolicyAnimation");
                                        float lastFraction = XposedHelpers.getFloatField(
                                                param.thisObject, "mLastBlurFraction");
                                        if (policy != null && policy.length > 0
                                                && policy[0] != null
                                                && policy[0][0] != 0
                                                && (policy[0].length < 2 || policy[0][1] == 0)
                                                && lastFraction > 0.0f) {
                                            param.args[3] = EXIT_FADE_MS;
                                            Port.log("ForceBlur policy-exit smoothed");
                                        }
                                    } catch (Throwable t) {
                                        Port.log("ForceBlur policy-exit check failed", t);
                                    }
                                    return;
                                }
                                if (scene != 2 || type != 0) {
                                    return;
                                }
                                try {
                                    Object state = XposedHelpers.getObjectField(
                                            param.thisObject, "mBlurLevelAnimationState");
                                    beforeAnim = (ValueAnimator) XposedHelpers.getObjectField(
                                            state, "mValueAnimator");
                                } catch (Throwable ignore) {
                                }
                                if (enter) {
                                    // 433 >= 300 also drops V14's extra 50ms
                                    // startDelay, matching V17 Motion exactly
                                    param.args[3] = 433L;
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!Port.on(Port.KEY_BLUR)) {
                                    return;
                                }
                                int scene = (Integer) param.args[0];
                                int type = (Integer) param.args[1];
                                boolean enter = (Boolean) param.args[2];
                                if (scene != 2 || type != 0) {
                                    return;
                                }
                                try {
                                    Object state = XposedHelpers.getObjectField(
                                            param.thisObject, "mBlurLevelAnimationState");
                                    ValueAnimator anim = (ValueAnimator) XposedHelpers.getObjectField(
                                            state, "mValueAnimator");
                                    if (anim == null || anim == beforeAnim || !anim.isStarted()) {
                                        return;
                                    }
                                    if (enter) {
                                        anim.setInterpolator(new PathInterpolator(
                                                0.32f, 0.94f, 0.6f, 1.0f));
                                    } else {
                                        anim.setInterpolator(new LinearInterpolator());
                                    }
                                    Port.log("ForceBlur motion-curve enter=" + enter
                                            + " dur=" + anim.getDuration());
                                } catch (Throwable t) {
                                    Port.log("ForceBlur motion-curve apply failed", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Port.log("ForceBlur motion-curve hook failed", t);
            }

            // Task 23: no-icon / capsule return-home blur snap fix.
            // Verified root cause: the icon path posts a graceful exit fade
            // (createWindowAnimationToHome:1245 -> changeScene(2,0,false,dur/3))
            // and bypasses StateManager, but the no-icon / capsule path
            // (OtherActivityInputConsumerEx.returnHomeWindowAnim) never posts
            // that fade; instead returnHome() -> finishCurrentTransitionToHome
            // (:2849) and returnHomeAnimationEnd() -> RecentsView.reset()
            // (:1989) call resetBlurFraction(0,false) = instant 0ms kill.
            // Fix: while blur is visible, swap every exit kill (f==0) for a
            // smooth changeScene(2,0,false,EXIT_FADE_MS) fade. The original
            // guards (open folder / OVERVIEW / recents policy row active) are
            // replicated so folder and recents blur keep stock behavior.
            // Re-kills during a fade are safe: doBlurAnimator re-anchors the
            // exit radius at the last rendered mLastBlurRadiu, so cancel +
            // restart never pops. The fade also clears mIsAppBlurEnter via
            // doApplicationAnimator, which disarms RecentsView.reset's own
            // getIsAppBlurEnter() kill right after.
            try {
                Class<?> lmFade = XposedHelpers.findClass(LEVEL_MGR, cl);
                Class<?> launcherCls = XposedHelpers.findClass(LAUNCHER, cl);
                Class<?> stateCls = XposedHelpers.findClass(HN_STATE, cl);
                XposedHelpers.findAndHookMethod(lmFade, "resetBlurFraction",
                        float.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!Port.on(Port.KEY_BLUR)) {
                                    return;
                                }
                                 // per-frame 探针: 仅调试时开 (帧路径 socket 写日志会阻塞主线程)
                                 if (Port.on(Port.KEY_WIN_DEBUG)) {
                                     try {
                                         Object probeMgr = param.thisObject;
                                         float probeLast = XposedHelpers.getFloatField(
                                                 probeMgr, "mLastBlurFraction");
                                         int[] probePol = (int[]) XposedHelpers.getObjectField(
                                                 probeMgr, "mPolicyValue");
                                         Port.log("rBFProbe f=" + param.args[0] + " z="
                                                 + param.args[1] + " last=" + probeLast
                                                 + " pol0=" + (probePol == null ? -1 : probePol[0]));
                                     } catch (Throwable t) {
                                         Port.log("rBFProbe failed", t);
                                     }
                                 }
                                if ((Float) param.args[0] != 0.0f) {
                                    return; // only intercept exit kills
                                }
                                Object mgr = param.thisObject;
                                float lastFraction = XposedHelpers.getFloatField(mgr, "mLastBlurFraction");
                                if (lastFraction <= 0.0f) {
                                    return; // nothing visible, original no-ops
                                }
                                // replicate original guards: folder / recents blur stay stock
                                Object launcher = XposedHelpers.callStaticMethod(launcherCls, "getLauncher");
                                if (launcher == null) {
                                    return;
                                }
                                Object workspace = XposedHelpers.callMethod(launcher, "getWorkspace");
                                if (workspace != null
                                        && XposedHelpers.callMethod(workspace, "getOpenFolder") != null) {
                                    return;
                                }
                                Object stateManager = XposedHelpers.callMethod(launcher, "getStateManager");
                                Object state = stateManager == null
                                        ? null : XposedHelpers.callMethod(stateManager, "getState");
                                if (state == XposedHelpers.getStaticObjectField(stateCls, "OVERVIEW")) {
                                    return;
                                }
                                int[] policy = (int[]) XposedHelpers.getObjectField(mgr, "mPolicyValue");
                                if (policy != null && policy[0] != 0) {
                                    return;
                                }
                                try {
                                    XposedHelpers.callMethod(mgr, "changeScene",
                                            2, 0, Boolean.FALSE, EXIT_FADE_MS);
                                    param.setResult(null);
                                } catch (Throwable t) {
                                    Port.log("ForceBlur smooth-exit failed, stock fallback", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Port.log("ForceBlur smooth-exit hook failed", t);
            }

            // Task 25: entering recents (navbar swipe-up = 进入后台) must settle
            // the desktop icon layer at 0.9. Chain: NoButtonNavbarToOverview
            // TouchController.goToOverviewOrHomeOnDragEnd:305 -> HomeOverview
            // AnimationHelper.goToOverviewOrHomeOnDragEnd:116 (hardcoded
            // OVERVIEW) -> processStateOutRecentAnimation -> doStateOutAnimation
            // settle animator -> updateBlurOutAnimation(:486-508). The settle's
            // OVERVIEW branch only fixes releases BELOW 0.9 (`if (f2 >= 0.9f)
            // return; f4 = (1-f)*(0.9-f2)+f2`); a release ABOVE 0.9 leaves the
            // icons at the finger position, so they never settle at the minimum.
            // Replace the branch so every release settles at 0.9:
            //   f4 = 0.9 + f * (f2 - 0.9),  f: 1 -> 0 over the settle.
            // (releases below 0.9 keep the stock bounce-up to 0.9; releases
            // above 0.9 now shrink down to 0.9 - never further, NOT to 0.85).
            // Only the OVERVIEW settle is replaced; the NORMAL branch (spring
            // back to 1.0 when returning home) keeps stock.
            try {
                Class<?> helperCls = XposedHelpers.findClass(
                        "com.hihonor.android.launcher.quickstep.util.uioverrides"
                                + ".HomeOverviewAnimationHelper", cl);
                Class<?> stateCls25 = XposedHelpers.findClass(
                        "com.android.launcher3.LauncherState", cl);
                final Class<?> hnStateCls = XposedHelpers.findClass(HN_STATE, cl);
                XposedHelpers.findAndHookMethod(helperCls, "updateBlurOutAnimation",
                        float.class, stateCls25, float.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!Port.on(Port.KEY_BLUR)) {
                                    return;
                                }
                                Object target = param.args[1];
                                Object overview = XposedHelpers.getStaticObjectField(
                                        hnStateCls, "OVERVIEW");
                                if (target != overview) {
                                    return; // NORMAL / other states keep stock
                                }
                                float f = (Float) param.args[0];
                                float releaseScale = (Float) param.args[2];
                                float settled = SETTLED_ICON_SCALE
                                        + f * (releaseScale - SETTLED_ICON_SCALE);
                                try {
                                    XposedHelpers.callMethod(param.thisObject,
                                            "setBackgroundScale", settled);
                                    param.setResult(null);
                                } catch (Throwable t) {
                                    Port.log("ForceBlur icon-settle failed, stock fallback", t);
                                }
                            }
                        });
            } catch (Throwable t) {
                Port.log("ForceBlur icon-settle hook failed", t);
            }

            // Swipe-up return-home blur restore. The icon-back path re-blurs
            // the desktop before fading (QuickstepAppTransitionManagerImpl
            // -> IconBackAnimation.startBlurAnimation -> resetBlurFraction
            // (1.0f, true)), but the createWindowAnimationToHome path taken
            // when swiping up right after the open animation settles only
            // posts the exit fade (lambda$createWindowAnimationToHome$2 ->
            // changeScene(2,0,false,duration/3)) with no re-enter; with the
            // open-completion kill already at 0 the fade runs 0 -> 0 and the
            // whole return shows an unblurred desktop. Re-enter blur here
            // when it is already gone so the exit fade starts from 1.
            try {
                Class<?> handlerCls = XposedHelpers.findClass(
                        "com.hihonor.android.launcher.extension.quickstep"
                                + ".HnLauncherSwipeHandlerV2", cl);
                Class<?> lmReturn = XposedHelpers.findClass(LEVEL_MGR, cl);
                XposedHelpers.findAndHookMethod(handlerCls,
                        "createWindowAnimationToHome",
                        View.class, RectF.class, RectF.class,
                        boolean.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (!Port.on(Port.KEY_BLUR)) {
                                    return;
                                }
                                try {
                                    Object mgr = XposedHelpers.callStaticMethod(
                                            lmReturn, "instance");
                                    float last = XposedHelpers.getFloatField(
                                            mgr, "mLastBlurFraction");
                                    if (last <= 0.0f) {
                                        XposedHelpers.callMethod(mgr,
                                                "resetBlurFraction", 1.0f, Boolean.TRUE);
                                        Port.log("ForceBlur return-home re-blur");
                                    }
                                } catch (Throwable t) {
                                    Port.log("ForceBlur return-home re-blur failed", t);
                                }
                            }
                        });
                Port.log("ForceBlur return-home restore hooked");
            } catch (Throwable t) {
                Port.log("ForceBlur return-home restore hook failed", t);
            }

            // 搜索页(全局搜索)高级模糊强制启用. V14 进入搜索页的窗口模糊
            // 机型区分链 (见分析):
            //   GlobalSearchUtils.isEnableWindowBlur()
            //     = Utilities.isEnableWindowBlur()         <- 已由上方 hook 强制
            //       && isEnableSearchBlur                  <- com.hihonor.search
            //       (app meta-data "window.blur.supported", 机型/搜索App版本区分)
            //       && (!isSearchSupportXmlScene || UseSpaceHierarchyAnim)
            // Utilities 之外的搜索 App meta-data 门槛在此汇总方法上一并强制,
            // 使所有机型进入搜索页都走真窗口模糊 (mBlurView 壁纸位图降级/
            // Lite 无模糊路径不再触发).
            try {
                Class<?> gsu = XposedHelpers.findClass(
                        "com.hihonor.android.launcher.globalsearch.GlobalSearchUtils", cl);
                XposedHelpers.findAndHookMethod(gsu, "isEnableWindowBlur", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_BLUR)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
                Port.log("ForceBlur search-blur hooked");
            } catch (Throwable t) {
                Port.log("ForceBlur search-blur hook failed", t);
            }

            Port.log("ForceBlur installed");
        } catch (Throwable t) {
            Port.log("ForceBlur install failed");
        }
    }

    private ForceBlur() {}
}
