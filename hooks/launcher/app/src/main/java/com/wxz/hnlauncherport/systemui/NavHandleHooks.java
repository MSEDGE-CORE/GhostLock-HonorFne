package com.wxz.hnlauncherport.systemui;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 手势提示条 (HwNavigationHandle) 控制:
 *  - KEY_NAV_HANDLE_NO_FOLLOW: 禁用跟手。stock 的 onTouchEvent 在 MOVE 里
 *    setTranslation(X/Y)(手指位移 * 0.5, Y 封顶 navigation_handle_touch_move_
 *    distance_y), 超阈值后 hanldDragged 弹回。after-hook 每次触摸把 translation
 *    归零即可抵消 (长按圈选/点击逻辑不受影响)。
 *  - KEY_NAV_HANDLE_HIDE: 隐藏提示条。before-hook 跳过 onDraw, 只是不画:
 *    视图仍占位, 底部手势区/导航栏高度(底部抬升)完全不变。
 */
public final class NavHandleHooks {
    private static final String TAG = "NavHandleHooks";
    private static final String HW_HANDLE =
            "com.android.systemui.navigationbar.HwNavigationHandle";
    private static final String BASE_HANDLE =
            "com.android.systemui.navigationbar.gestural.NavigationHandle";

    public static void install(ClassLoader cl) {
        Class<?> hwHandle;
        try {
            hwHandle = XposedHelpers.findClass(HW_HANDLE, cl);
        } catch (Throwable t) {
            Port.log(TAG + " HwNavigationHandle not found, skip", t);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(hwHandle, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!Port.on(Port.KEY_NAV_HANDLE_NO_FOLLOW)) {
                                return;
                            }
                            View v = (View) param.thisObject;
                            if (v.getTranslationX() != 0f || v.getTranslationY() != 0f) {
                                v.setTranslationX(0f);
                                v.setTranslationY(0f);
                            }
                        }
                    });
        } catch (Throwable t) {
            Port.log(TAG + " onTouchEvent hook failed", t);
        }
        try {
            // single-tap bounce (onClick: translationY 0 -> -mMaxMoveY -> 0,
            // 400ms) is part of the follow family - kill it too
            XposedHelpers.findAndHookMethod(hwHandle, "onClick", View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Port.on(Port.KEY_NAV_HANDLE_NO_FOLLOW)) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            Port.log(TAG + " onClick hook failed", t);
        }
        try {
            // tap flash (stock bug): ACTION_DOWN fires a circle-to-search
            // shrink PULSE (animateLongPress) that is cancelled on UP -
            // a visible flicker on every tap. With follow disabled skip
            // the pulse entirely (long-press CTS triggering still works).
            Class<?> base = XposedHelpers.findClass(BASE_HANDLE, cl);
            XposedHelpers.findAndHookMethod(base, "animateLongPress",
                    boolean.class, boolean.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Port.on(Port.KEY_NAV_HANDLE_NO_FOLLOW)) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            Port.log(TAG + " animateLongPress hook failed", t);
        }
        try {
            // UP/CANCEL -> handlePress(boolean): pressed && in-area fires
            // onClick (the 0 -> -mMaxMoveY -> 0 hop; a swipe-up whose MOVEs
            // were stolen by the launcher arrives as CANCEL with pressed
            // still true, so the bounce fires on SWIPE too). No-op it -
            // this is the precise tap/swipe bounce entry, no global hooks.
            XposedHelpers.findAndHookMethod(hwHandle, "handlePress", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Port.on(Port.KEY_NAV_HANDLE_NO_FOLLOW)) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            Port.log(TAG + " handlePress hook failed", t);
        }
        XC_MethodHook hideDraw = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_NAV_HANDLE_HIDE)) {
                    param.setResult(null);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(hwHandle, "onDraw", Canvas.class, hideDraw);
        } catch (Throwable t) {
            Port.log(TAG + " Hw onDraw hook failed", t);
        }
        try {
            Class<?> base = XposedHelpers.findClass(BASE_HANDLE, cl);
            XposedHelpers.findAndHookMethod(base, "onDraw", Canvas.class, hideDraw);
        } catch (Throwable t) {
            Port.log(TAG + " base onDraw hook failed", t);
        }
        Port.log(TAG + " installed");
    }

    private NavHandleHooks() {}
}
