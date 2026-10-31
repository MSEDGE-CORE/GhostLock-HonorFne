package com.wxz.hnlauncherport.systemui;

import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

import com.wxz.hnlauncherport.Port;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 悬浮窗 (hnfreeform) 手势与动效优化, 运行于 SystemUI 进程 (wm.shell):
 *
 * KEY_FF_GESTURE (悬浮窗手势优化):
 * (G2) 横滑判定残留: DOWN 时 cleanFlingParmars + 给 detector 重新喂 DOWN.
 * (G3) 拖窗贴边最小化 (替换原生速度判定): 标题栏/顶栏拖动松手时, 窗口中心 X
 * 距屏幕边 < 1/5 屏宽 -> 最小化(0=左/1=右). 原生 |vx|>1500 速度判定
 * (isFlingToBall) 被禁用, 无贴边命中恒 -1, 速度残留判反 bug 由此根治;
 * 松手真实速度写入 VM 字段, 供 onTaskMinimized 传给圆球.
 * 判定挂在 CaptionTouchEventListener.onTouch (外层, 必收到全部事件),
 * 而非 handleMotionEvent (部分路径被 MultiWindowGestureDetector 抢走).
 * (G4) 角落缩放甩动挂角: 左下(9)/右下(10)角缩放松手, 窗口正在缩小且速度
 * >=1000px/s -> 直接挂到对角.
 * (G5) 底边快速上滑: 放行 stock 关闭.
 * (G6) 慢速松手: 未到最小 -> stock settle; 滑到最小(橙色) -> stock 关闭
 * (挂起行为对调); 挂起仅由 G4 角落缩放甩动触发.
 * (G7) 角落优先: 标题栏拖到左上/右上角热点触发态 -> 跳过贴边最小化,
 * 交回 stock 迷你窗 (moveToMiniform).
 * (G8) 移除角落 "迷你窗/抬手进入" 提示及提示框 (热点圆保留).
 * (G9) 角落缩放热区扩大: setGeometry 的 cornerSize
 * (freeform_resize_corner) 30dp -> 60dp, 角区横向加倍, 底边上下缩放
 * 条左右各缩 30dp.
 *
 * KEY_FF_ANIM (悬浮窗动效优化):
 * (A1) 拖拽结束快照 live bounds + live scale (mCurScale*mInitialScale).
 * (A2 已移除) moveToMiniform 不换 startRect: stock rect4 含 X 缩放补偿,
 * 替换会致角落迷你窗 t=0 坐标跳回缩小前位置.
 * (A3) startBoundsAnimator 的 startScale 用 live 快照覆盖.
 * (A4) 挂起小窗结束动画: OvershootInterpolator 换 SpringInterpolator
 * (stiffness=800, damping=50.35, ζ≈0.89, v0=投影初速度), 时长换
 * spring 自然时长 (getDuration), 否则 300ms 内 spring 不可见.
 * (A5) 全屏过渡连贯: hook FreeformAnimationRunner.createChangeAnimationAdapters
 * before (早于 line314 的 f 读取与 line327 的 setupLeashStartState,
 * 保证 f 与 leash 预缩放一致). mode==6 且 pre==5->win==1 的 change:
 * 有 animLeash: setStartAbsBounds(liveRect) + S=L/(0.7r+0.3), 起播净
 * 缩放=live 视觉缩放, 末态 S*(1/S)=1;
 * 无 animLeash (adapter 每帧覆盖 matrix, start=fWidth, end=1/S):
 * 必须 S=1 (末态=1), 合成 startAbsBounds 宽高比 r'=(L-0.3)/0.7 且位置
 * =liveRect 左上 (start=L 连贯). stock 无 animLeash 时 S<1 -> 末帧 1/S>1
 * = 原生"末态过大".
 * (A6) 最小化进圆球动画 (startFreeformToBackAnimator, 200ms 默认插值器)
 * 换 spring + 上滑初速度, 时长换 spring 自然时长.
 * (A7) 贴边最小化过渡 (minimizeTask -> FreeformAnimationRunner):
 * getChangeAnimInterPolater 返回的 stock spring(206/27/v0=0) 换
 * spring(800/50.35/v0=贴边松手速度), 时长取 spring 自然时长.
 * (A9) 横屏任务进小窗的快照飞行时长: runner 的 z3 分支
 * (!isWideStatus && isTaskLandScape) 会以 j=500ms 调
 * BoundsChangeAnimationFactory.createBoundsChangeAnimations (该时长
 * 烤进返回的 Animation, z2&&z3 时插值器/时长均不被覆盖); hook 工厂把
 * 500ms 参数改为 1ms, 飞行近瞬时 (配合 A8 的旋转 jump-cut); 该工厂的
 * 500 入参仅来自 z3 分支, 200ms 竖屏分支与其他调用不受影响.
     * (A11) spring 路径时长减半: runner 以 spring 自然时长 (206/27 或 A7 的
     * 800/50.35) 调 FreeformAnimationAdapter.setDuration (:366/:387, 全工程
     * 仅此两处调用), 时长同时烤进 AnimationSet 子动画与 getDurationHint
     * (computeDurationHint) -> jMax -> ValueAnimator.setDuration(jMax) 实际
     * 播放; 入参减半 = 同一 spring 曲线 2 倍速播放; z2&&z3 横屏分支本就跳过
     * setDuration, 不受影响.
     * (A10) 横屏 OneStep 进小窗直接跳过飞行动画: mini transform 的 flight
 * transition (type==CHANGE, 无 displayChange, 含 flag 33554432 且
 * windowingMode==5 的 bounds 变化 task) 到达 FTT 时, 若 display 已横屏
 * (rotation!=0), 直接 apply 起始事务并立即 finish —— 跳过快照飞行,
 * 落终态 landscape bounds, WM 随即发起回竖屏旋转 transition, 由 A8
 * 继续 jump-cut; 跳过 FTT:125 的 setIsFreeformAnimRunning(true), 故
 * 手动补记 A8 所需的飞行标记; 竖屏 (rotation==0) 不命中, 保持 stock
 * 飞行; miniform/freeform 状态由 FreeformTaskTransitionObserver.
 * onTransitionFinished -> HnFreeformController.onTransitionFinished
 * 统一复位, 不会卡死.
 * (A8) 横屏 swipe-up mini 落位后系统回竖屏旋转 (mini 化让出 display
 * 方向话语权, Transition.finishTransition 内联发起 displayChange 的
 * CHANGE transition, stock 由 DefaultTransitionHandler 播 333ms
 * HnRotationAnimation): FreeformTaskTransitionHandler.startAnimation
 * 接管 — type==CHANGE 且带 displayChange, 且含 windowingMode==5 的
 * bounds 变化 task, 且该 task 近期 (FF_RECENT_MS 内) 有
 * setIsFreeformAnimRunning(taskId, true) (即刚经历过 mini 飞行) ->
 * 跳过旋转动画, 直接 apply 起始事务并立即 finish (与 stock no-anim
 * 分支同构), 屏幕瞬时切竖屏, mini 窗直落终态 bounds; 物理旋转等
 * 无近期飞行的场景不受影响, 走 stock 旋转.
 */
