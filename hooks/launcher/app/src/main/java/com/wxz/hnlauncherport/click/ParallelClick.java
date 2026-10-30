package com.wxz.hnlauncherport.click;

import android.content.Intent;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 连续切换应用 (sequential app switch), V17 InterruptAnimationV2 feel.
 *
 * Semantics: clicking a new app icon while a return-home animation runs must
 * IMMEDIATELY stop the previous app's animation and start the new app.
 *
 * Native V14 already routes different-app clicks during a running return anim
 * onto the quick-end path (HnLauncher.isNeedQuickEnd :764-776 returns true for
 * a different package while isReturnHomeAnimRunning). The quick-end path
 * (quickEndReturnHomeAnim :403) fast-forwards the remaining return anim over
 * up to sQuickEndMaxDuration(100)ms, then ends it and later starts the new
 * activity via runPendingStartActivity. This ~100ms delay is what makes
 * switching feel laggy versus V17.
 *
 * This feature makes the switch instant by intercepting quickEndReturnHomeAnim:
 *   1) hard-end the return anim FIRST via endReturnHomeAnim() (the proven
 *      native pattern at HnLauncher:595-596 - end then start);
 *   2) then run the pending-start runnable immediately;
 *   3) skip the native <=100ms fast-forward machinery (setResult).
 *
 * We do NOT force isNeedQuickEnd - native already returns true for the
 * different-app case, and forcing it would bypass component-null/multi-window
 * guards and crash.
 *
 * canBreak merge (摘要3 §A): the cold-start gap (click issued but
 * RemoteAnimationTarget not yet returned) has canBreak()==false, which makes
 * the bottom swipe-up "do nothing" during first app open. When this feature is
 * on, canBreak() is forced true so the break/gesture path engages even during
 * the gap.
 */
public final class ParallelClick {
    private static final String HN_LAUNCHER = "com.hihonor.android.launcher.HnLauncher";
    private static final String GAC = "com.hihonor.android.launcher.quickstep.breakanim.GestureAnimationController";
    private static final String ALA = "com.hihonor.android.launcher.quickstep.anim.AppLaunchAnimation";

