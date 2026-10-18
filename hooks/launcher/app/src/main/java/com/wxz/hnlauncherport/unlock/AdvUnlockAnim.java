package com.wxz.hnlauncherport.unlock;

import com.wxz.hnlauncherport.Port;

import java.util.List;
import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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
    private static final String SCALE_ANIM_CLS =
            "com.hihonor.hncircularscaleanimation.HnCircularScaleAnimation";

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

        // 中心点上移: 扩散/分组中心 y 从屏幕几何中心 (y/2) 改为 y*0.3
        // (x 保持屏幕水平中心). HnCircularScaleAnimation 的同心环分组
        // (getHierarchicalViewList), 每组缩放锚点 (animateCircle setScale
        // pivot) 与错峰时序全部由 mPivotX/Y 派生, 构造期替换参数即全部
        // 联动. 构造实参 i2 = screenRealSize.y/2, 故 y*0.3 = i2*0.6.
        try {
            final Class<?> scaleAnimCls = XposedHelpers.findClass(SCALE_ANIM_CLS, cl);
            XposedBridge.hookAllConstructors(scaleAnimCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_ADV_UNLOCK_ANIM)) {
                                return;
                            }
                            // (List<View>, int pivotX, int pivotY)
                            if (param.args == null || param.args.length != 3) {
                                return;
                            }
                            int halfY = (Integer) param.args[2];
                            param.args[2] = (int) (halfY * 0.6f);
                            Port.log("AdvUnlockAnim: center y *0.3 -> "
                                    + param.args[1] + "," + param.args[2]);
                        }
                    });
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim center hook failed", t);
        }

        // 透明度曲线替换: HnCircularScaleAnimation.animateCircle:104 的
        // alpha 插值器为 static final 字段 cubic_bezier_interpolator_type_33_33
        // = PathInterpolator(0.33, 0, 0.67, 1) (200ms), 替换为
        // PathInterpolator(0, 0, 0.5, 1) (慢起缓收). 写 static final 对象
        // 字段会先触发 <clinit> 再赋值, 我们的值生效.
        try {
            final Class<?> scaleAnimCls2 = XposedHelpers.findClass(SCALE_ANIM_CLS, cl);
            XposedHelpers.setStaticObjectField(scaleAnimCls2,
                    "cubic_bezier_interpolator_type_33_33",
                    new android.view.animation.PathInterpolator(0f, 0f, 0.5f, 1f));
            Port.log("AdvUnlockAnim: alpha curve -> bezier(0,0,0.5,1)");
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim alpha-curve failed", t);
        }

        Port.log("AdvUnlockAnim installed");
    }

    private AdvUnlockAnim() {}
}
