package com.wxz.hnlauncherport.systemui;

import android.view.View;
import android.view.WindowManager;

import com.wxz.hnlauncherport.Port;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI (com.android.systemui) hooks, SystemUI-状态栏/控制中心 分组:
 *
 * (1) 禁用状态栏自动缩小 (KEY_SB_NO_SHRINK): 灵动胶囊启用时
 *     HnPhoneStatusBarAnimController 不再把状态栏内容缩到 0.88.
 * (2) 禁用状态栏元素自动隐藏 (KEY_SB_NO_HIDE): 灵动胶囊启用时
 *     状态栏杂项图标 (statusIcons/signal/network 等) 不隐藏.
 * (3) 始终显示灵动胶囊 (KEY_CAPSULE_ALWAYS): CapsuleCountDownTimerManager
 *     的 startCountDown 拦截, 胶囊不再自动 收缩<->展开 乒乓,
 *     保持初始的带背景胶囊视图 (真正的大卡片是点击后的 headsus 流程).
 * (4) 控制中心动效优化 (KEY_CC_SPRING_VEL): HnSwipeSwitchControllerImpl
 *     .createSlideAnimator 松手后 spring 初速度 = 松手时手指速度
 *     (model 重解 solve, animator 时长按新解刷新).
 * (5) 控制中心高级模糊 (KEY_CC_REAL_BLUR): 下拉面板模糊位图 alpha 的
 *     每 setAlpha 帧, 同步驱动 NotificationShadeWindowControllerImpl
 *     .applyWindowBlur (锁屏同款 setBackgroundBlurRadius, 半径 =
 *     alpha * keyguard_blur_max), 实时跟手模糊度.
 *     + 卡片放大锚点: 原生 createCardScaleAnimator 不设 pivot (默认
 *     卡片自身中心). 开启后: 上排 4 张大卡 → 角锚点 (左半边卡右上角,
 *     右半边卡左上角, 从面板中轴向两侧生长), 下排 3 张卡 → 顶边中点;
 *     全部 pivotY=0. 动画结束 pivot 归位卡片中心.
 */
public final class SystemUIHooks {
    private static final String CTRL_CLS =
            "com.android.systemui.statusbar.phone.HnPhoneStatusBarAnimController";
    private static final String SHADE_CTRL_CLS =
            "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl";
    private static final String SWIPE_CTRL_CLS =
            "com.android.systemui.statusbar.phone.HnSwipeSwitchControllerImpl";
    private static final String CAPSULE_TIMER_CLS =
            "com.hihonor.systemui.dynamicserver.manager.CapsuleCountDownTimerManager";
    private static final String SHOT_BLUR_CLS =
            "com.android.magic.blur.ShotBlurView";
    private static final String PANEL_IMPL_CLS =
            "com.android.systemui.statusbar.phone.HnPanelViewControllerImpl";
    private static final String ANIM_MGR_CLS =
            "com.android.systemui.statusbar.phone.HnPanelAnimatorManager";

    private static WeakReference<Object> sShadeCtrl = new WeakReference<Object>(null);