    public static void install(ClassLoader cl) {
        try {
            Class<?> gac = XposedHelpers.findClass(GAC, cl);

            // Immediate stop of the previous app + immediate start of the new
            // one: end the old anim first (proven native pattern), then run
            // the pending runnable, and skip the native fast-forward.
            XposedHelpers.findAndHookMethod(gac, "quickEndReturnHomeAnim", Runnable.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_PARALLEL)) {
                                return;
                            }
                            boolean running = running(param.thisObject);
                            Port.log("SeqSwitchQuickEnd running=" + running);
                            if (!running) {
                                return;
                            }
                            // 1) end old anim first (native pattern: end then start)
                            try {
                                XposedHelpers.callMethod(param.thisObject, "endReturnHomeAnim");
                            } catch (Throwable t) {
                                Port.log("SeqSwitch endReturnHomeAnim failed", t);
                            }
                            // 2) then start the new activity immediately
                            Object r = param.args[0];
                            if (r instanceof Runnable) {
                                try {
                                    ((Runnable) r).run();
                                } catch (Throwable t) {
                                    Port.log("SeqSwitch pending-start failed", t);
                                }
                            }
                            // 3) skip the native <=100ms fast-forward
                            param.setResult(null);
                        }
                    });

            // canBreak merge: force true during cold-start gap so first-open
            // bottom swipe-up engages the break path (摘要3 §A).
            //
            // v2 (freeze fix): stock canBreak() returns false when no launch
            // anim exists (!mIsRecentStartInReverse). Forcing true there broke
            // the swipe-up-into-OneStep-hot-region drag: the false return is
            // what keeps HnLauncherSwipeHandlerV2:1587 from building a recent
            // transition animation (RT takeover) that fights OneStepWindowMgr
            // for rendering authority -> window rect freezes mid-gesture.
            // Only force true while an app launch is genuinely in flight
            // (mIsActivityStart: set at Launcher start, cleared when the
            // launch transition ends) - that covers the §A cold-start gap
            // (target not yet arrived) and the early-phase anim, and leaves
            // plain return-home / OneStep drags on the stock path.
            XposedHelpers.findAndHookMethod(gac, "canBreak", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_PARALLEL)) {
                                return;
                            }
                            if (!launchInFlight(param.thisObject)) {
                                return;
                            }
                            // 初次启动 gap / 启动动画早期: stock canBreak()==false 会让
                            // 上滑打断判定失败; 强制 true 使打断路径生效.
                            Port.log("SeqSwitch canBreak force-true (launch in flight)");
                            param.setResult(Boolean.TRUE);
                        }
                    });
            // 启动动画图标/窗口 alpha 端点强制 0.03/0.06 (非 MAGIC5 默认):
            // canBreak (:391) 以 progress > sIconAnimationAlphaEndPoint 为可打断
            // 判据, MAGIC5/云端配置把端点抬到 0.1/0.2 会让可打断窗推迟 3 倍进度;
            // 归属本开关, 关闭时保持 stock (launcher 重启后还原).
            Class<?> ala = XposedHelpers.findClass(ALA, cl);
            if (Port.on(Port.KEY_PARALLEL)) {
                forceAlphaEndpoints(ala);
            }
            // 云端参数 (updateAppLaunchAnimPara :1136) 会覆盖端点, 之后重压.
            XposedHelpers.findAndHookMethod(ala, "updateAppLaunchAnimPara", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_PARALLEL)) {
                                return;
                            }
                            forceAlphaEndpoints(ala);
                        }
                    });
            // 图标 GONE 边缘点 0.08: 淡化窗 (0.03-0.06) 结束后图标尽快隐藏,
            // 不再等 stock 的 0.2/0.3 (applyToIconView :543 GONE 判定).
            XposedHelpers.findAndHookMethod(ala, "getIconAnimationAlphaEdgePoint", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_PARALLEL)) {
                                return;
                            }
                            try {
                                java.lang.reflect.Field f = ala.getDeclaredField("mIconAnimationAlphaEdgePoint");
                                f.setAccessible(true);
                                f.setFloat(param.thisObject, 0.08f);
                            } catch (Throwable t) {
                                Port.log("SeqSwitch icon-gone force failed", t);
                            }
                        }
                    });
            // shouldContinueAppLaunch 阈值 0.1 -> 0.01 (GAC :44, 仅静态初始化
            // 一处写点, 无云端覆盖): 启动动画期间上滑不再"接管继续播", 尽早
            // 走 break 路径, 与 canBreak 端点 0.06 的"尽早可打断"语义对齐.
            if (Port.on(Port.KEY_PARALLEL)) {
                try {
                    java.lang.reflect.Field f = gac.getDeclaredField("SHOULD_CONTINUE_APP_LAUNCH_ANIM");
                    f.setAccessible(true);
                    f.setFloat(null, 0.0f);
                    Port.log("SeqSwitch should-continue threshold forced 0.01");
                } catch (Throwable t) {
                    Port.log("SeqSwitch should-continue force failed", t);
                }
            }
            Port.log("ParallelClick installed (seq-switch + canBreak)");
        } catch (Throwable t) {
            Port.log("ParallelClick install failed", t);
        }
    }

    private static boolean running(Object controller) {
        if (controller == null) {
            return false;
        }
        try {
            Object r = XposedHelpers.callMethod(controller, "isReturnHomeAnimRunning");
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True while an app launch is in flight (GAC.mIsActivityStart).
     *
     * 优先直读字段: jadx 显示的字段名即 dex 真名; 而 getter/setter 方法名在本
     * 构建中被混淆为 n (调用方 QuickstepAppTransitionManagerImpl 显示 .n(false)),
     * 反编译声明处显示的 getIsActivityStart 不可信 — callMethod 按名反射,
     * 名字对不上会静默 NoSuchMethodError -> 永远 false -> canBreak 强制失效.
     * 字段读取失败再按双名兜底 (getIsActivityStart / n).
     */
    private static boolean launchInFlight(Object controller) {
        if (controller == null) {
            return false;
        }
        try {
            return XposedHelpers.getBooleanField(controller, "mIsActivityStart");
        } catch (Throwable ignored) {
        }
        try {
            return Boolean.TRUE.equals(
                    XposedHelpers.callMethod(controller, "getIsActivityStart"));
        } catch (Throwable ignored) {
        }
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "n"));
        } catch (Throwable t) {
            Port.log("SeqSwitch launchInFlight probe failed", t);
            return false;
        }
    }

    /**
     * 启动动画 alpha 端点强制 0.03/0.06 (图标淡出 + 窗口淡入同窗):
     * sIconAnimationAlphaStartPoint/EndPoint + sWindowAnimationAlphaStartPoint/
     * EndPoint (AppLaunchAnimation static, 静态块 :165-171 与云端覆盖 :1162-1169).
     */
    private static void forceAlphaEndpoints(Class<?> ala) {
        try {
            setStaticFloat(ala, "sIconAnimationAlphaStartPoint", 0.03f);
            setStaticFloat(ala, "sIconAnimationAlphaEndPoint", 0.06f);
            setStaticFloat(ala, "sWindowAnimationAlphaStartPoint", 0.03f);
            setStaticFloat(ala, "sWindowAnimationAlphaEndPoint", 0.06f);
            Port.log("SeqSwitch alpha endpoints forced 0.03/0.06");
        } catch (Throwable t) {
            Port.log("SeqSwitch alpha force failed", t);
        }
    }

    private static void setStaticFloat(Class<?> c, String name, float v) throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.setFloat(null, v);
    }

    private ParallelClick() {}
}
