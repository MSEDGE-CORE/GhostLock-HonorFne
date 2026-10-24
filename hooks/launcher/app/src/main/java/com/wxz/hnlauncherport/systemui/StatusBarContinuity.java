package com.wxz.hnlauncherport.systemui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.animation.PathInterpolator;

import com.wxz.hnlauncherport.Port;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 状态栏连贯 (KEY_CC_STATUSBAR_CONTINUITY): 下拉进入控制中心/通知中心时,
 * 面板现成状态栏 (HwNotchStatusBar, "SharedStatusBar", 手机上 CC/NC 共用)
 * 从真状态栏位置随手指连续移动到面板自然位置.
 *
 * offset (两层移动距离, V17 getDistanceY 语义, V14 几何):
 *  = 面板栏电池自然屏幕Y − 真实栏电池屏幕Y.
 *  两栏 View 都在窗口 Y=0, 视觉差全在栏内部 (分机型适配 = 摄像头位置):
 *   - 中置摄像头 (胶囊): 面板栏高 max(N,S)+N 且内容行 paddingTop 运行时 += N;
 *   - 左/右置摄像头: 两个 +N 均不生效 → offset ≈ 0 (不下移).
 *  - 横屏: 每次 engage 重新实测 → 无向下 offset 时自然退化为仅横向收窄.
 *
 * 横向收窄 (V17 mLeft/mRightDistanceX 语义):
 *  engage 实测 左组 operator_name_container / 右组 system_icon_area(电池)
 *  与真实栏对应元素的 X 差, 跟手期 translationX 骑乘 (真实位→自然位收敛),
 *  settle 收敛 0, collapse/drop 回到真实栏位置.
 *
 * 时钟半层: 真实栏时钟 (CC 栏无时钟) 的像素级 TextView 拷贝, 挂在
 *  mQuickSettingsContainer (FrameLayout, 栏的父容器) 顶层, 骑乘整栏运动,
 *  settle 阈值处淡出, collapse/drop 淡回 — 停靠态与真实栏完全一致.
 *  stock startAnimationSet 会隐藏无 tag 子 View, settle/collapse hook
 *  同栈 (createStatusBarAnimation 在遍历之后调用) 重新 VISIBLE 对抗.
 *
 * 淡入淡出 (无任何整栏显示/隐藏淡入淡出, 唯一淡入 = 阈值切换图标组):
 *  - 整栏: alpha 恒 1, engage 即时显示 / 硬切隐藏 (无淡入淡出).
 *  - 阈值切换: 跟手期间隐藏 operator_name_container / statusIcons /
 *    statusIcons_more / network_area_more / network_area_icons (alpha 0),
 *    settle 时 当前值→原值 淡入 (100ms, delay 50ms); drop 回位同样淡回.
 *  - 时钟: 跟手可见, settle 淡出 (150ms, delay 50ms), collapse/drop 淡回.
 *
 * 收起结尾硬切时机: collapse 漂移结束不隐藏 — 停靠在真实栏位置
 *  (WAITING_REAL, 内容与真实栏一致), 等真实栏可重现的同帧
 *  (PanelBar.onPanelCollapsed: 面板 fraction 已到 0, scrim 已收回)
 *  硬切隐藏 → 无空缺. 若 onPanelCollapsed 先于漂移结束到达
 *  (sSwapPending) 则漂移结束即硬切. drop (未过阈值) 面板收回快,
 *  漂移结束即隐藏 (真实栏未被 scrim 遮挡, 无空缺).
 *
 * 开-关循环 (不松手): 收起/展开触发点 (onPanelTouch 的 isCollapse/
 *  isExpand band, showingType 门控) 每次穿越恰好触发一次 stock 一次性
 *  路径 → settle/collapse 走与首次完全相同的 hook; engage 不再看
 *  mIsPanelChangedWhenTracking (松手前恒 true, 会挡住第二次开),
 *  且可从 COLLAPSING/DROPPING/WAITING_REAL 中断接管 (取消在飞动画,
 *  从当前位置续接) → 第二次开起保持连贯.
 */
public final class StatusBarContinuity {
    private static final String TAG = "SBContinuity";
    private static final String PANEL_IMPL_CLS =
            "com.android.systemui.statusbar.phone.HnPanelViewControllerImpl";
    private static final String REAL_BAR_CLS =
            "com.android.systemui.statusbar.phone.HwPhoneStatusBarView";
    private static final String NOTCH_UTILS_CLS =
            "com.android.systemui.utils.HwNotchUtils";
    private static final String PHONE_SB_UTILS_CLS =
            "com.android.systemui.utils.PhoneStatusBarUtils";

