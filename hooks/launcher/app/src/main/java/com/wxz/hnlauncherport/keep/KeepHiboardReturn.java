package com.wxz.hnlauncherport.keep;

import android.os.Bundle;
import android.os.SystemClock;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 返家保持负一屏/应用资源库 (任务⑰, 参照 V17).
 *
 * Root cause (V14 14.0.0.262 vs V17 17.0.20.191, OtherActivityInputConsumer):
 * V17 never closes the hiboard on a swipe-up-to-home: its only mid-gesture close
 * (checkIfSwitchToNewTask) is reserved for deep task-switch swipes, and entering
 * recents closes at settle (AbsSwipeUpHandler CLOSE_HIBOARD_FOR_RECENT), so the
 * overlay re-shown by HiBoardClient.onResume(-111) survives the home landing.
 * V14 instead closes the overlay at ACTION_UP of EVERY swipe-up
 * (OtherActivityInputConsumerEx.closeHiboardIfNeeded -> hidePiflowNewsIfNecessary
 * "closeHiboard") plus once more during the gesture ("SwitchToRecents" via
 * LauncherActivityInterface.closeOverlay), both AFTER the launcher resumed and
 * re-showed the overlay: the re-shown 负一屏 is closed again -> the user lands on
 * the desktop. (HOME-key return has no gesture closes, hence stock works.)
 *
 * Fix (V17 parity), v3:
 *  - "closeHiboard" (ACTION_UP / slop of every swipe-up from an app) is blocked
 *    whenever the last home surface was the hiboard. v2 additionally required
 *    HnBackgroundAppState, but on a FAST flick the close fires BEFORE the
 *    launcher resumes (state still NormalState) -> slipped through, killed the
 *    flag and the re-shown hiboard. The string only ever comes from the swipe
 *    gesture paths (OtherActivityInputConsumer:274/:526), so flag + setting is
 *    a sufficient condition.
 *  - "SwitchToRecents" (AbsSwipeUpHandler.onGestureStarted / three-key
 *    LauncherActivityInterface.closeOverlay) is blocked only mid-gesture:
 *    launcher in HnBackgroundAppState (slow/paused swipe) or not yet resumed
 *    (fast flick). Three-key recents runs foreground + resumed in NormalState
 *    and keeps the stock close.
 *  - OtherActivityInputConsumerEx.onGestureEnded(GestureEndTarget):
 *      HOME / LAST_TASK -> keep the overlay (返回桌面/返回当前应用不退出,
 *          LAST_TASK 含上滑回落进当前应用与反向快切);
 *      RECENTS / NEW_TASK -> close it (进后台/横向切换应用退出) + drop flag;
 *  - HOME-key / freeform returns still restore through the onResume and
 *    onWindowFocusChanged(true) triggers.
 *  - 返回资源库 (HiLibrary) 的返家动画目标刷新: findExitAppAnimation 查到的
 *    桌面图标目标被清空, 令返家动画走 getDefaultToRectF 的 0.3 无图标默认
 *    路径 (与无桌面图标应用同一条路, 用户要求).
 */
public final class KeepHiboardReturn {
    private static final String LAUNCHER_CLS = "com.hihonor.android.launcher.Launcher";
    private static final String CONSUMER_EX_CLS =
            "com.hihonor.android.launcher.extension.quickstep.OtherActivityInputConsumerEx";
    private static final String END_TARGET_CLS =
            "com.android.quickstep.GestureState$GestureEndTarget";
    private static final String HIBOARD_MGR_CLS =
            "com.hihonor.android.launcher.hiboard.HiBoardClientManager";
    private static final String HIBOARD_IMPL_CLS =
            "com.hihonor.android.launcher.hiboard.HiBoardClientImpl";
    private static final String EXIT_ANIM_CLS =
            "com.hihonor.android.launcher.exitapp.ExitAppAnimationMananger";

