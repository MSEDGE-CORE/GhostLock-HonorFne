package com.wxz.hnlauncherport;

import android.content.Context;
import android.provider.Settings;

import de.robv.android.xposed.XposedBridge;

public final class Port {
    public static final String TAG = "HnLauncherPort";

    public static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    public static void log(String msg, Throwable t) {
        XposedBridge.log("[" + TAG + "] " + msg);
        XposedBridge.log(t);
    }

    // Feature switches (Settings.Global, readable by launcher process)
    public static final String KEY_MOTION = "hn_port_motion";       // 1 = MotionEngine-style return home
    public static final String KEY_PARALLEL = "hn_port_parallel";   // 1 = 连续切换应用: click new icon kills prev app anim immediately + canBreak bypass
    public static final String KEY_BLUR = "hn_port_blur";           // 1 = force transition blur on all models
    public static final String KEY_ICON_HIDE = "hn_port_icon_hide"; // 1 = hide launching icon during app-open anim (large-folder fix)
    public static final String KEY_WIN_SCALE = "hn_port_win_scale"; // 1 = V17-style swipe-up window trajectory (converge toward W/2, 0.3H)
    public static final String KEY_KEEP_HIBOARD = "hn_port_keep_hiboard"; // 1 = 返家保持负一屏/资源库, 进后台才退出
    public static final String KEY_ONESTEP_THRESHOLD = "hn_port_onestep_threshold"; // 1 = swipe小窗/分屏阈值 0.8/0.75 横0.3H竖0.5H, 松手需停顿
    public static final String KEY_DISABLE_ICON_CARD = "hn_port_disable_icon_card"; // 1 = 禁用桌面上滑图标弹卡片 (资源库不受影响)
    public static final String KEY_ADV_UNLOCK_ANIM = "hn_port_adv_unlock_anim"; // 1 = 解锁图标圆形扩散动画 (高级解锁动画)
    public static final String KEY_ICON_NO_LABEL = "hn_port_icon_no_label"; // 1 = 图标无字: 隐藏桌面图标文本
    public static final String KEY_WIN_DEBUG = "hn_port_win_debug"; // 1 = per-frame debug lines (logcat tag HnWFS, throttled)
    public static final String KEY_AOD_FIX_POS = "hn_port_aod_fix_pos"; // 1 = AOD 固定位置: 首移+80 后不再自动移动
    public static final String KEY_SB_NO_SHRINK = "hn_port_sb_no_shrink"; // 1 = 灵动胶囊启用时状态栏不缩到 0.88
    public static final String KEY_SB_NO_HIDE = "hn_port_sb_no_hide"; // 1 = 灵动胶囊启用时状态栏杂项图标不隐藏
    public static final String KEY_CAPSULE_ALWAYS = "hn_port_capsule_always"; // 1 = 灵动胶囊不自动收缩 (保持带背景胶囊视图)
    public static final String KEY_CC_SPRING_VEL = "hn_port_cc_spring_vel"; // 1 = 控制中心动效优化: 横滑松手 spring 初速度=手指速度 + 卡片放大锚点(上排4张角锚点, 下排3张顶边中点)
    public static final String KEY_CC_REAL_BLUR = "hn_port_cc_real_blur"; // 1 = 控制中心实时窗口模糊 (alpha 跟手半径)

    private static volatile Context sAppCtx;

    /** Lazily resolved application context (hidden ActivityThread, no import). */
    public static Context appContext() {
        Context c = sAppCtx;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context r = (Context) at.getMethod("currentApplication").invoke(null);
            if (r != null) {
                sAppCtx = r;
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Cheap feature-flag read: Settings.Global keeps a per-process name-value
     * cache, so repeated getInt calls do not hit binder after the first fetch.
     */
    public static boolean on(String key) {
        Context c = appContext();
        if (c == null) {
            return false;
        }
        return enabled(c, key);
    }

    public static boolean enabled(Context ctx, String key) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), key, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private Port() {}
}