    private static final int IDLE = 0;
    private static final int FOLLOWING = 1;
    private static final int SETTLING = 2;
    private static final int SETTLED = 3;
    private static final int COLLAPSING = 4;
    private static final int DROPPING = 5;
    /** 收起漂移结束: 停靠真实栏位置, 等待 onPanelCollapsed 同帧硬切. */
    private static final int WAITING_REAL = 6;

    private static final long SETTLE_MS = 200L;
    private static final long DROP_MS = 200L;
    private static final long MISC_FADE_MS = 100L;
    private static final long MISC_FADE_DELAY_MS = 50L;
    private static final long CLOCK_FADE_MS = 150L;
    private static final long CLOCK_FADE_DELAY_MS = 50L;
    private static final TimeInterpolator FOSI =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /** 跟手期间隐藏、阈值 (settle) 时淡入的"运营商/杂项图标"组. */
    private static final String[] MISC_NAMES = {
            "operator_name_container",
            "statusIcons",
            "statusIcons_more",
            "network_area_more",
            "network_area_icons",
    };

    private static volatile int sState = IDLE;
    private static volatile float sOffset = 0f;
    private static volatile boolean sOffsetValidated = false;
    private static WeakReference<View> sRealBar = new WeakReference<View>(null);
    private static WeakReference<View> sTarget = new WeakReference<View>(null);
    /** 当前相位动画 (settle/collapse/drop), 换相/engage 时取消. */
    private static AnimatorSet sPhaseAnim;
    /** onPanelCollapsed 先于收起漂移结束到达: 漂移结束即硬切. */
    private static boolean sSwapPending = false;
    private static Field sTouchAreaField;
    private static Class<?> sNotchUtilsCls;
    private static Class<?> sPhoneSbUtilsCls;
    private static int sBatteryId;
    private static int sClockId;
    private static int sOperatorId;
    private static int sIconAreaId;
    private static int[] sMiscIds;
    private static final ArrayList<View> sMisc = new ArrayList<View>();
    private static float[] sMiscBase = new float[0];
    /** 横向收窄: 左组 (operator) / 右组 (system_icon_area, 含电池). */
    private static WeakReference<View> sLeftGrp = new WeakReference<View>(null);
    private static WeakReference<View> sRightGrp = new WeakReference<View>(null);
    private static float sDeltaLeft = 0f;
    private static float sDeltaRight = 0f;
    /** 时钟半层 (真实栏时钟像素拷贝) 及其在容器内的基准 translation. */
    private static WeakReference<TextView> sClockLayer = new WeakReference<TextView>(null);
    private static WeakReference<ViewGroup> sClockParent = new WeakReference<ViewGroup>(null);
    private static float sClockBaseTx = 0f;
    private static float sClockBaseTy = 0f;

