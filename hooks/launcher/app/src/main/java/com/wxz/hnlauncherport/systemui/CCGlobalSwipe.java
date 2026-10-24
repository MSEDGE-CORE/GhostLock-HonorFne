package com.wxz.hnlauncherport.systemui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 全屏横滑切换 (V17 样式):
 * stock V14 isInterceptThisMove 只在起手位于头部视图内时才拦截;
 * V17 控制中心为全局横滑 (磁贴/卡片上起手也可切), 通知中心保留
 * 通知行手势 (isInContentBounds 排除, 行区域横滑删除不受影响).
 * 本 hook after isInterceptThisMove, stock 返回 false 时按 V17 语义重判:
 *  - 控制中心: 全局允许, 起手在亮度音量调节卡 (HnSliderCardController
 *    mCardView) 内除外 (滑条拖动不抢手势).
 *  - 通知中心: isInContentBounds(行区域)内不抢, 其余允许.
 */
public final class CCGlobalSwipe {
    private static final String TAG = "CCGlobalSwipe";
    private static final String CTRL =
            "com.android.systemui.statusbar.phone.HnSwipeSwitchControllerImpl";
    private static final String SLIDER =
            "com.android.systemui.controllercenter.slidercard.HnSliderCardController";

    /** 亮度音量调节卡根视图 (initController 时缓存) */
    private static volatile View sSliderCard;

    private CCGlobalSwipe() {
    }

    public static void install(ClassLoader cl) {
        // 缓存滑条卡根视图, 用于控制中心命中排除
        try {
            Class<?> slider = XposedHelpers.findClass(SLIDER, cl);
            XposedHelpers.findAndHookMethod(slider, "initController",
                    Context.class, ViewGroup.class,
                    XposedHelpers.findClass(
                            "com.android.systemui.controllercenter.HnCardManagerEx", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object v = XposedHelpers.getObjectField(param.thisObject, "mCardView");
                            if (v instanceof View) {
                                sSliderCard = (View) v;
                                Port.log(TAG + " slider card cached");
                            }
                        }
                    });
        } catch (Throwable t) {
            Port.log(TAG + " slider hook failed", t);
        }

        Class<?> ctrl = XposedHelpers.findClass(CTRL, cl);
        XposedHelpers.findAndHookMethod(ctrl, "isInterceptThisMove", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.TRUE.equals(param.getResult())) {
                            return; // stock 已拦截 (头部起手)
                        }
                        if (!Port.on(Port.KEY_CC_GLOBAL_SWIPE)) {
                            return;
                        }
                        Object impl = param.thisObject;
                        if (isLimited(impl)) {
                            return;
                        }
                        MotionEvent ev = (MotionEvent) param.args[0];
                        float initX = XposedHelpers.getFloatField(impl, "mInitTouchPosX");
                        float initY = XposedHelpers.getFloatField(impl, "mInitTouchPosY");
                        float dx = Math.abs(ev.getX() - initX);
                        float dy = Math.abs(ev.getY() - initY);
                        float slop = XposedHelpers.getIntField(impl, "mTouchSlop");
                        if (dx <= slop || dx <= dy) {
                            return; // 尚无横向意图
                        }
                        Object pvc = XposedHelpers.getObjectField(impl, "mPanelViewControllerEx");
                        boolean cc = (Boolean) XposedHelpers.callMethod(pvc, "isControlCenterShowing");
                        if (cc) {
                            View container =
                                    (View) XposedHelpers.getObjectField(impl, "mNotificationContainer");
                            if (!isOnSliderCard(container, initX, initY)) {
                                param.setResult(Boolean.TRUE);
                            }
                            return;
                        }
                        boolean nc = (Boolean) XposedHelpers.callMethod(pvc, "isNotificationCenterShowing");
                        if (nc) {
                            Object stack = XposedHelpers.getObjectField(impl, "mStackScroller");
                            boolean inContent = (Boolean) XposedHelpers.callMethod(
                                    stack, "isInContentBounds", initY);
                            if (!inContent) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    }
                });
        Port.log(TAG + " installed");
    }

    private static boolean isLimited(Object impl) {
        try {
            return (Boolean) XposedHelpers.callMethod(impl, "isLimitControlCenter");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 容器坐标系下的起手点是否落在滑条卡内 (屏幕坐标换算命中测试) */
    private static boolean isOnSliderCard(View container, float localX, float localY) {
        View card = sSliderCard;
        if (card == null || container == null || card.getWindowToken() == null) {
            return false;
        }
        int[] c = new int[2];
        int[] v = new int[2];
        container.getLocationOnScreen(c);
        card.getLocationOnScreen(v);
        float sx = localX + c[0];
        float sy = localY + c[1];
        return sx >= v[0] && sx <= v[0] + card.getWidth()
                && sy >= v[1] && sy <= v[1] + card.getHeight();
    }
}
