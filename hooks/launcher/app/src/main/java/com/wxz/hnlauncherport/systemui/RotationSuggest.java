package com.wxz.hnlauncherport.systemui;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 旋转建议 (KEY_ROTATION_SUGGEST): 手势导航下启用旋转建议悬浮球.
 *
 * stock 行为 (SystemUI 14): NavigationBarView.updateRotationButton 仅在
 * 三键导航 (HwNavBarFeaturesImpl.isButtonNavMode) 时把内嵌旋转按钮
 * (RotationContextButton) 接入 RotationButtonController; 手势导航分支
 * 直接 removeButton + setRotationButton(null, null) — AOSP 原生手势模式
 * 使用的 FloatingRotationButton (悬浮球) 虽被构造 (NavigationBarView
 * 构造器) 却从未注册, 手势导航下自动旋转关闭时无任何旋转建议.
 *
 * 开启后: updateRotationButton 后置拦截 — stock 未接按钮 (手势模式,
 * getRotationButton() == null) 时改接 mFloatingRotationButton (复用
 * stock 悬浮球, 含位置计算/显隐动画/点击旋转), 回调复用
 * mRotationButtonListener (触区/自动隐藏联动). 三键模式不干预.
 * 关闭时若当前接的是悬浮球则卸载 (setRotationButton(null, null)).
 */
public final class RotationSuggest {
    private static final String NBV_CLS =
            "com.android.systemui.navigationbar.NavigationBarView";
    private static final String FRB_CLS =
            "com.android.systemui.shared.rotation.FloatingRotationButton";

    public static void install(ClassLoader cl) {
        final Class<?> nbv = XposedHelpers.findClass(NBV_CLS, cl);
        final Class<?> frb = XposedHelpers.findClass(FRB_CLS, cl);
        XposedHelpers.findAndHookMethod(nbv, "updateRotationButton", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object view = param.thisObject;
                Object ctrl = XposedHelpers.getObjectField(view, "mRotationButtonController");
                if (ctrl == null) {
                    return;
                }
                Object cur = XposedHelpers.callMethod(ctrl, "getRotationButton");
                if (Port.on(Port.KEY_ROTATION_SUGGEST)) {
                    if (cur != null) {
                        return; // 三键模式: stock 已接内嵌按钮
                    }
                    Object floating = XposedHelpers.getObjectField(view, "mFloatingRotationButton");
                    Object listener = XposedHelpers.getObjectField(view, "mRotationButtonListener");
                    if (floating == null || listener == null) {
                        return;
                    }
                    XposedHelpers.callMethod(ctrl, "setRotationButton", floating, listener);
                    Port.log("RotationSuggest: floating rotation button wired (gesture nav)");
                } else if (frb.isInstance(cur)) {
                    XposedHelpers.callMethod(ctrl, "setRotationButton", null, null);
                    Port.log("RotationSuggest: floating rotation button unwired");
                }
            }
        });
        Port.log("RotationSuggest installed");
    }

    private RotationSuggest() {}
}
