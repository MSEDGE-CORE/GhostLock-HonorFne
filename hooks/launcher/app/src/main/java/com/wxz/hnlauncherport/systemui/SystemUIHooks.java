package com.wxz.hnlauncherport.systemui;

import android.view.View;

import com.wxz.hnlauncherport.Port;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * SystemUI (com.android.systemui) hooks, SystemUI-状态栏 分组:
 *
 * 灵动胶囊启用时状态栏缩小链 (HnPhoneStatusBarAnimController):
 * 胶囊展开 -> getStatusBarAnimForCapsule:
 *   - getScaleAnimatorsList: 全部子项 scale -> getScaleValue(true)=0.88;
 *   - getAlphaAnimatorsList: statusIcons/signal/network 等 5 组 alpha 1->0;
 *   - 动画结束 setContentsVisibility(false) 把图标 GONE;
 *   - resetScaleStateWithoutAnim: 无动画路径直接置 scale 0.88 / alpha 0.
 *
 * (1) 禁用状态栏自动缩小 (KEY_SB_NO_SHRINK): getScaleValue(true) -> 1.0,
 *     动画与无动画路径都不再缩到 0.88, 保持大尺寸.
 *     左挖孔 resolveStatusBarPadding/padding 路径不受影响.
 * (2) 禁用状态栏元素自动隐藏 (KEY_SB_NO_HIDE): alpha 动画列表置空 +
 *     setContentsVisibility 强制显示 + 无动画路径 alpha 归 1,
 *     灵动胶囊启用时状态栏杂项图标不隐藏.
 */
public final class SystemUIHooks {
    private static final String CTRL_CLS =
            "com.android.systemui.statusbar.phone.HnPhoneStatusBarAnimController";

    public static void install(ClassLoader cl) {
        Class<?> ctrl = XposedHelpers.findClass(CTRL_CLS, cl);

        // (1) getScaleValue(capsuleAppear=true) -> 1.0
        XposedHelpers.findAndHookMethod(ctrl, "getScaleValue", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Boolean.TRUE.equals(param.args[0]) && Port.on(Port.KEY_SB_NO_SHRINK)) {
                    param.setResult(1.0f);
                }
            }
        });

        // (2a) alpha 隐藏动画列表置空
        XposedHelpers.findAndHookMethod(ctrl, "getAlphaAnimatorsList",
                boolean.class, View[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Port.on(Port.KEY_SB_NO_HIDE)) {
                            param.setResult(new ArrayList<Object>());
                        }
                    }
                });

        // (2b) setContentsVisibility 强制保持显示
        XposedHelpers.findAndHookMethod(ctrl, "setContentsVisibility", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_SB_NO_HIDE)) {
                    param.args[0] = Boolean.TRUE;
                }
            }
        });

        // 无动画路径 (resetScaleStateWithoutAnim) 的状态归一
        XposedHelpers.findAndHookMethod(ctrl, "resetScaleStateWithoutAnim", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                boolean noShrink = Port.on(Port.KEY_SB_NO_SHRINK);
                boolean noHide = Port.on(Port.KEY_SB_NO_HIDE);
                if (!noShrink && !noHide) {
                    return;
                }
                if (noShrink) {
                    Object contents = XposedHelpers.getObjectField(param.thisObject, "mStatusBarContents");
                    if (contents != null) {
                        XposedHelpers.callMethod(param.thisObject, "setScaleXY", 1.0f, contents);
                    }
                    resetViewScale(param.thisObject, "mOperatorView");
                    resetViewScale(param.thisObject, "mOutsideBatteryView");
                }
                if (noHide) {
                    String[] names = {"mNetworkAreaMore", "mStatusIcons", "mSignalIcons",
                            "mNetworkIcons", "mSystemIconUnstableArea"};
                    for (String name : names) {
                        Object v = XposedHelpers.getObjectField(param.thisObject, name);
                        if (v instanceof View) {
                            ((View) v).setAlpha(1.0f);
                        }
                    }
                }
            }
        });

        Port.log("SystemUIHooks installed");
    }

    private static void resetViewScale(Object controller, String field) {
        Object v = XposedHelpers.getObjectField(controller, field);
        if (v instanceof View) {
            ((View) v).setScaleX(1.0f);
            ((View) v).setScaleY(1.0f);
        }
    }

    private SystemUIHooks() {}
}
