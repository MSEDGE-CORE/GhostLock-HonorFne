package com.wxz.hnlauncherport.systemui;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

import com.wxz.hnlauncherport.Port;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 悬浮窗 (hnfreeform) 手势与动效优化, 运行于 SystemUI 进程 (wm.shell):
 *
 * KEY_FF_GESTURE (悬浮窗手势优化):
 *  (G1) 屏幕底部上滑 (launcher home 手势): 原生会走 HnFreeformController
 *       .onTaskInfoChanged 可见性分支 -> operateFloatBall("add") 进圆球.
 *       拦截后改为 transformToMiniWindow(8, task, 2=右上角) 挂起小窗.
 *       小窗自身底部上滑 (flingUpFreeformToBack) 保持原样.
 *  (G2) 横滑判定修复: VM 的 mFlingGestureDetector 内部 mCurrentDownEvent
 *       残留 (DOWN 被抢走时), onFling 用旧 DOWN 算距离 -> 方向判反/两次
 *       滑动合并. 在 DOWN 时 cleanFlingParmars + 给 detector 重新喂 DOWN.
 *  (G4) 角落缩放甩动挂角: 左下(ctrlType 9)/右下(10)角缩放松手时, 若窗口
 *       正在缩小 (mCurScale<1) 且速度达底部上滑响应阈值 (acc>1000px/s),
 *       直接挂到对角 (9->右上, 10->左上).
 *
 * KEY_FF_ANIM (悬浮窗动效优化):
 *  (A1) 拖拽结束 (End/Cancel/ToMiniwindow) 快照 live bounds + live scale
 *       (mCurScale*mInitialScale), 供结束动画起播使用, 消除松手回跳.
 *  (A2) moveToMiniform 的 startRect 取的是更新前拷贝, 换成 live 的
 *       mRepositionTaskBounds.
 *  (A3) startBoundsAnimator 的 startScale 用 live 快照覆盖.
 *  (A4) 挂起小窗结束动画 interpolator: OvershootInterpolator(1.0) 换成
 *       SpringInterpolator(stiffness=800, damping=50.35, endPos=1, v0=投影
 *       初速度), 时长保持 stock 300ms. ζ = 50.35/(2*sqrt(800)) ≈ 0.89.
 *  (A5) 全屏过渡连贯: setupLeashStartState 处按 S = L/(0.7r+0.3) 设置
 *       change.lastFreeformScale, 使起播净缩放 = live 视觉缩放 L;
 *       无 freeformAnimationLeash 时 (adapter 直接覆盖 matrix, 末态=1/S)
 *       不改写, 避免"末态过大".
 *  (A6) 最小化进圆球动画 (startFreeformToBackAnimator, 200ms 默认插值器)
 *       换 spring + 上滑初速度.
 */
public final class FreeformFx {

    private static final long STASH_TTL_MS = 1200;

    private static final String FF_CTRL_CLS =
            "com.android.wm.shell.hnfreeform.HnFreeformController";
    private static final String FF_UTILS_CLS =
            "com.android.wm.shell.hnfreeform.HnFreeformUtils";
    private static final String MINI_CTRL_CLS =
            "com.android.wm.shell.hnfreeform.miniwindow.HnMiniformController";
    private static final String FLUID_POS_CLS =
            "com.android.wm.shell.windowdecor.FluidResizeTaskPositioner";
    private static final String CAPTION_VM_CLS =
            "com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel";
    private static final String CAPTION_LISTENER_CLS =
            "com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel$CaptionTouchEventListener";
    private static final String FLING_GD_CLS =
            "com.android.wm.shell.windowdecor.DragResizeInputListener$TaskResizeInputEventReceiver$FlingGestureDetector";
    private static final String FF_ANIM_CLS =
            "com.android.wm.shell.transition.HnFreeformAnimation";
    private static final String SPRING_CLS =
            "com.hihonor.dynamicanimation.interpolator.SpringInterpolator";

    private static class Live {
        final Rect bounds;
        final float scale;   // <=0 = 未知, 不覆盖
        final float vx;      // px/s, 松手手指速度
        final float vy;
        final long at;

        Live(Rect bounds, float scale, float vx, float vy) {
            this.bounds = bounds;
            this.scale = scale;
            this.vx = vx;
            this.vy = vy;
            this.at = SystemClock.uptimeMillis();
        }
    }

