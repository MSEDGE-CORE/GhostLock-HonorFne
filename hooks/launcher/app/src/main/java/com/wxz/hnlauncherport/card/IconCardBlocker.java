package com.wxz.hnlauncherport.card;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 任务25: 禁用"桌面上滑图标弹卡片"(AbilityForm 服务卡片).
 *
 * Stock 链路 (仅桌面图标, 资源库/hiboard 插件有独立实现不受影响):
 *   BubbleTextView.handleActionForAbilityForm
 *     -> AbilityFormSwipeDetector.onDrag (上滑位移/速度超阈值)
 *     -> AbilityFormManager.showForIcon(btv, velocity, true)   <- 手势路径
 *     -> AbilityFormContainer.showForIcon (弹卡片)
 *   其余调用 showForIcon(btv, 0, false) 来自 CardWidgetCustomizeManager:832
 *   (卡片管理预览), 保留.
 *
 * 拦截方式: before-hook showForIcon, 第三参为 true (手势触发) 时直接跳过.
 * isTouchDownOnIcon (HiBoardUtil:37 触点归属判断) 在 BubbleTextView:782
 * 设置, 不经过本方法, 保持原状.
 */
public final class IconCardBlocker {
    private static final String MGR_CLS =
            "com.hihonor.android.launcher.cardstyle.Abilityform.AbilityFormManager";
    private static final String BTV_CLS =
            "com.hihonor.android.launcher.BubbleTextView";

    public static void install(ClassLoader cl) {
        Class<?> mgrCls = XposedHelpers.findClass(MGR_CLS, cl);
        Class<?> btvCls = XposedHelpers.findClass(BTV_CLS, cl);

        XposedHelpers.findAndHookMethod(mgrCls, "showForIcon",
                btvCls, float.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_DISABLE_ICON_CARD)) {
                            return;
                        }
                        if (Boolean.TRUE.equals(param.args[2])) {
                            param.setResult(null);
                            Port.log("IconCardBlocker: block icon swipe-up card");
                        }
                    }
                });

        Port.log("IconCardBlocker installed");
    }

    private IconCardBlocker() {}
}
