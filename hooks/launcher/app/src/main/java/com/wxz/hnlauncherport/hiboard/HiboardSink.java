package com.wxz.hnlauncherport.hiboard;

import android.view.View;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 进入负一屏时桌面下沉 (KEY_HIBOARD_SINK).
 *
 * Stock V14 (14.0.0.262) NegativeScreenClientCallBack.onOverlayScrollChanged:
 *   mHiBoardType == 1 (负一屏, 横滑): dragLayer.setTranslationX(progress * 宽)
 *     + setAlpha(dragLayer, progress) -> 桌面整体横移淡出;
 *   mHiBoardType == 2 (资源库, 上滑): dragLayer.setScaleX/Y(1 - 0.1 * progress)
 *     + setAlpha(...) -> 桌面缩小淡出, 无位移.
 *
 * 开启后负一屏改走资源库同款视觉: 桌面 DragLayer 不再横移,
 * 而是 scale 1 -> 0.9 缩小 (居中下沉), alpha 淡出复用 stock 路径.
 * 松手 settle (hiboard 进程回吐 progress) 与跟手共用同一回调, 天然覆盖.
 *
 * 复位路径:
 *  - progress == 0 时本 hook 主动 setScale(1.0) (stock type-1 从不复位 scale);
 *  - Launcher.resetDragLayerState (service 断连等兜底) 只复位 translationX/alpha,
 *    追加 after-hook 同步复位 scale, 保证桌面不会卡在 0.9.
 */
public final class HiboardSink {
    private static final String CALLBACK_CLS =
            "com.hihonor.android.launcher.hiboard.NegativeScreenClientCallBack";
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";
    /** 资源库同款缩小系数: 1 - SINK_FACTOR * progress. */
    private static final float SINK_FACTOR = 0.1f;

    public static void install(ClassLoader cl) {
        final Class<?> callbackCls = XposedHelpers.findClass(CALLBACK_CLS, cl);
        final Class<?> launcherCls = XposedHelpers.findClass(LAUNCHER_CLS, cl);

        // 每帧 progress 回调: type-1 (负一屏) 时以缩小替换横移.
        XposedHelpers.findAndHookMethod(callbackCls, "onOverlayScrollChanged",
                float.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!Port.on(Port.KEY_HIBOARD_SINK)) {
                        return;
                    }
                    int type = XposedHelpers.getIntField(param.thisObject, "mHiBoardType");
                    if (type != 1) {
                        return; // 仅负一屏 (横滑); 资源库走 stock 缩小
                    }
                    float progress = (Float) param.args[0];
                    Object launcher = XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                    if (launcher == null) {
                        return;
                    }
                    Object dragLayerObj = XposedHelpers.callMethod(launcher, "getDragLayer");
                    if (!(dragLayerObj instanceof View)) {
                        return;
                    }
                    View dragLayer = (View) dragLayerObj;
                    dragLayer.setTranslationX(0.0f); // 抵消 stock 横移
                    float scale = 1.0f - SINK_FACTOR * progress;
                    dragLayer.setScaleX(scale);
                    dragLayer.setScaleY(scale);
                } catch (Throwable t) {
                    Port.log("HiboardSink: scroll frame failed", t);
                }
            }
        });

        // 断连兜底: stock 只复位 translationX/alpha, 补一个 scale 复位.
        XposedHelpers.findAndHookMethod(launcherCls, "resetDragLayerState",
                new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!Port.on(Port.KEY_HIBOARD_SINK)) {
                        return;
                    }
                    Object dragLayerObj = XposedHelpers.getObjectField(param.thisObject, "mDragLayer");
                    if (dragLayerObj instanceof View) {
                        ((View) dragLayerObj).setScaleX(1.0f);
                        ((View) dragLayerObj).setScaleY(1.0f);
                    }
                } catch (Throwable t) {
                    Port.log("HiboardSink: reset state failed", t);
                }
            }
        });

        Port.log("HiboardSink installed");
    }

    private HiboardSink() {
    }
}
