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
                            param.setResult(Boolean.TRUE);
                        }
                    });
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

    /** True while an app launch is in flight (GAC.mIsActivityStart). */
    private static boolean launchInFlight(Object controller) {
        if (controller == null) {
            return false;
        }
        try {
            Object r = XposedHelpers.callMethod(controller, "getIsActivityStart");
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private ParallelClick() {}
}
