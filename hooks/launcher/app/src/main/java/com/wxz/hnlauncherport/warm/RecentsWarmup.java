package com.wxz.hnlauncherport.warm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.wxz.hnlauncherport.Port;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * V14 应用内上滑进后台冷启动优化 (quickstep 远程动画链路, 非桌面手势).
 *
 * 冷路径 (应用内上滑初期, AbsSwipeUpHandler):
 *  onGestureStarted -> launcher Activity (冷=进程冷启动) -> onActivityInit
 *  -> notifyGestureAnimationStartToRecents -> RecentsView.onGestureAnimationStart
 *  -> showCurrentTask (池取+bind+手动 measure/layout + reloadIfNeeded)
 *  -> getTasks 异步回调落在跟手中途 -> applyLoadPlan (removeAllViews 全量重建);
 *  drawBlurDraglayer/getBlurDraglayerCache 冷缓存时 HwBlurEngine.blur 同帧全屏截图+模糊.
 *
 * 方案:
 *  1. 进程极早期预载模型 (handleLoadPackage 即起后台线程, 早于 Application.onCreate):
 *     轮询拿到 context 后主线程触发 RecentsModel.getTasks(null) 预填 RecentTasksList,
 *     冷首滑 showCurrentTask -> reloadIfNeeded 直接命中已填模型, 省掉手势期 binder 等待;
 *  2. 空闲触发点 (Launcher.onResume / AbsSwipeUpHandler.onGestureEnded 后) 仅 NORMAL 态:
 *     重截 blur-draglayer 缓存 (记时间戳) + reloadIfNeeded;
 *  3. recycleBlurDraglayerCache 保护: 缓存 20s 内视为新鲜, 跳过 recycle —
 *     手势构建期的 recycle+get 变为缓存命中;
 *  4. applyLoadPlan:
 *     a) 小差异 (新增<=2) 且保留任务相对顺序不变 -> 增量 add/remove, 保留卡不重绑;
 *     b) 手势进行中 (mGestureActive) 大量新增 -> 分帧绑定: 立即绑前 2 张 + 尾部簿记,
 *        其余每帧 (view.post) 绑 2 张, 新 applyLoadPlan 到达 (代数变化) 即中止回退 stock;
 *     c) after-hook: 逐 task 预热缩略图 (TaskThumbnailCache.updateThumbnailInCache
 *     填 task.thumbnail), 手势期 loadVisibleTaskData 命中同步快路径;
 *  5. 首手势 running task 预取: 上滑手势的 InputMonitor 事件在主线程分发
 *     (RealTouchInteractionMgr 用 Looper.getMainLooper()), 而冷启动后首手势
 *     createGestureState -> TopTaskTracker.getCachedTopTask(false) 列表为空时
 *     主线程同步 binder (mAtm.getTasks) 直接阻塞手指事件 — 进程早期/warm 兜底
 *     预填 mOrderedTaskList, 之后 TaskStackChangeListener 持续维护新鲜度;
 *     任何校验失败/异常回退 stock 全量重建 (removeAllViews 幂等, 可自愈).
 */
public final class RecentsWarmup {
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";
    private static final String UTILITIES_CLS =
            "com.hihonor.android.launcher.Utilities";
    private static final String RECENTS_VIEW_CLS =
            "com.android.quickstep.views.RecentsView";
    private static final String HN_STATE_CLS =
            "com.hihonor.android.launcher.quickstep.util.HnLauncherState";
    private static final String ABS_SWIPE_HANDLER_CLS =
            "com.android.quickstep.AbsSwipeUpHandler";
    private static final String OTHER_ACTIVITY_CONSUMER_CLS =
            "com.android.quickstep.inputconsumers.OtherActivityInputConsumer";
    private static final String RECENTS_MODEL_CLS =
            "com.android.quickstep.RecentsModel";
    private static final String TOP_TASK_TRACKER_CLS =
            "com.android.quickstep.TopTaskTracker";
    private static final String AM_WRAPPER_CLS =
            "com.android.systemui.shared.system.ActivityManagerWrapper";

    /** 缓存新鲜窗口: 窗口期内跳过 recycle, 手势构建期缓存命中. */
    private static final long CACHE_FRESH_MS = 20000;
    /** 两次 warmup 最小间隔, 防止连发触发重复预热. */
    private static final long WARM_INTERVAL_MS = 8000;
    /** 分帧绑定: 单批绑定量. */
    private static final int STAGED_BATCH = 2;
    /** 分帧绑定: 允许分帧的最大新增数. */
    private static final int STAGED_MAX_ADDS = 20;

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Object> sLauncher;
    private static volatile long sCacheWarmedAt;
    private static volatile long sLastWarmAt;
    /** applyLoadPlan 调用代数: 分帧批次发现代数变化即中止. */
    private static volatile int sLoadPlanGen;
    private static volatile boolean sEarlyPreloadDone;

