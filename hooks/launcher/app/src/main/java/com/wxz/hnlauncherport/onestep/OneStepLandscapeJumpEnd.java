package com.wxz.hnlauncherport.onestep;

import android.animation.Animator;
import android.graphics.RectF;
import android.view.View;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * A11 (launcher 侧, 挂 KEY_FF_ANIM): OneStep 进小窗松手后若为横屏,
 * 桌面返家动画 (windowReturnHomeAnimator) 直接跳到 end, 不播飞行帧.
 *
 * 链路: returnHomeWithAnimation -> returnHomeWindowAnim(rectF, rectF2, view)
 *   -> mInteractionHandler.getWindowReturnHomeAnimator
 *   -> HnLauncherSwipeHandlerV2.createWindowAnimationToHome(view, from, to,
 *      isReturnToIcon=false, isHotseatIcon=false) 返回 AnimatorSet
 *   -> addListener(onStart: transformFullscreenToMiniWindow 等; onEnd: 清理
 *      + returnHome + resetLauncherOrientation) -> start().
 *
 * 做法:
 *  (1) after-hook createWindowAnimationToHome: 仅当 z==false (非图标返回路径,
 *      避开 OtherActivityInputConsumerEx:796 的 isReturnToIcon=true 调用)
 *      且横屏 (DisplayRotationListener.isLandscape) 且在小窗热区
 *      (OneStepWindowMgr.isInFloatOrSplitWindowHot) 时, 捕获返回的 AnimatorSet;
 *  (2) before-hook returnHomeWindowAnim: 清掉可能的陈旧捕获 (防串扰);
 *  (3) after-hook returnHomeWindowAnim: start() 已同步跑完 onStart
 *      (transformFullscreenToMiniWindow 照发), 立即 end() 跳到终态,
 *      onEnd 清理链 (cleanWinSnapshotWithAnim/returnHome/
 *      setIsLandscapeBackHomeAnimDoing(false)/resetLauncherOrientation) 同帧执行.
 * 竖屏不受影响, 保持 stock.
 */
public final class OneStepLandscapeJumpEnd {
    private static final String CONSUMER_CLS =
            "com.hihonor.android.launcher.extension.quickstep.OtherActivityInputConsumerEx";
    private static final String HANDLER_CLS =
            "com.hihonor.android.launcher.extension.quickstep.HnLauncherSwipeHandlerV2";
    private static final String MGR_CLS =
            "com.hihonor.android.launcher.onestep.OneStepWindowMgr";
    private static final String ROT_CLS =
            "com.hihonor.android.launcher.quickstep.util.rotation.DisplayRotationListener";

    /** 本次 returnHomeWindowAnim 创建的 windowReturnHomeAnimator (仅主线程). */
    private static volatile Animator sPendingAnimator;

    public static void install(ClassLoader cl) {
        final Class<?> handlerCls = XposedHelpers.findClass(HANDLER_CLS, cl);
        final Class<?> mgrCls = XposedHelpers.findClass(MGR_CLS, cl);
        final Class<?> rotCls = XposedHelpers.findClass(ROT_CLS, cl);
        Class<?> consumerCls = XposedHelpers.findClass(CONSUMER_CLS, cl);

        // (1) 捕获 createWindowAnimationToHome 返回的 AnimatorSet.
        XposedHelpers.findAndHookMethod(handlerCls, "createWindowAnimationToHome",
                View.class, RectF.class, RectF.class, boolean.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        try {
                            if ((boolean) param.args[3]) {
                                return; // isReturnToIcon=true 的 :796 路径, 不属于 OneStep 热区链路
                            }
                            Animator animator = (Animator) param.getResult();
                            if (animator == null) {
                                return;
                            }
                            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
                            boolean hot = (boolean) XposedHelpers.callMethod(
                                    mgr, "isInFloatOrSplitWindowHot");
                            if (!hot) {
                                return;
                            }
                            boolean landscape = (boolean) XposedHelpers.callStaticMethod(
                                    rotCls, "isLandscape");
                            if (!landscape) {
                                return;
                            }
                            sPendingAnimator = animator;
                        } catch (Throwable t) {
                            Port.log("OneStepLandscapeJumpEnd capture failed", t);
                        }
                    }
                });

        // (2)+(3) returnHomeWindowAnim: before 清陈旧捕获, after start() 已同步
        //     跑完 onStart (transformFullscreenToMiniWindow 照发), 立即 end().
        XposedHelpers.findAndHookMethod(consumerCls, "returnHomeWindowAnim",
                RectF.class, RectF.class, View.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        sPendingAnimator = null;
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Animator animator = sPendingAnimator;
                        sPendingAnimator = null;
                        if (animator == null) {
                            return;
                        }
                        try {
                            animator.end();
                            Port.log("OneStepLandscapeJumpEnd: landscape return-home animator jump to end");
                        } catch (Throwable t) {
                            Port.log("OneStepLandscapeJumpEnd end failed", t);
                        }
                    }
                });
    }
}
