package com.wxz.hnlauncherport.back;

import com.wxz.hnlauncherport.Port;
import com.wxz.hnlauncherport.motion.SpringFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 返回键回桌面 - 图标分支弹簧调优 (KEY_MOTION, 应用启动关闭动效优化).
 *
 * V14 back-key-with-icon animation: QuickstepAppTransitionManagerImpl
 * -> IconBackAnimation; the 5 property animators (center x/y, width,
 * corner, clip) each take getInterpolator(i) built from
 * ICON_BACK_SPRING_PARAM_LIST (default zeta 0.76..0.94, durations
 * longer than the swipe-up home path).
 *
 * Tune, when the result is non-null and KEY_MOTION is on:
 *   - whole spring group to zeta = 0.9 (damping = 1.8 * sqrt(stiffness))
 *   - duration capped at 670 ms = AnimConstants.sMaxDurationReturnHome,
 *     the same cap as the swipe-up return-home branch: if the zeta-0.9
 *     spring still settles slower, scale stiffness by (dur/670)^2
 *     (settle time ~ 1/sqrt(k) at fixed zeta) and recompute damping.
 * Live values are read each call so cloud AnimScene overrides
 * (updateIconBackAnimPara) are respected as the base.
 */
public final class IconBackSpringTune {

    private static final String ICON_BACK_CLS =
            "com.hihonor.android.launcher.quickstep.iconback.IconBackAnimation";
    private static final float TARGET_ZETA = 1.0f;
    private static final float TARGET_DURATION_MS = 450f;

    public static void install(ClassLoader cl) {
        Class<?> iconBack = XposedHelpers.findClass(ICON_BACK_CLS, cl);
        XposedHelpers.findAndHookMethod(iconBack, "getInterpolator", int.class,
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() == null) {
                    return;
                }
                if (!Port.on(Port.KEY_MOTION)) {
                    return;
                }
                try {
                    Object[] list = (Object[]) XposedHelpers.getStaticObjectField(
                            iconBack, "ICON_BACK_SPRING_PARAM_LIST");
                    int i = (Integer) param.args[0];
                    if (list == null || list.length == 0) {
                        return;
                    }
                    if (i < 0 || i >= list.length) {
                        i = 0; // stock uses the same clamp
                    }
                    Object sp = list[i];
                    float k = XposedHelpers.getFloatField(sp, "stiffness");
                    float v = XposedHelpers.getFloatField(sp, "velocity");
                    float mvc = XposedHelpers.getFloatField(sp, "minVisibleChange");
                    float d = (float) (TARGET_ZETA * 2.0 * Math.sqrt((double) k));
                    Object itp = SpringFactory.make(cl, k, d, 1.0f, v, mvc);
                    if (itp == null) {
                        return;
                    }
                    float dur = ((Number) XposedHelpers.callMethod(itp, "getDuration"))
                            .floatValue();
                    if (dur > TARGET_DURATION_MS) {
                        float scale = (dur / TARGET_DURATION_MS) * (dur / TARGET_DURATION_MS);
                        k *= scale;
                        d = (float) (TARGET_ZETA * 2.0 * Math.sqrt((double) k));
                        itp = SpringFactory.make(cl, k, d, 1.0f, v, mvc);
                        if (itp == null) {
                            return;
                        }
                    }
                    param.setResult(itp);
                    Port.log("IconBackTune i=" + i + " k=" + k + " d=" + d
                            + " dur=" + XposedHelpers.callMethod(itp, "getDuration"));
                } catch (Throwable t) {
                    Port.log("IconBackTune failed", t);
                }
            }
        });
        Port.log("IconBackSpringTune installed");
    }

    private IconBackSpringTune() {}
}
