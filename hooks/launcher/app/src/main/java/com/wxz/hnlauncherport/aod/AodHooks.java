package com.wxz.hnlauncherport.aod;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 熄屏显示 (com.hihonor.aod) hooks, SystemUI-AOD 分组:
 *
 * 固定AOD位置 (KEY_AOD_FIX_POS): 禁用每分钟防烧屏自动下移.
 * 链路: TimeTicker(60s) -> AodApHost.onTimeTick -> BaseViewController
 * -> AbsBaseProtector.onMove -> onVerticalMove (步进 step=80px).
 * 策略: onMove 一律拦截 (setResult(null)), 位置始终保持初始位置不动.
 */
public final class AodHooks {

    public static void install(ClassLoader cl) {
        Class<?> absProtector = XposedHelpers.findClass(
                "com.hihonor.aod.doze.protector.AbsBaseProtector", cl);
        XposedHelpers.findAndHookMethod(absProtector, "onMove", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (Port.on(Port.KEY_AOD_FIX_POS)) {
                    param.setResult(null);
                }
            }
        });

        Port.log("AodHooks installed");
    }

    private AodHooks() {}
}
