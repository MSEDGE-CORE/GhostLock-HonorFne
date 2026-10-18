package com.wxz.hnlauncherport.press;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 强行启用后台 (recents) 卡片按下缩小 (KEY_MOTION, 应用启动关闭动效优化).
 *
 * V14 TaskViewEx.startClickDownAnim/startClickUpAnim/cancelClick*
 * (extension/quickstep/views/taskview/TaskViewEx :100-187) all early-return
 * when
 *   LOW_LOAD_RECENT_ANIM || RecentsAnimUtils.isPerformancePrefer()
 * (or, when the "anim_multi_task_start_app" cloud overlay is present,
 * PowerModeManager.isPerformanceMode() || !sOpenStartClickAnim), so in
 * performance mode the recents card never shrinks to sClickDownScale on
 * press. RecentsAnimUtils.isPerformancePrefer (:231) is
 *   LOW_PERF_ANIM || PowerModeManager.isPerformanceMode()
 * i.e. performance mode alone suppresses the animation.
 *
 * Hook it to return false while KEY_MOTION is on: the TaskViewEx gate
 * passes and the stock startClickAnim (spring to 0.95 / back to 1.0)
 * runs natively. SpringAnimationHelper.clickForScaleAnim shares the same
 * gate, so the icon press shrink also returns to the fully stock path.
 */
public final class ForceRecentsPressScale {

    private static final String UTILS_CLS =
            "com.hihonor.android.launcher.quickstep.util.RecentsAnimUtils";

    public static void install(ClassLoader cl) {
        Class<?> utils = XposedHelpers.findClass(UTILS_CLS, cl);
        XposedHelpers.findAndHookMethod(utils, "isPerformancePrefer", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!Boolean.TRUE.equals(param.getResult())) {
                    return;
                }
                if (!Port.on(Port.KEY_MOTION)) {
                    return;
                }
                param.setResult(Boolean.FALSE);
                Port.log("ForceRecentsPressScale: isPerformancePrefer -> false");
            }
        });
        Port.log("ForceRecentsPressScale installed");
    }

    private ForceRecentsPressScale() {}
}