    public static void install(ClassLoader cl) {
        Class<?> ctrl = XposedHelpers.findClass(CTRL_CLS, cl);

        // (1) getScaleValue(capsuleAppear=true) -> 1.0
        XposedHelpers.findAndHookMethod(ctrl, "getScaleValue", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Boolean.TRUE.equals(param.args[0]) && Port.on(Port.KEY_SB_NO_SHRINK)) {
                    param.setResult(1.0f);
                }
            }
        });

        // (2a) alpha 隐藏动画列表置空
        XposedHelpers.findAndHookMethod(ctrl, "getAlphaAnimatorsList",
                boolean.class, View[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_SB_NO_HIDE)) {
                            param.setResult(new ArrayList<Object>());
                        }
                    }
                });

        // (2b) setContentsVisibility 强制保持显示
        XposedHelpers.findAndHookMethod(ctrl, "setContentsVisibility", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_SB_NO_HIDE)) {
                    param.args[0] = Boolean.TRUE;
                }
            }
        });

        // 无动画路径 (resetScaleStateWithoutAnim) 的状态归一
        XposedHelpers.findAndHookMethod(ctrl, "resetScaleStateWithoutAnim", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                boolean noShrink = Port.on(Port.KEY_SB_NO_SHRINK);
                boolean noHide = Port.on(Port.KEY_SB_NO_HIDE);
                if (!noShrink && !noHide) {
                    return;
                }
                if (noShrink) {
                    Object contents = XposedHelpers.getObjectField(param.thisObject, "mStatusBarContents");
                    if (contents != null) {
                        XposedHelpers.callMethod(param.thisObject, "setScaleXY", 1.0f, contents);
                    }
                    resetViewScale(param.thisObject, "mOperatorView");
                    resetViewScale(param.thisObject, "mOutsideBatteryView");
                }
                if (noHide) {
                    String[] names = {"mNetworkAreaMore", "mStatusIcons", "mSignalIcons",
                            "mNetworkIcons", "mSystemIconUnstableArea"};
                    for (String name : names) {
                        Object v = XposedHelpers.getObjectField(param.thisObject, name);
                        if (v instanceof View) {
                            ((View) v).setAlpha(1.0f);
                        }
                    }
                }
            }
        });

        // (3) 始终显示灵动胶囊: 拦截自动状态切换计时
        Class<?> capsuleTimer = XposedHelpers.findClass(CAPSULE_TIMER_CLS, cl);
        XposedBridge.hookAllMethods(capsuleTimer, "startCountDown", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_CAPSULE_ALWAYS)) {
                    param.setResult(null);
                }
            }
        });

        // (4) 控制中心横滑 spring 初速度 = 松手手指速度
        Class<?> swipeCtrl = XposedHelpers.findClass(SWIPE_CTRL_CLS, cl);
        XposedHelpers.findAndHookMethod(swipeCtrl, "createSlideAnimator",
                View.class, boolean.class, boolean.class, float.class, float.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_CC_SPRING_VEL)) {
                            return;
                        }
                        if (Boolean.TRUE.equals(param.args[2])) {
                            return; // rebound 走 bezier 路径, 不处理
                        }
                        Object anim = param.getResult();
                        if (!(anim instanceof android.animation.ValueAnimator)) {
                            return;
                        }
                        android.animation.ValueAnimator va = (android.animation.ValueAnimator) anim;
                        Object interp = va.getInterpolator();
                        if (interp == null) {
                            return;
                        }
                        Class<?> springCls = XposedHelpers.findClass(
                                "com.hihonor.dynamicanimation.interpolator.SpringInterpolator", cl);
                        if (!springCls.isInstance(interp)) {
                            return;
                        }
                        float fingerV = (Float) param.args[4];
                        float endX = (Float) param.args[3];
                        View view = (View) param.args[0];
                        float dist = endX - view.getTranslationX();
                        if (Math.abs(dist) < 1f || Math.abs(fingerV) < 1f) {
                            return;
                        }
                        float modelV = fingerV / dist;
                        if (modelV > 15f) {
                            modelV = 15f;
                        } else if (modelV < -15f) {
                            modelV = -15f;
                        }
                        try {
                            Object model = XposedHelpers.callMethod(interp, "getModel");
                            XposedHelpers.setFloatField(model, "mStartVelocity", modelV);
                            float startPos = XposedHelpers.getFloatField(model, "mStartPosition");
                            float endPos = XposedHelpers.getFloatField(model, "mEndPosition");
                            Object solution = XposedHelpers.callMethod(model,
                                    "solve", startPos - endPos, modelV);
                            XposedHelpers.setObjectField(model, "mSolution", solution);
                            float newDur = (Float) XposedHelpers.callMethod(model, "getEstimatedDuration");
                            va.setDuration((long) (float) newDur);
                            Port.log("SystemUIHooks: swipe spring velocity=" + modelV
                                    + " dur=" + newDur);
                        } catch (Throwable t) {
                            Port.log("SystemUIHooks: spring vel failed", t);
                        }
                    }
                });

        // (5) 控制中心高级模糊: 模糊位图每帧 alpha 同步驱动真窗口模糊
        final Class<?> shadeCtrlCls = XposedHelpers.findClass(SHADE_CTRL_CLS, cl);
        XposedBridge.hookAllConstructors(shadeCtrlCls, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sShadeCtrl = new WeakReference<Object>(param.thisObject);
            }
        });
        final Class<?> shotBlurCls = XposedHelpers.findClass(SHOT_BLUR_CLS, cl);
        XposedHelpers.findAndHookMethod(View.class, "setAlpha", float.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_CC_REAL_BLUR)) {
                    return;
                }
                if (!shotBlurCls.isInstance(param.thisObject)) {
                    return;
                }
                applyShadeBlur(shadeCtrlCls, (Float) param.args[0]);
            }
        });

        // (4b) 控制中心卡片放大锚点 (同 KEY_CC_SPRING_VEL): 上排角锚点, 下排顶边中点
        final Class<?> panelImplCls = XposedHelpers.findClass(PANEL_IMPL_CLS, cl);
        XposedHelpers.findAndHookMethod(panelImplCls, "createCardScaleAnimator",
                View.class, boolean.class, boolean.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_CC_SPRING_VEL)) {
                            return;
                        }
                        if (!"ControlCenter".equals(param.args[3])) {
                            return;
                        }
                        applyCardAnchor((View) param.args[0], cl);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_CC_SPRING_VEL)) {
                            final View v = (View) param.args[0];
                            v.setPivotX(v.getWidth() / 2f);
                            v.setPivotY(v.getHeight() / 2f);
                            return;
                        }
                        if (!"ControlCenter".equals(param.args[3])) {
                            return;
                        }
                        Object anim = param.getResult();
                        final View v = (View) param.args[0];
                        if (!(anim instanceof android.animation.Animator)) {
                            return;
                        }
                        ((android.animation.Animator) anim).addListener(
                                new android.animation.Animator.AnimatorListener() {
                            @Override
                            public void onAnimationStart(android.animation.Animator a) {}

                            @Override
                            public void onAnimationRepeat(android.animation.Animator a) {}

                            @Override
                            public void onAnimationCancel(android.animation.Animator a) {}

                            @Override
                            public void onAnimationEnd(android.animation.Animator a) {
                                // pivot 归位卡片中心, 不影响其它缩放效果
                                //v.setPivotX(v.getWidth() / 2f);
                                //v.setPivotY(v.getHeight() / 2f);
                            }
                        });
                    }
                });

        Port.log("SystemUIHooks installed");
    }

    private static void applyShadeBlur(Class<?> shadeCtrlCls, float alpha) {
        Object ctrl = sShadeCtrl.get();
        if (ctrl == null) {
            return;
        }
        try {
            View shadeView = (View) XposedHelpers.getObjectField(ctrl, "mNotificationShadeView");
            if (shadeView == null) {
                return;
            }
            int max = 60;
            Object ctx = XposedHelpers.getObjectField(ctrl, "mContext");
            if (ctx instanceof android.content.Context) {
                max = android.provider.Settings.Global.getInt(
                        ((android.content.Context) ctx).getContentResolver(),
                        "keyguard_blur_max", 60);
            }
            float radius = alpha * max;
            XposedHelpers.callMethod(ctrl, "applyWindowBlur", shadeView, radius);
        } catch (Throwable t) {
            Port.log("SystemUIHooks: applyShadeBlur failed", t);
        }
    }

    /**
     * (4b) 按窗口坐标给每张控制中心卡分配放大锚点:
     *   - 下排 3 张 (Y 最大的 3 张): 顶边中点 pivot=(w/2, 0)
     *   - 上排其余卡: 左半边 (centerX <= 屏中线) -> 右上角 (w, 0);
     *                右半边 -> 左上角 (0, 0)
     * 与原生差异仅在 pivot; scaleX/scaleY 关键帧不变.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyCardAnchor(View trigger, ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClass(ANIM_MGR_CLS, cl);
            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
            Set<View> views = (Set) XposedHelpers.callMethod(mgr, "getControlScaleViews");
            if (views == null || views.isEmpty()) {
                return;
            }
            ArrayList<View> list = new ArrayList<View>(views);
            int n = list.size();
            int[] xs = new int[n];
            int[] ys = new int[n];
            int[] loc = new int[2];
            for (int i = 0; i < n; i++) {
                list.get(i).getLocationInWindow(loc);
                xs[i] = loc[0];
                ys[i] = loc[1];
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    if (ys[a] != ys[b]) {
                        return ys[a] < ys[b] ? -1 : 1;
                    }
                    return xs[a] == xs[b] ? 0 : (xs[a] < xs[b] ? -1 : 1);
                }
            });
            int bottomCount = n > 4 ? Math.min(3, n - 4) : 0;
            java.util.HashSet<Integer> bottomIdx = new java.util.HashSet<Integer>();
            for (int k = n - bottomCount; k < n; k++) {
                bottomIdx.add(order[k]);
            }
            int midX = trigger.getRootView().getWidth() / 2;
            for (int i = 0; i < n; i++) {
                View c = list.get(i);
                float w = c.getWidth();
                if (bottomIdx.contains(i)) {
                    c.setPivotX(w / 2f);
                    c.setPivotY(0);
                } else if (xs[i] + w / 2f <= midX) {
                    c.setPivotX(w / 2f);
                    c.setPivotY(0f);
                } else {
                    c.setPivotX(w / 2f);
                    c.setPivotY(0f);
                }
            }
        } catch (Throwable t) {
            Port.log("SystemUIHooks: card anchor failed", t);
        }
    }

    private static void resetViewScale(Object controller, String field) {
        Object v = XposedHelpers.getObjectField(controller, field);
        if (v instanceof View) {
            ((View) v).setScaleX(1.0f);
            ((View) v).setScaleY(1.0f);
        }
    }

    private SystemUIHooks() {}
}
