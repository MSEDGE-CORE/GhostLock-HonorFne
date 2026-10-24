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
 * (G5) 底边快速上滑: 未到最小 -> 挂起为小窗; 已到最小(橙色) -> stock 关闭.
 * (G6) 挂起行为对调: 慢速滑到最小(橙色)松手 -> 关闭 (stock 为挂起);
 * 快速挂起先 stashLive 传递平滑缩放 (动效优化);
 * 挂起小窗左右角判定: 窗口中心 X < 屏宽 1/3 -> 左上, 其余 -> 右上
 * (stock: 屏宽 1/2 对半分).
 *
 * KEY_FF_ANIM (悬浮窗动效优化):
 * (A1) 拖拽结束快照 live bounds + live scale (mCurScale*mInitialScale).
 * (A2) moveToMiniform 的 startRect 换 live 的 mRepositionTaskBounds.
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
    private static final String SPRING_CLS = "com.hihonor.dynamicanimation.interpolator.SpringInterpolator";

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

    private static final ThreadLocal<Boolean> sFastFling = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> sCloseGuard = new ThreadLocal<>();
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

        // (G5) 底边快速上滑: 未到最小(非橙色) -> 挂起为小窗; 已到最小(橙色)
        // -> 放行 stock onDragPositioningCancel(2) 关闭 (滑到最小-关闭).
        // 分类以松手缩放状态为准, 不依赖 stock 速度判定 (拖到最小的松手
        // 速度常仍超阈值, 会被误判为快速上滑).
        XposedHelpers.findAndHookMethod(pos, "onDragPositioningCancel",
                float.class, float.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_FF_GESTURE)) {
                            return;
                        }
                        if (((Integer) param.args[2]).intValue() != 2) {
                            return; // 仅快速上滑路径
                        }
                        if (Boolean.TRUE.equals(sCloseGuard.get())) {
                            return; // G6 慢速滑到最小 -> 关闭的内部调用, 放行 stock 关闭
                        }
                        try {
                            float cur = XposedHelpers.getFloatField(param.thisObject, "mCurScale");
                            float init = XposedHelpers.getFloatField(param.thisObject, "mInitialScale");
                            float mini = XposedHelpers.getFloatField(param.thisObject, "mMiniWindowScale");
                            if (cur * init - mini < 0.01f) {
                                return; // 已到最小(橙色): 走 stock 关闭
                            }
                            sFastFling.set(Boolean.TRUE);
                            Object r;
                            try {
                                r = XposedHelpers.callMethod(param.thisObject,
                                        "onDragPositioningToMiniwindow", Boolean.TRUE);
                            } finally {
                                sFastFling.set(null);
                            }
                            if (Boolean.TRUE.equals(r)) {
                                param.setResult(null); // 跳过关闭
                                Port.log("FreeformFx: bottom fast fling -> miniform task="
                                        + positionerTaskId(param.thisObject));
                            }
                        } catch (Throwable t) {
                            Port.log("FreeformFx: bottom fast fling redirect failed", t);
                        }
                    }
                });

        // (G6) 挂起行为对调: 快速上滑 -> 挂起 (跳过橙色校验); 慢速滑到最小(橙色)
        // 松手 -> 关闭 (走 stock onDragPositioningCancel(2), 由 sCloseGuard 放行).
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
                            boolean fast = Boolean.TRUE.equals(sFastFling.get());
                            if (!fast) {
                                float cur = XposedHelpers.getFloatField(positioner, "mCurScale");
                                float init = XposedHelpers.getFloatField(positioner, "mInitialScale");
                                float mini = XposedHelpers.getFloatField(positioner, "mMiniWindowScale");
                                if (cur * init - mini >= 0.01f) {
                                    return; // 慢速未到最小, 交回 stock (settle)
                                }
                                // 慢速滑到最小(橙色) -> 关闭
                                sCloseGuard.set(Boolean.TRUE);
                                try {
                                    XposedHelpers.callMethod(positioner,
                                            "onDragPositioningCancel", 0f, 0f, 2);
                                } finally {
                                    sCloseGuard.set(null);
                                }
                                param.setResult(Boolean.TRUE);
                                Port.log("FreeformFx: orange slow release -> close task="
                                        + positionerTaskId(positioner));
                                return;
                            }
                            Object optional = XposedHelpers.getObjectField(
                                    positioner, "mHnFreeformControllerOptional");
                            Object ctrl0 = XposedHelpers.callMethod(optional, "get");
                            if (!Boolean.TRUE.equals(
                                    XposedHelpers.callMethod(ctrl0, "isMiniWindowEnable"))) {
                                return;
                            }
                            Object miniCtrl = XposedHelpers.callMethod(ctrl0, "getMiniformController");
                            // 先快照 live bounds/scale (动效优化用); 本 hook 先于 A1b
                            // stashHook 执行且随后重置 mCtrlType=0, A1b 会跳过, 故需自行 stash
                            stashLive(positioner);
                            Object decor = XposedHelpers.getObjectField(positioner, "mWindowDecoration");
                            XposedHelpers.callMethod(decor, "setFrameThicknessColor", 1);
                            Rect curBounds = (Rect) XposedHelpers.getObjectField(
                                    positioner, "mCurrentBounds");
                            int centerX = (curBounds.left + curBounds.right) / 2;
                            Object dctx = XposedHelpers.getObjectField(decor, "mContext");
                            int dw = ((Integer) XposedHelpers.callStaticMethod(
                                    ffUtils, "getDisplayWidth", dctx)).intValue();
                            int loc = centerX < dw / 3 ? 1 : 2; // 1/3 左上, 其余右上
                            int taskId = positionerTaskId(positioner);
                            XposedHelpers.callMethod(miniCtrl, "transformToMiniWindow",
                                    8, taskId, loc,
                                    new Rect((Rect) XposedHelpers.getObjectField(
                                            positioner, "mTaskBoundsAtDragEnd")),
                                    (Bundle) null, (Point) null);
                            XposedHelpers.callMethod(XposedHelpers.getObjectField(
                                    positioner, "mTaskBoundsAtDragStart"), "setEmpty");
                            XposedHelpers.callMethod(XposedHelpers.getObjectField(
                                    positioner, "mRepositionStartPoint"), "set", 0f, 0f);
                            XposedHelpers.setIntField(positioner, "mCtrlType", 0);
                            XposedHelpers.setBooleanField(positioner, "mHasDragResized", false);
                            XposedHelpers.setBooleanField(positioner, "mIsMaxDragStart", false);
                            XposedHelpers.callMethod(XposedHelpers.callMethod(
                                    miniCtrl, "getMiniformMotionHelper"), "endProcess", taskId);
                            param.setResult(Boolean.TRUE);
                            Port.log("FreeformFx: to miniform loc=" + loc
                                    + " centerX=" + centerX + " dw=" + dw + " task=" + taskId);
                        } catch (Throwable t) {
                            Port.log("FreeformFx: to miniform override failed", t);
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