public final class FreeformFx {

    private static final long STASH_TTL_MS = 1200;

    private static final String FF_CTRL_CLS = "com.android.wm.shell.hnfreeform.HnFreeformController";
    private static final String FF_UTILS_CLS = "com.android.wm.shell.hnfreeform.HnFreeformUtils";
    private static final String MINI_CTRL_CLS = "com.android.wm.shell.hnfreeform.miniwindow.HnMiniformController";
    private static final String FLUID_POS_CLS = "com.android.wm.shell.windowdecor.FluidResizeTaskPositioner";
    private static final String CAPTION_VM_CLS = "com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel";
    private static final String CAPTION_LISTENER_CLS = "com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel$CaptionTouchEventListener";
    private static final String FLING_GD_CLS = "com.android.wm.shell.windowdecor.DragResizeInputListener$TaskResizeInputEventReceiver$FlingGestureDetector";
    private static final String FF_RUNNER_CLS = "com.android.wm.shell.freeform.FreeformAnimationRunner";
    private static final String FTT_HANDLER_CLS = "com.android.wm.shell.freeform.FreeformTaskTransitionHandler";
    private static final String SPRING_CLS = "com.hihonor.dynamicanimation.interpolator.SpringInterpolator";

    /** (A8) mini 飞行起始标记有效期: 飞行 500ms + WM 建 transition/dispatch 延迟可达 ~2s */
    private static final long FF_RECENT_MS = 2500L;

    private static class Live {
        final Rect bounds;
        final float scale; // <=0 = 未知, 不覆盖
        final float vx; // px/s, 松手手指速度
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
    /** caption listener -> {downRawX, lastRawX, lastEventTimeMs} (贴边判定/速度用) */
    private static final WeakHashMap<Object, float[]> sTrack = new WeakHashMap<>();
    /** 贴边命中方向 (onTouch UP -> isFlingToBall 同线程传递) */
    private static final ThreadLocal<Integer> sEdgeDir = new ThreadLocal<>();
    /** 贴边最小化 spring 初速度 {v0progress, atMs} (跨线程) */
    private static volatile float[] sMinimizeVel;
    /** startBoundsAnimator -> ValueAnimator.setInterpolator 同线程标记 */
    private static final ThreadLocal<Boolean> sSpringMarker = new ThreadLocal<>();
    private static final ThreadLocal<Float> sPendingV0 = new ThreadLocal<>();
    /** startFreeformToBackAnimator -> ValueAnimator.setDuration 同线程标记 */
    private static final ThreadLocal<Boolean> sBallMarker = new ThreadLocal<>();
    private static final ThreadLocal<Float> sBallV0 = new ThreadLocal<>();
    /** 角落甩动已自行 stash 标记 (corner hook -> A1 stash hook) */
    private static final ThreadLocal<Boolean> sCornerExtra = new ThreadLocal<>();


