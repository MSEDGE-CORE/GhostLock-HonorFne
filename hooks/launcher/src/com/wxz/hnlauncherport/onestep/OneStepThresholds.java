package com.wxz.hnlauncherport.onestep;

import android.graphics.PointF;
import android.os.Bundle;
import android.os.SystemClock;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 任务24: swipe 进小窗/分屏 的判定阈值重调 (参照摘要3 OneStepWindowMgr 链路),
 * 单开关 KEY_ONESTEP_THRESHOLD 同时控制阈值与松手停顿判定.
 *
 * Stock (OneStepWindowMgr.updateLayoutParams:974-979):
 *   mShowBgHotY   = 0.8H   中间过渡态背景可见
 *   mShowIconHotY = 0.75H  中间过渡态图标可见
 *   mScaleHotY    = 0.75H  图标跟手缩放起点
 *   mShapeHotY    = 0.55H  角部椭圆热区纵半轴 (横半轴 mShapeHotX = 0.5W 不变)
 *   松手 commit 纯位置态 (OtherActivityInputConsumerEx:1211 读
 *   isInFloatOrSplitWindowHot -> :1222 transformFullscreenToMiniWindow).
 *
 * New:
 *  (1) 过渡态阈值 0.8 (背景) / 0.75 (图标); 椭圆纵半轴横屏 0.3H, 竖屏 0.5H;
 *  (2) 松手增加速度判定: AbsSwipeUpHandler.handleNormalGestureEnd 的 fling 位
 *      (z = mGestureStarted && !mIsMotionPaused && |v| > quickstep_fling_
 *      threshold_speed, :1325) 为 true 即"快速滑动/返回桌面的速度" -> 不允许
 *      小窗/分屏 commit; 仅 z == false (停顿, 进入后台的速度) 才保留热区状态.
 *      注意 setUpGone() 只是 handler.post 异步清理, 而同一调用栈稍后的 commit
 *      判定 (:1211) 同步读 mState, post 来不及 -> fling 释放时记时间戳, 由
 *      (3) 的读值钩子在窗口内强制返回"不在热区" (与区域外释放走同一分支);
 *      setUpGone 照常调用, post 出去的 runnable 经 gotoState(GONE) 收尾视图.
 *      非 fling (停顿) 释放清掉时间戳, commit 正常按位置态生效.
 */
public final class OneStepThresholds {
    private static final String MGR_CLS =
            "com.hihonor.android.launcher.onestep.OneStepWindowMgr";
    private static final String HANDLER_CLS =
            "com.android.quickstep.AbsSwipeUpHandler";

    /** Uptime of the last fling release inside the small-window/split hot zone. */
    private static volatile long sFlingHotReleaseMs = 0L;
    private static final long FLING_NO_COMMIT_WINDOW_MS = 1500L;

    public static void install(ClassLoader cl) {
        final Class<?> mgrCls = XposedHelpers.findClass(MGR_CLS, cl);
        Class<?> handlerCls = XposedHelpers.findClass(HANDLER_CLS, cl);

        // (1) 判定阈值: 过渡态 0.8/0.75 (不区分横竖屏); 椭圆纵半轴
        //     横屏 0.3H, 竖屏 0.5H (横半轴 mShapeHotX = 0.5W 不变).
        XposedHelpers.findAndHookMethod(mgrCls, "updateLayoutParams",
                int.class, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_ONESTEP_THRESHOLD)) {
                            return;
                        }
                        try {
                            int h = XposedHelpers.getIntField(param.thisObject, "mScreenHeight");
                            int w = XposedHelpers.getIntField(param.thisObject, "mScreenWidth");
                            boolean landscape = w > h;
                            float shapeYRatio = landscape ? 0.3f : 0.5f;
                            XposedHelpers.setFloatField(param.thisObject, "mShowBgHotY", h * 0.8f);
                            XposedHelpers.setFloatField(param.thisObject, "mShowIconHotY", h * 0.75f);
                            XposedHelpers.setFloatField(param.thisObject, "mScaleHotY", h * 0.75f);
                            XposedHelpers.setFloatField(param.thisObject, "mShapeHotY", h * shapeYRatio);
                            Port.log("OneStepThresholds: thresholds updated h=" + h
                                    + " bg=" + (h * 0.8f) + " icon=" + (h * 0.75f)
                                    + " shapeY=" + (h * shapeYRatio)
                                    + (landscape ? " (landscape)" : " (portrait)"));
                        } catch (Throwable t) {
                            Port.log("OneStepThresholds updateLayoutParams failed", t);
                        }
                    }
                });

        // (2) 松手速度判定: 快速滑出 (fling, 返回桌面的速度) 时不允许
        //     小窗/分屏 commit, 仅停顿/慢速释放 (进入后台的速度) 保留.
        XposedHelpers.findAndHookMethod(handlerCls, "handleNormalGestureEnd",
                float.class, boolean.class, PointF.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_ONESTEP_THRESHOLD)) {
                            return;
                        }
                        boolean fling = ((Boolean) param.args[1]).booleanValue();
                        if (!fling) {
                            // 停顿/慢速释放: 清掉上一轮 fling 标记, commit 按位置态正常生效.
                            sFlingHotReleaseMs = 0L;
                            return;
                        }
                        try {
                            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
                            Object inHot = XposedHelpers.callMethod(mgr, "isInFloatOrSplitWindowHot");
                            if (inHot instanceof Boolean && ((Boolean) inHot).booleanValue()) {
                                sFlingHotReleaseMs = SystemClock.uptimeMillis();
                                XposedHelpers.callMethod(mgr, "setUpGone");
                                Port.log("OneStepThresholds: fling release in hot zone -> block commit");
                            }
                        } catch (Throwable t) {
                            Port.log("OneStepThresholds gestureEnd check failed", t);
                        }
                    }
                });

        // (3) commit 判定窗口: fling 释放后的热区读值一律视为 false
        //     (setUpGone 异步, 同步判定读值只能在这里拦).
        XC_MethodHook noHotWhenFling = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_ONESTEP_THRESHOLD)) {
                    return;
                }
                long t = sFlingHotReleaseMs;
                if (t == 0L || SystemClock.uptimeMillis() - t >= FLING_NO_COMMIT_WINDOW_MS) {
                    return;
                }
                param.setResult(Boolean.FALSE);
            }
        };
        XposedHelpers.findAndHookMethod(mgrCls, "isInFloatOrSplitWindowHot", noHotWhenFling);
        XposedHelpers.findAndHookMethod(mgrCls, "isInSplitWindowHot", noHotWhenFling);
        XposedHelpers.findAndHookMethod(mgrCls, "isInFloatWindowHot", noHotWhenFling);

        Port.log("OneStepThresholds installed");
    }

    private OneStepThresholds() {}
}
