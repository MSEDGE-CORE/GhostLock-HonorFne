package com.wxz.hnlauncherport.swipe;

import android.graphics.PointF;
import android.view.animation.Interpolator;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 任务: "跟手V17" (KEY_WIN_SCALE) 开时, swipeup 松手进后台
 * (endTarget = RECENTS) 的处理:
 *   - 主卡片 (松手窗口 -> 卡片槽位) 的 settle 动画禁用, 直接到 end
 *     (animateToProgress 时长置 0: frameCountInDuration(0)=0 跳过 sink
 *     路径 doEnterRecentsSinkAnim, shift 路径 animatorSet.setDuration(0)
 *     瞬时完成; onAnimationSuccess/onAnimationEnd 状态机照常收尾).
 *   - 其他卡片 (轮播 snapToPage 等) 保持 stock 350ms, 不干预.
 */
public final class DisableEnterRecentsAnim {
    private static final String HANDLER_CLS =
            "com.android.quickstep.AbsSwipeUpHandler";
    private static final String END_TARGET_CLS =
            "com.android.quickstep.GestureState$GestureEndTarget";

    public static void install(ClassLoader cl) {
        Class<?> handlerCls = XposedHelpers.findClass(HANDLER_CLS, cl);
        Class<?> endTargetCls = XposedHelpers.findClass(END_TARGET_CLS, cl);
        XposedHelpers.findAndHookMethod(handlerCls, "animateToProgress",
                float.class, float.class, long.class, Interpolator.class,
                endTargetCls, PointF.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_WIN_SCALE)) {
                            return;
                        }
                        Object target = param.args[4];
                        if (target == null || !"RECENTS".equals(target.toString())) {
                            return;
                        }
                        long dur = (Long) param.args[2];
                        if (dur > 0L) {
                            param.args[2] = 0L;
                            Port.log("DisableEnterRecentsAnim: RECENTS settle "
                                    + dur + "ms -> instant (main card jumps to end)");
                        }
                    }
                });
        Port.log("DisableEnterRecentsAnim installed");
    }

    private DisableEnterRecentsAnim() {}
}
