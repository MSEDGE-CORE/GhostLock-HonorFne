package com.wxz.hnlauncherport.aod;

import com.wxz.hnlauncherport.Port;

import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 熄屏显示 (com.hihonor.aod) hooks, SystemUI-AOD 分组:
 *
 * 固定AOD位置 (KEY_AOD_FIX_POS): 禁用每分钟防烧屏自动下移.
 * 链路: TimeTicker(60s) -> AodApHost.onTimeTick -> BaseViewController
 * -> AbsBaseProtector.onMove -> onVerticalMove (步进 step=80px).
 * 策略: onMove 第一次调用放行 (从初始位置移动一步 = 最初位置+80),
 * 之后一律拦截 (setResult(null)), 位置保持不变.
 */
public final class AodHooks {
    /** protector 实例 -> 是否已完成第一次移动 (放行后固定). */
    private static final WeakHashMap<Object, Boolean> sMovedOnce = new WeakHashMap<>();

    public static void install(ClassLoader cl) {
        Class<?> absProtector = XposedHelpers.findClass(
                "com.hihonor.aod.doze.protector.AbsBaseProtector", cl);
        XposedHelpers.findAndHookMethod(absProtector, "onMove", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_AOD_FIX_POS)) {
                    return;
                }
                synchronized (sMovedOnce) {
                    if (sMovedOnce.containsKey(param.thisObject)) {
                        param.setResult(null);
                        return;
                    }
                    sMovedOnce.put(param.thisObject, Boolean.TRUE);
                }
                Port.log("AodHooks: first move allowed (init+80), then fixed");
            }
        });

        Port.log("AodHooks installed");
    }

    private AodHooks() {}
}
