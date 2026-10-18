package com.wxz.hnlauncherport.press;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.util.Property;
import android.view.View;

import com.wxz.hnlauncherport.Port;
import com.wxz.hnlauncherport.motion.SpringFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 强行启用图标按下缩小 (KEY_MOTION, 应用启动关闭动效优化).
 *
 * V14 SpringAnimationHelper.clickForScaleAnim (animutils :42-73) skips the
 * press-scale animation - returns null - when
 *   (RoPropConstant.LOW_LOAD_RECENT_ANIM && !mIsExistLevel)
 *   || !mIsIconUseAnim || RecentsAnimUtils.isPerformancePrefer()
 * so on this device (LOW_LOAD + no anim_icon scene push) icons never
 * shrink on press. When the stock method returns null, replicate the
 * exact stock animation (same static spring params, SCALE_PROPERTY,
 * min(spring duration, caller cap)) and return it so BubbleTextView
 * keeps its normal mClickAnimator cancel/restore path.
 */
public final class ForceIconPressScale {

    private static final String HELPER_CLS =
            "com.hihonor.android.launcher.animutils.SpringAnimationHelper";
    private static final String LAU_CLS =
            "com.hihonor.android.launcher.animutils.LauncherAnimUtils";

    public static void install(ClassLoader cl) {
        Class<?> helper = XposedHelpers.findClass(HELPER_CLS, cl);
        final Class<?> lau = XposedHelpers.findClass(LAU_CLS, cl);
        XposedHelpers.findAndHookMethod(helper, "clickForScaleAnim",
                View.class, float.class, float.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() != null) {
                    return; // stock animation already running
                }
                if (!Port.on(Port.KEY_MOTION)) {
                    return;
                }
                try {
                    View view = (View) param.args[0];
                    float from = (Float) param.args[1];
                    float to = (Float) param.args[2];
                    int capMs = (Integer) param.args[3];
                    if (view == null || Float.compare(from, to) == 0) {
                        return; // genuinely nothing to animate
                    }
                    // live static params (cloud AnimScene may have tuned them)
                    float k = asFloat(XposedHelpers.getStaticObjectField(helper, "mStiffnessScale"));
                    float d = asFloat(XposedHelpers.getStaticObjectField(helper, "mDampingScale"));
                    float vel = asFloat(XposedHelpers.getStaticObjectField(helper, "mClickScaleVelocity"));
                    float thr = asFloat(XposedHelpers.getStaticObjectField(helper, "mThresholdScale"));
                    Object itp = SpringFactory.make(cl, k, d,
                            Math.abs(from - to), vel, thr);
                    if (itp == null) {
                        return;
                    }
                    float dur = ((Number) XposedHelpers.callMethod(itp, "getDuration"))
                            .floatValue();
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    ObjectAnimator anim = ObjectAnimator.ofFloat(view,
                            (Property) XposedHelpers.getStaticObjectField(lau, "SCALE_PROPERTY"),
                            from, to);
                    anim.setInterpolator((TimeInterpolator) itp);
                    anim.setDuration((long) Math.min(dur, (float) capMs));
                    anim.start();
                    param.setResult(anim);
                    Port.log("ForcePressScale from=" + from + " to=" + to
                            + " dur=" + anim.getDuration());
                } catch (Throwable t) {
                    Port.log("ForcePressScale failed", t);
                }
            }
        });
        Port.log("ForceIconPressScale installed");
    }

    private static float asFloat(Object boxed) {
        return boxed instanceof Number ? ((Number) boxed).floatValue() : 0f;
    }

    private ForceIconPressScale() {}
}
