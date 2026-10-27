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
    public static final String KEY_HIBOARD_SINK = "hn_port_hiboard_sink"; // 1 = 进入负一屏时桌面下沉 (缩小+淡出, 资源库同款)
    public static final String KEY_ONESTEP_THRESHOLD = "hn_port_onestep_threshold"; // 1 = swipe小窗/分屏阈值 0.8/0.75 横0.3H竖0.5H, 松手需停顿
    public static final String KEY_ONESTEP_PX = "hn_port_onestep_px"; // float = 竖屏椭圆热区横半轴比例 (0.1-0.5, 默认 0.4)
    public static final String KEY_ONESTEP_PY = "hn_port_onestep_py"; // float = 竖屏椭圆热区纵半轴比例 (0.1-0.6, 默认 0.55)
    public static final String KEY_ONESTEP_LX = "hn_port_onestep_lx"; // float = 横屏椭圆热区横半轴比例 (0.1-0.5, 默认 0.4)
    public static final String KEY_ONESTEP_LY = "hn_port_onestep_ly"; // float = 横屏椭圆热区纵半轴比例 (0.1-0.6, 默认 0.5)
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
    public static final String KEY_CC_GLOBAL_SWIPE = "hn_port_cc_global_swipe"; // 1 = 通知/控制中心全屏范围横滑切换 (V17 样式, 滑条卡/通知行除外)
    public static final String KEY_KG_HIDE_STEPS = "hn_port_kg_hide_steps"; // 1 = 隐藏锁屏左下角步数
    public static final String KEY_KG_NO_TOOLBOX = "hn_port_kg_no_toolbox"; // 1 = 禁用锁屏底部上滑工具菜单
    public static final String KEY_KG_SKIP_FACE_ANIM = "hn_port_kg_skip_face_anim"; // 1 = 跳过密码界面面部解锁成功动画
    public static final String KEY_KG_SINGLE_TAP_WAKE = "hn_port_kg_single_tap_wake"; // 1 = 单击亮屏 (轻触屏幕以唤醒)
    public static final String KEY_NAV_HANDLE_NO_FOLLOW = "hn_port_nav_handle_no_follow"; // 1 = 禁用底部手势提示条跟手移动
    public static final String KEY_NAV_HANDLE_HIDE = "hn_port_nav_handle_hide"; // 1 = 隐藏手势提示条, 保留底部抬升(导航栏高度不变)
    public static final String KEY_FF_GESTURE = "hn_port_ff_gesture"; // 1 = 悬浮窗手势优化: 底部上滑挂起(不进圆球) + 拖窗贴边最小化(替换横滑甩动判定)
    public static final String KEY_FF_ANIM = "hn_port_ff_anim"; // 1 = 悬浮窗动效优化: 最小化/挂起 spring(ζ=0.89, 初速度) + 全屏过渡 live 起播连贯
    public static final String KEY_CC_STATUSBAR_CONTINUITY = "hn_port_cc_statusbar_continuity"; // 1 = 状态栏连贯: 进入控制中心/通知中心时状态栏随下拉跟手移动 (V17)
    public static final String KEY_ADV_UNLOCK_SCALE = "hn_port_adv_unlock_scale"; // float = 自定义解锁动画起始尺寸 (0.5-4), 未设置(NaN) = stock
    public static final String KEY_ADV_UNLOCK_ANCHOR = "hn_port_adv_unlock_anchor"; // float = 自定义解锁动画缩放锚点 (0-1 屏高比例), 默认 0.3
    public static final String KEY_ADV_UNLOCK_DELAY = "hn_port_adv_unlock_delay"; // float = 自定义解锁动画分组透明度延时 (0-60ms), 未设置(NaN) = stock(30ms)

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

    /** Reads a Settings.Global float flag; returns def when unset or unreadable. */
    public static float getFloat(String key, float def) {
        Context c = appContext();
        if (c == null) {
            return def;
        }
        try {
            return Settings.Global.getFloat(c.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private Port() {}
}