    /** (A8) task -> setIsFreeformAnimRunning(taskId,true) 时刻 */
    private static final Map<Integer, Long> sFfAnimStart = new HashMap<>();
    private static ClassLoader sCl;
    private static volatile boolean sPreFieldMissing;

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
                                sVel.put(taskId, new float[] { vx, vy });
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

        // (G5) 底边快速上滑: 放行 stock onDragPositioningCancel(2) 关闭.
        //
        // (G6) 慢速松手: 未到最小 -> 交回 stock (settle); 滑到最小(橙色)
        // -> stock 关闭 (onDragPositioningCancel(2), 挂起行为对调).
        // 挂起左右角判定: 窗口中心 X < 屏宽 1/3 -> 左上(1), 其余 -> 右上(2)
        // (stock: 屏宽 1/2 对半分).
        final Class<?> ffUtils = XposedHelpers.findClass(FF_UTILS_CLS, cl);
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningToMiniwindow",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_GESTURE)) {
                            return;
                        }
                        if (!Boolean.TRUE.equals(param.args[0])) {
                            return;
                        }
                        try {
                            Object positioner = param.thisObject;
                            if (XposedHelpers.getIntField(positioner, "mCtrlType") != 8) {
                                return;
                            }
                            float cur = XposedHelpers.getFloatField(positioner, "mCurScale");
                            float init = XposedHelpers.getFloatField(positioner, "mInitialScale");
                            float mini = XposedHelpers.getFloatField(positioner, "mMiniWindowScale");
                            if (cur * init - mini >= 0.01f) {
                                return; // 未到最小, 交回 stock (settle)
                            }
                            // 滑到最小(橙色) -> stock 关闭
                            XposedHelpers.callMethod(positioner,
                                    "onDragPositioningCancel", 0f, 0f, 2);
                            param.setResult(Boolean.TRUE);
                            Port.log("FreeformFx: orange slow release -> close task="
                                    + positionerTaskId(positioner));
                        } catch (Throwable t) {
                            Port.log("FreeformFx: orange slow release close failed", t);
                        }
                    }
                });

        final Class<?> capListener = XposedHelpers.findClass(CAPTION_LISTENER_CLS, cl);

        // (G2a/G3) 标题栏 touch: DOWN 清残留+跟踪, MOVE 跟踪, UP 贴边判定.
        // 挂在 onTouch (外层) 而非 handleMotionEvent —— 后者在部分路径被
        // MultiWindowGestureDetector 抢走收不到事件.
        XposedHelpers.findAndHookMethod(capListener, "onTouch", View.class, MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_GESTURE)) {
                            return;
                        }
                        MotionEvent ev = (MotionEvent) param.args[1];
                        if (ev == null) {
                            return;
                        }
                        int action = ev.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            resetCaptionFlingState(param.thisObject, ev);
                            trackTouch(param.thisObject, ev.getRawX(), ev.getRawX(),
                                    ev.getEventTime());
                            sEdgeDir.set(null);
                        } else if (action == MotionEvent.ACTION_MOVE) {
                            trackTouch(param.thisObject, -1f, ev.getRawX(), ev.getEventTime());
                        } else if (action == MotionEvent.ACTION_UP) {
                            try {
                                handleEdgeMinimize(param.thisObject, ev);
                            } catch (Throwable t) {
                                Port.log("FreeformFx: edge minimize failed", t);
                            }
                            synchronized (sTrack) {
                                sTrack.remove(param.thisObject);
                            }
                        } else if (action == MotionEvent.ACTION_CANCEL) {
                            synchronized (sTrack) {
                                sTrack.remove(param.thisObject);
                            }
                        }
                    }
                });

        // (G2b) handleMotionEvent DOWN 兜底 (DragDetector 路径)
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

        // (G8) 移除角落 "迷你窗/抬手进入" 文字提示及提示框
        // (HnMiniformHotspotUIController showTip/updateTip, 热点圆保留).
        // 注: showTip 为极小私有方法, 疑被 dex2oat 内联进 lambda$show$4,
        // hook 注册成功但编译产物不再经其入口 (16:06 测试: 注册无异常但
        // tip 窗口仍 attach). 故 G8b 在 WindowManagerImpl.addView 层兜底.
        final Class<?> hotspotUi = XposedHelpers.findClass(
                "com.android.wm.shell.hnfreeform.miniwindow.HnMiniformHotspotUIController", cl);
        XposedHelpers.findAndHookMethod(hotspotUi, "showTip", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_FF_GESTURE)) {
                    param.setResult(null); // 不添加 "Freeform-HotSpotTip" 提示框窗口
                }
            }
        });
        XposedHelpers.findAndHookMethod(hotspotUi, "updateTip", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_FF_GESTURE)) {
                    param.setResult(null); // 不更新 "迷你窗/抬手进入" 文案
                }
            }
        });
        // (G8b) 兜底: addView 层拦截 tip 窗口 (framework 方法, 不受 OEM AOT
        // 内联影响; 仅拦 "Freeform-HotSpotTip", 热点圆 "Freeform-Hotspot" 放行).
        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("android.view.WindowManagerImpl", cl),
                "addView", View.class, ViewGroup.LayoutParams.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) param.args[1];
                if (!(lp instanceof WindowManager.LayoutParams)) {
                    return;
                }
                CharSequence title = ((WindowManager.LayoutParams) lp).getTitle();
                if (title == null || !"Freeform-HotSpotTip".contentEquals(title)) {
                    return;
                }
                if (Port.on(Port.KEY_FF_GESTURE)) {
                    Port.log("FreeformFx: block hotspot tip window add");
                    param.setResult(null);
                }
            }
        });

        // (G8c) 隐藏热点圆/四分之一圆背景 overlay (onDraw 置空, 窗口保留
        // 但不绘制; onDraw 为 framework 虚调用, 不会被 AOT 内联, 必定生效).
        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass(
                        "com.android.wm.shell.hnfreeform.miniwindow.HnMiniformHotspotView", cl),
                "onDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_FF_GESTURE)) {
                    param.setResult(null);
                }
            }
        });

        // (G9) 扩大角落缩放热区: freeform_resize_corner 30dp -> 60dp.
        // setGeometry(taskW, taskH, handleThickness, cornerSize, slop) 的
        // arg3 即 cornerSize (getDimensionPixelSize 返回 px, 3x 密度下
        // 30dp=90px, 不能按 ==30 判断), 左下/右下角热区宽 = cornerSize,
        // 底边条(ctrl 8) 左右各让出 cornerSize; 恒 ×2 (90px->180px=60dp),
        // 调用方每次传原始 dimen 值, 幂等.
        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass(
                        "com.android.wm.shell.windowdecor.DragResizeInputListener", cl),
                "setGeometry", int.class, int.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_GESTURE)) {
                            return;
                        }
                        int corner = (Integer) param.args[3];
                        if (corner > 0) {
                            param.args[3] = Integer.valueOf(corner * 3);
                            Port.log("FreeformFx: corner size " + corner + "px -> " + corner * 2 + "px");
                        }
                    }
                });

        // (G3) 贴边判定替换速度判定: 贴边命中返回方向, 否则恒 -1 (禁用原生甩动)
        final Class<?> capVm = XposedHelpers.findClass(CAPTION_VM_CLS, cl);
        XposedHelpers.findAndHookMethod(capVm, "isFlingToBall",
                XposedHelpers.findClass("android.app.ActivityManager$RunningTaskInfo", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_GESTURE)) {
                            return;
                        }
                        Integer edge = sEdgeDir.get();
                        sEdgeDir.set(null);
                        param.setResult(edge != null ? edge : Integer.valueOf(-1));
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

    /** downX<0 表示仅更新 last 值 (MOVE), 否则整体重置 (DOWN) */
    private static void trackTouch(Object listener, float downX, float x, long t) {
        synchronized (sTrack) {
            if (downX >= 0f) {
                sTrack.put(listener, new float[] { downX, x, t });
            } else {
                float[] cur = sTrack.get(listener);
                if (cur != null) {
                    cur[1] = x;
                    cur[2] = t;
                }
            }
        }
    }

    /**
     * (G3) 标题栏拖动松手贴边判定: 窗口宽 8.5/20 越出屏幕边 -> 最小化.
     * 命中时写入真实松手速度 (供 onTaskMinimized 传圆球) + sEdgeDir (供
     * isFlingToBall hook) + sMinimizeVel (供 A7 spring 初速度).
     */
    private static void handleEdgeMinimize(Object listener, MotionEvent ev) {
        float[] track;
        synchronized (sTrack) {
            track = sTrack.get(listener);
        }
        if (track == null) {
            return;
        }
        Object vm = XposedHelpers.getObjectField(listener, "this$0");
        int taskId = XposedHelpers.getIntField(listener, "mTaskId");
        Object organizer = XposedHelpers.getObjectField(vm, "mTaskOrganizer");
        Object rt = XposedHelpers.callMethod(organizer, "getRunningTaskInfo", taskId);
        if (rt == null) {
            return;
        }
        if ((Integer) XposedHelpers.callMethod(rt, "getWindowingMode") == 1) {
            return;
        }
        Object ffCtrl = XposedHelpers.getObjectField(vm, "mHnFreeformController");
        if (ffCtrl != null && Boolean.TRUE.equals(XposedHelpers.callMethod(
                XposedHelpers.callMethod(ffCtrl, "getMiniformController"),
                "isMiniWindow", taskId))) {
            return;
        }
        // (G7) 角落热点触发态 -> 优先 stock 迷你窗 (moveToMiniform), 跳过贴边最小化
        if (ffCtrl != null) {
            try {
                Object helper = XposedHelpers.callMethod(
                        XposedHelpers.callMethod(ffCtrl, "getMiniformController"),
                        "getMiniformMotionHelper");
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "isNeedToGoMini", taskId))) {
                    Port.log("FreeformFx: hotspot trigger, skip edge minimize task=" + taskId);
                    return;
                }
            } catch (Throwable t) {
                Port.log("FreeformFx: hotspot check failed", t);
            }
        }
        Object cfg = XposedHelpers.getObjectField(rt, "configuration");
        Object wc = XposedHelpers.getObjectField(cfg, "windowConfiguration");
        Rect taskBounds = (Rect) XposedHelpers.callMethod(wc, "getBounds");
        Rect maxBounds = (Rect) XposedHelpers.callMethod(wc, "getMaxBounds");
        if (taskBounds == null || taskBounds.isEmpty()
                || maxBounds == null || maxBounds.isEmpty()) {
            return;
        }
        // 视觉窗口 = 任务边界 × currentScale (锚定左上): HnFreeform 把边界
        // 放大 1/scale 再用 surface matrix scale 缩回 (coordinator
        // rect.scale(getCurrentScale())+offsetTo(left,top); 横屏内容/
        // 宽小窗 scale<1). 直接用未缩放边界会高估右侧越出量与阈值 ->
        // 宽小窗轻碰即最小化 / 横屏小窗范围错位.
        float scale = 1f;
        if (ffCtrl != null) {
            try {
                Object fti = XposedHelpers.callMethod(ffCtrl, "getFreeformTaskInfo", taskId);
                if (fti != null) {
                    float cs = (Float) XposedHelpers.callMethod(fti, "getCurrentScale");
                    if (cs > 0f && cs < 1.5f) {
                        scale = cs;
                    }
                }
            } catch (Throwable t) {
                Port.log("FreeformFx: currentScale read failed", t);
            }
        }
        Rect visual = new Rect(taskBounds.left, taskBounds.top,
                taskBounds.left + (int) (taskBounds.width() * scale),
                taskBounds.top + (int) (taskBounds.height() * scale));
        float dx = ev.getRawX() - track[0];
        float liveLeft = visual.left + dx;
        float liveRight = visual.right + dx;
        // 越出屏幕的部分 >= 视觉宽 0.3 -> 贴边
        float offThreshold = visual.width() * 0.3f;
        int dir = maxBounds.left - liveLeft >= offThreshold ? 0
                : liveRight - maxBounds.right >= offThreshold ? 1 : -1;
        if (dir < 0) {
            return;
        }
        // 真实松手速度: 最后 <=250ms 内的位移, 超时视为 0
        float vx = 0f;
        long dt = ev.getEventTime() - (long) track[2];
        if (dt > 0 && dt <= 250L) {
            vx = (ev.getRawX() - track[1]) * 1000f / dt;
        }
        XposedHelpers.setFloatField(vm, "mVelocityX", vx);
        XposedHelpers.setFloatField(vm, "mVelocityY", 0f);
        XposedHelpers.setFloatField(vm, "mMoveXDistance", dir == 0 ? -50f : 50f);
        setLongFieldReflect(vm, "mDeltaTime", 100L);
        XposedHelpers.setBooleanField(listener, "mIsCtrlTopBar", true);
        sEdgeDir.set(Integer.valueOf(dir));
        float v0 = (dir == 0 ? -vx : vx) / (maxBounds.width() * 0.4f);
        if (v0 < 0f) {
            v0 = 0f;
        } else if (v0 > 8f) {
            v0 = 8f;
        }
        sMinimizeVel = new float[] { v0, SystemClock.uptimeMillis() };
        Port.log("FreeformFx: edge minimize dir=" + (dir == 0 ? "left" : "right")
                + " vx=" + (int) vx + " v0=" + v0 + " task=" + taskId);
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

        // (A2 removed) moveToMiniform 不再替换 startRect: stock drag end 先拷贝
        // 含 X 缩放补偿的 rect4 (= 松手时 surface 真实 left/top, 动画 t=0 应当
        // 用的位置), 再以 5 参 updateTaskBounds (无补偿) 重算
        // mRepositionTaskBounds; 原替换会把 t=0 坐标换回缩小前位置, 造成角落
        // 迷你窗入场水平跳变 (1-scale)*W/2. zoom 期间 surface 位置不变
        // (updateCurScaleValue 仅设字段), stock rect4 即精确起点, 无需修正.

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
                            Object spring = XposedHelpers.newInstance(
                                    springCls, 800f, 50.35f, 1.0f, v0);
                            param.args[0] = spring;
                            // 300ms 内 spring 不可见, 时长换 spring 自然时长
                            long dur = springDuration(spring);
                            if (dur > 0) {
                                ((ValueAnimator) param.thisObject).setDuration(dur);
                            }
                            Port.log("FreeformFx: miniform spring v0=" + v0 + " dur=" + dur);
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
                            Object spring = XposedHelpers.newInstance(
                                    springCls, 800f, 50.35f, 1.0f, v0);
                            ((ValueAnimator) param.thisObject).setInterpolator(
                                    (TimeInterpolator) spring);
                            long dur = springDuration(spring);
                            if (dur > 0) {
                                param.args[0] = dur; // 200ms -> spring 自然时长
                            }
                            Port.log("FreeformFx: ball spring v0=" + v0 + " dur=" + dur);
                        } catch (Throwable t) {
                            Port.log("FreeformFx: ball spring replace failed", t);
                        }
                    }
                });

        // (A7) 贴边最小化过渡: stock change spring(206/27/v0=0) 换带初速度 spring
        final Class<?> runnerCls = XposedHelpers.findClass(FF_RUNNER_CLS, cl);
        XposedHelpers.findAndHookMethod(runnerCls, "getChangeAnimInterPolater",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        try {
                            float[] stash = sMinimizeVel;
                            if (stash == null
                                    || SystemClock.uptimeMillis() - (long) stash[1] > 800L) {
                                return;
                            }
                            sMinimizeVel = null;
                            float v0 = Math.max(0f, Math.min(8f, stash[0]));
                            Class<?> springCls = XposedHelpers.findClass(SPRING_CLS, sCl);
                            param.setResult(XposedHelpers.newInstance(
                                    springCls, 800f, 50.35f, 1.0f, v0));
                            Port.log("FreeformFx: minimize spring v0=" + v0);
                        } catch (Throwable t) {
                            Port.log("FreeformFx: minimize spring replace failed", t);
                        }
                    }
                });

        // (A5) 全屏过渡连贯: 在 f 读取(line 314)前修正 change,
        // 保证 f 与 setupLeashStartState 的 leash 预缩放一致
        XposedHelpers.findAndHookMethod(runnerCls, "createChangeAnimationAdapters",
                XposedHelpers.findClass("android.window.TransitionInfo", cl),
                XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        try {
                            Object info = param.args[0];
                            Iterable<?> changes = (Iterable<?>) XposedHelpers.callMethod(info, "getChanges");
                            for (Object change : changes) {
                                fixFullscreenChange(change);
                            }
                        } catch (Throwable t) {
                            Port.log("FreeformFx: fullscreen change fix failed", t);
                        }
                    }
                });

        // (A9) 横屏进小窗飞行 500ms -> 1ms (仅 z3 横屏任务的 500 入参)
        final Class<?> bcfCls = XposedHelpers.findClass(
                "com.android.wm.shell.freeform.BoundsChangeAnimationFactory", cl);
        XposedHelpers.findAndHookMethod(bcfCls, "createBoundsChangeAnimations",
                XposedHelpers.findClass("android.window.TransitionInfo$Change", cl),
                Rect.class, long.class, float.class, float.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        if (((Long) param.args[2]).longValue() == 500L) {
                            param.args[2] = Long.valueOf(0L);
                            Port.log("FreeformFx: landscape mini flight 500ms -> 1ms");
                        }
                    }
                });

        // (A11) spring 路径时长 *0.5 (同曲线 2 倍速): 唯一调用方是 runner
        // :366/:387 传入的 spring 自然时长
        final Class<?> faaCls = XposedHelpers.findClass(
                "com.android.wm.shell.freeform.FreeformAnimationAdapter", cl);
        XposedHelpers.findAndHookMethod(faaCls, "setDuration",
                long.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        long d = (Long) param.args[0];
                        if (d > 0L) {
                            param.args[0] = Long.valueOf(d / 2L);
                            Port.log("FreeformFx: spring path duration " + d + " -> " + d / 2L);
                        }
                    }
                });

        // (A8a) 记录 mini 飞行起始: FTT.startAnimation 对 windowingMode==5 的
        // task 会调 setIsFreeformAnimRunning(taskId, true)
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(FF_CTRL_CLS, cl),
                "setIsFreeformAnimRunning", int.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        if (!Boolean.TRUE.equals(param.args[1])) {
                            return;
                        }
                        int taskId = (Integer) param.args[0];
                        synchronized (sFfAnimStart) {
                            pruneFfAnimStartLocked();
                            sFfAnimStart.put(taskId, SystemClock.uptimeMillis());
                        }
                    }
                });

        // (A8b) 接管回竖屏旋转: type==CHANGE 且带 displayChange, 且含
        // windowingMode==5 的 bounds 变化 task 且近期有 mini 飞行 -> 跳过旋转
        final Class<?> fttCls = XposedHelpers.findClass(FTT_HANDLER_CLS, cl);
        XposedHelpers.findAndHookMethod(fttCls, "startAnimation",
                IBinder.class,
                XposedHelpers.findClass("android.window.TransitionInfo", cl),
                XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl),
                XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl),
                XposedHelpers.findClass(
                        "com.android.wm.shell.transition.Transitions$TransitionFinishCallback", cl),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_ANIM)) {
                            return;
                        }
                        try {
                            maybeJumpCutRotation(param);
                        } catch (Throwable t) {
                            Port.log("FreeformFx: rotation takeover failed", t);
                        }
                    }
                });
    }

    /**
     * (A8/A10) 判定并接管 mini 化相关 transition:
     * 共同条件: type==CHANGE 且存在 mode==CHANGE 且 windowingMode==5 且
     * start/end bounds 不同的 task change.
     * A8 (带 displayChange): 该 task 在 FF_RECENT_MS 内有 mini 飞行标记 ->
     * 跳过回竖屏旋转.
     * A10 (无 displayChange 且 change 带 flag 33554432 且 display 已横屏):
     * 跳过快照飞行, 落终态 landscape bounds, 并补记 A8 所需的飞行标记
     * (跳过了 FTT:125 的 setIsFreeformAnimRunning(true)).
     * 命中后 apply 起始事务 + 主线程立即 onTransitionFinished(null, null)
     * (与 stock no-anim 分支同构), setResult(true).
     */
    private static void maybeJumpCutRotation(XC_MethodHook.MethodHookParam param) {
        Object info = param.args[1];
        if (((Integer) XposedHelpers.callMethod(info, "getType")).intValue() != 6) {
            return;
        }
        boolean hasDisplayChange = false;
        int miniTaskId = -1;
        boolean miniTransformFlag = false;
        Iterable<?> changes = (Iterable<?>) XposedHelpers.callMethod(info, "getChanges");
        for (Object change : changes) {
            int sr = (Integer) XposedHelpers.callMethod(change, "getStartRotation");
            int er = (Integer) XposedHelpers.callMethod(change, "getEndRotation");
            if (sr != er) {
                hasDisplayChange = true;
            }
            Object taskInfo = XposedHelpers.callMethod(change, "getTaskInfo");
            if (taskInfo == null
                    || (Integer) XposedHelpers.callMethod(change, "getMode") != 6
                    || (Integer) XposedHelpers.callMethod(taskInfo, "getWindowingMode") != 5) {
                continue;
            }
            Rect sb = (Rect) XposedHelpers.callMethod(change, "getStartAbsBounds");
            Rect eb = (Rect) XposedHelpers.callMethod(change, "getEndAbsBounds");
            if (sb == null || eb == null || sb.equals(eb)) {
                continue;
            }
            miniTaskId = XposedHelpers.getIntField(taskInfo, "taskId");
            miniTransformFlag = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(change, "hasFlags", Integer.valueOf(33554432)));
            break;
        }
        if (miniTaskId < 0) {
            return;
        }
        String kind;
        if (hasDisplayChange) {
            // A8: 回竖屏旋转
            if (!takeRecentFfAnimStart(miniTaskId)) {
                return;
            }
            kind = "rotation";
        } else {
            // A10: 横屏 OneStep 飞行
            if (!miniTransformFlag || landscapeRotation(param.thisObject) == 0) {
                return; // 竖屏/非 mini transform: 保持 stock 飞行
            }
            synchronized (sFfAnimStart) {
                sFfAnimStart.put(miniTaskId, SystemClock.uptimeMillis());
            }
            kind = "flight";
        }
        Object startTx = param.args[2];
        XposedHelpers.callMethod(startTx, "apply");
        final Object finishCb = param.args[4];
        Object transitions = XposedHelpers.getObjectField(param.thisObject, "mTransitions");
        Object executor = XposedHelpers.callMethod(transitions, "getMainExecutor");
        XposedHelpers.callMethod(executor, "execute", (Object) new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(finishCb, "onTransitionFinished",
                            (Object) null, (Object) null);
                } catch (Throwable t) {
                    Port.log("FreeformFx: " + "transition finish callback failed", t);
                }
            }
        });
        param.setResult(Boolean.TRUE);
        Port.log("FreeformFx: " + kind + " jump-cut task=" + miniTaskId);
    }

    /** (A10) FTT.mContext 当前 display rotation, 失败返回 0 (视为竖屏不命中) */
    private static int landscapeRotation(Object handler) {
        try {
            Object ctx = XposedHelpers.getObjectField(handler, "mContext");
            Object display = XposedHelpers.callMethod(ctx, "getDisplay");
            return (Integer) XposedHelpers.callMethod(display, "getRotation");
        } catch (Throwable t) {
            Port.log("FreeformFx: display rotation read failed", t);
            return 0;
        }
    }

    /** (A8) 命中并移除近期 mini 飞行标记 */
    private static boolean takeRecentFfAnimStart(int taskId) {
        synchronized (sFfAnimStart) {
            Long at = sFfAnimStart.remove(taskId);
            return at != null && SystemClock.uptimeMillis() - at.longValue() <= FF_RECENT_MS;
        }
    }

    private static void pruneFfAnimStartLocked() {
        long now = SystemClock.uptimeMillis();
        Iterator<Map.Entry<Integer, Long>> it = sFfAnimStart.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().longValue() > FF_RECENT_MS) {
                it.remove();
            }
        }
    }

    /**
     * (A5) 对 mode==6 且 pre==5 -> win==1 的 change 修正:
     * 有 animLeash: setStartAbsBounds(liveRect), S=L/(0.7r+0.3) -> 起播净缩放=L;
     * 无 animLeash (adapter 每帧覆盖 matrix, end=1/S): S=1 保证末态=1,
     * 合成 startAbsBounds r'=(L-0.3)/0.7 -> 起播 fWidth=L 连贯.
     */
    private static void fixFullscreenChange(Object change) {
        try {
            if ((Integer) XposedHelpers.callMethod(change, "getMode") != 6) {
                return;
            }
            Object taskInfo = XposedHelpers.callMethod(change, "getTaskInfo");
            if (taskInfo == null) {
                return;
            }
            int pre;
            try {
                pre = XposedHelpers.getIntField(taskInfo, "preWindowMode");
            } catch (Throwable t) {
                if (!sPreFieldMissing) {
                    sPreFieldMissing = true;
                    Port.log("FreeformFx: preWindowMode field missing, skip A5");
                }
                return;
            }
            int win = (Integer) XposedHelpers.callMethod(taskInfo, "getWindowingMode");
            if (pre != 5 || win != 1) {
                return;
            }
            int taskId = XposedHelpers.getIntField(taskInfo, "taskId");
            Live live = takeLive(taskId);
            if (live == null || live.bounds == null
                    || live.scale <= 0f || live.scale > 1.5f) {
                return;
            }
            Rect end = (Rect) XposedHelpers.callMethod(change, "getEndAbsBounds");
            if (end == null || end.isEmpty()) {
                return;
            }
            Object animLeash = XposedHelpers.callMethod(change, "getFreeformAnimationLeash");
            boolean hasLeash = animLeash != null
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(animLeash, "isValid"));
            if (hasLeash) {
                Rect start = new Rect(live.bounds);
                try {
                    XposedHelpers.callMethod(change, "setStartAbsBounds", start);
                } catch (Throwable t) {
                    Port.log("FreeformFx: setStartAbsBounds failed", t);
                    return;
                }
                float r = start.width() / (float) end.width();
                float s = live.scale / (0.7f * r + 0.3f);
                setChangeLastScale(change, s);
                Port.log("FreeformFx: fullscreen(leash) S=" + s + " L=" + live.scale
                        + " r=" + r + " task=" + taskId);
            } else {
                float rp = (live.scale - 0.3f) / 0.7f;
                if (rp <= 0.05f) {
                    // live 太小, 无法合成连贯 start, 仅保证末态正确
                    setChangeLastScale(change, 1.0f);
                    Port.log("FreeformFx: fullscreen(no leash) S=1 only, L="
                            + live.scale + " task=" + taskId);
                    return;
                }
                Rect synth = new Rect(0, 0,
                        (int) (rp * end.width()), (int) (rp * end.height()));
                synth.offsetTo(live.bounds.left, live.bounds.top);
                try {
                    XposedHelpers.callMethod(change, "setStartAbsBounds", synth);
                } catch (Throwable t) {
                    Port.log("FreeformFx: setStartAbsBounds(no leash) failed", t);
                    return;
                }
                setChangeLastScale(change, 1.0f);
                Port.log("FreeformFx: fullscreen(no leash) S=1 r'=" + rp
                        + " task=" + taskId);
            }
        } catch (Throwable t) {
            Port.log("FreeformFx: fullscreen change skip", t);
        }
    }

    private static void setChangeLastScale(Object change, float s) {
        try {
            XposedHelpers.callMethod(change, "setLastFreeformScale", s);
            return;
        } catch (Throwable ignored) {
        }
        XposedHelpers.setFloatField(change, "mLastFreeformScale", s);
    }

    /** SpringInterpolator.getDuration(): spring 自然时长 ms, 失败返回 0 */
    private static long springDuration(Object spring) {
        try {
            Object d = XposedHelpers.callMethod(spring, "getDuration");
            if (d instanceof Number) {
                return ((Number) d).longValue();
            }
        } catch (Throwable ignored) {
        }
        return 0L;
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

    private static void setLongFieldReflect(Object obj, String name, long value) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setLong(obj, value);
        } catch (Throwable t) {
            Port.log("FreeformFx: set long field " + name + " failed", t);
        }
    }

    private FreeformFx() {
    }
}
