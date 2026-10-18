package com.wxz.hnlauncherport.warm;

import android.os.Bundle;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 启动预热: 桌面进程初次启动时在后台线程预加载返回/启动动效热路径类.
 *
 * 原理: 首次手势返回掉帧中可归因于"类首次加载 + verify + 静态初始化"
 * (binder 预热会话之外的另一块冷开销). 把这部分挪到启动期后台完成:
 * - Class.forName(name, true, cl): 触发 load + verify + <clinit>,
 *   插值器/动画工具类的静态字段 (Interpolator 实例等) 在此阶段构造;
 * - 静态初始化执行到的方法同时喂给 ART 解释器累积 JIT profile.
 *
 * 边界 (诚实说明): JIT 只编译"执行过"的方法, 后台无法凭空编译 View
 * 绘制代码——绘制路径的完整预热需系统级 baseline profile (dexopt),
 * 模块层不可达. 本 hook 消除的是类加载/verify/静态初始化这部分冷开销.
 *
 * 线程安全: 仅做类加载, 不触碰 View/Looper, 不持有 launcher 引用.
 * 策略: 每类独立 try/catch, 单类缺失 (不同版本差异) 不影响其余.
 */
public final class StartupWarmup {
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";

    /** 返回/启动动效热路径类 (V14 FNE 实测类名). */
    private static final String[] WARM_CLASSES = {
            // 状态
            "com.hihonor.android.launcher.quickstep.util.uioverrides.HnBackgroundAppState",
            "com.hihonor.android.launcher.state.LauncherState",
            "com.hihonor.android.launcher.state.LauncherStatusChangeMgr",
            // 动画核心/插值器 (静态初始化构造 Interpolator 实例)
            "com.hihonor.android.launcher.anim.CommonInterpolators",
            "com.hihonor.android.launcher.anim.AnimationManager",
            "com.hihonor.android.launcher.anim.AnimBooster",
            "com.hihonor.android.launcher.animutils.Curves",
            "com.hihonor.android.launcher.animutils.AnimationHelper",
            "com.hihonor.android.launcher.animutils.FirstFrameAnimatorHelper",
            // 上滑手势/窗口变换
            "com.android.quickstep.SwipeUpAnimationLogic",
            "com.android.quickstep.util.SurfaceTransaction",
            "com.android.quickstep.util.SurfaceTransactionApplier",
            "com.android.quickstep.util.RectFSpringAnim",
            "com.android.quickstep.util.StaggeredWorkspaceAnim",
            "com.android.quickstep.util.FlingSpringAnim",
            // 返回桌面编排
            "com.hihonor.android.launcher.anim.OverviewToHomeAnim",
            "com.hihonor.android.launcher.MotionManager",
            "com.hihonor.android.launcher.AppMultiTaskAnimController",
    };

    public static void install(ClassLoader cl) {
        Class<?> launcher = XposedHelpers.findClass(LAUNCHER_CLS, cl);

        XposedHelpers.findAndHookMethod(launcher, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_MOTION)) {
                            return;
                        }
                        // onLoadInstanceState 为 null = 冷启动, 避免每次旋转/重建重复预热
                        // (重建时进程内类已加载, Class.forName 直接命中缓存, 开销可忽略;
                        //  仍只跑一次以保持日志干净)
                        warmAsync(cl);
                    }
                });

        Port.log("StartupWarmup installed");
    }

    private static volatile boolean sDone = false;

    private static void warmAsync(final ClassLoader cl) {
        if (sDone) {
            return;
        }
        sDone = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long start = android.os.SystemClock.uptimeMillis();
                int ok = 0;
                for (String name : WARM_CLASSES) {
                    try {
                        Class.forName(name, true, cl);
                        ok++;
                    } catch (Throwable ignored) {
                        // 不同版本类名可能缺失, 跳过
                    }
                }
                Port.log("StartupWarmup: " + ok + "/" + WARM_CLASSES.length
                        + " classes warmed in "
                        + (android.os.SystemClock.uptimeMillis() - start) + "ms");
            }
        }, "HnPortWarmup");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private StartupWarmup() {}
}
