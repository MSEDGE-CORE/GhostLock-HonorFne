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
    /** 快照层弱引用 (重放半径时读实时 alpha). */
    private static WeakReference<View> sBlurView = new WeakReference<View>(null);
    /** 逐帧重放回调 (对抗 surface 重建/relayout 重置模糊半径). */
    private static boolean sBlurLoopRunning = false;
    /** 当前半径基线 (同步到窗口 LayoutParams, relayout 时自动带上). */
    private static int sCurBlurRadius = 0;

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
                // 静态快照层的 alpha 动画只作驱动信号, 不再改写它本身:
                // 强制 alpha=0 会污染 stock 淡入/淡出动画的起点值
                // (进入闪一帧 0 模糊, 退出模糊度突变 0).
                // Port.log("blurProbe setAlpha t=" + android.os.SystemClock.uptimeMillis()
                //         + " a=" + param.args[0] + " vis=" + ((View) param.thisObject).getVisibility());
                sBlurView = new WeakReference<View>((View) param.thisObject);
                applyShadeBlur(shadeCtrlCls, (Float) param.args[0]);
                ensureBlurLoop(shadeCtrlCls);
            }
        });
        // 真窗口模糊过渡期桥接, 全开 (alpha=1) 时归零隐藏 — 不动 stock 静态层.

        // [fix] 把当前模糊半径基线同步进窗口 LayoutParams.backgroundBlurRadius:
        // surface 重建/relayout (子面板, 长按, 通知, 上滑接管) 会重置 transaction
        // 写入的模糊半径 → 逐帧重放只能晚一帧恢复. 写进 LP 后任何 relayout
        // 自动携带模糊, 重建即带半径, 不再有 1 帧空窗.
        try {
            XposedBridge.hookAllMethods(shadeCtrlCls, "applyWindowLayoutParams",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Port.on(Port.KEY_CC_REAL_BLUR)) {
                        return;
                    }
                    Object ctrl = param.thisObject;
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(ctrl, "mLp");
                    WindowManager.LayoutParams lpc = (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(ctrl, "mLpChanged");
                    if (lp != null) {
                        XposedHelpers.setIntField(lp, "backgroundBlurRadius", sCurBlurRadius);
                    }
                    if (lpc != null) {
                        XposedHelpers.setIntField(lpc, "backgroundBlurRadius", sCurBlurRadius);
                    }
                }
            });
        } catch (Throwable t) {
            Port.log("SystemUIHooks: lp blur sync failed", t);
        }
        // [probe] 上滑接管瞬间: 面板 fraction 硬切 (setExpandedFraction(0)).
        // 方法声明在 PanelView, 挂子类会 NoSuchMethodError 中断 install.
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(
                            "com.android.systemui.statusbar.phone.PanelView", cl),
                    "setExpandedFraction", float.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Port.log("blurProbe fraction t=" + android.os.SystemClock.uptimeMillis()
                    //         + " f=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            Port.log("SystemUIHooks: fraction probe failed", t);
        }
        // [probe] stock 侧任何窗口模糊写入 (含 0)
        try {
            XposedHelpers.findAndHookMethod(shadeCtrlCls, "applyWindowBlur",
                    View.class, float.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Port.log("blurProbe awb t=" + android.os.SystemClock.uptimeMillis()
                    //         + " r=" + param.args[1]);
                }
            });
        } catch (Throwable t) {
            Port.log("SystemUIHooks: awb probe failed", t);
        }
        // [probe] stock 退出/进入模糊动画启动
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(PANEL_IMPL_CLS, cl), "startBlurAnimation",
                    boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Port.log("blurProbe startBlurAnim t=" + android.os.SystemClock.uptimeMillis()
                    //         + " isIn=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            Port.log("SystemUIHooks: startBlurAnim probe failed", t);
        }

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
                        // 关闭 (z=false) 时末值 0.9 -> 1.0: 不再缩小消失
                        if (!Boolean.TRUE.equals(param.args[1])
                                && anim instanceof android.animation.ValueAnimator) {
                            rewriteScaleEnd((android.animation.ValueAnimator) anim, v,
                                    Boolean.TRUE.equals(param.args[2]));
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

        // (6) 锁屏隐藏步数: 真正渲染步数的是 FooterView.setVisibilityOfStep
        // (左下角步数文本/图标), 一律 GONE.
        final Class<?> footerViewCls = XposedHelpers.findClass(
                "com.hihonor.keyguard.view.FooterView", cl);
        XposedHelpers.findAndHookMethod(footerViewCls, "setVisibilityOfStep", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_KG_HIDE_STEPS)) {
                    param.args[0] = 8; // GONE
                }
            }
        });

        // (7) 跳过密码界面面部解锁成功动画: 面部成功(10)时 stock 依次播
        // detect_animation_complete -> detect_animation_success (笑脸)
        // 后才 notifyKeyguardAuthenticated; 改为直接解锁.
        final Class<?> faceStateListenerCls = XposedHelpers.findClass(
                "com.hihonor.keyguard.HwKeyguardAbsKeyInputView$FaceUnlockStateListener", cl);
        final Class<?> kgPolicyCls = XposedHelpers.findClass(
                "com.hihonor.keyguard.inf.HwKeyguardPolicy", cl);
        XposedHelpers.findAndHookMethod(faceStateListenerCls,
                "startDetectCompletedAnimation", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_KG_SKIP_FACE_ANIM)) {
                    return;
                }
                if (((Integer) param.args[0]).intValue() != 10) {
                    return; // 仅面部成功
                }
                Object policy = XposedHelpers.callStaticMethod(kgPolicyCls, "getInst");
                XposedHelpers.callMethod(policy, "notifyKeyguardAuthenticated");
                param.setResult(null); // 跳过 complete + 笑脸动画
                Port.log("SystemUIHooks: face success anim skipped, direct unlock");
            }
        });
        // 兜底: 若笑脸动画仍被其它路径直接触发, 同样直接解锁
        XposedHelpers.findAndHookMethod(faceStateListenerCls,
                "startDetectSuccessAnimation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_KG_SKIP_FACE_ANIM)) {
                    return;
                }
                Object policy = XposedHelpers.callStaticMethod(kgPolicyCls, "getInst");
                XposedHelpers.callMethod(policy, "notifyKeyguardAuthenticated");
                param.setResult(null);
                Port.log("SystemUIHooks: face success anim skipped (fallback)");
            }
        });

        // 杂志锁屏路径: MagazineDetailView.isInterestedEvent (mTouchMode=6 lift)
        final Class<?> magazineDetailCls = XposedHelpers.findClass(
                "com.android.keyguard.hwlockscreen.magazine.MagazineDetailView", cl);
        XposedHelpers.findAndHookMethod(magazineDetailCls, "isInterestedEvent",
                android.view.MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_KG_NO_TOOLBOX)) {
                            return;
                        }
                        int mode = XposedHelpers.getIntField(param.thisObject, "mTouchMode");
                        if (mode == 6) {
                            XposedHelpers.setIntField(param.thisObject, "mTouchMode", 0);
                            param.setResult(false);
                            Port.log("SystemUIHooks: toolbox lift blocked (MagazineDetail mode 6)");
                        }
                    }
                });
        // 非杂志锁屏路径: InfoCenterView.isInterestedEvent
        // (mTouchMode=6 lift 路径), 底部上滑不再滑出工具菜单;
        // 工具菜单行保持显示, 菜单内按钮点击 (mode 1) 不受影响.
        final Class<?> infoCenterCls = XposedHelpers.findClass(
                "com.hihonor.keyguard.view.widget.InfoCenterView", cl);
        XposedHelpers.findAndHookMethod(infoCenterCls, "isInterestedEvent",
                android.view.MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_KG_NO_TOOLBOX)) {
                            return;
                        }
                        int mode = XposedHelpers.getIntField(param.thisObject, "mTouchMode");
                        if (mode == 6) {
                            XposedHelpers.setIntField(param.thisObject, "mTouchMode", 0);
                            param.setResult(false);
                            Port.log("SystemUIHooks: toolbox lift blocked (InfoCenter mode 6)");
                        }
                    }
                });

        // (4c) 控制中心卡片缩放 0.9 -> 1.0: 打开初值 0.9 -> 1.0,
        //      关闭末值 0.9 -> 1.0 (Notification 的 0.95 不动)
        XposedHelpers.findAndHookMethod(panelImplCls, "getCardAnimatorStartScale",
                View.class, boolean.class, boolean.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_CC_SPRING_VEL)) {
                            return;
                        }
                        if (!"ControlCenter".equals(param.args[3])) {
                            return;
                        }
                        // z=true 打开 (含续接 z2, 续接时原取拖拽中已被缩小的
                        // view.getScaleX(), 导致仍从小放大): 一律 1.0
                        if (Boolean.TRUE.equals(param.args[1])) {
                            param.setResult(1.0f);
                        }
                    }
                });
        // 打开路径走的是 createCardAnimation -> getAnimateStartScale (0.95),
        // 也要归 1
        XposedHelpers.findAndHookMethod(panelImplCls, "getAnimateStartScale",
                View.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_CC_SPRING_VEL)
                                && Boolean.TRUE.equals(param.args[1])) {
                            param.setResult(1.0f);
                        }
                    }
                });

        // 关闭路径 createCardAnimation (header/scroller 走这条) 末值 0.95 -> 1.0,
        // 否则停在 0.95 不复位, 下次显示起始也 <1
        XposedHelpers.findAndHookMethod(panelImplCls, "createCardAnimation",
                View.class, boolean.class, boolean.class, boolean.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_CC_SPRING_VEL)) {
                            return;
                        }
                        if (Boolean.TRUE.equals(param.args[1])) {
                            return; // 打开路径: start 已由 getAnimateStartScale hook 归 1
                        }
                        Object anim = param.getResult();
                        if (anim instanceof android.animation.ValueAnimator) {
                            rewriteScaleEnd((android.animation.ValueAnimator) anim,
                                    (View) param.args[0], Boolean.TRUE.equals(param.args[2]));
                        }
                    }
                });

        // [DEBUG] 临时: 记录 CC 视图被缩到 <1 的调用源, 定位显示动画初值来源
        final Class<?> ccScrollCls = XposedHelpers.findClass(
                "com.android.systemui.controllercenter.HnControlCenterScrollView", cl);
        final Class<?> ccHeaderCls = XposedHelpers.findClass(
                "com.android.systemui.controllercenter.HnControlCenterHeader", cl);
        XC_MethodHook scaleProbe = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                float v = (Float) param.args[0];
                Object thiz = param.thisObject;
                if (v >= 0.995f && v <= 1.005f) {
                    return;
                }
                if (!ccScrollCls.isInstance(thiz) && !ccHeaderCls.isInstance(thiz)) {
                    return;
                }
                StackTraceElement[] st = new Throwable().getStackTrace();
                StringBuilder sb = new StringBuilder("scaleProbe " + v + " on "
                        + thiz.getClass().getSimpleName() + " <- ");
                int n = 0;
                for (StackTraceElement e : st) {
                    String cn = e.getClassName();
                    if (cn.startsWith("com.wxz.") || cn.startsWith("de.robv")
                            || cn.startsWith("android.animation")
                            || cn.equals("java.lang.Thread")) {
                        continue;
                    }
                    sb.append(cn, cn.lastIndexOf('.') + 1, cn.length())
                      .append('.').append(e.getMethodName()).append(':')
                      .append(e.getLineNumber()).append(' ');
                    if (++n >= 6) {
                        break;
                    }
                }
                Port.log(sb.toString());
            }
        };
        XposedHelpers.findAndHookMethod(View.class, "setScaleX", float.class, scaleProbe);
        XposedHelpers.findAndHookMethod(View.class, "setScaleY", float.class, scaleProbe);

        // (7) 悬浮窗手势/动效优化 (独立类, 各自 try-catch)
        FreeformFx.install(cl);

        Port.log("SystemUIHooks installed");
    }

    /** 关闭动画 scaleX/scaleY 末值改 1.0 (续接时初值=当前 scale, 否则 1.0). */
    private static void rewriteScaleEnd(android.animation.ValueAnimator va, View v, boolean resume) {
        try {
            android.animation.PropertyValuesHolder[] holders = va.getValues();
            if (holders == null) {
                return;
            }
            float start = resume ? v.getScaleX() : 1.0f;
            java.lang.reflect.Method m = android.animation.PropertyValuesHolder.class
                    .getMethod("setFloatValues", float[].class);
            for (android.animation.PropertyValuesHolder h : holders) {
                String n = h.getPropertyName();
                if ("scaleX".equals(n) || "scaleY".equals(n)) {
                    m.invoke(h, new Object[]{new float[]{start, 1.0f}});
                }
            }
        } catch (Throwable t) {
            Port.log("SystemUIHooks: rewriteScaleEnd failed", t);
        }
    }

    /** 逐帧重放: surface 重建/窗口 relayout 会重置背景模糊半径 (子面板
     *  打开, 长按, 通知详情, 上滑接管等), stock 的静态层是 view 内部位图
     *  不受影响, 而我们的真窗口模糊会被清掉. 每帧按快照层实时 alpha 重写,
     *  快照层不可见时清零并停止. */
    private static void ensureBlurLoop(final Class<?> shadeCtrlCls) {
        if (sBlurLoopRunning) {
            return;
        }
        sBlurLoopRunning = true;
        android.view.Choreographer.getInstance().postFrameCallback(
                new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                View v = sBlurView.get();
                if (v == null || v.getVisibility() != View.VISIBLE) {
                    applyShadeBlur(shadeCtrlCls, 0f); // 半径清零
                    sBlurLoopRunning = false;
                    return;
                }
                applyShadeBlur(shadeCtrlCls, v.getAlpha());
                android.view.Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    /** stock 快照层遮罩色 (已停用, 保留备用): R.color.window_blur_view_mask_color
     *  (0x7f060c02), SystemUI 自己的资源, 日/夜间值由系统解析; 取不到退回 20% 黑. */
    // private static int blurMaskColor(View v) {
    //     try {
    //         return v.getContext().getColor(0x7f060c02);
    //     } catch (Throwable t) {
    //         Port.log("SystemUIHooks: mask color fallback", t);
    //         return 0x33000000;
    //     }
    // }

    /** 模糊半径匹配静态快照层 alpha: radius = alpha * keyguard_blur_max * 4.
     *  alpha=1 (全开, stock 静态层完整显示) 时动态层 (真窗口模糊) 归零隐藏,
     *  避免与静态层叠加; 过渡期 (alpha<1) 按 alpha 同步桥接淡入淡出. */
    private static void applyShadeBlur(final Class<?> shadeCtrlCls, float alpha) {
        Object ctrl = sShadeCtrl.get();
        if (ctrl == null) {
            return;
        }
        try {
            View shadeView = (View) XposedHelpers.getObjectField(ctrl, "mNotificationShadeView");
            if (shadeView == null) {
                return;
            }
            float radius;
            if (alpha >= 0.999f) {
                radius = 0f; // 全开: 静态层独占, 动态层隐藏
            } else {
                int max = 60;
                Object ctx = XposedHelpers.getObjectField(ctrl, "mContext");
                if (ctx instanceof android.content.Context) {
                    max = android.provider.Settings.Global.getInt(
                            ((android.content.Context) ctx).getContentResolver(),
                            "keyguard_blur_max", 60);
                }
                radius = alpha * max * 4f;
            }
            int r = (int) radius;
            sCurBlurRadius = r;
            // 不做去重: surface 重建会静默清掉实际半径, 必须逐帧重放
            XposedHelpers.callMethod(ctrl, "applyWindowBlur", shadeView, radius);
            // Port.log("shadeBlur a=" + alpha + " r=" + radius);
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
                if (bottomIdx.contains(i) || xs[i] + w / 2f == midX) {
                    c.setPivotX(w / 2f);
                    c.setPivotY(0);
                } else if (xs[i] + w / 2f < midX) {
                    c.setPivotX(w);
                    c.setPivotY(0f);
                } else {
                    c.setPivotX(0f);
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
