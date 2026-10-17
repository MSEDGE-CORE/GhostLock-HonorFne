package com.wxz.hnlauncherport.unlock;

import com.wxz.hnlauncherport.Port;

import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 任务26: 启用高级解锁动画 (图标圆形扩散入场).
 *
 * Stock 链路: Launcher.initFollowers:1489
 *   -> UnlockAnimationManagerFactory.createAnimationManager(launcher)
 *   -> 档位判定:
 *      framework 数组(33816590)配置优先 ("anim_unlock_enter_launcher:0/1");
 *      否则 NormalUnlockAnimationManager.getAnimationLevel()
 *      = sysprop msc.launcher.unlock_animation_level (本机=1)
 *      -> ==1 强制 LiteUnlockAnimationManager (轻量版).
 * 本机 msc.launcher.unlock_animation_level=1 -> 当前就是 Lite 轻量版.
 *
 * 拦截: before-hook createAnimationManager, 开关开启时直接返回
 * CircularUnlockAnimationManager (fullAnim=0 高级档, 图标从屏幕中心
 * 圆形扩散入场, HnCircularScaleAnimation spring 参数自带非零默认值
 * 350/30/15/1.8, 无 AnimScene 云配置也能正常播放).
 */
public final class AdvUnlockAnim {
    private static final String FACTORY_CLS =
            "com.hihonor.android.launcher.unlock.UnlockAnimationManagerFactory";
    private static final String CIRCULAR_CLS =
            "com.hihonor.android.launcher.unlock.CircularUnlockAnimationManager";
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";

    public static void install(ClassLoader cl) {
        final Class<?> factoryCls = XposedHelpers.findClass(FACTORY_CLS, cl);
        final Class<?> circularCls = XposedHelpers.findClass(CIRCULAR_CLS, cl);
        Class<?> launcherCls = XposedHelpers.findClass(LAUNCHER_CLS, cl);

        XposedHelpers.findAndHookMethod(factoryCls, "createAnimationManager",
                launcherCls, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_ADV_UNLOCK_ANIM)) {
                            return;
                        }
                        try {
                            Object mgr = XposedHelpers.newInstance(circularCls, param.args[0]);
                            param.setResult(Optional.of(mgr));
                            Port.log("AdvUnlockAnim: force circular unlock animation");
                        } catch (Throwable t) {
                            Port.log("AdvUnlockAnim create failed", t);
                        }
                    }
                });

        Port.log("AdvUnlockAnim installed");
    }

    private AdvUnlockAnim() {}
}