    private static final Map<Integer, Live> sLive = new HashMap<>();
    private static final Map<Integer, float[]> sVel = new HashMap<>();
    /** 最近已重定向挂起的 task (防 home 动画期间重复触发) */
    private static final Map<Integer, Long> sRecent = new HashMap<>();
    /** startBoundsAnimator -> ValueAnimator.setInterpolator 同线程标记 */
    private static final ThreadLocal<Boolean> sSpringMarker = new ThreadLocal<>();
    private static final ThreadLocal<Float> sPendingV0 = new ThreadLocal<>();
    /** startFreeformToBackAnimator -> ValueAnimator.setDuration 同线程标记 */
    private static final ThreadLocal<Boolean> sBallMarker = new ThreadLocal<>();
    private static final ThreadLocal<Float> sBallV0 = new ThreadLocal<>();
    /** 同一次 onTaskInfoChanged 调用内传递 hnInfo (before->after) */
    private static final ThreadLocal<Object> sHnInfoExtra = new ThreadLocal<>();
    /** 角落甩动已自行 stash 标记 (corner hook -> A1 stash hook) */
    private static final ThreadLocal<Boolean> sCornerExtra = new ThreadLocal<>();
    private static ClassLoader sCl;

    public static void install(ClassLoader cl) {
        sCl = cl;
        try {
            installVelocity(cl);
        } catch (Throwable t) {
            Port.log("FreeformFx: velocity install failed", t);
        }
        try {
            installGesture(cl);
        } catch (Throwable t) {
            Port.log("FreeformFx: gesture install failed", t);
        }
        try {
            installAnim(cl);
        } catch (Throwable t) {
            Port.log("FreeformFx: anim install failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 速率采集 (手势/动效共用)
    // ------------------------------------------------------------------

    private static void installVelocity(ClassLoader cl) {
        final Class<?> flingGd = XposedHelpers.findClass(FLING_GD_CLS, cl);
        XposedHelpers.findAndHookMethod(flingGd, "onFling",
                MotionEvent.class, MotionEvent.class, float.class, float.class,
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Object recv = XposedHelpers.getObjectField(param.thisObject, "this$1");
                    Object listener = XposedHelpers.getObjectField(recv, "this$0");
                    Object taskInfo = XposedHelpers.getObjectField(listener, "mTaskInfo");
                    int taskId = XposedHelpers.getIntField(taskInfo, "taskId");
                    float vx = (Float) param.args[2];
                    float vy = (Float) param.args[3];
                    synchronized (sVel) {
                        sVel.put(taskId, new float[]{vx, vy});
                    }
                } catch (Throwable t) {
                    Port.log("FreeformFx: fling vel stash failed", t);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 手势
    // ------------------------------------------------------------------

    private static void installGesture(ClassLoader cl) {
        final Class<?> ffCtrl = XposedHelpers.findClass(FF_CTRL_CLS, cl);

        // (G1) 屏幕 bottom home 上滑: 可见性分支 (进圆球) 重定向为右上角挂起
        XposedHelpers.findAndHookMethod(ffCtrl, "onTaskInfoChanged",
                XposedHelpers.findClass("android.app.ActivityManager$RunningTaskInfo", cl),
                XposedHelpers.findClass(
                        "com.android.wm.shell.hnfreeform.HnRunningFreeformTaskInfo$Changes", cl),
                XposedHelpers.findClass("android.view.SurfaceControl", cl),
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_GESTURE)) {
                    return;
                }
                try {
                    Object info = param.args[0];
                    if (info == null) {
                        return;
                    }
                    boolean visReq = XposedHelpers.getBooleanField(info, "isVisibleRequested");
                    boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
                    if (visReq || visible) {
                        return;
                    }
                    Object ctrl = param.thisObject;
                    int taskId = XposedHelpers.getIntField(info, "taskId");
                    synchronized (sRecent) {
                        Long at = sRecent.get(taskId);
                        if (at != null
                                && SystemClock.uptimeMillis() - at < 1500L) {
                            return;
                        }
                    }
                    Object hnInfo = XposedHelpers.callMethod(ctrl,
                            "getFreeformTaskInfo", taskId);
                    if (hnInfo == null) {
                        return;
                    }
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(hnInfo,
                            "isFreeformToRecentTriggered"))) {
                        return;
                    }
                    if (!(Boolean) XposedHelpers.callMethod(ctrl, "isMiniWindowEnable")) {
                        return;
                    }
                    Object mini = XposedHelpers.callMethod(ctrl, "getMiniformController");
                    // 复刻 stock 分支其余 guard, guard 不满足时不重定向
                    if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass(FF_UTILS_CLS, sCl),
                            "isExcludeFromRecents", info))) {
                        return;
                    }
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(mini,
                            "isDeleting", taskId))) {
                        return;
                    }
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(ctrl,
                            "oprateFloatballAppOptOut", info))) {
                        return;
                    }
                    // 抑制进圆球: 分支 guard 会因 flag=true 跳过 operateFloatBall
                    XposedHelpers.callMethod(hnInfo, "setFreeformToRecentTriggered", true);
                    sHnInfoExtra.set(hnInfo);
                    // 挂起到右上角
                    Object helper = XposedHelpers.callMethod(mini, "getMiniformMotionHelper");
                    XposedHelpers.callMethod(helper, "endProcess", taskId);
                    Rect bounds = (Rect) XposedHelpers.callMethod(info, "getBounds");
                    Rect start = bounds != null ? new Rect(bounds) : null;
                    XposedHelpers.callMethod(mini, "transformToMiniWindow",
                            8, taskId, 2, start, (Bundle) null, (Point) null);
                    synchronized (sRecent) {
                        sRecent.put(taskId, SystemClock.uptimeMillis());
                    }
                    Port.log("FreeformFx: home-swipe -> miniform task=" + taskId);
                } catch (Throwable t) {
                    Port.log("FreeformFx: home-swipe redirect failed", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object hnInfo = sHnInfoExtra.get();
                sHnInfoExtra.set(null);
                if (hnInfo != null) {
                    try {
                        XposedHelpers.callMethod(hnInfo, "setFreeformToRecentTriggered", false);
                    } catch (Throwable t) {
                        Port.log("FreeformFx: recent-flag reset failed", t);
                    }
                }
            }
        });

        final Class<?> pos = XposedHelpers.findClass(FLUID_POS_CLS, cl);

        // (G4) 角落缩放甩动挂角: onDragPositioningEnd 前拦截
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningEnd",
                float.class, float.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_GESTURE)) {
                    return;
                }
                try {
                    Object positioner = param.thisObject;
                    int ctrl = XposedHelpers.getIntField(positioner, "mCtrlType");
                    if (ctrl != 9 && ctrl != 10) {
                        return; // 仅左下/右下角缩放
                    }
                    float curScale = XposedHelpers.getFloatField(positioner, "mCurScale");
                    if (curScale >= 1.0f) {
                        return; // 不在缩小
                    }
                    int taskId = positionerTaskId(positioner);
                    if (taskId < 0) {
                        return;
                    }
                    float[] vel;
                    synchronized (sVel) {
                        vel = sVel.remove(taskId);
                    }
                    if (vel == null) {
                        return;
                    }
                    double speed = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1]);
                    if (speed < 1000.0) {
                        return; // 未达底部上滑响应阈值 (acc>1000px/s)
                    }
                    Object optional = XposedHelpers.getObjectField(
                            positioner, "mHnFreeformControllerOptional");
                    Object ctrl0 = XposedHelpers.callMethod(optional, "get");
                    Object mini = XposedHelpers.callMethod(ctrl0, "getMiniformController");
                    Rect base = new Rect((Rect) XposedHelpers.getObjectField(
                            positioner, "mTaskBoundsAtDragEnd"));
                    float scale = curScale
                            * XposedHelpers.getFloatField(positioner, "mInitialScale");
                    synchronized (sLive) {
                        sLive.put(taskId, new Live(base, scale, vel[0], vel[1]));
                    }
                    Object helper = XposedHelpers.callMethod(mini, "getMiniformMotionHelper");
                    XposedHelpers.callMethod(helper, "endProcess", taskId);
                    int loc = ctrl == 9 ? 2 : 1; // 左下->右上, 右下->左上
                    XposedHelpers.callMethod(mini, "transformToMiniWindow",
                            ctrl, taskId, loc, new Rect(base), (Bundle) null, (Point) null);
                    XposedHelpers.setIntField(positioner, "mCtrlType", 0);
                    XposedHelpers.setBooleanField(positioner, "mHasDragResized", false);
                    sCornerExtra.set(Boolean.TRUE);
                    param.setResult(null); // 跳过 stock settle
                    Port.log("FreeformFx: corner fling -> miniform task=" + taskId
                            + " ctrl=" + ctrl + " speed=" + (int) speed);
                } catch (Throwable t) {
                    Port.log("FreeformFx: corner fling redirect failed", t);
                }
            }
        });

        // (G2a) 标题栏 touch DOWN: 清残留 + 给 detector 喂新 DOWN
        final Class<?> capListener = XposedHelpers.findClass(CAPTION_LISTENER_CLS, cl);
        XposedHelpers.findAndHookMethod(capListener, "onTouch", View.class, MotionEvent.class,
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_GESTURE)) {
                    return;
                }
                MotionEvent ev = (MotionEvent) param.args[1];
                if (ev == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
                    return;
                }
                resetCaptionFlingState(param.thisObject, ev);
            }
        });

        // (G2b) handleMotionEvent DOWN 同样兜底 (DragDetector 路径)
        XposedHelpers.findAndHookMethod(capListener, "handleMotionEvent", MotionEvent.class,
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_GESTURE)) {
                    return;
                }
                MotionEvent ev = (MotionEvent) param.args[0];
                if (ev == null || ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
                    return;
                }
                resetCaptionFlingState(param.thisObject, ev);
            }
        });
    }

    /** cleanFlingParmars + 重喂 detector DOWN, 重置合并判定残留 */
    private static void resetCaptionFlingState(Object listener, MotionEvent ev) {
        try {
            Object vm = XposedHelpers.getObjectField(listener, "this$0");
            XposedHelpers.callMethod(vm, "cleanFlingParmars");
            Object gd = XposedHelpers.getObjectField(vm, "mFlingGestureDetector");
            if (gd != null) {
                XposedHelpers.callMethod(gd, "onTouchEvent", ev);
            }
        } catch (Throwable t) {
            Port.log("FreeformFx: caption fling reset failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 动效
    // ------------------------------------------------------------------

    private static void installAnim(ClassLoader cl) {
        final Class<?> pos = XposedHelpers.findClass(FLUID_POS_CLS, cl);

        // (A1a) 拖拽开始: 清掉旧快照
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningStart",
                int.class, float.class, float.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                int taskId = positionerTaskId(param.thisObject);
                if (taskId >= 0) {
                    synchronized (sLive) {
                        sLive.remove(taskId);
                    }
                }
            }
        });

        // (A1b) 拖拽结束: 快照 live bounds + live scale
        XC_MethodHook stashHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                if (Boolean.TRUE.equals(sCornerExtra.get())) {
                    sCornerExtra.set(null);
                    return; // 角落甩动已自行 stash
                }
                try {
                    stashLive(param.thisObject);
                } catch (Throwable t) {
                    Port.log("FreeformFx: stash live failed", t);
                }
            }
        };
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningEnd",
                float.class, float.class, stashHook);
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningCancel",
                float.class, float.class, int.class, stashHook);
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningToMiniwindow",
                boolean.class, stashHook);

        // (A2) moveToMiniform: startRect 换 live 的 mRepositionTaskBounds
        XposedHelpers.findAndHookMethod(pos, "moveToMiniform", Rect.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                try {
                    Rect live = (Rect) XposedHelpers.getObjectField(
                            param.thisObject, "mRepositionTaskBounds");
                    if (live != null && !live.isEmpty()) {
                        param.args[0] = new Rect(live);
                    }
                } catch (Throwable t) {
                    Port.log("FreeformFx: moveToMiniform rect fix failed", t);
                }
            }
        });

        // (A3/A4) 挂起小窗 bounds 动画: live startScale + spring + 初速度
        final Class<?> miniCtrl = XposedHelpers.findClass(MINI_CTRL_CLS, cl);
        XposedHelpers.findAndHookMethod(miniCtrl, "startBoundsAnimator",
                int.class, Rect.class, float.class, Rect.class, float.class,
                boolean.class, XposedHelpers.findClass("android.view.SurfaceControl", cl),
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                try {
                    int taskId = (Integer) param.args[0];
                    Live live = takeLive(taskId);
                    if (live != null && live.scale > 0f) {
                        param.args[2] = live.scale;
                    }
                    float v0 = 0f;
                    if (live != null && live.bounds != null) {
                        Rect start = (Rect) param.args[1];
                        Rect end = (Rect) param.args[3];
                        float dx = end.centerX() - start.centerX();
                        float dy = end.centerY() - start.centerY();
                        float len = (float) Math.sqrt(dx * dx + dy * dy);
                        if (len > 1f) {
                            // 手指速度在起止路径上的投影, 归一成 progress/s
                            v0 = (live.vx * dx + live.vy * dy) / (len * len);
                            if (v0 > 8f) {
                                v0 = 8f;
                            } else if (v0 < -8f) {
                                v0 = -8f;
                            }
                        }
                    }
                    sPendingV0.set(Float.valueOf(v0));
                    sSpringMarker.set(Boolean.TRUE);
                } catch (Throwable t) {
                    Port.log("FreeformFx: startBoundsAnimator pre failed", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sSpringMarker.set(null);
            }
        });

        // (A4) 拦截 stock setInterpolator(OvershootInterpolator) 换 spring
        XposedHelpers.findAndHookMethod(ValueAnimator.class,
                "setInterpolator", TimeInterpolator.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                if (!Boolean.TRUE.equals(sSpringMarker.get())) {
                    return;
                }
                sSpringMarker.set(null);
                Object ip = param.args[0];
                if (!(ip instanceof OvershootInterpolator)) {
                    return;
                }
                try {
                    Float v0Boxed = sPendingV0.get();
                    float v0 = v0Boxed != null ? v0Boxed.floatValue() : 0f;
                    sPendingV0.set(null);
                    Class<?> springCls = XposedHelpers.findClass(SPRING_CLS, sCl);
                    param.args[0] = XposedHelpers.newInstance(
                            springCls, 800f, 50.35f, 1.0f, v0);
                    Port.log("FreeformFx: miniform spring v0=" + v0);
                } catch (Throwable t) {
                    Port.log("FreeformFx: spring replace failed", t);
                }
            }
        });

        // (A6) 最小化进圆球动画 (200ms 默认插值器) 换 spring + 上滑初速度
        final Class<?> ffCtrl = XposedHelpers.findClass(FF_CTRL_CLS, cl);
        XposedBridge.hookAllMethods(ffCtrl, "startFreeformToBackAnimator", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                try {
                    int taskId = (Integer) param.args[0];
                    Live live = takeLive(taskId);
                    float v0 = 0f;
                    if (live != null && live.vy < 0f) {
                        v0 = Math.min(8f, -live.vy / 1000f);
                    }
                    sBallV0.set(Float.valueOf(v0));
                    sBallMarker.set(Boolean.TRUE);
                } catch (Throwable t) {
                    Port.log("FreeformFx: ball anim pre failed", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sBallMarker.set(null);
            }
        });
        XposedHelpers.findAndHookMethod(ValueAnimator.class, "setDuration", long.class,
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                if (!Boolean.TRUE.equals(sBallMarker.get())) {
                    return;
                }
                sBallMarker.set(null);
                try {
                    Float v0Boxed = sBallV0.get();
                    float v0 = v0Boxed != null ? v0Boxed.floatValue() : 0f;
                    sBallV0.set(null);
                    Class<?> springCls = XposedHelpers.findClass(SPRING_CLS, sCl);
                    ((ValueAnimator) param.thisObject).setInterpolator(
                            (TimeInterpolator) XposedHelpers.newInstance(
                                    springCls, 800f, 50.35f, 1.0f, v0));
                    Port.log("FreeformFx: ball spring v0=" + v0);
                } catch (Throwable t) {
                    Port.log("FreeformFx: ball spring replace failed", t);
                }
            }
        });

        // (A5) 全屏过渡: S = L/(0.7r+0.3), 起播净缩放 = live 视觉缩放
        final Class<?> ffAnim = XposedHelpers.findClass(FF_ANIM_CLS, cl);
        XposedHelpers.findAndHookMethod(ffAnim, "setupLeashStartState",
                XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl),
                XposedHelpers.findClass("android.window.TransitionInfo$Change", cl),
                XposedHelpers.findClass("android.window.TransitionInfo", cl),
                XposedHelpers.findClass(
                        "com.android.wm.shell.hnfreeform.HnRunningFreeformTaskInfo", cl),
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_FF_ANIM)) {
                    return;
                }
                try {
                    Object change = param.args[1];
                    Object hnInfo = param.args[3];
                    Object taskInfo = XposedHelpers.callMethod(change, "getTaskInfo");
                    if (taskInfo == null) {
                        return;
                    }
                    int taskId = XposedHelpers.getIntField(taskInfo, "taskId");
                    Live live = takeLive(taskId);
                    if (live == null || live.scale <= 0f) {
                        return;
                    }
                    // 无 freeformAnimationLeash: adapter 每帧覆盖 matrix, 末态=1/S,
                    // 改写 S 会导致"末态过大", 跳过 (stock 行为)
                    Object animLeash = XposedHelpers.callMethod(change,
                            "getFreeformAnimationLeash");
                    if (animLeash == null) {
                        Port.log("FreeformFx: fullscreen skip (no anim leash) task=" + taskId);
                        return;
                    }
                    Rect start = (Rect) XposedHelpers.callMethod(change, "getStartAbsBounds");
                    Rect end = (Rect) XposedHelpers.callMethod(change, "getEndAbsBounds");
                    if (start == null || end == null || end.width() <= 0) {
                        return;
                    }
                    float r = start.width() / (float) end.width();
                    float p = 0.7f;
                    float s = live.scale / (p * r + (1 - p));
                    try {
                        XposedHelpers.callMethod(change, "setLastFreeformScale", s);
                    } catch (Throwable t) {
                        XposedHelpers.setFloatField(change, "mLastFreeformScale", s);
                    }
                    if (hnInfo != null) {
                        XposedHelpers.callMethod(hnInfo, "setCurrentScale", live.scale);
                    }
                    Port.log("FreeformFx: fullscreen S=" + s + " L=" + live.scale
                            + " r=" + r + " task=" + taskId);
                } catch (Throwable t) {
                    Port.log("FreeformFx: setupLeashStartState override failed", t);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static int positionerTaskId(Object positioner) {
        try {
            Object decor = XposedHelpers.getObjectField(positioner, "mWindowDecoration");
            Object taskInfo = XposedHelpers.getObjectField(decor, "mTaskInfo");
            return XposedHelpers.getIntField(taskInfo, "taskId");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void stashLive(Object positioner) {
        int taskId = positionerTaskId(positioner);
        if (taskId < 0) {
            return;
        }
        int ctrl = XposedHelpers.getIntField(positioner, "mCtrlType");
        if (ctrl == 0) {
            return; // 非悬浮窗自由拖拽
        }
        Rect src = ctrl == 8
                ? (Rect) XposedHelpers.getObjectField(positioner, "mCurrentBounds")
                : (Rect) XposedHelpers.getObjectField(positioner, "mTaskBoundsAtDragEnd");
        Rect bounds = src != null ? new Rect(src) : null;
        float scale = XposedHelpers.getFloatField(positioner, "mCurScale")
                * XposedHelpers.getFloatField(positioner, "mInitialScale");
        float[] vel;
        synchronized (sVel) {
            vel = sVel.remove(taskId);
        }
        float vx = vel != null ? vel[0] : 0f;
        float vy = vel != null ? vel[1] : 0f;
        synchronized (sLive) {
            pruneLocked();
            sLive.put(taskId, new Live(bounds, scale, vx, vy));
        }
    }

    private static Live takeLive(int taskId) {
        synchronized (sLive) {
            Live live = sLive.remove(taskId);
            if (live != null
                    && SystemClock.uptimeMillis() - live.at > STASH_TTL_MS) {
                return null;
            }
            return live;
        }
    }

    private static void pruneLocked() {
        long now = SystemClock.uptimeMillis();
        Iterator<Map.Entry<Integer, Live>> it = sLive.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().at > STASH_TTL_MS) {
                it.remove();
            }
        }
    }

    private FreeformFx() {}
}