    // ---- 手势输入延迟探针 ----
    /** 单个 move 事件排队阈值: 超过记一次 stall (健康批处理 ~4-20ms). */
    private static final long INPUT_LAG_WARN_MS = 32;
    /** 主线程 watchdog 帧间隔阈值. */
    private static final long FRAME_GAP_WARN_MS = 48;
    private static volatile boolean sProbeRunning;
    private static long sGestureStartAt;
    private static long sLastTickAt;
    private static int sMoveCount;
    private static long sLagSum;
    private static long sMaxLag;
    private static int sLagWarnCount;

    // ---- 主线程堆栈采样 (仅手势期间, 后台线程 10ms/次, 内存 ring, stall 时 dump) ----
    private static final int STACK_RING = 8;
    private static final int STACK_FRAMES = 14;
    private static final StackTraceElement[][] sStackRing =
            new StackTraceElement[STACK_RING][];
    private static int sStackCursor;
    private static int sStackDumpCount;

    private static final Thread sStackSampler = new Thread(new Runnable() {
        @Override
        public void run() {
            Thread main = Looper.getMainLooper().getThread();
            while (true) {
                if (sProbeRunning) {
                    StackTraceElement[] st = main.getStackTrace();
                    if (st != null && st.length > 0) {
                        synchronized (sStackRing) {
                            sStackRing[sStackCursor % STACK_RING] = st;
                            sStackCursor++;
                        }
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }, "HnPortStackSampler");

    private static final Runnable sWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!sProbeRunning) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            long gap = now - sLastTickAt;
            sLastTickAt = now;
            if (gap > FRAME_GAP_WARN_MS && sStackDumpCount < 3) {
                sStackDumpCount++;
                dumpMainStack(gap);
            }
            sMain.postDelayed(this, 16);
        }
    };

    /** dump stall 窗口内采到的主线程堆栈 (最近 3 个样本, 各截前 N 帧). */
    private static void dumpMainStack(long stallMs) {
        Port.log("GestureProbe: main-thread stall " + stallMs + "ms, recent stacks:");
        StackTraceElement[][] latest = new StackTraceElement[3][];
        synchronized (sStackRing) {
            for (int k = 0; k < 3; k++) {
                int idx = (sStackCursor - 1 - k) % STACK_RING;
                if (idx < 0) {
                    idx += STACK_RING;
                }
                latest[k] = sStackRing[idx];
            }
        }
        for (int k = 0; k < latest.length; k++) {
            StackTraceElement[] st = latest[k];
            if (st == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder("GestureProbe: sample[-" + (k + 1) + "]");
            int n = Math.min(STACK_FRAMES, st.length);
            for (int i = 0; i < n; i++) {
                sb.append("\n    at ").append(st[i].getClassName())
                        .append('.').append(st[i].getMethodName())
                        .append('(').append(st[i].getFileName())
                        .append(':').append(st[i].getLineNumber()).append(')');
            }
            Port.log(sb.toString());
        }
    }

    /**
     * 双名 hook: jadx 反混淆名与 dex 真实名 (常为 n) 依次尝试, 命中其一即返回.
     * 全部失败仅记日志不抛出 (探针/warm 属增强, 不影响宿主).
     */
    private static void hookDualName(Class<?> cls, String[] names, Class<?>[] params,
            XC_MethodHook hook) {
        Throwable last = null;
        for (String name : names) {
            try {
                Object[] args = new Object[params.length + 1];
                System.arraycopy(params, 0, args, 0, params.length);
                args[params.length] = hook;
                XposedHelpers.findAndHookMethod(cls, name, args);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        Port.log("RecentsWarmup: hook " + names[0] + " skipped", last);
    }

    private static void startGestureProbe() {
        if (sProbeRunning) {
            return;
        }
        sProbeRunning = true;
        sGestureStartAt = SystemClock.uptimeMillis();
        sLastTickAt = sGestureStartAt;
        sMoveCount = 0;
        sLagSum = 0;
        sMaxLag = 0;
        sLagWarnCount = 0;
        sStackDumpCount = 0;
        sMain.removeCallbacks(sWatchdog);
        sMain.postDelayed(sWatchdog, 16);
        if (!sStackSampler.isAlive()) {
            sStackSampler.setDaemon(true);
            sStackSampler.setPriority(Thread.NORM_PRIORITY - 1);
            sStackSampler.start();
        }
        Port.log("GestureProbe: gesture start");
    }

    private static void onGestureMove(android.view.MotionEvent ev) {
        if (!sProbeRunning || !Port.on(Port.KEY_RECENTS_WARMUP)) {
            return;
        }
        if (ev.getActionMasked() != android.view.MotionEvent.ACTION_MOVE) {
            return;
        }
        // eventTime 与 uptimeMillis 同基准: 差值 = 事件在主线程队列里的等待时长
        long lag = SystemClock.uptimeMillis() - ev.getEventTime();
        sMoveCount++;
        sLagSum += lag;
        if (lag > sMaxLag) {
            sMaxLag = lag;
        }
        if (lag > INPUT_LAG_WARN_MS) {
            sLagWarnCount++; // 不逐条打日志, 避免日志本身加重排队, 汇总在 gesture end
        }
    }

    private static void stopGestureProbe() {
        if (!sProbeRunning) {
            return;
        }
        sProbeRunning = false;
        sMain.removeCallbacks(sWatchdog);
        long dur = SystemClock.uptimeMillis() - sGestureStartAt;
        long avg = sMoveCount > 0 ? sLagSum / sMoveCount : 0;
        Port.log("GestureProbe: gesture end, dur=" + dur + "ms moves=" + sMoveCount
                + " avgLag=" + avg + "ms maxLag=" + sMaxLag + "ms lagWarn=" + sLagWarnCount
                + (sLagWarnCount == 0 && sMaxLag < INPUT_LAG_WARN_MS
                        ? " -> main thread healthy, lag is pipeline/curve inherent"
                        : " -> main thread busy, events were queued"));
    }

    public static void install(final ClassLoader cl) {
        final Class<?> launcherCls = XposedHelpers.findClass(LAUNCHER_CLS, cl);
        final Class<?> utilCls = XposedHelpers.findClass(UTILITIES_CLS, cl);
        final Class<?> rvCls = XposedHelpers.findClass(RECENTS_VIEW_CLS, cl);
        final Class<?> stateCls = XposedHelpers.findClass(HN_STATE_CLS, cl);
        final Class<?> modelCls = XposedHelpers.findClass(RECENTS_MODEL_CLS, cl);

        // 1. 进程极早期模型预载 (早于 Application.onCreate)
        startEarlyModelPreload(modelCls);

        // 2. Launcher.onResume -> 延迟预热
        XposedHelpers.findAndHookMethod(launcherCls, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sLauncher = new WeakReference<Object>(param.thisObject);
                scheduleWarm(1200);
            }
        });

        // 3. 应用内上滑手势结束 -> 重新预热 (下一个手势前刷新列表/缓存)
        //    注意: 本构建 dex 中 onGestureStarted/onGestureEnded 真名为 n (重载),
        //    jadx 显示的是反混淆名, hook 必须双名兼容.
        try {
            Class<?> handlerCls = XposedHelpers.findClass(ABS_SWIPE_HANDLER_CLS, cl);
            XC_MethodHook endHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    stopGestureProbe();
                    scheduleWarm(1200);
                }
            };
            hookDualName(handlerCls, new String[]{"onGestureEnded", "n"},
                    new Class[]{float.class, android.graphics.PointF.class}, endHook);
            // 6. 手势输入延迟探针: 区分 "主线程忙导致事件排队" vs "管线固有延迟"
            //    白圈 (SystemUI 输入层) vs 窗口轨迹 (launcher 主线程驱动) 跟手差的量化归因
            XC_MethodHook startHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    startGestureProbe();
                }
            };
            hookDualName(handlerCls, new String[]{"onGestureStarted", "n"},
                    new Class[]{boolean.class}, startHook);
            Class<?> otherConsumerCls = XposedHelpers.findClass(OTHER_ACTIVITY_CONSUMER_CLS, cl);
            XC_MethodHook moveHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    onGestureMove((android.view.MotionEvent) param.args[0]);
                }
            };
            hookDualName(otherConsumerCls, new String[]{"onMotionEvent", "n"},
                    new Class[]{android.view.MotionEvent.class}, moveHook);
        } catch (Throwable t) {
            Port.log("RecentsWarmup: gesture probe hook skipped", t);
        }

        // 4. 模糊缓存保护: 新鲜缓存不 recycle, 构建期 get 直接命中
        XposedHelpers.findAndHookMethod(utilCls, "recycleBlurDraglayerCache", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_RECENTS_WARMUP)) {
                    return;
                }
                long t = sCacheWarmedAt;
                if (t != 0) {
                    long age = SystemClock.uptimeMillis() - t;
                    if (age >= 0 && age < CACHE_FRESH_MS) {
                        param.setResult(null); // 跳过 recycle, 保留新鲜缓存
                    }
                }
            }
        });

        // 5. applyLoadPlan: before = 增量/分帧, after = 缩略图预热
        XposedHelpers.findAndHookMethod(rvCls, "applyLoadPlan", ArrayList.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                sLoadPlanGen++;
                if (!Port.on(Port.KEY_RECENTS_WARMUP)) {
                    return;
                }
                ArrayList<?> list = (ArrayList<?>) param.args[0];
                if (list == null || list.isEmpty()) {
                    return; // 空列表: stock 清空路径
                }
                View rv = (View) param.thisObject;
                if (XposedHelpers.getObjectField(rv, "mPendingAnimation") != null) {
                    return; // 挂起动画: stock 延迟路径
                }
                if (XposedHelpers.getObjectField(rv, "mDesktopTaskView") != null) {
                    return; // 桌面任务在场: 结构复杂, stock
                }
                try {
                    if (applyIncremental(rv, list, modelCls)) {
                        param.setResult(null); // 跳过 stock 全量重建
                    }
                } catch (Throwable t) {
                    Port.log("RecentsWarmup incremental failed, fallback to stock", t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_RECENTS_WARMUP)) {
                    return;
                }
                try {
                    prewarmThumbnails((View) param.thisObject, modelCls);
                } catch (Throwable ignored) {
                }
            }
        });

        Port.log("RecentsWarmup installed");
    }

    // ------------------------------------------------------------------
    // 进程极早期模型预载 (P0-2)
    // ------------------------------------------------------------------

    private static void startEarlyModelPreload(final Class<?> modelCls) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Context ctx = null;
                for (int i = 0; i < 240; i++) { // 最多等 60s
                    if (sEarlyPreloadDone) {
                        return;
                    }
                    ctx = Port.appContext();
                    if (ctx != null) {
                        break;
                    }
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (ctx == null || sEarlyPreloadDone) {
                    return;
                }
                sEarlyPreloadDone = true;
                final Context appCtx = ctx;
                sMain.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!Port.on(Port.KEY_RECENTS_WARMUP)) {
                                return;
                            }
                            android.os.UserManager um =
                                    (android.os.UserManager) appCtx.getSystemService(
                                            android.os.UserManager.class);
                            if (um != null && !um.isUserUnlocked()) {
                                return;
                            }
                            Object model = getModel(modelCls, appCtx);
                            if (model == null) {
                                return;
                            }
                            // consumer=null: 只预填 RecentTasksList 缓存
                            XposedHelpers.callMethod(model, "getTasks", new Object[]{null});
                            Port.log("RecentsWarmup: early model preload issued");
                        } catch (Throwable t) {
                            Port.log("RecentsWarmup: early model preload failed", t);
                        }
                        // 首手势 running task 预取: 填 TopTaskTracker.mOrderedTaskList,
                        // createGestureState -> getCachedTopTask(false) 免主线程同步 IPC
                        prefetchTopTasks(appCtx);
                    }
                });
            }
        }, "HnPortEarlyPreload");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** RecentsModel.INSTANCE.get(ctx) — 本构建中方法名被混淆为 lambda$get$1, 双名兼容. */
    private static Object getModel(Class<?> modelCls, Context ctx) {
        try {
            Object instance = XposedHelpers.getStaticObjectField(modelCls, "INSTANCE");
            try {
                return XposedHelpers.callMethod(instance, "get", ctx);
            } catch (Throwable ignored) {
                return XposedHelpers.callMethod(instance, "lambda$get$1", ctx);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 预取 running task 填充 TopTaskTracker.mOrderedTaskList (主线程).
     *
     * 首手势 createGestureState -> getCachedTopTask(false) 发现列表为空时会在主线程
     * 同步 binder (mAtm.getTasks) — 而上滑手势的 InputMonitor 事件恰在主线程分发,
     * 该 IPC 直接加重跟手初期卡顿. 进程早期把列表填好, 之后 TaskStackChangeListener
     * 的 onTaskMovedToFront/onTaskRemoved 回调会持续维护其新鲜度, 首手势即缓存命中.
     */
    private static void prefetchTopTasks(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> tttCls = XposedHelpers.findClass(TOP_TASK_TRACKER_CLS, cl);
            Object holder = XposedHelpers.getStaticObjectField(tttCls, "INSTANCE");
            Object tracker;
            try {
                tracker = XposedHelpers.callMethod(holder, "get", ctx);
            } catch (Throwable ignored) {
                tracker = XposedHelpers.callMethod(holder, "lambda$get$1", ctx);
            }
            if (tracker == null) {
                return;
            }
            // 已有数据: listener 一直在维护, 不动
            LinkedList<?> list = (LinkedList<?>)
                    XposedHelpers.getObjectField(tracker, "mOrderedTaskList");
            if (list == null || !list.isEmpty()) {
                return;
            }
            Class<?> amwCls = XposedHelpers.findClass(AM_WRAPPER_CLS, cl);
            Object amw = XposedHelpers.callStaticMethod(amwCls, "getInstance");
            // 与 stock getCachedTopTask(false) 的回填完全同序 (getTasks top-first)
            Object[] tasks = (Object[]) XposedHelpers.callMethod(amw, "getRunningTasks", false);
            if (tasks == null || tasks.length == 0) {
                return;
            }
            java.util.Collections.addAll((java.util.Collection<Object>) list, tasks);
            // 对齐 stock: 顶部为自由窗口时做一次校正 (此时 IPC 无手势压力)
            java.lang.reflect.Method upd = tttCls.getDeclaredMethod("updateTopTask");
            upd.setAccessible(true);
            upd.invoke(tracker);
            Port.log("RecentsWarmup: top task prefetch, n=" + tasks.length);
        } catch (Throwable t) {
            Port.log("RecentsWarmup: top task prefetch failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 空闲预热
    // ------------------------------------------------------------------

    private static void scheduleWarm(long delay) {
        if (!Port.on(Port.KEY_RECENTS_WARMUP)) {
            return;
        }
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                warm();
            }
        }, delay);
    }

    private static void warm() {
        if (!Port.on(Port.KEY_RECENTS_WARMUP)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastWarmAt < WARM_INTERVAL_MS) {
            return;
        }
        WeakReference<Object> ref = sLauncher;
        Object launcher = ref == null ? null : ref.get();
        if (launcher == null) {
            return;
        }
        try {
            // 仅 NORMAL 态 (桌面空闲) 预热
            Object state = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(launcher, "getStateManager"), "getState");
            Object normal = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(HN_STATE_CLS, launcher.getClass().getClassLoader()),
                    "NORMAL");
            if (state != normal) {
                return;
            }
            sLastWarmAt = now;
            long t0 = SystemClock.uptimeMillis();

            // 0. 兜底: running task 预取 (早期预载被锁屏跳过时在此补)
            // Launcher 本身即 Context, 不走 getContext (该构建中方法名不可用)
            prefetchTopTasks((Context) launcher);

            // 1. 重新截取模糊缓存 (先清时间戳让 recycle 放行, 再强制重截)
            sCacheWarmedAt = 0;
            Class<?> utilCls = XposedHelpers.findClass(UTILITIES_CLS,
                    launcher.getClass().getClassLoader());
            XposedHelpers.callStaticMethod(utilCls, "recycleBlurDraglayerCache");
            XposedHelpers.callStaticMethod(utilCls, "getBlurDraglayerCache", launcher);
            sCacheWarmedAt = SystemClock.uptimeMillis();

            // 2. 任务列表: getTasks + applyLoadPlan 落在空闲帧
            Object panel = XposedHelpers.callMethod(launcher, "getRecentOverviewPanel");
            if (panel == null) {
                return;
            }
            logPool(panel, "mTaskViewPool");
            logPool(panel, "mGroupedTaskViewPool");
            XposedHelpers.callMethod(panel, "reloadIfNeeded");
            Port.log("RecentsWarmup: warmed in "
                    + (SystemClock.uptimeMillis() - t0) + "ms (reloadIfNeeded issued)");
        } catch (Throwable t) {
            Port.log("RecentsWarmup warm failed", t);
        }
    }

    private static void logPool(Object panel, String field) {
        try {
            Object pool = XposedHelpers.getObjectField(panel, field);
            if (pool != null) {
                Port.log("RecentsWarmup: " + field + " size="
                        + XposedHelpers.getIntField(pool, "mCurrentSize"));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // applyLoadPlan 增量 / 分帧
    // ------------------------------------------------------------------

    private static final class TvInfo {
        Object view;
        int[] ids;
        int newIdx = -1; // 新列表中的位置, -1 = 被移除
    }

    private static boolean applyIncremental(View rv, ArrayList<?> list, Class<?> modelCls)
            throws Exception {
        int oldCount = (int) XposedHelpers.callMethod(rv, "getTaskViewCount");
        if (oldCount == 0) {
            return false; // 首建: stock
        }

        // ---- 校验阶段 (任何失败必须在结构改动之前 return false) ----
        ArrayList<TvInfo> olds = new ArrayList<>();
        for (int i = 0; i < oldCount; i++) {
            Object tv = XposedHelpers.callMethod(rv, "getTaskViewAt", i);
            if (tv == null) {
                return false;
            }
            if (tv.getClass().getSimpleName().contains("Desktop")) {
                return false;
            }
            int[] ids = (int[]) XposedHelpers.callMethod(tv, "getTaskIds");
            if (ids == null || ids.length == 0) {
                return false;
            }
            TvInfo info = new TvInfo();
            info.view = tv;
            info.ids = ids;
            olds.add(info);
        }

        ArrayList<Object> addGts = new ArrayList<>();
        ArrayList<Integer> addJs = new ArrayList<>();
        int n = list.size();
        for (int j = 0; j < n; j++) {
            Object gt = list.get(j);
            if (gt == null
                    || "DesktopTask".equals(gt.getClass().getSimpleName())) {
                return false;
            }
            int type = XposedHelpers.getIntField(gt, "taskViewType");
            if (type == 3) {
                return false;
            }
            Object t1 = XposedHelpers.getObjectField(gt, "task1");
            if (t1 == null) {
                return false;
            }
            int id1 = taskId(t1);
            Object t2 = XposedHelpers.getObjectField(gt, "task2");
            boolean grouped = t2 != null;
            int id2 = grouped ? taskId(t2) : -1;
            if (grouped) {
                Object sb = XposedHelpers.getObjectField(gt, "mSplitBounds");
                if (sb == null) {
                    return false; // stock 此处也依赖 splitBounds
                }
            }
            TvInfo match = null;
            for (TvInfo o : olds) {
                if (o.newIdx == -1 && o.ids[0] == id1
                        && (grouped ? (o.ids.length == 2 && o.ids[1] == id2)
                                    : o.ids.length == 1)) {
                    match = o;
                    break;
                }
            }
            if (match != null) {
                match.newIdx = j;
            } else {
                addGts.add(gt);
                addJs.add(Integer.valueOf(j));
            }
        }

        int removed = 0;
        for (TvInfo o : olds) {
            if (o.newIdx == -1) {
                removed++;
            }
        }
        if (addGts.isEmpty() && removed == 0) {
            Port.log("RecentsWarmup: applyLoadPlan no-change, skip stock rebuild");
            return true;
        }

        // 分帧模式: 手势进行中 + 大量新增 (冷启动首滑: stub 卡 + N-1 新增)
        boolean gestureActive = false;
        try {
            gestureActive = XposedHelpers.getBooleanField(rv, "mGestureActive");
        } catch (Throwable ignored) {
        }
        boolean staged = addGts.size() > 2
                && addGts.size() <= STAGED_MAX_ADDS
                && gestureActive
                && removed <= 4;
        if (!staged && (addGts.size() > 2 || removed > 4)) {
            return false; // 大差异且非分帧条件: stock
        }

        // 保留任务相对顺序检查: child 顺序中 newIdx 必须单调不减
        int lastNew = -1;
        for (TvInfo o : olds) {
            if (o.newIdx != -1) {
                if (o.newIdx < lastNew) {
                    return false;
                }
                lastNew = o.newIdx;
            }
        }

        // ---- 变更阶段 ----
        int keptCount = olds.size() - removed;
        int curPage = XposedHelpers.getIntField(rv, "mCurrentPage");
        int[] curIds = null;
        try {
            Object curTv = XposedHelpers.callMethod(rv, "getTaskViewAt", curPage);
            if (curTv != null) {
                curIds = (int[]) XposedHelpers.callMethod(curTv, "getTaskIds");
            }
        } catch (Throwable ignored) {
        }

        for (TvInfo o : olds) {
            if (o.newIdx == -1) {
                XposedHelpers.callMethod(rv, "removeView", o.view);
            }
        }

        Object orientation = XposedHelpers.getObjectField(rv, "mOrientationState");
        int immediate = staged ? STAGED_BATCH : addGts.size();
        if (immediate > addGts.size()) {
            immediate = addGts.size();
        }
        for (int k = 0; k < immediate; k++) {
            addOne(rv, olds, addGts.get(k), addJs.get(k).intValue(), orientation, modelCls);
        }

        // ---- 簿记 (立即部分) ----
        XposedHelpers.setBooleanField(rv, "mLoadPlanEverApplied", true);
        Object filterState = XposedHelpers.getObjectField(rv, "mFilterState");
        XposedHelpers.callMethod(filterState, "updateInstanceCountMap", list);

        if (curIds != null) {
            Object v = XposedHelpers.callMethod(rv, "getTaskViewByTaskIds", curIds);
            if (v != null) {
                int ni = (int) XposedHelpers.callMethod(rv, "indexOfChild", v);
                if (ni != curPage) {
                    XposedHelpers.callMethod(rv, "setCurrentPage", ni);
                }
            } else {
                int c = (int) XposedHelpers.callMethod(rv, "getTaskViewCount");
                if (c > 0) {
                    Object v0 = XposedHelpers.callMethod(rv, "getTaskViewAt", 0);
                    if (v0 != null) {
                        XposedHelpers.callMethod(rv, "setCurrentPage",
                                (int) XposedHelpers.callMethod(rv, "indexOfChild", v0));
                    }
                }
            }
        }

        String[] idFields = {"mRunningTaskViewId", "mFocusedTaskViewId", "mIgnoreResetTaskId"};
        for (String name : idFields) {
            int id = XposedHelpers.getIntField(rv, name);
            if (id != -1
                    && XposedHelpers.callMethod(rv, "getTaskViewByTaskId", id) == null) {
                XposedHelpers.setIntField(rv, name, -1);
            }
        }

        XposedHelpers.callMethod(rv, "updateTaskSize");
        XposedHelpers.callMethod(rv, "updateChildTaskOrientations");

        if (staged) {
            // ---- 分帧: 其余卡每帧绑 2 张, 代数变化即中止 ----
            final int gen = sLoadPlanGen;
            final int totalAdds = addGts.size();
            final int immediateCount = immediate;
            final View frv = rv;
            final ArrayList<TvInfo> fonds = olds;
            final ArrayList<Object> restGts = new ArrayList<>(addGts.subList(immediate, addGts.size()));
            final ArrayList<Integer> restJs = new ArrayList<>(addJs.subList(immediate, addJs.size()));
            final Object forient = orientation;
            final Class<?> fModelCls = modelCls;
            final int[] cursor = new int[1];
            frv.post(new Runnable() {
                @Override
                public void run() {
                    if (gen != sLoadPlanGen || !frv.isAttachedToWindow()) {
                        return; // 新 applyLoadPlan 已接管 (stock/增量), 中止分帧
                    }
                    try {
                        int batch = Math.min(STAGED_BATCH, restGts.size() - cursor[0]);
                        for (int k = 0; k < batch; k++) {
                            int idx = cursor[0] + k;
                            addOne(frv, fonds, restGts.get(idx), restJs.get(idx).intValue(),
                                    forient, fModelCls);
                        }
                        cursor[0] += batch;
                        XposedHelpers.callMethod(frv, "updateTaskSize");
                        XposedHelpers.callMethod(frv, "updateChildTaskOrientations");
                        if (cursor[0] >= restGts.size()) {
                            // 末批: 完整尾部簿记 (对齐 stock)
                            XposedHelpers.callMethod(frv, "resetTaskVisuals");
                            XposedHelpers.callMethod(frv, "onTaskStackUpdated");
                            XposedHelpers.callMethod(frv, "updateEnabledOverlays");
                            if ((boolean) XposedHelpers.callMethod(frv, "isPageScrollsInitialized")) {
                                XposedHelpers.callMethod(frv, "onPageScrollsInitialized");
                            }
                            Port.log("RecentsWarmup: staged bind done, total adds="
                                    + totalAdds);
                        } else {
                            frv.post(this);
                        }
                    } catch (Throwable t) {
                        Port.log("RecentsWarmup: staged batch failed", t);
                    }
                }
            });
            Port.log("RecentsWarmup: applyLoadPlan staged, immediate=" + immediate
                    + " staged=" + restGts.size() + " rm=" + removed + " kept=" + keptCount);
            return true;
        }

        // ---- 非分帧: 完整尾部簿记 (对齐 stock) ----
        XposedHelpers.callMethod(rv, "resetTaskVisuals");
        XposedHelpers.callMethod(rv, "onTaskStackUpdated");
        XposedHelpers.callMethod(rv, "updateEnabledOverlays");
        if ((boolean) XposedHelpers.callMethod(rv, "isPageScrollsInitialized")) {
            XposedHelpers.callMethod(rv, "onPageScrollsInitialized");
        }

        Port.log("RecentsWarmup: applyLoadPlan incremental, add=" + addGts.size()
                + " rm=" + removed + " kept=" + keptCount + " total=" + list.size());
        return true;
    }

    /** 取池卡 + bind + 按 kept 邻居插入 + 注册 + 缩略图预热. */
    private static void addOne(View rv, ArrayList<TvInfo> olds, Object gt, int j,
            Object orientation, Class<?> modelCls) throws Exception {
        int type = XposedHelpers.getIntField(gt, "taskViewType");
        Object tvNew = XposedHelpers.callMethod(rv, "getTaskViewFromPool", type);
        if (type == 2) {
            Object sb = XposedHelpers.getObjectField(gt, "mSplitBounds");
            int leftTopId = XposedHelpers.getIntField(sb, "leftTopTaskId");
            Object t1 = XposedHelpers.getObjectField(gt, "task1");
            Object t2 = XposedHelpers.getObjectField(gt, "task2");
            Object first = leftTopId == taskId(t1) ? t1 : t2;
            Object second = leftTopId == taskId(t1) ? t2 : t1;
            XposedHelpers.callMethod(tvNew, "bind", first, second, orientation, sb);
        } else {
            XposedHelpers.callMethod(tvNew, "bind",
                    XposedHelpers.getObjectField(gt, "task1"), orientation);
        }
        int insertIdx = computeInsertIndex(rv, olds, j);
        XposedHelpers.callMethod(rv, "addView", tvNew, insertIdx);
        TvInfo info = new TvInfo();
        info.view = tvNew;
        info.ids = (int[]) XposedHelpers.callMethod(tvNew, "getTaskIds");
        info.newIdx = j;
        olds.add(info);
        prewarmTaskThumbnail(rv, tvNew, modelCls);
    }

    /** 新卡插入位置: kept 中 newIdx > j 的最小者之前; 否则 clear-all 按钮之前; 否则末尾. */
    private static int computeInsertIndex(View rv, ArrayList<TvInfo> olds, int j)
            throws Exception {
        Object bestView = null;
        int bestJ = Integer.MAX_VALUE;
        for (TvInfo o : olds) {
            if (o.newIdx != -1 && o.newIdx > j && o.newIdx < bestJ) {
                bestJ = o.newIdx;
                bestView = o.view;
            }
        }
        if (bestView != null) {
            return (int) XposedHelpers.callMethod(rv, "indexOfChild", bestView);
        }
        Object clearAll = XposedHelpers.getObjectField(rv, "mClearAllButton");
        if (clearAll != null) {
            int idx = (int) XposedHelpers.callMethod(rv, "indexOfChild", clearAll);
            if (idx != -1) {
                return idx;
            }
        }
        return (int) XposedHelpers.callMethod(rv, "getChildCount");
    }

    // ------------------------------------------------------------------
    // 缩略图预热 (after-hook / 分帧批内)
    // ------------------------------------------------------------------

    private static void prewarmThumbnails(View rv, Class<?> modelCls) throws Exception {
        int count = (int) XposedHelpers.callMethod(rv, "getTaskViewCount");
        for (int i = 0; i < count; i++) {
            Object tv = XposedHelpers.callMethod(rv, "getTaskViewAt", i);
            if (tv != null) {
                prewarmTaskThumbnail(rv, tv, modelCls);
            }
        }
    }

    private static void prewarmTaskThumbnail(View rv, Object tv, Class<?> modelCls) {
        try {
            Object task = XposedHelpers.callMethod(tv, "getTask");
            if (task == null) {
                return;
            }
            Object model = getModel(modelCls, (Context) XposedHelpers.callMethod(rv, "getContext"));
            if (model == null) {
                return;
            }
            Object thumbCache = XposedHelpers.callMethod(model, "getThumbnailCache");
            // updateThumbnailInCache: task.thumbnail 为空时后台加载并回填,
            // 手势期 updateThumbnailInBackground 命中同步快路径
            XposedHelpers.callMethod(thumbCache, "updateThumbnailInCache", task);
        } catch (Throwable ignored) {
        }
    }

    private static int taskId(Object task) throws Exception {
        Object key = XposedHelpers.callMethod(task, "getKey");
        return XposedHelpers.getIntField(key, "id");
    }

    private RecentsWarmup() {}
}