    public static void install(ClassLoader cl) {
        final Class<?> panelImpl = XposedHelpers.findClass(PANEL_IMPL_CLS, cl);

        // 真状态栏视图捕获 (电池锚点测量基准 + onPanelCollapsed 硬切点)
        final Class<?> realBarCls = XposedHelpers.findClass(REAL_BAR_CLS, cl);
        XposedBridge.hookAllConstructors(realBarCls, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                sRealBar = new WeakReference<View>((View) param.thisObject);
            }
        });

        // 跟手驱动: 面板每个 MOVE 步回调 (f = 触摸 Y)
        XposedHelpers.findAndHookMethod(panelImpl, "onPanelTouch", float.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            onMove(param);
                        } catch (Throwable t) {
                            Port.log(TAG + ": onMove failed", t);
                        }
                    }
                });

        // 展开 settle / 收起一次性 替换
        XposedHelpers.findAndHookMethod(panelImpl, "createStatusBarAnimation",
                View.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            onStatusBarAnim(param);
                        } catch (Throwable t) {
                            Port.log(TAG + ": statusbar anim failed", t);
                        }
                    }
                });

        // 松手 drop-back (ACTION_UP/CANCEL, 未达展开阈值时面板 fling 回收)
        XposedHelpers.findAndHookMethod(panelImpl, "resetCoordinates", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    onFingerUp(param);
                } catch (Throwable t) {
                    Port.log(TAG + ": fingerUp failed", t);
                }
            }
        });

        // 收起结尾硬切点: 面板 fraction 到 0 且未在跟手 (scrim 已收回,
        // 真实栏可重现) 的同帧. (PanelBar.onPanelCollapsed, 真实栏上的 override 链)
        XposedHelpers.findAndHookMethod(realBarCls, "onPanelCollapsed", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    onRealBarCollapsed();
                } catch (Throwable t) {
                    Port.log(TAG + ": swap failed", t);
                }
            }
        });

        Port.log("StatusBarContinuity installed");
    }

    // ---------------------------------------------------------------- driver

    private static void onMove(XC_MethodHook.MethodHookParam param) {
        boolean enabled = Port.on(Port.KEY_CC_STATUSBAR_CONTINUITY);
        if (!enabled) {
            if (sState == FOLLOWING || sState == COLLAPSING
                    || sState == DROPPING || sState == WAITING_REAL) {
                View bar = sTarget.get();
                if (bar != null) {
                    hideBar(bar);
                }
                sState = IDLE;
            }
            return;
        }
        float y = (Float) param.args[0];
        if (sState == FOLLOWING) {
            View bar = sTarget.get();
            if (bar == null) {
                sState = IDLE;
                return;
            }
            follow(param.thisObject, bar, y);
            return;
        }
        if (sState == IDLE || sState == COLLAPSING
                || sState == DROPPING || sState == WAITING_REAL) {
            tryEngage(param.thisObject, y);
        }
        // SETTLING/SETTLED: 展开态, 由一次性 hook 接管
    }

    /**
     * engage 判定 (新手势, 或 开-关循环 中断接管). 不检查
     * mIsPanelChangedWhenTracking: 不松手的第二次开它恒为 true.
     */
    private static void tryEngage(Object ctrl, float y) {
        View bar = pickBar(ctrl);
        if (bar == null) {
            return;
        }
        if (showingType(ctrl) != 0) {
            return; // 已有面板在显示 (切换/横滑/收起路径), 无连贯
        }
        float initialY = XposedHelpers.getFloatField(ctrl, "mInitialY");
        float touchLimit = XposedHelpers.getFloatField(ctrl, "mHeightForTouchExpand");
        if (touchLimit <= 0f || initialY > touchLimit) {
            return; // 非状态栏区域起手 (stock 触发展开同样不成立)
        }
        View panelView = (View) XposedHelpers.getObjectField(ctrl, "mPanelView");
        if (panelView == null
                || ((Boolean) XposedHelpers.callMethod(panelView, "isKeyguardShowing")).booleanValue()) {
            return; // 不含锁屏侧
        }
        if (!canSwipe(ctrl)) {
            return; // 引导中, getExpectHeight 恒 0, 面板不会展开
        }
        if (y - initialY <= 0f) {
            return;
        }
        cancelPhaseAnim();
        engage(bar);
        follow(ctrl, bar, y);
    }

    private static void follow(Object ctrl, View bar, float y) {
        if (showingType(ctrl) != 0) {
            return; // 展开一次性已接管 (同一 MOVE 内 stock 先触发, 状态已切)
        }
        if (!canSwipe(ctrl)) {
            return;
        }
        if (!sOffsetValidated) {
            // engage 后布局已跑过 → 直接测量生效, 静默校正
            float direct = measureOffsetDirect(bar);
            if (direct > 0f) {
                sOffset = direct;
                sOffsetValidated = true;
                Port.log(TAG + ": offset validated=" + direct);
            }
        }
        float initialY = XposedHelpers.getFloatField(ctrl, "mInitialY");
        float pull = y - initialY;
        float threshold = XposedHelpers.getIntField(ctrl, "mDistanceForExpand");
        float p = (threshold > 0f && pull > 0f) ? Math.min(pull / threshold, 1f) : 0f;
        ride(bar, p);
    }

    /** p: 0=真实栏位置, 1=面板自然位. 整栏 ty + 横向收窄 + 时钟半层 一起骑乘. */
    private static void ride(View bar, float p) {
        bar.setTranslationY(-sOffset * (1f - p));
        View g = sLeftGrp.get();
        if (g != null && sDeltaLeft != 0f) {
            g.setTranslationX(sDeltaLeft * (1f - p));
        }
        g = sRightGrp.get();
        if (g != null && sDeltaRight != 0f) {
            g.setTranslationX(sDeltaRight * (1f - p));
        }
        TextView clock = sClockLayer.get();
        if (clock != null) {
            clock.setVisibility(View.VISIBLE); // 对抗 stock 遍历隐藏无 tag 子 View
            clock.setTranslationY(sClockBaseTy + sOffset * p);
        }
    }

    /** engage: 量 offset/横向收窄, bar 就位于 -offset, 杂项图标隐藏, 时钟半层建立. */
    private static void engage(View bar) {
        sOffset = resolveOffset(bar);
        sTarget = new WeakReference<View>(bar);
        restoreMisc();   // 先复位到上次的原值, 防止中断接管时把中间值当原值
        captureMisc(bar);
        hideMisc();
        captureDeltas(bar);
        buildClockLayer(bar);
        bar.setAlpha(1f);
        bar.setVisibility(View.VISIBLE);
        ride(bar, 0f);
        sState = FOLLOWING;
        Port.log(TAG + ": engage offset=" + sOffset + " validated=" + sOffsetValidated
                + " dxL=" + sDeltaLeft + " dxR=" + sDeltaRight);
    }

    // ----------------------------------------------------------------- offset

    private static float resolveOffset(View bar) {
        float direct = measureOffsetDirect(bar);
        if (direct > 0f) {
            sOffsetValidated = true;
            return direct;
        }
        sOffsetValidated = false;
        return fallbackOffset(bar);
    }

    /** 直接测量: 面板电池 layout 位置 GONE 后保留 (mTop 不清), 不受 translation 影响. */
    private static float measureOffsetDirect(View bar) {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return -1f;
            }
            View realBat = findBattery(realBar);
            View panelBat = findBattery(bar);
            if (realBat == null || panelBat == null) {
                return -1f;
            }
            int[] a = new int[2];
            int[] b = new int[2];
            realBat.getLocationOnScreen(a);
            panelBat.getLocationOnScreen(b);
            if (b[1] <= 0) {
                return -1f; // 面板栏尚未布局过 (首次手势)
            }
            float off = b[1] - a[1];
            return off > 0f ? off : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /**
     * 首次手势兜底 (面板栏未布局过): V14 几何 —
     * 面板电池自然Y = 运行时 paddingTop + (面板栏高 − paddingTop − 电池高)/2.
     * 中置摄像头 (胶囊): paddingTop 与栏高各含胶囊高 → offset ≈ 胶囊高;
     * 左/右置: 运行时值即无加成 → offset ≈ 0. 分机型自动生效.
     */
    private static float fallbackOffset(View bar) {
        try {
            Context ctx = bar.getContext();
            View realBar = sRealBar.get();
            View realBat = realBar == null ? null : findBattery(realBar);
            int padTop = callStaticInt(phoneSbUtilsCls(ctx), "getNotchStatusBarPaddingTop", ctx);
            int p = callStaticInt(notchUtilsCls(ctx), "getNotchPanelTopMargin", ctx, Boolean.FALSE);
            float realY;
            if (realBat != null) {
                int[] a = new int[2];
                realBat.getLocationOnScreen(a);
                realY = a[1];
            } else {
                realY = callStaticInt(notchUtilsCls(ctx), "getStatusBarHeight", ctx) / 2f;
            }
            int batH = realBat == null ? 0 : realBat.getHeight();
            float panelBatY = padTop + (p - padTop - batH) / 2f;
            float off = panelBatY - realY;
            return off > 0f ? off : 0f;
        } catch (Throwable t) {
            return sOffset;
        }
    }

    /** settle 结束刷新 (栏已 VISIBLE 且布局于自然位, 测量条件最优). */
    private static void refreshOffset(View bar) {
        float direct = measureOffsetDirect(bar);
        if (direct > 0f) {
            sOffset = direct;
            sOffsetValidated = true;
        }
        Port.log(TAG + ": settle-end offset=" + sOffset + " validated=" + sOffsetValidated);
    }

    // -------------------------------------------------- 横向收窄 + 时钟半层

    /** 横向收窄量 (V17 mLeft/mRightDistanceX 语义): 真实栏元素 X − 面板栏元素 X. */
    private static void captureDeltas(View bar) {
        sDeltaLeft = 0f;
        sDeltaRight = 0f;
        sLeftGrp = new WeakReference<View>(null);
        sRightGrp = new WeakReference<View>(null);
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return;
            }
            ensureIds(bar.getContext());
            float maxDelta = bar.getContext().getResources()
                    .getDisplayMetrics().widthPixels / 2f;

            // 右组: system_icon_area (含电池), 以电池为锚
            View panelArea = sIconAreaId == 0 ? null : bar.findViewById(sIconAreaId);
            if (panelArea != null) {
                sRightGrp = new WeakReference<View>(panelArea);
            }
            View realBat = findBattery(realBar);
            View panelBat = findBattery(bar);
            if (realBat != null && panelBat != null) {
                int[] a = new int[2];
                int[] b = new int[2];
                realBat.getLocationOnScreen(a);
                panelBat.getLocationOnScreen(b);
                float d = a[0] - b[0];
                if (b[0] > 0 && Math.abs(d) < maxDelta && d != 0f) {
                    sDeltaRight = d;
                }
            }

            // 左组: operator_name_container (两侧都可见才有对齐意义)
            if (sOperatorId != 0) {
                View realOp = realBar.findViewById(sOperatorId);
                View panelOp = bar.findViewById(sOperatorId);
                if (realOp != null && panelOp != null
                        && realOp.getVisibility() == View.VISIBLE
                        && panelOp.getVisibility() == View.VISIBLE
                        && realOp.getWidth() > 0 && panelOp.getWidth() > 0) {
                    sLeftGrp = new WeakReference<View>(panelOp);
                    int[] a = new int[2];
                    int[] b = new int[2];
                    realOp.getLocationOnScreen(a);
                    panelOp.getLocationOnScreen(b);
                    float d = a[0] - b[0];
                    if (Math.abs(d) < maxDelta && d != 0f) {
                        sDeltaLeft = d;
                    }
                }
            }
        } catch (Throwable t) {
            Port.log(TAG + ": captureDeltas failed", t);
        }
    }

    /**
     * 时钟半层: 真实栏时钟 (HwStatusBarClock, id=clock) 的像素级 TextView
     * 拷贝, 挂到栏的父容器 (mQuickSettingsContainer, FrameLayout) 顶层,
     * translation 定位到真实时钟位置, 之后随整栏运动骑乘.
     */
    private static void buildClockLayer(View bar) {
        removeClockLayer();
        sClockBaseTx = 0f;
        sClockBaseTy = 0f;
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return;
            }
            ensureIds(bar.getContext());
            if (sClockId == 0) {
                return;
            }
            View realClockV = realBar.findViewById(sClockId);
            if (!(realClockV instanceof TextView)
                    || realClockV.getVisibility() != View.VISIBLE
                    || realClockV.getWidth() <= 0 || realClockV.getHeight() <= 0) {
                return; // 真实栏时钟不可见 (被关/未加载): 无半层
            }
            TextView realClock = (TextView) realClockV;
            if (!(bar.getParent() instanceof FrameLayout)) {
                return;
            }
            FrameLayout parent = (FrameLayout) bar.getParent();

            TextView tv = new TextView(parent.getContext());
            tv.setText(realClock.getText());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, realClock.getTextSize());
            tv.setTextColor(realClock.getCurrentTextColor());
            tv.setTypeface(realClock.getTypeface());
            tv.setPaddingRelative(realClock.getPaddingStart(), 0,
                    realClock.getPaddingEnd(), 0);
            tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            tv.setSingleLine(true);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, realClock.getHeight());
            parent.addView(tv, lp);

            int[] pc = new int[2];
            int[] cc = new int[2];
            parent.getLocationOnScreen(pc);
            realClock.getLocationOnScreen(cc);
            sClockBaseTx = cc[0] - pc[0] - parent.getPaddingLeft();
            sClockBaseTy = cc[1] - pc[1] - parent.getPaddingTop();
            tv.setTranslationX(sClockBaseTx);
            tv.setTranslationY(sClockBaseTy);
            tv.setAlpha(realClock.getAlpha() <= 0f ? 1f : realClock.getAlpha());
            sClockLayer = new WeakReference<TextView>(tv);
            sClockParent = new WeakReference<ViewGroup>(parent);
        } catch (Throwable t) {
            Port.log(TAG + ": clock layer failed", t);
        }
    }

    private static void removeClockLayer() {
        TextView clock = sClockLayer.get();
        sClockLayer = new WeakReference<TextView>(null);
        if (clock != null) {
            ViewGroup parent = sClockParent.get();
            if (parent != null && clock.getParent() == parent) {
                parent.removeView(clock);
            }
        }
        sClockParent = new WeakReference<ViewGroup>(null);
    }

    private static void showClockLayer() {
        TextView clock = sClockLayer.get();
        if (clock != null) {
            clock.setVisibility(View.VISIBLE);
        }
    }

    private static void resetGroupX() {
        View g = sLeftGrp.get();
        if (g != null) {
            g.setTranslationX(0f);
        }
        g = sRightGrp.get();
        if (g != null) {
            g.setTranslationX(0f);
        }
    }

    // --------------------------------------------------- settle / collapse

    private static void onStatusBarAnim(XC_MethodHook.MethodHookParam param) {
        if (!Port.on(Port.KEY_CC_STATUSBAR_CONTINUITY)) {
            return;
        }
        View view = (View) param.args[0];
        View target = sTarget.get();
        if (target == null || view != target) {
            return; // 非本连贯接管的状态栏 (或 stock 其它路径)
        }
        if ((Boolean) param.args[1]) {
            onSettle(view, param);
        } else {
            onCollapse(view, param);
        }
    }

    /** 展开 settle: 跟手值 -> 自然位 (无整栏淡入); 杂项图标淡入, 时钟半层淡出. */
    private static void onSettle(final View bar, XC_MethodHook.MethodHookParam param) {
        if (sState != FOLLOWING && sState != SETTLING
                && sState != COLLAPSING && sState != DROPPING) {
            return; // 未跟手 (锁屏 shade / 补偿路径), stock 淡入
        }
        sState = SETTLING;
        cancelPhaseAnim();
        bar.setVisibility(View.VISIBLE); // 同帧对抗 stock 前置 GONE
        showClockLayer();                // 对抗 stock 遍历隐藏无 tag 子 View
        ArrayList<Animator> anims = new ArrayList<Animator>();
        anims.add(objAnim(bar, "translationY", bar.getTranslationY(), 0f, SETTLE_MS));
        View g = sLeftGrp.get();
        if (g != null && sDeltaLeft != 0f) {
            anims.add(objAnim(g, "translationX", g.getTranslationX(), 0f, SETTLE_MS));
        }
        g = sRightGrp.get();
        if (g != null && sDeltaRight != 0f) {
            anims.add(objAnim(g, "translationX", g.getTranslationX(), 0f, SETTLE_MS));
        }
        for (int i = 0; i < sMisc.size(); i++) {
            final View v = sMisc.get(i);
            final float base = i < sMiscBase.length ? sMiscBase[i] : 1f;
            ObjectAnimator fade = ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), base);
            fade.setInterpolator(FOSI);
            fade.setDuration(MISC_FADE_MS);
            fade.setStartDelay(MISC_FADE_DELAY_MS);
            anims.add(fade);
        }
        TextView clock = sClockLayer.get();
        if (clock != null) {
            anims.add(objAnim(clock, "translationY",
                    clock.getTranslationY(), sClockBaseTy + sOffset, SETTLE_MS));
            ObjectAnimator cf = ObjectAnimator.ofFloat(clock, "alpha", clock.getAlpha(), 0f);
            cf.setInterpolator(FOSI);
            cf.setDuration(CLOCK_FADE_MS);
            cf.setStartDelay(CLOCK_FADE_DELAY_MS);
            anims.add(cf);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anims);
        set.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator a) {
            }

            @Override
            public void onAnimationEnd(Animator a) {
                bar.setTranslationY(0f);
                resetGroupX();
                restoreMisc();
                refreshOffset(bar);
                if (sState == SETTLING) {
                    sState = SETTLED;
                }
            }

            @Override
            public void onAnimationCancel(Animator a) {
                // 被下一相位取消: 不强制归位, 从当前值续接
                if (sState == SETTLING) {
                    sState = SETTLED;
                }
            }

            @Override
            public void onAnimationRepeat(Animator a) {
            }
        });
        sPhaseAnim = set;
        param.setResult(set);
        Port.log(TAG + ": settle from ty=" + bar.getTranslationY() + " misc=" + sMisc.size());
    }

    /**
     * 收起一次性: 0→−offset 回位 (无 stock alpha 淡出), 杂项图标不动
     * (停靠态须与真实栏一致), 时钟半层淡回; 结束停靠等 onPanelCollapsed 硬切.
     */
    private static void onCollapse(final View bar, XC_MethodHook.MethodHookParam param) {
        if (sState != FOLLOWING && sState != SETTLING && sState != SETTLED
                && sState != COLLAPSING && sState != WAITING_REAL) {
            return; // 未接管, stock 原地淡出
        }
        sState = COLLAPSING;
        cancelPhaseAnim();
        bar.setVisibility(View.VISIBLE);
        showClockLayer();
        ArrayList<Animator> anims = new ArrayList<Animator>();
        anims.add(objAnim(bar, "translationY", bar.getTranslationY(), -sOffset, SETTLE_MS));
        View g = sLeftGrp.get();
        if (g != null && sDeltaLeft != 0f) {
            anims.add(objAnim(g, "translationX", g.getTranslationX(), sDeltaLeft, SETTLE_MS));
        }
        g = sRightGrp.get();
        if (g != null && sDeltaRight != 0f) {
            anims.add(objAnim(g, "translationX", g.getTranslationX(), sDeltaRight, SETTLE_MS));
        }
        TextView clock = sClockLayer.get();
        if (clock != null) {
            anims.add(objAnim(clock, "translationY",
                    clock.getTranslationY(), sClockBaseTy, SETTLE_MS));
            ObjectAnimator cf = ObjectAnimator.ofFloat(clock, "alpha", clock.getAlpha(), 1f);
            cf.setInterpolator(FOSI);
            cf.setDuration(CLOCK_FADE_MS);
            cf.setStartDelay(CLOCK_FADE_DELAY_MS);
            anims.add(cf);
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anims);
        set.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator a) {
            }

            @Override
            public void onAnimationEnd(Animator a) {
                finishCollapse(bar);
            }

            @Override
            public void onAnimationCancel(Animator a) {
                finishCollapse(bar);
            }

            @Override
            public void onAnimationRepeat(Animator a) {
            }
        });
        sPhaseAnim = set;
        param.setResult(set);
        Port.log(TAG + ": collapse drift to " + (-sOffset));
    }

    /** 收起漂移结束: 已见 onPanelCollapsed → 即时硬切; 否则停靠等硬切. */
    private static void finishCollapse(View bar) {
        if (sSwapPending) {
            sSwapPending = false;
            hideBar(bar);
            sState = IDLE;
            Port.log(TAG + ": swap (drift-end)");
        } else if (sState == COLLAPSING) {
            sState = WAITING_REAL;
            Port.log(TAG + ": parked at -" + sOffset);
        }
    }

    /** 真实栏可重现 (PanelBar.onPanelCollapsed, scrim 已收回): 停靠硬切. */
    private static void onRealBarCollapsed() {
        View bar = sTarget.get();
        if (bar == null) {
            return;
        }
        if (sState == WAITING_REAL) {
            hideBar(bar);
            sState = IDLE;
            Port.log(TAG + ": swap");
        } else if (sState == COLLAPSING || sState == DROPPING) {
            sSwapPending = true;
        }
    }

    // ------------------------------------------------------------- drop-back

    private static void onFingerUp(XC_MethodHook.MethodHookParam param) {
        boolean enabled = Port.on(Port.KEY_CC_STATUSBAR_CONTINUITY);
        if (sState != FOLLOWING) {
            return; // settle 已接管或未 engage
        }
        Object ctrl = param.thisObject;
        final View bar = sTarget.get();
        if (bar == null) {
            sState = IDLE;
            return;
        }
        if (!enabled) {
            hideBar(bar);
            sState = IDLE;
            return;
        }
        if (showingType(ctrl) != 0) {
            return; // 展开已触发 (settle 已接管, 状态应为 SETTLING)
        }
        // drop-back: 无淡入, 一次性回位 −offset; 杂项图标淡回, 时钟随行;
        // 结束即时隐藏 (面板未过阈值, scrim 收回快, 真实栏未被遮挡 → 无空缺)
        sState = DROPPING;
        cancelPhaseAnim();
        ArrayList<Animator> anims = new ArrayList<Animator>();
        anims.add(objAnim(bar, "translationY", bar.getTranslationY(), -sOffset, DROP_MS));
        View g = sLeftGrp.get();
        if (g != null && sDeltaLeft != 0f) {
            anims.add(objAnim(g, "translationX", g.getTranslationX(), sDeltaLeft, DROP_MS));
        }
        g = sRightGrp.get();
        if (g != null && sDeltaRight != 0f) {
            anims.add(objAnim(g, "translationX", g.getTranslationX(), sDeltaRight, DROP_MS));
        }
        for (int i = 0; i < sMisc.size(); i++) {
            View v = sMisc.get(i);
            float base = i < sMiscBase.length ? sMiscBase[i] : 1f;
            ObjectAnimator fade = ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), base);
            fade.setInterpolator(FOSI);
            fade.setDuration(MISC_FADE_MS);
            fade.setStartDelay(MISC_FADE_DELAY_MS);
            anims.add(fade);
        }
        TextView clock = sClockLayer.get();
        if (clock != null) {
            anims.add(objAnim(clock, "translationY",
                    clock.getTranslationY(), sClockBaseTy, DROP_MS));
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anims);
        set.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator a) {
            }

            @Override
            public void onAnimationEnd(Animator a) {
                finishDrop(bar);
            }

            @Override
            public void onAnimationCancel(Animator a) {
                finishDrop(bar);
            }

            @Override
            public void onAnimationRepeat(Animator a) {
            }
        });
        sPhaseAnim = set;
        set.start();
        Port.log(TAG + ": drop-back from ty=" + bar.getTranslationY());
    }

    private static void finishDrop(View bar) {
        if (sState == DROPPING) {
            hideBar(bar);
            sState = IDLE;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void cancelPhaseAnim() {
        AnimatorSet a = sPhaseAnim;
        if (a != null) {
            sPhaseAnim = null;
            try {
                a.cancel();
            } catch (Throwable t) {
                Port.log(TAG + ": cancel phase failed", t);
            }
        }
    }

    /** 复位 stock 隐藏态 (GONE + alpha 1 + ty 0 + 横向收窄归零 + 杂项/时钟复位). */
    private static void hideBar(View bar) {
        bar.setVisibility(View.GONE);
        bar.setAlpha(1f);
        bar.setTranslationY(0f);
        resetGroupX();
        restoreMisc();
        removeClockLayer();
    }

    private static ObjectAnimator objAnim(View v, String prop, float from, float to, long ms) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, prop, from, to);
        a.setInterpolator(FOSI);
        a.setDuration(ms);
        return a;
    }

    private static void captureMisc(View bar) {
        ensureIds(bar.getContext());
        sMisc.clear();
        for (int id : sMiscIds) {
            if (id != 0) {
                View v = bar.findViewById(id);
                if (v != null) {
                    sMisc.add(v);
                }
            }
        }
        sMiscBase = new float[sMisc.size()];
        for (int i = 0; i < sMisc.size(); i++) {
            sMiscBase[i] = sMisc.get(i).getAlpha();
        }
    }

    private static void hideMisc() {
        for (int i = 0; i < sMisc.size(); i++) {
            sMisc.get(i).setAlpha(0f);
        }
    }

    private static void restoreMisc() {
        for (int i = 0; i < sMisc.size(); i++) {
            float base = i < sMiscBase.length ? sMiscBase[i] : 1f;
            sMisc.get(i).setAlpha(base);
        }
    }

    private static void ensureIds(Context ctx) {
        if (sMiscIds != null) {
            return;
        }
        Resources r = ctx.getResources();
        sMiscIds = new int[MISC_NAMES.length];
        for (int i = 0; i < MISC_NAMES.length; i++) {
            sMiscIds[i] = r.getIdentifier(MISC_NAMES[i], "id", "com.android.systemui");
        }
        sBatteryId = r.getIdentifier("battery", "id", "com.android.systemui");
        sClockId = r.getIdentifier("clock", "id", "com.android.systemui");
        sOperatorId = r.getIdentifier("operator_name_container", "id", "com.android.systemui");
        sIconAreaId = r.getIdentifier("system_icon_area", "id", "com.android.systemui");
    }

    private static View findBattery(View root) {
        ensureIds(root.getContext());
        return sBatteryId == 0 ? null : root.findViewById(sBatteryId);
    }

    private static int callStaticInt(Class<?> cls, String method, Object... args) {
        Integer r = (Integer) XposedHelpers.callStaticMethod(cls, method, args);
        return r == null ? 0 : r.intValue();
    }

    private static Class<?> notchUtilsCls(Context ctx) throws ClassNotFoundException {
        if (sNotchUtilsCls == null) {
            sNotchUtilsCls = XposedHelpers.findClass(NOTCH_UTILS_CLS, ctx.getClassLoader());
        }
        return sNotchUtilsCls;
    }

    private static Class<?> phoneSbUtilsCls(Context ctx) throws ClassNotFoundException {
        if (sPhoneSbUtilsCls == null) {
            sPhoneSbUtilsCls = XposedHelpers.findClass(PHONE_SB_UTILS_CLS, ctx.getClassLoader());
        }
        return sPhoneSbUtilsCls;
    }

    private static byte showingType(Object ctrl) {
        Byte b = (Byte) XposedHelpers.callMethod(ctrl, "getPanelShowingType");
        return b == null ? 0 : b.byteValue();
    }

    private static boolean canSwipe(Object ctrl) {
        Object guide = XposedHelpers.getObjectField(ctrl, "mGuideController");
        if (guide == null) {
            return true;
        }
        Boolean r = (Boolean) XposedHelpers.callMethod(guide, "canSwipeDownPanelView");
        return r == null || r.booleanValue();
    }

    /** 手机: CC/NC 共用 mHnNotchStatusBar; 平板: 按触区选 NC/CC 独立栏. */
    private static View pickBar(Object ctrl) {
        View cc = (View) XposedHelpers.getObjectField(ctrl, "mHnNotchStatusBar");
        if (XposedHelpers.getBooleanField(ctrl, "mIsTablet") && touchArea(ctrl) == 0) {
            View ntf = (View) XposedHelpers.getObjectField(ctrl, "mHnNotchNotificationsStatusBar");
            if (ntf != null) {
                return ntf;
            }
        }
        return cc;
    }

    private static byte touchArea(Object ctrl) {
        try {
            if (sTouchAreaField == null) {
                Class<?> c = ctrl.getClass();
                while (c != null) {
                    try {
                        Field f = c.getDeclaredField("mTouchArea");
                        f.setAccessible(true);
                        sTouchAreaField = f;
                        break;
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
            }
            if (sTouchAreaField == null) {
                return 0;
            }
            return sTouchAreaField.getByte(ctrl);
        } catch (Throwable t) {
            return 0;
        }
    }

    private StatusBarContinuity() {}
}
