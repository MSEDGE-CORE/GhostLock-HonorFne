package com.wxz.hnlauncherport.systemui;

import android.os.Handler;
import android.os.Looper;

import com.wxz.hnlauncherport.Port;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 灵动胶囊回桌反馈守卫 + 200ms 对账 (归属: 连续切换应用 KEY_PARALLEL).
 *
 * V14 缺陷 (17:10 日志实锤): swipe-up 回桌反馈链 mPlayFeedBackAnimationRunnable →
 * reShowCapsuleView 直接复用残留 304x79 展开态布局的 view 实例加回, 无前置守卫、
 * 无事后纠正; swipe-up 返桌 + 打开应用循环时胶囊卡展开不收起.
 *
 * V17 修法 (CapsuleClusterController$1 case 0 / updateCapsuleRecordForeground):
 *   1. 前置守卫 — 目标包已可见时反馈 reShow 无意义, 拦截;
 *   2. postDelayed(200ms) 全量对账 — 中间态最多存活 200ms.
 *
 * 本 hook 在 V14 上以同语义实现, 挂在回桌反馈链唯一汇聚点
 * BaseCapsuleStatusBarHelper#reShowCapsuleView(CapsuleRecord, boolean):
 *   guard: record.pkg 可见 (ActivityManagerEx.getVisiblePackages(), 失败回退
 *          helper.mVisiblePackageNames) → 跳过 reShow;
 *   reconcile: 放行的 reShow 200ms 后复查可见包, 仍可见 → hideCapsuleView 隐藏.
 * 隐藏复用宿主公开 hideCapsuleView(record, key), 不碰动画队列、不改 view 布局.
 */
public final class CapsuleReconcile {
    private static final String HELPER_CLASS =
            "com.hihonor.systemui.dynamicserver.helper.BaseCapsuleStatusBarHelper";
    private static final String RECORD_CLASS =
            "com.hihonor.systemui.dynamicserver.record.CapsuleRecord";
    private static final String AMEX_CLASS = "com.hihonor.android.app.ActivityManagerEx";
    private static final long RECONCILE_DELAY_MS = 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Class<?> sAmEx;
    private static Runnable sPending;
    private static boolean sAmExBroken = false;

    public static void install(ClassLoader cl) {
        Class<?> helper = XposedHelpers.findClass(HELPER_CLASS, cl);
        Class<?> record = XposedHelpers.findClass(RECORD_CLASS, cl);
        try {
            sAmEx = XposedHelpers.findClass(AMEX_CLASS, cl);
        } catch (Throwable t) {
            Port.log("CapsuleReconcile: ActivityManagerEx not found, fallback to helper field", t);
            sAmEx = null;
        }
        XposedHelpers.findAndHookMethod(helper, "reShowCapsuleView", record, boolean.class,
                new XC_MethodHook() {
                    private boolean mFirstLog = true;

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (mFirstLog) {
                            mFirstLog = false;
                            Port.log("CapsuleReconcile hook live, parallel=" + Port.on(Port.KEY_PARALLEL)
                                    + " amex=" + (sAmEx != null));
                        }
                        if (!Port.on(Port.KEY_PARALLEL)) {
                            return;
                        }
                        Object rec = param.args[0];
                        String pkg = rec == null ? null : (String) XposedHelpers.getObjectField(rec, "pkg");
                        if (pkg == null || pkg.isEmpty()) {
                            return;
                        }
                        if (isVisible(param.thisObject, pkg)) {
                            Port.log("CapsuleReconcile guard block reShow pkg=" + pkg);
                            param.setResult(null);
                            return;
                        }
                        scheduleReconcile(param.thisObject, rec, pkg);
                    }
                });
        Port.log("CapsuleReconcile installed");
    }

    private static void scheduleReconcile(final Object helper, final Object rec, final String pkg) {
        if (sPending != null) {
            MAIN.removeCallbacks(sPending);
        }
        sPending = new Runnable() {
            @Override
            public void run() {
                sPending = null;
                if (!isVisible(helper, pkg)) {
                    return;
                }
                String key = (String) XposedHelpers.getObjectField(rec, "key");
                Port.log("CapsuleReconcile hide pkg=" + pkg + " key=" + key);
                try {
                    XposedHelpers.callMethod(helper, "hideCapsuleView", rec, key);
                } catch (Throwable t) {
                    Port.log("CapsuleReconcile hide failed pkg=" + pkg, t);
                }
            }
        };
        MAIN.postDelayed(sPending, RECONCILE_DELAY_MS);
    }

    private static boolean isVisible(Object helper, String pkg) {
        if (sAmEx != null && !sAmExBroken) {
            try {
                List<?> visibles = (List<?>) XposedHelpers.callStaticMethod(sAmEx, "getVisiblePackages");
                return visibles != null && visibles.contains(pkg);
            } catch (Throwable t) {
                sAmExBroken = true;
                Port.log("CapsuleReconcile getVisiblePackages failed, fallback to helper field", t);
            }
        }
        try {
            List<?> field = (List<?>) XposedHelpers.getObjectField(helper, "mVisiblePackageNames");
            return field != null && field.contains(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private CapsuleReconcile() {}
}
