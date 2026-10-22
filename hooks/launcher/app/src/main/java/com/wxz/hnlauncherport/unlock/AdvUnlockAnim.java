package com.wxz.hnlauncherport.unlock;

import android.graphics.Matrix;
import android.view.View;

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

        // 中心点/锚点: 扩散/分组中心 y 默认移到屏高 0.3 处 (x 保持屏幕水平
        // 中心), 可通过 KEY_ADV_UNLOCK_ANCHOR 自定义 (0-1 屏高比例).
        // HnCircularScaleAnimation 的同心环分组 (getHierarchicalViewList),
        // 每组缩放锚点 (animateCircle setScale pivot) 与错峰时序全部由
        // mPivotX/Y 派生, 构造期替换参数即全部联动. 构造实参 i2 =
        // screenRealSize.y/2, 故 pivotY = i2 * anchor * 2.
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
                            float anchor = Port.getFloat(Port.KEY_ADV_UNLOCK_ANCHOR, 0.3f);
                            if (anchor < 0f) {
                                anchor = 0f;
                            } else if (anchor > 1f) {
                                anchor = 1f;
                            }
                            int halfY = (Integer) param.args[2];
                            param.args[2] = (int) (halfY * anchor * 2f);
                        }
                    });
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim center hook failed", t);
        }

        // 起始尺寸: KEY_ADV_UNLOCK_SCALE (0.5-4) 覆盖 stock startScaleRatio
        // (stock 默认 1.8, 云配置可变); 未设置 (NaN) 保留 stock.
        try {
            final Class<?> scaleAnimCls3 = XposedHelpers.findClass(SCALE_ANIM_CLS, cl);
            XposedHelpers.findAndHookMethod(scaleAnimCls3, "setStartScaleRatio",
                    float.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_ADV_UNLOCK_ANIM)) {
                                return;
                            }
                            float sc = Port.getFloat(Port.KEY_ADV_UNLOCK_SCALE, Float.NaN);
                            if (Float.isNaN(sc)) {
                                return;
                            }
                            if (sc < 0.5f) {
                                sc = 0.5f;
                            } else if (sc > 4f) {
                                sc = 4f;
                            }
                            param.args[0] = sc;
                        }
                    });
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim scale hook failed", t);
        }

        // 分组透明度延时: KEY_ADV_UNLOCK_DELAY (0-60ms) 覆盖 stock 每组
        // 错峰延时 mDelay (stock 默认 30ms, 云配置可变); 未设置 (NaN) 保留
        // stock. 缩放错峰与 stock 一致, mDelay 同时驱动缩放错峰起始与
        // 每组 alpha 窗口起始.
        try {
            final Class<?> scaleAnimCls4 = XposedHelpers.findClass(SCALE_ANIM_CLS, cl);
            XposedHelpers.findAndHookMethod(scaleAnimCls4, "setDelay",
                    long.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_ADV_UNLOCK_ANIM)) {
                                return;
                            }
                            float dl = Port.getFloat(Port.KEY_ADV_UNLOCK_DELAY, Float.NaN);
                            if (Float.isNaN(dl)) {
                                return;
                            }
                            if (dl < 0f) {
                                dl = 0f;
                            } else if (dl > 60f) {
                                dl = 60f;
                            }
                            param.args[0] = (long) dl;
                        }
                    });
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim delay hook failed", t);
        }

        // 缩放不错峰: 整体替换 animateCircle — stock 中 mDelay 同时错峰
        // 缩放与 alpha (每组起始 = mDelay*组序号), 替换后缩放全组同步从
        // 动画起点播放, 透明度保留每组 mDelay 错峰 (KEY_ADV_UNLOCK_DELAY
        // 可调, 默认 stock 30ms). 异常时放行 stock 自愈.
        try {
            final Class<?> scaleAnimCls5 = XposedHelpers.findClass(SCALE_ANIM_CLS, cl);
            XposedHelpers.findAndHookMethod(scaleAnimCls5, "animateCircle",
                    float.class, List.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_ADV_UNLOCK_ANIM)) {
                                return;
                            }
                            try {
                                animateCircleNoStagger(param.thisObject,
                                        (Float) param.args[0],
                                        (List<?>) param.args[1],
                                        (Integer) param.args[2]);
                                param.setResult(null);
                            } catch (Throwable t) {
                                Port.log("AdvUnlockAnim animateCircle failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim circle hook failed", t);
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

    /**
     * animateCircle 自定义 alpha 版: 缩放错峰与 stock 完全一致 — 组 i 在
     * elapsed > mDelay*i 后开始, progress = (elapsed - mDelay*i) /
     * (mAllDuration - mDelay*i); 透明度保留每组错峰 — 组 i 的 alpha 窗口
     * = [mDelay*i, mDelay*i + mAlphaDuration], 组内走 bezier 曲线
     * (已被替换为 PathInterpolator(0,0,0.5,1)), 窗口前 alpha=0, 窗口后
     * 恢复 1. 每组完整淡入, 不像 stock 全局曲线会让晚启动的组跳变.
     */
    private static void animateCircleNoStagger(Object anim, float f, List<?> rawList, int idx) {
        long dur = ((Number) XposedHelpers.getObjectField(anim, "mAllDuration")).longValue();
        long delay = ((Number) XposedHelpers.getObjectField(anim, "mDelay")).longValue();
        long alphaDur = ((Number) XposedHelpers.getObjectField(anim, "mAlphaDuration")).longValue();
        float startRatio = XposedHelpers.getFloatField(anim, "mStartScaleRatio");
        int pivotX = XposedHelpers.getIntField(anim, "mPivotX");
        int pivotY = XposedHelpers.getIntField(anim, "mPivotY");
        float elapsed = dur * f;
        float groupStart = delay * idx;

        // 缩放: 与 stock 完全一致的错峰 (组 i 于 mDelay*i 后开始, 归一化
        // 到剩余时长, 全组同时于 mAllDuration 结束)
        if (elapsed > groupStart) {
            Object spring = XposedHelpers.getObjectField(anim, "mSpringInterpolator");
            float progress = (elapsed - groupStart) / (dur - groupStart);
            float interp = (Float) XposedHelpers.callMethod(spring, "getInterpolation", progress);
            float scale = startRatio + interp * (1f - startRatio);
            Matrix matrix = new Matrix();
            for (Object o : rawList) {
                View view = (View) o;
                int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                matrix.setScale(scale, scale, pivotX - loc[0], pivotY - loc[1]);
                view.setAnimationMatrix(matrix);
            }
        }

        // 透明度: 每组错峰, 组 i 起始 = mDelay*i, 窗口长 mAlphaDuration
        float groupElapsed = elapsed - groupStart;
        if (groupElapsed <= 0f) {
            for (Object o : rawList) {
                ((View) o).setAlpha(0f);
            }
        } else if (alphaDur > 0 && groupElapsed <= alphaDur) {
            Object bezier = XposedHelpers.getStaticObjectField(
                    anim.getClass(), "cubic_bezier_interpolator_type_33_33");
            float alpha = (Float) XposedHelpers.callMethod(
                    bezier, "getInterpolation", groupElapsed / (float) alphaDur);
            for (Object o : rawList) {
                ((View) o).setAlpha(alpha);
            }
        } else {
            for (Object o : rawList) {
                View view = (View) o;
                if (Math.abs(view.getAlpha() - 1f) > Float.MIN_VALUE) {
                    view.setAlpha(1f);
                }
            }
        }
    }

    private AdvUnlockAnim() {}
}