    /** True while the last home surface was 负一屏/资源库. */
    private static volatile boolean sFromHiboard = false;
    /** Mirrors Launcher.onResume/onPause - true after resume, false after pause. */
    private static volatile boolean sLauncherResumed = false;
    /**
     * Last positive overlay type seen via HiBoardClientImpl.setHiBoardType
     * (1 = 负一屏, 2 = 资源库). The stock mHiBoardType is reset to -1 at the
     * ACTION_UP of the very swipe that OPENED the overlay (resetTouchEvent ->
     * reset -> setHiBoardType(-1)), so reading it back at return time always
     * yields -1; we must remember the open type ourselves.
     */
    private static volatile int sLastOpenType = 0;
    /** Uptime of the last restore, debounces onResume + focus double-fire. */
    private static volatile long sLastRestore = 0L;
    private static final long RESTORE_DEBOUNCE_MS = 1200L;
    /**
     * Uptime of the last "closeHiboard" close (the ACTION_UP close of every
     * app-return gesture, OtherActivityInputConsumer:526). The same gesture's
     * "SwitchToRecents" close lands 1-6ms later, but on a swipe where the
     * launcher already resumed AND left HnBackgroundAppState both legacy
     * mid-gesture signals are gone - the window catches that case.
     */
    private static volatile long sLastCloseHiboardMs = 0L;
    private static final long CLOSE_HIBOARD_WINDOW_MS = 350L;

    private static Class<?> sMgrCls;

