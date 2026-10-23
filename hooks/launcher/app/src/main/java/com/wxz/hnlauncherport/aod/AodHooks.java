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

        // 单击亮屏: TP 固件单击上报 keycode 716, AodApHost$4.onKey stock
        // 仅 tapShowAod (显示AOD不亮屏); 开启后改为 PowerManager.wakeUp 直接亮屏.
        // AOD 进程持有 DEVICE_POWER 权限.
        Class<?> keyListener = XposedHelpers.findClass(
                "com.hihonor.aod.doze.AodApHost$4", cl);
        XposedHelpers.findAndHookMethod(keyListener, "onKey",
                android.view.View.class, int.class, android.view.KeyEvent.class,
                new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!Port.on(Port.KEY_KG_SINGLE_TAP_WAKE)) {
                        return;
                    }
                    int keyCode = (Integer) param.args[1];
                    android.view.KeyEvent event = (android.view.KeyEvent) param.args[2];
                    if (keyCode != 716 || event.getAction() != 0) {
                        return; // 仅单击按下
                    }
                    Object host = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    Object ctxObj = XposedHelpers.getObjectField(host, "mContext");
                    if (!(ctxObj instanceof android.content.Context)) {
                        return;
                    }
                    android.os.PowerManager pm = (android.os.PowerManager)
                            ((android.content.Context) ctxObj).getSystemService("power");
                    if (pm == null) {
                        return;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    try {
                        pm.getClass().getMethod("wakeUp", long.class, String.class)
                                .invoke(pm, Long.valueOf(now), "HnPort SINGLE_TAP_WAKE");
                    } catch (NoSuchMethodException nsme) {
                        pm.getClass().getMethod("wakeUp", long.class)
                                .invoke(pm, Long.valueOf(now));
                    }
                    param.setResult(Boolean.TRUE);
                    Port.log("AodHooks: single tap wake (716)");
                } catch (Throwable t) {
                    Port.log("AodHooks: single tap wake failed", t);
                }
            }
        });

        Port.log("AodHooks installed");
    }

    private AodHooks() {}
}
