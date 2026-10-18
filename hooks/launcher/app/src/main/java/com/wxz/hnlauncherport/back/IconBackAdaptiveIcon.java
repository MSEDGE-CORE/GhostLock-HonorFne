package com.wxz.hnlauncherport.back;

import android.graphics.RectF;
import android.view.View;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 返回键回桌面 - 浮动图标改双层自适应 (KEY_MOTION, 应用启动关闭动效优化).
 *
 * V14 composeIconBackAnimation -> FloatingIconView.getFloatingIconView(
 * ..., z2=false) -> getIconResult -> getDrawableIcon: desktop app icons
 * (itemType 1/7) always fall back to the single-layer
 * BitmapDrawable(info.getIcon()), so the closing floating icon renders
 * flat instead of the adaptive foreground/background layers the desktop
 * BubbleTextView shows natively (swipe-up path).
 *
 * After getFloatingIconView returns with z2 == false (closing) and the
 * loaded icon is NOT adaptive, upgrade it: re-setIcon with
 * info.getAdaptiveIcon() so setAdaptiveIcon() splits the two layers.
 * Opening (z2 == true) is left untouched - its consumer sizes the view
 * differently. Folder flows override via setBitmapForFoldIcon afterwards
 * and are unaffected. Non-BubbleTextView views (shortcuts etc.) have no
 * getInfo()/getAdaptiveIcon() and are skipped by the reflection guard.
 */
public final class IconBackAdaptiveIcon {

    private static final String FIV_CLS =
            "com.hihonor.android.launcher.views.FloatingIconView";
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";

    public static void install(ClassLoader cl) {
        Class<?> fiv = XposedHelpers.findClass(FIV_CLS, cl);
        Class<?> launcher = XposedHelpers.findClass(LAUNCHER_CLS, cl);
        XposedHelpers.findAndHookMethod(fiv, "getFloatingIconView",
                launcher, View.class, boolean.class, RectF.class, boolean.class,
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_MOTION)) {
                    return;
                }
                boolean isOpening = (Boolean) param.args[4];
                if (isOpening) {
                    return; // only the closing (back-key) path
                }
                Object f = param.getResult();
                Object view = param.args[1];
                if (f == null || view == null) {
                    return;
                }
                try {
                    if (XposedHelpers.getBooleanField(f, "mIsAdaptiveIcon")) {
                        return; // already two-layer
                    }
                    Object info = XposedHelpers.callMethod(view, "getInfo");
                    Object adaptive = XposedHelpers.callMethod(info, "getAdaptiveIcon");
                    if (adaptive == null) {
                        return;
                    }
                    XposedHelpers.callMethod(f, "setIcon", adaptive, adaptive);
                    Port.log("IconBackAdaptiveIcon upgraded to adaptive layers");
                } catch (Throwable t) {
                    // non-BubbleTextView views or missing info - keep stock
                }
            }
        });
        Port.log("IconBackAdaptiveIcon installed");
    }

    private IconBackAdaptiveIcon() {}
}