    public static void install(ClassLoader cl) {
        final Class<?> launcher = XposedHelpers.findClass(LAUNCHER_CLS, cl);
        sMgrCls = XposedHelpers.findClass(HIBOARD_MGR_CLS, cl);

        XposedHelpers.findAndHookMethod(launcher, "setHiBoardShowing",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        boolean showing = ((Boolean) param.args[0]).booleanValue();
                        if (showing) {
                            sFromHiboard = true;
                            Port.log("KeepHiboardReturn: hiboard showing=true (fromHiboard="
                                    + sFromHiboard + ")");
                            return;
                        }
                        // Overlay closed. Only keep the flag when the launcher is
                        // mid-transition to an app (HnBackgroundAppState), e.g. the
                        // plugin reporting the park at app-open; a close while the
                        // launcher sits on the desktop means the user left the
                        // 负一屏/资源库 deliberately.
                        boolean bgApp = inState(param.thisObject, "BackgroundApp");
                        boolean keep = bgApp && sFromHiboard;
                        if (!keep) {
                            sFromHiboard = false;
                        }
                        Port.log("KeepHiboardReturn: hiboard showing=false bgApp=" + bgApp
                                + " keepFlag=" + sFromHiboard);
                    }
                });

        XposedHelpers.findAndHookMethod(launcher, "hidePiflowNewsIfNecessary",
                int.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // V14 closes the hiboard here on every swipe-up gesture
                        // (both "closeHiboard" at ACTION_UP and "SwitchToRecents"
                        // mid-gesture), regardless of the end target. V17 only
                        // closes for task switches; the home landing must keep the
                        // overlay. v3: no state dependency on "closeHiboard" - on
                        // a fast flick the close fires before the launcher resumes
                        // (state still NormalState), so the v2 BackgroundApp check
                        // let it through and the flag died with it.
                        String by = (String) param.args[1];
                        boolean closeBy = "closeHiboard".equals(by);
                        boolean switchBy = "SwitchToRecents".equals(by);
                        if (!closeBy && !switchBy) {
                            return;
                        }
                        if (closeBy) {
                            sLastCloseHiboardMs = SystemClock.uptimeMillis();
                        }
                        if (!sFromHiboard || !keepEnabled()) {
                            return;
                        }
                        // "SwitchToRecents" is also used by the three-key recents
                        // path (launcher foreground, resumed, NormalState): only
                        // block it while an app swipe gesture is mid-flight.
                        if (switchBy && !midGesture(param.thisObject)) {
                            return;
                        }
                        param.setResult(Boolean.FALSE);
                        Port.log("KeepHiboardReturn: block gesture close by=" + by);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.getResult();
                        boolean closed = r instanceof Boolean && ((Boolean) r).booleanValue();
                        if (closed) {
                            boolean keep = keepEnabled() && sFromHiboard
                                    && inState(param.thisObject, "BackgroundApp");
                            if (!keep) {
                                sFromHiboard = false;
                            }
                        }
                        Port.log("KeepHiboardReturn: hidePiflowNewsIfNecessary by=" + param.args[1]
                                + " closed=" + closed + " fromHiboard=" + sFromHiboard);
                    }
                });

        Class<?> exCls = XposedHelpers.findClass(CONSUMER_EX_CLS, cl);
        Class<?> endTargetCls = XposedHelpers.findClass(END_TARGET_CLS, cl);
        XposedHelpers.findAndHookMethod(exCls, "onGestureEnded", endTargetCls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object target = param.args[0];
                        if (target == null) {
                            return;
                        }
                        String end = ((Enum<?>) target).name();
                        if ("HOME".equals(end) || "LAST_TASK".equals(end)) {
                            // Swipe-up returned home (HOME) or the gesture fell
                            // back into the current app (LAST_TASK, 含上滑回落
                            // 与反向快切): the blocked closes left the overlay
                            // open (re-shown by resume/-111) -> keep it.
                            Port.log("KeepHiboardReturn: gestureEnd " + end
                                    + " keep=" + sFromHiboard);
                            return;
                        }
                        if (!sFromHiboard) {
                            return;
                        }
                        // RECENTS / NEW_TASK: 进后台或横向切换任务 ->
                        // 退出负一屏 (V17 CLOSE_HIBOARD_FOR_RECENT parity).
                        // LAST_TASK (返回当前应用) 与 HOME 同走保持分支.
                        sFromHiboard = false;
                        if (keepEnabled()) {
                            try {
                                Object launcherObj =
                                        XposedHelpers.callStaticMethod(launcher, "getLauncher");
                                if (launcherObj != null) {
                                    Object showing = XposedHelpers.callMethod(launcherObj,
                                            "isHiboardShowing");
                                    boolean isShowing = showing instanceof Boolean
                                            && ((Boolean) showing).booleanValue();
                                    if (isShowing) {
                                        Object mgr = XposedHelpers.callStaticMethod(
                                                sMgrCls, "getInstance");
                                        XposedHelpers.callMethod(mgr, "closeOverlay",
                                                Integer.valueOf(200), Boolean.valueOf(false));
                                    }
                                }
                            } catch (Throwable t) {
                                Port.log("KeepHiboardReturn gestureEnd close failed", t);
                            }
                        }
                        Port.log("KeepHiboardReturn: gestureEnd " + end + " -> exit hiboard");
                    }
                });

        // Track the overlay type ourselves (see sLastOpenType): the stock
        // mHiBoardType dies at the opening swipe's ACTION_UP.
        Class<?> hiboardImplCls = XposedHelpers.findClass(HIBOARD_IMPL_CLS, cl);
        XposedHelpers.findAndHookMethod(hiboardImplCls, "setHiBoardType",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int t = ((Integer) param.args[0]).intValue();
                        if (t > 0) {
                            sLastOpenType = t;
                            Port.log("KeepHiboardReturn: hiboard type=" + t);
                        }
                    }
                });

        Class<?> exitAnimCls = XposedHelpers.findClass(EXIT_ANIM_CLS, cl);

        // 返回资源库 (HiLibrary) 时的返家动画目标: stock 的 shouldDoExitAni
        // (ProviderCallUtils:1988) 见 isHiboardShowing() 直接返回 false,
        // deliverExitAppData / findExitAppAnimation 整个被跳过, 上面那个
        // after-hook 钩子成为死代码; returnHomeWithAnimation:727 读到的因此
        // 是上一次 (回桌面/开应用) 留下的陈旧 mAnimationInfo -> 应用缩向桌面
        // 图标. 在 returnHomeWithAnimation 入口清空 mAnimationInfo, 令 :740
        // isNeedReturnHomeWindowAnim(null) = true -> returnHomeWindowAnim ->
        // getDefaultToRectF 的 0.3 无图标默认路径 (与无桌面图标应用同一条路).
        XposedHelpers.findAndHookMethod(exCls, "returnHomeWithAnimation",
                float.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!keepEnabled() || !sFromHiboard || sLastOpenType != 2) {
                            return;
                        }
                        try {
                            Object mgr = XposedHelpers.callStaticMethod(exitAnimCls, "getInstance");
                            XposedHelpers.callMethod(mgr, "clearAnimationInfo");
                            Port.log("KeepHiboardReturn: clear return-anim target -> default (HiLibrary)");
                        } catch (Throwable t) {
                            Port.log("KeepHiboardReturn clear target failed", t);
                        }
                    }
                });

        // 返回资源库 (HiLibrary) 时刷新返家动画目标: 清掉 findExitAppAnimation
        // 查到的桌面图标 (deliverExitAppData 是每个返家手势的目标刷新总入口,
        // 先置空再重查), 令 getToRectF(null) 走 getDefaultToRectF 的 0.3 无图标
        // 默认路径 - 与无桌面图标应用完全同一条路 (无浮动图标, isReturnToIcon
        // 为 false).
        XposedHelpers.findAndHookMethod(exitAnimCls, "findExitAppAnimation",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        boolean keep = keepEnabled();
                        boolean from = sFromHiboard;
                        boolean lib = sLastOpenType == 2;
                        if (keep && from && lib) {
                            XposedHelpers.callMethod(param.thisObject, "clearAnimationInfo");
                            Port.log("KeepHiboardReturn: refresh return-anim target -> default (HiLibrary)");
                        } else {
                            Port.log("KeepHiboardReturn: animTarget keep=" + keep
                                    + " from=" + from + " type=" + sLastOpenType);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(launcher, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sLauncherResumed = true;
                Port.log("KeepHiboardReturn: onResume trigger");
                maybeRestore(param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(launcher, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sLauncherResumed = false;
            }
        });

        XposedHelpers.findAndHookMethod(launcher, "onWindowFocusChanged",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        boolean hasFocus = ((Boolean) param.args[0]).booleanValue();
                        if (hasFocus) {
                            Port.log("KeepHiboardReturn: onWindowFocusChanged(true) trigger");
                            maybeRestore(param.thisObject);
                        }
                    }
                });

        Port.log("KeepHiboardReturn installed");
    }

    private static boolean keepEnabled() {
        try {
            return Port.on(Port.KEY_KEEP_HIBOARD);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True while a swipe-up gesture from an app is in flight: the launcher has
     * switched to HnBackgroundAppState (slow/paused swipe, resume already
     * happened) or has not resumed yet (fast flick - the closes fire before
     * onResume), or the "SwitchToRecents" close landed within the
     * closeHiboard window (same ACTION_UP; launcher resumed AND already left
     * HnBackgroundAppState - the state signal died before the close fired).
     * Three-key recents runs foreground + resumed in NormalState with no
     * preceding "closeHiboard" and returns false.
     */
    private static boolean midGesture(Object launcher) {
        if (inState(launcher, "BackgroundApp")) {
            return true;
        }
        if (!sLauncherResumed) {
            return true;
        }
        long last = sLastCloseHiboardMs;
        return last > 0L && SystemClock.uptimeMillis() - last < CLOSE_HIBOARD_WINDOW_MS;
    }

    private static boolean inState(Object launcher, String token) {
        try {
            Object state = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(launcher, "getStateManager"), "getState");
            return state != null && state.getClass().getSimpleName().contains(token);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void maybeRestore(Object launcher) {
        if (!keepEnabled()) {
            Port.log("KeepHiboardReturn: skip, setting off");
            return;
        }
        if (!sFromHiboard) {
            Port.log("KeepHiboardReturn: skip, !fromHiboard");
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastRestore < RESTORE_DEBOUNCE_MS) {
            return;
        }
        try {
            // 即将进入后台(最近任务) -> keep the stock exit, drop the flag.
            if (inState(launcher, "Overview")) {
                sFromHiboard = false;
                Port.log("KeepHiboardReturn: in Overview, drop flag");
                return;
            }
            Class<?> mgrCls = XposedHelpers.findClass(HIBOARD_MGR_CLS,
                    launcher.getClass().getClassLoader());
            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
            Object showing = XposedHelpers.callMethod(launcher, "isHiboardShowing");
            boolean isShowing = showing instanceof Boolean && ((Boolean) showing).booleanValue();
            if (isShowing) {
                // Plugin-side state may still say "open" while its window was
                // parked by the app-open close: repeat the stock resume signal
                // so it corrects visibility (same call the HOME path makes).
                XposedHelpers.callMethod(mgr, "invoke", Integer.valueOf(-111), (Bundle) null);
            } else {
                // Overlay state fully dropped: explicit re-open. HiBoardClient
                // .openOverlay enriches the bundle with the current type
                // (HiBoard / HiLibrary), so 资源库 restores as 资源库.
                XposedHelpers.callMethod(mgr, "openOverlay", Boolean.valueOf(true), (Bundle) null);
            }
            sLastRestore = now;
            Port.log("KeepHiboardReturn: restore issued (isShowing=" + isShowing + ")");
        } catch (Throwable t) {
            Port.log("KeepHiboardReturn restore failed", t);
        }
    }

    private KeepHiboardReturn() {}
}
