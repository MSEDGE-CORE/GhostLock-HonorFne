package com.wxz.hnlauncherport.launch;

import com.wxz.hnlauncherport.Port;
import com.wxz.hnlauncherport.motion.CurveDemoSpring;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 应用启动动效 - CurveDemo 解析式弹簧移植 (KEY_MOTION + KEY_MOTION_PATH,
 * 运动路径优化).
 *
 * Host pipeline (Launcher 14):
 *  - AppLaunchAnimation ctor: mAppLaunchInterpolator = new SpringInterpolator(
 *    GestureAnimationController.sAppLaunch{Stiffness,Damping,Velocity,
 *    ValueThreshold}) and, when sAppLaunchAnimLevel == 0,
 *    QuickstepAppTransitionManagerImpl.sAppLaunchDuration = getDuration();
 *    getOpeningWindowAnimators then does valueAnimator.setDuration(
 *    sAppLaunchDuration) - ctor-time sync keeps them consistent.
 *  - AppLaunchAnimation.createLaunchAnimInterpolator(): shared factory -
 *    feeds updateAppLaunchDurationIfNeeded (:256, pre-launch duration sync)
 *    and SinkAppLaunchAnimation.mAppLaunchInterpolator (:114).
 *  - TaskBarIconClickAnimation ctor: own mAppLaunchInterpolator field,
 *    same GestureAnimationController params (dock-bar icon launch).
 *
 * Replace all three with the CurveDemo slot-0 spring (zeta 0.82, T = 1.1s,
 * v0 = 0, minVisibleChange 0.001). Duration follows automatically because
 * every consumer derives it from the interpolator's getDuration().
 */
public final class LaunchSpringTune {

    private static final String APP_LAUNCH_CLS =
            "com.hihonor.android.launcher.quickstep.anim.AppLaunchAnimation";
    private static final String TASKBAR_CLS =
            "com.hihonor.android.launcher.extension.hntaskbar.TaskBarIconClickAnimation";
    private static final String QATMI_CLS =
            "com.hihonor.android.launcher.QuickstepAppTransitionManagerImpl";

    public static void install(ClassLoader cl) {
        Class<?> appLaunch = XposedHelpers.findClass(APP_LAUNCH_CLS, cl);

        // (1) ctor: swap the instance interpolator + resync sAppLaunchDuration
        // (covers subclass CardLaunchAnimation: base ctor runs during build)
        XposedBridge.hookAllConstructors(appLaunch, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                swapField(cl, param.thisObject, true);
            }
        });

        // (2) static factory: covers updateAppLaunchDurationIfNeeded and
        // SinkAppLaunchAnimation's field
        XposedHelpers.findAndHookMethod(appLaunch, "createLaunchAnimInterpolator",
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() == null || !enabled()) {
                    return;
                }
                try {
                    Object spring = CurveDemoSpring.make(cl, CurveDemoSpring.ZETA_XY,
                            1.0f, 0.0f, 0.001f);
                    if (spring != null) {
                        param.setResult(spring);
                    }
                } catch (Throwable t) {
                    Port.log("LaunchTune factory failed", t);
                }
            }
        });

        // (3) taskbar icon click animation
        try {
            Class<?> taskBar = XposedHelpers.findClassIfExists(TASKBAR_CLS, cl);
            if (taskBar != null) {
                XposedBridge.hookAllConstructors(taskBar, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        swapField(cl, param.thisObject, false);
                    }
                });
            }
        } catch (Throwable t) {
            Port.log("LaunchTune taskbar install failed", t);
        }
        Port.log("LaunchSpringTune installed");
    }

    private static boolean enabled() {
        return Port.on(Port.KEY_MOTION) && Port.on(Port.KEY_MOTION_PATH);
    }

    /**
     * Replace thisObject's mAppLaunchInterpolator with the CurveDemo spring
     * and resync QuickstepAppTransitionManagerImpl.sAppLaunchDuration when
     * syncDuration && AppLaunchAnimation.sAppLaunchAnimLevel == 0 (the same
     * gate stock uses).
     */
    private static void swapField(ClassLoader cl, Object target, boolean syncDuration) {
        if (!enabled()) {
            return;
        }
        try {
            Object spring = CurveDemoSpring.make(cl, CurveDemoSpring.ZETA_XY,
                    1.0f, 0.0f, 0.001f);
            if (spring == null) {
                return;
            }
            XposedHelpers.setObjectField(target, "mAppLaunchInterpolator", spring);
            if (syncDuration) {
                syncDuration(cl, spring);
            }
            Port.log("LaunchTune " + target.getClass().getSimpleName()
                    + " dur=" + XposedHelpers.callMethod(spring, "getDuration"));
        } catch (Throwable t) {
            Port.log("LaunchTune swap failed", t);
        }
    }

    private static void syncDuration(ClassLoader cl, Object spring) {
        try {
            Class<?> appLaunch = cl.loadClass(APP_LAUNCH_CLS);
            int level = XposedHelpers.getStaticIntField(appLaunch, "sAppLaunchAnimLevel");
            if (level != 0) {
                return;
            }
            int dur = ((Number) XposedHelpers.callMethod(spring, "getDuration")).intValue();
            Class<?> qatmi = cl.loadClass(QATMI_CLS);
            XposedHelpers.setStaticIntField(qatmi, "sAppLaunchDuration", dur);
        } catch (Throwable t) {
            Port.log("LaunchTune duration sync failed", t);
        }
    }

    private LaunchSpringTune() {}
}
