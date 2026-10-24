package com.wxz.hnlauncherport.systemui;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.wxz.hnlauncherport.Port;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 状态栏连贯 (KEY_CC_STATUSBAR_CONTINUITY) 第三轮: 真实栏左右两段整体位图 + 阈值交叉淡化.
 *
 * 架构 (V14 双窗口): ① 状态栏窗口 (super_status_bar: 真实栏 HwPhoneStatusBarView,
 * 内容显隐由 disable 标志驱动); ② shade 窗口 (super_notification_shade: 连贯层
 * HnNotchStatusBar = mHnNotchStatusBar/mHnNotchNotificationsStatusBar, 手机 CC/NC 共用).
 *
 * 位图半层 (0/0.1/0.2): fresh engage 把真实栏 HwStatusbarContents 按左右两段整体截成
 * Bitmap (分割线 = system_icon_area 左缘, 兜底电池左缘/0.6w, 只画内容容器避开栏背景),
 * 两个 ImageView 挂面板栏父容器 (mQuickSettingsContainer, FrameLayout) 顶层; 跟手期
 * 位图为唯一可见 (面板 contents alpha 0) → 运营商/杂项/时钟/隐私胶囊与真实栏设置像素级
 * 同步. settle (展开提交, 手指可能未抬): 跟手贯穿 — SETTLING 仍由 onMove 手指-进度
 * 映射驱动 (不与卡片进入动画绑定), 真实 CC 栏仅在进度100 (p≥0.999) 由 applyProgress
 * 内联接替 (tint 先切→contents 复显→复制层瞬隐, 可逆), 松手 (含 fling 展开未达位)
 * 按手指速率 (剩余距离/|速率|, 恒速线性) 续接未达部分; collapse 阈值镜像
 * 1 帧硬切 (位图接管/栏瞬隐), 位图像素本身即真实色, 无 tint 硬切闪, 漂移同样速率续接.
 * 旋转: engage 检测 rotation 变更 → 复刻 stock 位置定义
 * (callMethod updateStatusBarPaddings, 内部按实际 rotation 分支) + 强制重排;
 * 且层基准初始 X 与上次不同 (旋转后首拉) → 静态手势: 位移全冻结, 复制层不动.
 * 位图缓存跨循环 re-engage 复用 (此时真实栏已被 disable 隐藏).
 *
 * 左右收窄 (0.1, 容器级, 与运营商显示无关):
 *   左: (面板 contents 左缘+paddingStart) − (真实 contents 左缘+paddingStart)
 *   右: (面板 contents 右缘−paddingEnd) − (真实 contents 右缘−paddingEnd)
 * 骑乘 translationX = base + shift*p; 纵向 = baseTy + offset*p.
 *
 * 旋转新鲜度门控 (1): 面板栏/contents 休眠态 GONE 不参与旋转重排 → 首次几何过期.
 * panelFresh = 栏宽 ≈ 当前屏宽 (±25%, 旋转差远超容差); 过期时 measureOffsetDirect /
 * captureShifts 返回失败, follow 每步重试, 栏 VISIBLE 重排后自动生效; settle tint 改用
 * 空区域+纯色 (不再引用捕获区域 → 无过期 Rect).
 *
 * 隐私胶囊等实时同步 (3): CC 期间隐私胶囊显示/隐藏、时钟分钟、电量变化会让停靠位图过期
 * (收起错位). 收尾轮询发现真实栏内容开始重现 (VISIBLE 且 alpha>0, 布局已刷新) 时
 * 强制不透明重截两段位图 (临时把后代 alpha 置 1 再画, 同帧恢复) + 重测 base 吸附停靠位.
 *
 * offset (两层移动距离, V17 getDistanceY 语义, V14 几何):
 *  = 面板栏电池自然屏幕Y − 真实栏电池屏幕Y (GONE 保留 layout, 受新鲜度门控).
 *
 * 截断 (点3): engage 对 面板栏 / contents / 父容器 链 clipChildren+clipToPadding 置
 * false (记录原值), 硬切恢复 — 半层外移不再被裁.
 *
 * 收起结尾 (点6, 延长浮层直到真栏显示): 收尾期 (COLLAPSING/DROPPING/WAITING_REAL):
 * ① PanelBar L269 的面板 INVISIBLE 同调用复原; ② shade 窗口根 applyVisibility 的
 * INVISIBLE 同调用复原; ③ 漂移结束停靠真实栏位置, 33ms 轮询真实栏时钟/电池 isShown
 * 且 alpha≥0.9 (上限 ~500ms), 重现后硬切并恢复面板/shade 根 INVISIBLE.
 *
 * 开-关循环 (不松手): 收起/展开触发点每次穿越恰好触发一次 stock 一次性路径 →
 * settle/collapse 走与首次相同的 hook; engage 不看 mIsPanelChangedWhenTracking,
 * 可从 DROPPING/WAITING_REAL 中断接管 (取消在飞动画与轮询, 位图复用, 续接).
 * COLLAPSING 除外: 带内回穿收起即提交性 fling 关闭 (面板自主收起, 手指失效),
 * 重 engage 会劫持收起漂移 → 连贯缺失; 只能走完 drift→WAITING_REAL→swap.
 */
public final class StatusBarContinuity {
    private static final String TAG = "SBContinuity";
    private static final String PANEL_IMPL_CLS =
            "com.android.systemui.statusbar.phone.HnPanelViewControllerImpl";
    private static final String REAL_BAR_CLS =
            "com.android.systemui.statusbar.phone.HwPhoneStatusBarView";
    private static final String SHADE_CONTROLLER_CLS =
            "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl";
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
    /** 收尾停靠: 停靠真实栏位置, 轮询真实栏内容重现后硬切 (点6). */
    private static final int WAITING_REAL = 6;

    private static final int POLL_MS = 33;
    /** 收起漂移固定时长 (用户定 0.5s). */
    private static final long COLLAPSE_DRIFT_MS = 300L;
    /** spring 全局参数 (所有状态栏移动/跟手): SPRING_DUR=时标(ms), SPRING_K=整体系数. */
    private static final float SPRING_DUR = 300f;
    private static final float SPRING_K = 1.0f;
    private static final int POLL_MAX = 15;
    private static final TimeInterpolator FOSI =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private static volatile int sState = IDLE;
    private static volatile float sOffset = 0f;
    private static volatile boolean sOffsetValidated = false;
    private static WeakReference<View> sRealBar = new WeakReference<View>(null);
    private static WeakReference<View> sTarget = new WeakReference<View>(null);
    /** 当前相位动画 (settle 占位/collapse/drop), 换相/engage 时取消. */
    private static Animator sPhaseAnim;
    private static Field sTouchAreaField;
    private static Class<?> sNotchUtilsCls;
    private static Class<?> sPhoneSbUtilsCls;
    private static boolean sIdsReady;
    private static int sBatteryId;
    private static int sClockId;
    private static int sIconAreaId;
    private static int sContentsId;
    /** CC 栏横向移动分组: 左组 (运营商/杂项) 与右组同 iconArea, 时钟复用 sClockId. */
    private static int sOperatorId;
    private static int sUnstableId;
    /** 采集时屏宽: 横竖屏新鲜度 token (CC 开着旋转 → collapse 几何过期降级). */
    private static int sGeomW;
    /** 位图半层: 左右两段 (真实栏像素), 缓存跨循环复用. */
    private static boolean sBmpCached;
    private static boolean sBmpOk;
    private static Bitmap sLeftBmp;
    private static Bitmap sRightBmp;
    private static int sSplitX;
    private static WeakReference<ImageView> sLeftLayer = new WeakReference<ImageView>(null);
    private static WeakReference<ImageView> sRightLayer = new WeakReference<ImageView>(null);
    private static WeakReference<ViewGroup> sLayerParentRef = new WeakReference<ViewGroup>(null);
    /** 半层基准 translation (p=0 = 真实栏位置, 父容器坐标). */
    private static float sBaseLeftTx;
    private static float sBaseRightTx;
    private static float sBaseTy;
    /** 左右收窄量 (p=1 目标增量, 容器级, 与运营商显示无关). */
    private static float sLeftShift;
    private static float sRightShift;
    /** 分组各自缩进 (横屏布局时钟/杂项在右侧 — 二分左右致右组套左缩进). */
    private static float sOpShift;
    private static float sClockShift;
    private static float sUnstableShift;
    private static float sIconShift;
    private static boolean sShiftsFresh;
    /** 面板 contents 隐藏 (跟手期位图独占), 原值记录. */
    private static boolean sContentsHidden;
    private static float sContentsOrigAlpha = 1f;
    /** tint 静态 (fresh engage 捕获; 区域弃用 — settle 用空区域+纯色, 旋转安全). */
    private static int sRealTint;
    private static int sPanelTint;
    private static WeakReference<View> sRealClockRef = new WeakReference<View>(null);
    /** 点3: clipChildren/clipToPadding 原值记录, 硬切恢复. */
    private static final ArrayList<ViewGroup> sClipViews = new ArrayList<ViewGroup>();
    private static final ArrayList<Boolean> sClipChildOrig = new ArrayList<Boolean>();
    private static final ArrayList<Boolean> sClipPadOrig = new ArrayList<Boolean>();
    /** 点6: 收尾期强制 VISIBLE 的宿主 (面板/shade 根), swap 时恢复各自被强制前的原值. */
    private static final ArrayList<View> sForcedVis = new ArrayList<View>();
    /** 点6: 各宿主被强制前的原始可见性 (swap 按原值归还, 不硬编码 INVISIBLE). */
    private static final ArrayList<Integer> sForcedVisOrig = new ArrayList<Integer>();
    /** 点6: swap 轮询 (真实栏内容重现探针 + 隐私胶囊重截窗口). */
    private static int sPollCount;
    private static boolean sResyncDone;
    /** 松手循环门: 手指未抬时收尾轮询只停靠不 swap (防循环中途拆层后不复现). */
    private static volatile boolean sFingerDown;
    /** engage/接管放弃原因 (去重日志, 变化才打 — issue1 循环不复现定位). */
    private static String sLastBail = "";
    /** 当前进度 p (0=真实栏位, 1=面板自然位); 真实 CC 栏接替阈值 = 1 (进度100). */
    private static volatile float sProgress;
    /** 进度≥0.999 时真实 CC 栏已接替 (复制层瞬隐); 回落再切回复制栏. */
    private static boolean sRevealed;
    /** 手指速率 (px/ms, 平滑) 与上次 MOVE 采样: 松手续接动画时长依据. */
    private static volatile float sVelY;
    private static float sLastMoveY;
    private static long sLastMoveT;
    /** 旋转 token: engage 时变化则复刻 stock 按 rotation 的状态栏位置定义. */
    private static int sLastRotation = -1;
    /** 静态手势 (旋转后首拉: 初始 X 变更): 位移全冻结, 复制层不移动. */
    private static boolean sStaticGesture;
    /** 上次 engage 层基准初始 X: 旋转识别 token (本次与上次不同 → 静态手势). */
    private static float sLastEngageBaseX = Float.MIN_VALUE;
    /** 上次 engage 屏宽: 旋转识别 token (屏宽变更 → 静态手势, 与位图无关). */
    private static int sLastEngageW = -1;
    /** 跟手 spring 追踪 (进度域): 目标=手指进度, 帧循环二阶弹簧逼近 (ω=2πk·1000/dur). */
    private static float sSpringP;
    private static float sSpringVel;
    private static float sSpringTarget;
    private static long sSpringLastT;
    private static boolean sSpringRunning;
    private static final Runnable sSpringRunner = new Runnable() {
        @Override
        public void run() {
            View bar = sTarget.get();
            if (bar == null || !sSpringRunning
                    || (sState != FOLLOWING && sState != SETTLING)) {
                sSpringRunning = false;
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min((now - sSpringLastT) / 1000f, 0.032f);
            sSpringLastT = now;
            float omega = 2f * (float) Math.PI * 1000f * SPRING_K / SPRING_DUR;
            float zeta = 0.8f; // 欠阻尼微弹 (内置)
            float a = omega * omega * (sSpringTarget - sSpringP)
                    - 2f * zeta * omega * sSpringVel;
            sSpringVel += a * dt;
            sSpringP += sSpringVel * dt;
            if (Math.abs(sSpringTarget - sSpringP) < 0.0008f
                    && Math.abs(sSpringVel) < 0.01f) {
                sSpringP = sSpringTarget;
                sSpringVel = 0f;
                sSpringRunning = false;
            }
            applyProgress(bar, sSpringP);
            if (sSpringRunning) {
                bar.postOnAnimation(this);
            }
        }
    };
    /** collapse 漂移保险: 自启 set 若意外停滞, 超时强制收尾 (防 COLLAPSING 卡死). */
    private static final Runnable sCollapseWatch = new Runnable() {
        @Override
        public void run() {
            View bar = sTarget.get();
            if (bar != null && sState == COLLAPSING) {
                Port.log(TAG + ": collapse watch fired (drift stalled)");
                finishCollapse(bar);
            }
        }
    };
    private static final Runnable sSwapPoll = new Runnable() {
        @Override
        public void run() {
            View bar = sTarget.get();
            if (bar == null || sState != WAITING_REAL) {
                return; // 状态已变 (re-engage / 重置)
            }
            keepHostsVisible(bar);
            // 连续钉住: 面板收尾期父容器仍可能移动 → 每拍重测 base 随行
            // (停靠位=真实栏位恒定, 消除漂移终点到 resync 间的错位帧)
            if (sBmpOk) {
                measureBase(bar);
                rideLayers(0f);
            }
            // 隐私胶囊/时钟分钟等变化: 真实栏开始重现 (布局已刷新) 时重截+吸附
            if (!sResyncDone && realShowFading()) {
                sResyncDone = true;
                resyncLayers(bar);
            }
            // 松手循环 (下上下上不抬指): swap 会拆层且重 engage 竞态 → 手指
            // 未抬只停靠 (复制层+真栏同位双显), 松手后 onFingerUp 重启轮询 swap.
            if (sFingerDown && sPollCount < POLL_MAX * 4) {
                sPollCount++;
                bar.postDelayed(this, POLL_MS);
                return;
            }
            // 空缺根除: 上限到点但真实栏淡入仍在进行 → 继续等到 ≥0.99 再切;
            // 无重现迹象 (真栏确实隐藏) 或 4 倍上限 → 硬切.
            boolean fading = realShowFading();
            if (sPollCount >= POLL_MAX * 4 || (sPollCount >= POLL_MAX && !fading)
                    || realBarShown()) {
                if (!sResyncDone) { // 探针通过仍未重截 (极端): 换前同帧吸附
                    sResyncDone = true;
                    resyncLayers(bar);
                }
                swapNow(bar);
            } else {
                sPollCount++;
                bar.postDelayed(this, POLL_MS);
            }
        }
    };

    public static void install(ClassLoader cl) {
        final Class<?> panelImpl = XposedHelpers.findClass(PANEL_IMPL_CLS, cl);

        // 真状态栏视图捕获 (电池锚点测量基准)
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

        // 点6①: PanelBar.panelExpansionChanged 在 fraction 0 (z=false) 时把
        // 面板 (真栏 mPanel, 连贯层宿主) 置 INVISIBLE — 收尾期每次调用后
        // 立即复原, 保持连贯层可见直到真实栏内容重现.
        XposedHelpers.findAndHookMethod(realBarCls, "panelExpansionChanged",
                float.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!inEndgame()) {
                                return;
                            }
                            Object pv = XposedHelpers.getObjectField(param.thisObject, "mPanel");
                            if (!(pv instanceof View)) {
                                return;
                            }
                            if ((Boolean) param.args[1]) {
                                // stock 自身复显面板: 撤销对抗记录 (swap 不回置)
                                unforceVisible((View) pv);
                            } else {
                                forceVisible((View) pv);
                            }
                        } catch (Throwable t) {
                            Port.log(TAG + ": keep panel failed", t);
                        }
                    }
                });

        // 点6②: shade 窗口根在"非展开"态被 applyVisibility 置 INVISIBLE
        // (scrims 归零后触发) — 收尾期同调用复原.
        final Class<?> shadeCtlCls = XposedHelpers.findClass(SHADE_CONTROLLER_CLS, cl);
        final Class<?> shadeStateCls = XposedHelpers.findClass(SHADE_CONTROLLER_CLS + "$State", cl);
        XposedHelpers.findAndHookMethod(shadeCtlCls, "applyVisibility", shadeStateCls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!inEndgame()) {
                                return;
                            }
                            Object root = XposedHelpers.getObjectField(
                                    param.thisObject, "mNotificationShadeView");
                            if (root instanceof View) {
                                if (((View) root).getVisibility() == View.VISIBLE) {
                                    // stock applyVisibility 落点即 VISIBLE (如 21:23:11.229
                                    // 快速重开后的 true): shade 根当前就该显示 — 撤销对抗记录,
                                    // 防 swap 把 stock 已复显的根打回 INVISIBLE (真栏+导航栏消失根因)
                                    unforceVisible((View) root);
                                } else {
                                    forceVisible((View) root);
                                }
                            }
                        } catch (Throwable t) {
                            Port.log(TAG + ": keep shade root failed", t);
                        }
                    }
                });

        Port.log("StatusBarContinuity installed");
    }

    // ---------------------------------------------------------------- driver

    private static void onMove(XC_MethodHook.MethodHookParam param) {
        boolean enabled = Port.on(Port.KEY_CC_STATUSBAR_CONTINUITY);
        if (!enabled) {
            if (sState == FOLLOWING || sState == SETTLING || sState == COLLAPSING
                    || sState == DROPPING || sState == WAITING_REAL) {
                View bar = sTarget.get();
                if (bar != null) {
                    bar.removeCallbacks(sSwapPoll);
                    hideBar(bar);
                    restoreForcedVis();
                }
                sState = IDLE;
            }
            return;
        }
        float y = (Float) param.args[0];
        trackVelocity(y);
        // 跟手: FOLLOWING 与 SETTLING (展开提交后手指未抬) 都手指映射 —
        // settle 切换不冻结跟手, 不与卡片进入动画绑定
        if (sState == FOLLOWING || sState == SETTLING) {
            View bar = sTarget.get();
            if (bar == null) {
                sState = IDLE;
                return;
            }
            follow(param.thisObject, bar, y);
            return;
        }
        // COLLAPSING 不可重 engage: 带内回穿收起是提交性 fling 关闭
        // (notifyControlCenterPanelFlingClose, 手指几何已失效), 必须走
        // drift→WAITING_REAL→swap 收尾; fling 结束后 (WAITING_REAL/IDLE)
        // 的 MOVE 仍可重 engage → 不松手再下拉不受影响.
        if (sState == IDLE || sState == DROPPING || sState == WAITING_REAL) {
            tryEngage(param.thisObject, y);
        }
        // SETTLED: 静止展开态
    }

    /** 手指速率跟踪 (px/ms, 平滑): 松手动画 "相同速率续接" 时长依据. */
    private static void trackVelocity(float y) {
        long t = android.os.SystemClock.uptimeMillis();
        float dy = y - sLastMoveY;
        long dt = t - sLastMoveT;
        if (dt >= 8L && Math.abs(dy) < 4000f) {
            float v = dy / (float) dt;
            sVelY = 0.6f * v + 0.4f * sVelY;
        }
        sLastMoveY = y;
        sLastMoveT = t;
    }

    /**
     * engage 判定 (新手势, 或 开-关循环 中断接管). 不检查
     * mIsPanelChangedWhenTracking: 不松手的第二次开它恒为 true.
     */
    private static void tryEngage(Object ctrl, float y) {
        View bar = pickBar(ctrl);
        if (bar == null) {
            bail("no bar");
            return;
        }
        if (showingType(ctrl) != 0) {
            // 停靠期重开 (新下滑, stock 面板仍在显示): 停靠替身即将被新开面板
            // 遮盖而过时, 不撤必成 "复制层停原位 + CC 同现" 双层残影 —
            // 撤层并复位 CC 栏为 stock 可接管态; 上滑 (收尾延续) 不受影响.
            if (sState == WAITING_REAL
                    && y - XposedHelpers.getFloatField(ctrl, "mInitialY") > 0f) {
                abandonParked();
            }
            bail("showingType=" + showingType(ctrl)); // 已有面板在显示 (收起 fling 中/切换), 无连贯
            return;
        }
        float initialY = XposedHelpers.getFloatField(ctrl, "mInitialY");
        float touchLimit = XposedHelpers.getFloatField(ctrl, "mHeightForTouchExpand");
        if (touchLimit <= 0f || initialY > touchLimit) {
            bail("outside touch area"); // 非状态栏区域起手 (stock 触发展开同样不成立)
            return;
        }
        View panelView = (View) XposedHelpers.getObjectField(ctrl, "mPanelView");
        if (panelView == null
                || ((Boolean) XposedHelpers.callMethod(panelView, "isKeyguardShowing")).booleanValue()) {
            bail("keyguard");
            return; // 不含锁屏侧
        }
        if (!canSwipe(ctrl)) {
            bail("guide");
            return; // 引导中, getExpectHeight 恒 0, 面板不会展开
        }
        if (y - initialY <= 0f) {
            return; // 未下滑 (高频, 不计 bail)
        }
        cancelPhaseAnim();
        bar.removeCallbacks(sSwapPoll);
        bar.removeCallbacks(sCollapseWatch);
        engage(bar);
        follow(ctrl, bar, y);
    }

    /** bail 原因变化才打日志 (tryEngage 每 MOVE 调用, 防刷屏); engage 成功复位. */
    private static void bail(String why) {
        if (!why.equals(sLastBail)) {
            sLastBail = why;
            Port.log(TAG + ": engage bail: " + why);
        }
    }

    private static void follow(Object ctrl, View bar, float y) {
        // SETTLING 绕过 showingType/canSwipe 门: 展开提交后面板已显示
        // (showingType!=0) 但手指未抬仍连续手指映射 — settle 切换跟手贯穿,
        // 不与 stock 卡片进入动画绑定.
        if (sState != SETTLING) {
            if (showingType(ctrl) != 0) {
                return; // 展开一次性已接管 (同一 MOVE 内 stock 先触发, 状态已切)
            }
            if (!canSwipe(ctrl)) {
                return;
            }
        }
        if (!sShiftsFresh) {
            // 清掉跟手骑乘的分组位移保证锚测量纯净; ride 随后按新值重挂
            resetGroupShifts(bar);
            sShiftsFresh = captureShifts(bar); // 旋转后栏 VISIBLE 重排 → 生效
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
        springTo(bar, p);
    }

    /** 跟手 spring 追踪: 目标=手指进度; 未运行则起帧循环 (FOLLOWING/SETTLING). */
    private static void springTo(View bar, float target) {
        sSpringTarget = target;
        if (!sSpringRunning) {
            sSpringRunning = true;
            sSpringLastT = android.os.SystemClock.uptimeMillis();
            bar.postOnAnimation(sSpringRunner);
        }
    }

    /**
     * 进度统一应用: 位置 ride(p) + 进度100 真栏接替内联切换 (可逆).
     * p≥0.999 (进度100): tint 先切 (contents 不可见时) 再复显真实 CC 栏,
     * 复制层瞬隐; 回落: 复制层瞬显 + contents 重新隐藏 — 接替由进度驱动,
     * 与 stock 卡片进入动画完全解耦.
     */
    private static void applyProgress(View bar, float p) {
        sProgress = p;
        boolean full = p >= 0.999f;
        if (full && !sRevealed) {
            sRevealed = true;
            View pc = getPanelContents(bar);
            applyTint(pc, sPanelTint); // contents 不可见时切换 → 无闪
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
            resetGroupShifts(bar); // 分组精确归零 (p≈1 本已 0, 双保险)
            restoreContentsAlpha(bar);
            setLayersAlpha(0f);
            if (sStaticGesture) {
                // 旋转首拉: reveal 后 (面板已提交重排) 下一帧几何恢复可信 →
                // 重测并把复制层刷到 settle 位, 本次收起即用正确几何
                bar.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshStaticGeometry(bar);
                    }
                });
            }
            Port.log(TAG + ": reveal (progress 100)");
        } else if (!full && sRevealed) {
            sRevealed = false;
            bar.setAlpha(1f);
            hidePanelContents(bar);
            ride(bar, p);
            setLayersAlpha(1f);
            Port.log(TAG + ": re-copy (progress " + p + ")");
        } else {
            ride(bar, p);
        }
    }

    private static void setLayersAlpha(float alpha) {
        ImageView l = sLeftLayer.get();
        if (l != null) {
            l.setAlpha(alpha);
        }
        ImageView r = sRightLayer.get();
        if (r != null) {
            r.setAlpha(alpha);
        }
    }

    /**
     * 静态手势 (旋转首拉) 几何刷新: 面板已展开重排 → 直接测量可信时重测
     * offset/收窄/基准, 并把复制层刷到 settle 位 (p=1, 真栏 reveal 所在处)
     * — 本次收起即用正确几何漂移. 测量不可信 (布局未就绪) 则保持冻结.
     */
    private static void refreshStaticGeometry(View bar) {
        if (!sStaticGesture || !sRevealed
                || (sState != SETTLING && sState != SETTLED)) {
            return; // 未 reveal (跟手期冻结一致) 或非静态手势: 不刷
        }
        float direct = measureOffsetDirect(bar);
        if (direct <= 0f) {
            return; // 布局仍未就绪: 保持冻结 (收起前再试)
        }
        sOffset = direct;
        sOffsetValidated = true;
        sShiftsFresh = captureShifts(bar);
        measureBase(bar);
        rideLayers(1f); // 复制层 → settle 位 (本次收起的漂移起点)
        sStaticGesture = false;
        Port.log(TAG + ": static geometry refreshed offset=" + sOffset
                + " dxL=" + sLeftShift + " dxR=" + sRightShift);
    }

    /** p: 0=真实栏位置, 1=面板自然位. 位图可用: 仅复制层骑乘 (CC 栏 contents 隐藏, 不位移); 降级: 栏 ty + 分组横移即连贯本体. */
    private static void ride(View bar, float p) {
        if (!sBmpOk) {
            bar.setTranslationY(-sOffset * (1f - p));
            rideGroups(bar, p);
        }
        rideLayers(p);
    }

    /**
     * v1 初始位置语义: engage(p=0) 分组即位于真实栏 X (tx = −shift),
     * 收敛到 p=1 归零. 降级 (无位图) 时 contents 可见, 此骑乘即横向连贯本体.
     */
    private static void rideGroups(View bar, float p) {
        View pc = getPanelContents(bar);
        if (pc == null) {
            return;
        }
        float lo = -sOpShift * (1f - p);
        setGroupTx(pc, sOperatorId, lo);
        setGroupTx(pc, sClockId, -sClockShift * (1f - p));
        setGroupTx(pc, sUnstableId, -sUnstableShift * (1f - p));
        setGroupTx(pc, sIconAreaId, -sIconShift * (1f - p));
    }

    private static void setGroupTx(View pc, int id, float tx) {
        if (id != 0) {
            View v = pc.findViewById(id);
            if (v != null && v.getTranslationX() != tx) {
                v.setTranslationX(tx);
            }
        }
    }

    /**
     * engage: 量 offset/收窄量, 截两段位图挂半层, 隐藏面板 contents
     * (跟手期位图独占 → 运营商/杂项/胶囊/时钟与真实栏像素级一致), 裁剪保护.
     */
    private static void engage(View bar) {
        boolean cycle = sBmpOk;
        // 旋转检测 (需求2): 位置定义复刻 — HwStatusbarContents
        // .updateStatusBarPaddings 内部按实际 getRotation() 分支取
        // PhoneStatusBarUtils 的 start/end/top padding (横竖屏各自定义);
        // GONE 面板树不随旋转重刷 → 旋转变更时手动复刻 + 强制重排,
        // 首次手势位置即新方向 (修横屏 dxL=311/dxR=-221 旧 padding 残留).
        int rot = currentRotation(bar);
        if (rot != -1 && rot != sLastRotation) {
            boolean changed = sLastRotation != -1;
            sLastRotation = rot;
            if (changed) {
                try {
                    View pc0 = getPanelContents(bar);
                    if (pc0 != null) {
                        XposedHelpers.callMethod(pc0, "updateStatusBarPaddings");
                    }
                } catch (Throwable t) {
                    Port.log(TAG + ": rotation paddings refresh failed", t);
                }
                forceRelayoutPanelBar(bar);
                sShiftsFresh = false;
                Port.log(TAG + ": rotation changed -> paddings refreshed rot=" + rot);
            }
        }
        // 旋转后首次手势: GONE 面板树不随旋转重排 (栏+父链全旧方向几何) →
        // 沿祖先链自顶向下强制重排 → offset(X,Y)/shift 首次手势当场实测,
        // 复制层从 p=0 即收敛, 不依赖第二次手势. 必须先于 resolveOffset.
        if (!panelFresh(bar)) {
            forceRelayoutPanelBar(bar);
            if (!panelFresh(bar)) {
                sLeftShift = 0f;
                sRightShift = 0f;
                sOpShift = 0f;
                sClockShift = 0f;
                sUnstableShift = 0f;
                sIconShift = 0f;
                sShiftsFresh = false;
            }
        }
        sOffset = resolveOffset(bar);
        // 进度模型复位: 新手势从进度0/未接替/零速率开始
        sProgress = 0f;
        sRevealed = false;
        sVelY = 0f;
        sSpringP = 0f;
        sSpringVel = 0f;
        sSpringTarget = 0f;
        sSpringRunning = false;
        sTarget = new WeakReference<View>(bar);
        restoreContentsAlpha(bar); // 先复位上次隐藏, 防把中间值当原值
        restoreClip();             // 同上, 防重复记录
        resetGroupShifts(bar);     // 同上, 残留分组位移会污染 captureShifts 测量
        captureBitmaps(bar, false);
        if (!sBmpOk) {
            Port.log(TAG + ": capture unavailable, degrade");
        }
        attachLayers(bar);
        sShiftsFresh = captureShifts(bar);
        // 旋转识别 (需求2, 双判定): ① 屏宽变更 (旋转必变, 与位图无关);
        // ② 层基准初始 X 变更. 任一命中 → 几何不信任 (旋转后首拉, 缩进/
        // offset 实测不可靠) — 本次手势冻结: 收窄/offset 全零, settle 位置 =
        // 当前位置, 复制层不移动 (宁可不动不错位); 下次手势同方向一致 →
        // 恢复正常连贯.
        int engageW = bar.getResources().getDisplayMetrics().widthPixels;
        boolean widthChanged = sLastEngageW != -1 && engageW != sLastEngageW;
        sLastEngageW = engageW;
        boolean baseXChanged = sBmpOk && sLastEngageBaseX != Float.MIN_VALUE
                && Math.abs(sBaseLeftTx - sLastEngageBaseX) > 0.5f;
        if (sBmpOk) {
            sLastEngageBaseX = sBaseLeftTx;
        }
        sStaticGesture = widthChanged || baseXChanged;
        if (sStaticGesture) {
            sLeftShift = 0f;
            sRightShift = 0f;
            sOpShift = 0f;
            sClockShift = 0f;
            sUnstableShift = 0f;
            sIconShift = 0f;
            sShiftsFresh = true; // 冻结位移: 防 follow 重测覆盖
            sOffset = 0f;
            sOffsetValidated = true; // 防 follow 静默校正覆盖
            Port.log(TAG + ": static gesture (widthChanged=" + widthChanged
                    + " baseXChanged=" + baseXChanged + ")");
        }
        hidePanelContents(bar);
        disableClip(bar);
        captureTintStatics(bar);
        bar.setAlpha(1f);
        bar.setVisibility(View.VISIBLE);
        ride(bar, 0f);
        sState = FOLLOWING;
        sFingerDown = true;
        sLastBail = "";
        Port.log(TAG + ": engage " + (cycle ? "cycle" : "fresh")
                + " offset=" + sOffset + " validated=" + sOffsetValidated
                + " bmp=" + sBmpOk + " shiftsFresh=" + sShiftsFresh
                + " panelFresh=" + panelFresh(bar)
                + " dxL=" + sLeftShift + " dxR=" + sRightShift
                + " " + layerChainState());
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

    /**
     * 直接测量: 面板电池 layout 位置 GONE 后保留 (mTop 不清), 不受 translation 影响.
     * 旋转新鲜度门控: 休眠栏几何过期 (宽≠当前屏宽) → 失败, follow 每步重试.
     */
    private static float measureOffsetDirect(View bar) {
        try {
            if (!panelFresh(bar)) {
                return -1f;
            }
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
     * 首次手势兜底 (面板栏未布局过/旋转后过期): V14 几何 —
     * 面板电池自然Y = 运行时 paddingTop + (面板栏高 − paddingTop − 电池高)/2.
     * 中置摄像头 (胶囊): paddingTop 与栏高各含胶囊高 → offset ≈ 胶囊高;
     * 左/右置: 运行时值即无加成 → offset ≈ 0. 分机型自动生效 (工具类实时值,
     * 旋转后同样新鲜).
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

    // ---------------------------------------------------------------- bitmaps

    /** 采集门: 真实栏内容当前可见 (fresh engage 时刻自然态; 循环 re-engage 时已隐藏). */
    private static boolean realContentsExposed() {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return false;
            }
            ensureIds(realBar.getContext());
            if (sBatteryId != 0) {
                View b = realBar.findViewById(sBatteryId);
                if (b != null && b.getVisibility() == View.VISIBLE && b.getAlpha() > 0.5f) {
                    return true;
                }
            }
            if (sClockId != 0) {
                View c = realBar.findViewById(sClockId);
                if (c != null && c.getVisibility() == View.VISIBLE && c.getAlpha() > 0.5f) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 两段位图采集. forceOpaque: 绕过可见性门 (收尾轮询重截 — 此时真实栏
     * 内容正在淡入, alpha<1, 强制不透明画取得完整像素). 门失败且有缓存 →
     * 复用缓存 (开-关循环); 无缓存 → 降级 (contents 不隐藏, 纯 tint 切换).
     */
    private static void captureBitmaps(View bar, boolean forceOpaque) {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return;
            }
            View rc = getRealContents(realBar);
            if (rc == null || rc.getWidth() <= 0 || rc.getHeight() <= 0) {
                if (!cacheFresh(bar)) {
                    sBmpOk = false;
                }
                return;
            }
            if (!forceOpaque && !realContentsExposed()) {
                if (!cacheFresh(bar)) {
                    sBmpOk = false;
                }
                return;
            }
            ensureIds(bar.getContext());
            int w = rc.getWidth();
            int h = rc.getHeight();
            sSplitX = measureSplitX(realBar, rc);
            if (sSplitX <= 0 || sSplitX >= w) {
                if (!cacheFresh(bar)) {
                    sBmpOk = false;
                }
                return;
            }
            int density = rc.getResources().getDisplayMetrics().densityDpi;
            Bitmap left = Bitmap.createBitmap(sSplitX, h, Bitmap.Config.ARGB_8888);
            Bitmap right = Bitmap.createBitmap(w - sSplitX, h, Bitmap.Config.ARGB_8888);
            left.setDensity(density); // createBitmap 默认 160dpi, 不设会被 ImageView 错缩放
            right.setDensity(density);
            drawForcedOpaque(rc, left, right);
            // 截空防护: 真实栏内容此刻被 stock 隐藏/移出 → 全透明位图
            // 会让整轮连贯渲染空白 ("复制层消失不归"). 检出即弃, 保留旧好缓存.
            int pl = bmpMaxAlpha(left);
            int pr = bmpMaxAlpha(right);
            View bat = findBattery(realBar);
            View clk = sClockId == 0 ? null : realBar.findViewById(sClockId);
            if ((pl <= 0 || pr <= 0) && cacheFresh(bar)
                    && sLeftBmp != null && sRightBmp != null
                    && bmpMaxAlpha(sLeftBmp) > 0 && bmpMaxAlpha(sRightBmp) > 0) {
                Port.log(TAG + ": capture blank (pxL=" + pl + " pxR=" + pr
                        + ") keep cache rc[v=" + rc.getVisibility()
                        + ",a=" + rc.getAlpha() + ",ty=" + rc.getTranslationY() + "]"
                        + " bat[" + (bat == null ? "null" : "v=" + bat.getVisibility()
                        + ",a=" + bat.getAlpha() + ",ty=" + bat.getTranslationY()) + "]"
                        + " clk[" + (clk == null ? "null" : "v=" + clk.getVisibility()
                        + ",a=" + clk.getAlpha() + ",ty=" + clk.getTranslationY()) + "]");
                return;
            }
            Port.log(TAG + ": capture pxL=" + pl + " pxR=" + pr
                    + " rc[v=" + rc.getVisibility() + ",a=" + rc.getAlpha() + "]");
            sLeftBmp = left;
            sRightBmp = right;
            sBmpCached = true;
            sBmpOk = true;
            sGeomW = bar.getResources().getDisplayMetrics().widthPixels; // 采集时屏宽
        } catch (Throwable t) {
            Port.log(TAG + ": capture failed", t);
            if (!cacheFresh(bar)) {
                sBmpOk = false;
            }
        }
    }

    /** 缓存新鲜度: 采集屏宽 == 当前屏宽 (横竖屏切换后旧方向位图/几何不可复用). */
    private static boolean cacheFresh(View bar) {
        return sBmpCached && sGeomW != 0
                && sGeomW == bar.getResources().getDisplayMetrics().widthPixels;
    }

    /** 分割线: system_icon_area 左缘 (contents 坐标), 兜底电池左缘, 再兜底 0.6w. */
    private static int measureSplitX(View realBar, View rc) {
        int[] rcPos = new int[2];
        rc.getLocationOnScreen(rcPos);
        if (sIconAreaId != 0) {
            View area = realBar.findViewById(sIconAreaId);
            if (area != null && area.getWidth() > 0) {
                int[] a = new int[2];
                area.getLocationOnScreen(a);
                float x = a[0] - rcPos[0];
                if (x > 0f && x < rc.getWidth()) {
                    return (int) x;
                }
            }
        }
        View bat = findBattery(realBar);
        if (bat != null && bat.getWidth() > 0) {
            int[] b = new int[2];
            bat.getLocationOnScreen(b);
            float x = b[0] - rcPos[0];
            if (x > 0f && x < rc.getWidth()) {
                return (int) x;
            }
        }
        return (int) (rc.getWidth() * 0.6f);
    }

    /**
     * 强制不透明绘制: 临时把 contents 树内 alpha<1 的 view 置 1, 同帧画完恢复
     * (淡入 animator 下帧覆写, 安全). 左段画 [0, split), 右段平移画 [split, w).
     */
    private static void drawForcedOpaque(View rc, Bitmap left, Bitmap right) {
        ArrayList<View> touched = new ArrayList<View>();
        ArrayList<Float> orig = new ArrayList<Float>();
        collectAlpha(rc, touched, orig);
        int n = touched.size();
        for (int i = 0; i < n; i++) {
            touched.get(i).setAlpha(1f);
        }
        try {
            rc.draw(new Canvas(left));
            Canvas cr = new Canvas(right);
            cr.translate(-sSplitX, 0f);
            rc.draw(cr);
        } finally {
            for (int i = 0; i < n; i++) {
                touched.get(i).setAlpha(orig.get(i));
            }
        }
    }

    private static void collectAlpha(View v, ArrayList<View> touched, ArrayList<Float> orig) {
        if (v.getAlpha() < 1f) {
            touched.add(v);
            orig.add(v.getAlpha());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAlpha(g.getChildAt(i), touched, orig);
            }
        }
    }

    // ------------------------------------------------------------------ 半层

    /**
     * 位图半层挂栏祖父容器 (mSubPanelContainer) 顶层, z 序在栏上.
     * 不挂父容器 (mQuickSettingsContainer): stock startAnimationSet 卡片动画
     * 子遍历 (tag 匹配) 与 hideAllViews 无条件子遍历都只扫 mQuickSettingsContainer —
     * 挂父曾被卡片动画污染 (层 alpha 拉回 1 / translationY 飞到 -mPanelMaxTranslationY
     * = settle 后复制层复现 + CC 收起目标 Y 为负 + 0,0 重影的共同根因).
     * 挂祖父两层遍历都扫不到, 无需 tag 伪装.
     */
    private static void attachLayers(View bar) {
        removeLayers();
        if (!sBmpOk || sLeftBmp == null || sRightBmp == null) {
            return;
        }
        try {
            ViewParent p = bar.getParent();
            // 优先祖父 (mSubPanelContainer); 不可用退父层 (仅降一档, 仍可用)
            ViewGroup host = null;
            if (p instanceof ViewGroup && p.getParent() instanceof ViewGroup) {
                host = (ViewGroup) p.getParent();
            } else if (p instanceof FrameLayout) {
                host = (FrameLayout) p;
            }
            if (host == null) {
                sBmpOk = false; // 无法挂载 → 降级
                return;
            }
            ViewGroup parent = host;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ImageView left = new ImageView(parent.getContext());
            ImageView right = new ImageView(parent.getContext());
            left.setImageBitmap(sLeftBmp);
            right.setImageBitmap(sRightBmp);
            // MATRIX: 1:1 绘制与 bounds 无关 — 重截换图 (胶囊/电量致宽变) 时
            // FIT_CENTER 会把新图挤进旧 bounds 一帧 (收起结尾 X 错位根因)
            left.setScaleType(ImageView.ScaleType.MATRIX);
            right.setScaleType(ImageView.ScaleType.MATRIX);
            parent.addView(left, lp);
            parent.addView(right, lp);
            sLeftLayer = new WeakReference<ImageView>(left);
            sRightLayer = new WeakReference<ImageView>(right);
            sLayerParentRef = new WeakReference<ViewGroup>(parent);
            measureBase(bar);
            rideLayers(0f);
        } catch (Throwable t) {
            Port.log(TAG + ": attach layers failed", t);
            sBmpOk = false;
            removeLayers();
        }
    }

    /** 半层基准 translation (p=0 = 真实栏位置; 层宿主内容坐标, 减 padding). */
    private static void measureBase(View bar) {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return;
            }
            View rc = getRealContents(realBar);
            // 层宿主 = attachLayers 实际挂载容器 (祖父 mSubPanelContainer),
            // translation 基准必须与挂载点一致; 未挂载退栏父.
            ViewGroup parent = sLayerParentRef.get();
            if (parent == null) {
                ViewParent p = bar.getParent();
                if (p instanceof ViewGroup) {
                    parent = (ViewGroup) p;
                }
            }
            if (rc == null || parent == null) {
                return;
            }
            int[] rPos = new int[2];
            int[] pPos = new int[2];
            rc.getLocationOnScreen(rPos);
            parent.getLocationOnScreen(pPos);
            sBaseLeftTx = rPos[0] - pPos[0] - parent.getPaddingLeft();
            sBaseRightTx = rPos[0] + sSplitX - pPos[0] - parent.getPaddingLeft();
            sBaseTy = rPos[1] - pPos[1] - parent.getPaddingTop();
        } catch (Throwable t) {
            Port.log(TAG + ": measureBase failed", t);
        }
    }

    /**
     * 全链新鲜度: bar 到窗口根逐级宽匹配 (MATCH_PARENT → 父内容区宽).
     * GONE 子树不随旋转重排 → 与已重排父宽不匹配即检出; 横屏面板栏真实宽
     * ≠ 屏宽 (卡片布局), 旧 "宽≈屏宽" 判定在横屏恒误判 (缩进不刷新根因).
     */
    private static boolean panelFresh(View bar) {
        View c = bar;
        ViewParent p = c.getParent();
        while (p instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) p;
            if (c.getWidth() <= 0 || c.getWidth() != expectedWidth(c, g)) {
                return false;
            }
            c = g;
            p = g.getParent();
        }
        return c.getWidth() > 0;
    }

    /** 期望宽: MATCH_PARENT → 父内容区宽; 其它布局参数无法预测 → 现宽 (视为匹配). */
    private static int expectedWidth(View child, ViewGroup parent) {
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            return parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        }
        return child.getWidth();
    }

    /**
     * 强制重排面板栏及其过期祖先链: 沿链收集宽不匹配的容器 (与 panelFresh
     * 同判据), 自顶向下逐级 measure+layout — 宽高全按父容器 LayoutParams
     * 推导 (横屏卡片布局面板栏真实宽 ≠ 屏宽, EXACTLY(屏宽) 曾排出错位树,
     * 测出 dxL=311/dxR=419 垃圾缩进). stock 真布局随后同几何覆盖, 无冲突.
     */
    private static void forceRelayoutPanelBar(View bar) {
        try {
            ArrayList<ViewGroup> stale = new ArrayList<ViewGroup>();
            View c = bar;
            ViewParent p = c.getParent();
            while (p instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) p;
                if (c.getWidth() <= 0 || c.getWidth() != expectedWidth(c, g)) {
                    stale.add(g);
                    c = g;
                    p = g.getParent();
                } else {
                    break; // 该级匹配: 其上由更早重排保证
                }
            }
            for (int i = stale.size() - 1; i >= 0; i--) { // 自顶向下
                relayoutView(stale.get(i));
            }
            relayoutView(bar);
            Port.log(TAG + ": force relayout chain=" + stale.size()
                    + " bar=" + bar.getWidth() + "x" + bar.getHeight());
        } catch (Throwable t) {
            Port.log(TAG + ": force relayout failed", t);
        }
    }

    /** 按父容器当前几何推导的规格重排 (宽高均遵循 LayoutParams, 与 stock 一致). */
    private static void relayoutView(View v) {
        ViewParent pp = v.getParent();
        if (!(pp instanceof ViewGroup)) {
            return; // 无父 (窗口根): 无需也不可手动重排
        }
        ViewGroup parent = (ViewGroup) pp;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int wSpec = ViewGroup.getChildMeasureSpec(parent.getWidth(),
                parent.getPaddingLeft() + parent.getPaddingRight(),
                lp == null ? ViewGroup.LayoutParams.WRAP_CONTENT : lp.width);
        int hSpec = ViewGroup.getChildMeasureSpec(parent.getHeight(),
                parent.getPaddingTop() + parent.getPaddingBottom(),
                lp == null ? ViewGroup.LayoutParams.WRAP_CONTENT : lp.height);
        v.measure(wSpec, hSpec);
        // 从未布局过的 view (left/top/right/bottom 全 0) 不能 layout 在 (0,0) —
        // 父容器有 padding (mSubPanelContainer 72px) 时整树少偏一个 padding,
        // 首次下拉 dxL=-54/dxR=-90 (正确值减 72) 的根因. 首布局定位到
        // 父 padding + 自身 margin, 与 stock 首次布局落点一致.
        boolean first = v.getLeft() == 0 && v.getTop() == 0
                && v.getRight() == 0 && v.getBottom() == 0;
        int left;
        int top;
        if (first) {
            left = parent.getPaddingLeft();
            top = parent.getPaddingTop();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                left += mlp.leftMargin;
                top += mlp.topMargin;
            }
        } else {
            left = v.getLeft();
            top = v.getTop();
        }
        v.layout(left, top, left + v.getMeasuredWidth(), top + v.getMeasuredHeight());
    }

    /**
     * 左右收窄量 (0.1, 运营商无关):
     *   左: 面板时钟X − 真实时钟X (时钟锚; 容器缘两侧等宽测恒 0 — 上轮 X 移动丢失根因)
     *   右: 面板电池X − 真实电池X (电池锚)
     * 锚不可用退容器缘公式; 失败不清零 (保留上次有效值, 防跟手期 X 跳变).
     */
    private static boolean captureShifts(View bar) {
        try {
            View realBar = sRealBar.get();
            if (realBar == null || !panelFresh(bar)) {
                return false;
            }
            View rc = getRealContents(realBar);
            View pc = getPanelContents(bar);
            if (rc == null || pc == null || pc.getWidth() <= 0) {
                return false;
            }
            ensureIds(bar.getContext());
            float cap = bar.getResources().getDisplayMetrics().widthPixels * 0.75f;
            View realClock = sClockId == 0 ? null : realBar.findViewById(sClockId);
            View panelClock = sClockId == 0 ? null : bar.findViewById(sClockId);
            float ls;
            if (realClock != null && panelClock != null
                    && realClock.getVisibility() == View.VISIBLE
                    && panelClock.getVisibility() == View.VISIBLE) {
                ls = screenX(panelClock) - screenX(realClock);
            } else {
                ls = (screenX(pc) + pc.getPaddingStart() - rc.getPaddingStart()) - screenX(rc);
            }
            View realBat = findBattery(realBar);
            View panelBat = findBattery(bar);
            float rs;
            if (realBat != null && panelBat != null) {
                rs = screenX(panelBat) - screenX(realBat);
            } else {
                rs = (screenX(pc) + pc.getWidth() - pc.getPaddingEnd() + rc.getPaddingEnd())
                        - (screenX(rc) + rc.getWidth());
            }
            sLeftShift = Math.abs(ls) < cap ? ls : 0f;
            sRightShift = Math.abs(rs) < cap ? rs : 0f;
            // 分组各自锚点: 横屏布局时钟/杂项位于右侧, 须各对真实栏对应元素实测
            sOpShift = resolveGroupShift(realBar, bar, sOperatorId, sLeftShift);
            sClockShift = resolveGroupShift(realBar, bar, sClockId, sLeftShift);
            sUnstableShift = resolveGroupShift(realBar, bar, sUnstableId, sRightShift);
            sIconShift = resolveGroupShift(realBar, bar, sIconAreaId, sRightShift);
            return true;
        } catch (Throwable t) {
            Port.log(TAG + ": captureShifts failed", t);
            return false;
        }
    }

    /** 分组对各自真实栏对应元素实测缩进; 锚缺失/越界退层缩进. */
    private static float resolveGroupShift(View realBar, View bar, int id, float fallback) {
        if (id == 0) {
            return fallback;
        }
        View rv = realBar.findViewById(id);
        View pv = bar.findViewById(id);
        if (rv == null || pv == null || rv.getWidth() <= 0 || pv.getWidth() <= 0
                || rv.getVisibility() != View.VISIBLE || pv.getVisibility() != View.VISIBLE) {
            return fallback;
        }
        float d = screenX(pv) - screenX(rv);
        float cap = bar.getResources().getDisplayMetrics().widthPixels * 0.75f;
        return Math.abs(d) < cap ? d : fallback;
    }

    private static float screenX(View v) {
        int[] pos = new int[2];
        v.getLocationOnScreen(pos);
        return pos[0];
    }

    /** 半层骑乘: left/right tx = base + shift*p, ty = baseTy + offset*p. */
    private static void rideLayers(float p) {
        ImageView l = sLeftLayer.get();
        ImageView r = sRightLayer.get();
        if (l == null && r == null) {
            return;
        }
        float ty = sBaseTy + sOffset * p;
        if (l != null) {
            l.setVisibility(View.VISIBLE); // 层挂祖父容器, stock 子遍历已扫不到, 双保险
            l.setTranslationX(sBaseLeftTx + sLeftShift * p);
            l.setTranslationY(ty);
        }
        if (r != null) {
            r.setVisibility(View.VISIBLE);
            r.setTranslationX(sBaseRightTx + sRightShift * p);
            r.setTranslationY(ty);
        }
    }

    private static void showLayers() {
        ImageView l = sLeftLayer.get();
        ImageView r = sRightLayer.get();
        if (l != null) {
            l.setVisibility(View.VISIBLE);
        }
        if (r != null) {
            r.setVisibility(View.VISIBLE);
        }
    }

    /** 诊断: 层渲染链状态 (自身 alpha/vis/位移/位图 + 祖先链 visibility/alpha, 定位不可见断点). */
    private static String layerChainState() {
        ImageView l = sLeftLayer.get();
        if (l == null) {
            return "layer=none";
        }
        int[] sp = new int[2];
        l.getLocationOnScreen(sp);
        StringBuilder sb = new StringBuilder("layer[a=").append(l.getAlpha())
                .append(",v=").append(l.getVisibility())
                .append(",tx=").append(l.getTranslationX())
                .append(",ty=").append(l.getTranslationY())
                .append(",bmp=").append(sLeftBmp == null ? "null"
                        : sLeftBmp.getWidth() + "x" + sLeftBmp.getHeight())
                .append(",wh=").append(l.getWidth()).append("x").append(l.getHeight())
                .append(",pxL=").append(sLeftBmp == null ? "?" : bmpMaxAlpha(sLeftBmp))
                .append(",pxR=").append(sRightBmp == null ? "?" : bmpMaxAlpha(sRightBmp))
                .append(",scr=[").append(sp[0]).append(",").append(sp[1]).append("]")
                .append(",shown=").append(l.isShown())
                .append("] chain:");
        View c = l;
        int depth = 0;
        while (c != null && depth < 10) {
            ViewParent p = c.getParent();
            if (!(p instanceof View)) {
                sb.append("|root");
                break;
            }
            View pv = (View) p;
            int[] pp = new int[2];
            pv.getLocationOnScreen(pp);
            sb.append("|").append(pv.getClass().getSimpleName())
                    .append("[v=").append(pv.getVisibility())
                    .append(",a=").append(pv.getAlpha())
                    .append(",tx=").append(pv.getTranslationX())
                    .append(",ty=").append(pv.getTranslationY())
                    .append(",scr=[").append(pp[0]).append(",").append(pp[1]).append("]")
                    .append("]");
            c = pv;
            depth++;
        }
        // 真实栏内容参照 (层应停靠其位置)
        try {
            View realBar = sRealBar.get();
            if (realBar != null) {
                View rc = getRealContents(realBar);
                if (rc != null) {
                    int[] rp = new int[2];
                    rc.getLocationOnScreen(rp);
                    sb.append(" rc=[").append(rp[0]).append(",").append(rp[1]).append("]");
                }
            }
        } catch (Throwable t) {
            // 忽略
        }
        return sb.toString();
    }

    /** 诊断: 位图稀疏网格最大 alpha (0=全透明, 255=有不透明像素; stride 16). */
    private static int bmpMaxAlpha(Bitmap b) {
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            int max = 0;
            for (int y = h / 4; y < h; y += 16) {
                for (int x = 2; x < w; x += 16) {
                    int a = (b.getPixel(x, y) >>> 24);
                    if (a > max) {
                        max = a;
                    }
                }
            }
            return max;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void removeLayers() {
        detachLayer(sLeftLayer.get());
        detachLayer(sRightLayer.get());
        sLeftLayer = new WeakReference<ImageView>(null);
        sRightLayer = new WeakReference<ImageView>(null);
        sLayerParentRef = new WeakReference<ViewGroup>(null);
    }

    private static void detachLayer(ImageView layer) {
        if (layer != null && layer.getParent() instanceof ViewGroup) {
            ((ViewGroup) layer.getParent()).removeView(layer);
        }
    }

    /**
     * 收尾轮询重截 (3: 隐私胶囊/时钟分钟/电量): 真实栏内容开始重现 (布局已刷新)
     * 时强制不透明重截 + 重测 base + 换图 + 吸附停靠位.
     */
    private static void resyncLayers(View bar) {
        if (!sBmpOk) {
            return;
        }
        captureBitmaps(bar, true);
        measureBase(bar);
        ImageView l = sLeftLayer.get();
        ImageView r = sRightLayer.get();
        if (l != null) {
            l.setImageBitmap(sLeftBmp);
        }
        if (r != null) {
            r.setImageBitmap(sRightBmp);
        }
        rideLayers(0f);
        Port.log(TAG + ": resync layers split=" + sSplitX + " " + layerChainState());
    }

    // -------------------------------------------------------------- contents

    /** 降级 (无位图) 时不隐藏: 空栏风险, contents 保持可见走纯 tint 切换. */
    private static void hidePanelContents(View bar) {
        if (!sBmpOk) {
            return;
        }
        View pc = getPanelContents(bar);
        if (pc == null) {
            return;
        }
        sContentsOrigAlpha = pc.getAlpha();
        pc.setAlpha(0f);
        sContentsHidden = true;
    }

    private static void restoreContentsAlpha(View bar) {
        if (!sContentsHidden) {
            return;
        }
        View pc = getPanelContents(bar);
        if (pc != null) {
            pc.setAlpha(sContentsOrigAlpha);
        }
        sContentsHidden = false;
    }

    // ----------------------------------------------- CC 栏横向移动 (分组)

    /**
     * CC 栏横向移动: settle/collapse 交叉淡化期 contents 分组 translationX.
     * 左组 (运营商/时钟/杂项) 按左移量, 右组 (图标区) 按右移量 —
     * 与位图半层 base+shift*p 的 X 骑乘对齐, contents 淡入/淡出时同步横移.
     */
    private static void addGroupShiftAnims(View bar, ArrayList<Animator> anims,
            boolean toZero, long ms, TimeInterpolator ip) {
        View pc = getPanelContents(bar);
        if (pc == null) {
            return;
        }
        float lo = toZero ? 0f : -sOpShift;
        addOneGroupAnim(sOperatorId == 0 ? null : pc.findViewById(sOperatorId), lo, anims, ms, ip);
        addOneGroupAnim(sClockId == 0 ? null : pc.findViewById(sClockId),
                toZero ? 0f : -sClockShift, anims, ms, ip);
        addOneGroupAnim(sUnstableId == 0 ? null : pc.findViewById(sUnstableId),
                toZero ? 0f : -sUnstableShift, anims, ms, ip);
        addOneGroupAnim(sIconAreaId == 0 ? null : pc.findViewById(sIconAreaId),
                toZero ? 0f : -sIconShift, anims, ms, ip);
    }

    private static void addOneGroupAnim(View v, float to,
            ArrayList<Animator> anims, long ms, TimeInterpolator ip) {
        if (v != null && v.getTranslationX() != to) {
            anims.add(objAnim(v, "translationX", v.getTranslationX(), to, ms, ip));
        }
    }

    /** 分组 translationX 硬复位 (engage 起点/settle 结束/hideBar/旋转降级). */
    private static void resetGroupShifts(View bar) {
        View pc = getPanelContents(bar);
        if (pc == null) {
            return;
        }
        if (sOperatorId != 0) {
            resetOneGroupShift(pc.findViewById(sOperatorId));
        }
        if (sClockId != 0) {
            resetOneGroupShift(pc.findViewById(sClockId));
        }
        if (sUnstableId != 0) {
            resetOneGroupShift(pc.findViewById(sUnstableId));
        }
        if (sIconAreaId != 0) {
            resetOneGroupShift(pc.findViewById(sIconAreaId));
        }
    }

    private static void resetOneGroupShift(View v) {
        if (v != null && v.getTranslationX() != 0f) {
            v.setTranslationX(0f);
        }
    }

    // ------------------------------------------------------------------ tint

    /** 静态捕获: 纯色 tint (区域弃用 — settle 用空区域+纯色, 旋转安全). */
    private static void captureTintStatics(View bar) {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return;
            }
            View rc = getRealContents(realBar);
            View pc = getPanelContents(bar);
            if (rc == null || pc == null) {
                return;
            }
            sRealTint = XposedHelpers.getIntField(rc, "mIconTint");
            sPanelTint = XposedHelpers.getIntField(pc, "mIconTint");
            View clock = sClockId == 0 ? null : realBar.findViewById(sClockId);
            sRealClockRef = new WeakReference<View>(clock);
        } catch (Throwable t) {
            Port.log(TAG + ": tint capture failed", t);
        }
    }

    /** settle 结束刷新 (CC 期间 DarkIconDispatcher 可能已重算面板 tint). */
    private static void refreshTintStatics(View bar) {
        try {
            View pc = getPanelContents(bar);
            if (pc != null) {
                sPanelTint = XposedHelpers.getIntField(pc, "mIconTint");
            }
        } catch (Throwable t) {
            // 保持旧值
        }
    }

    /** 空区域 + 纯色: onDarkChanged 拷贝 Rect 进 mTintArea, 空区域 = 全域统一. */
    private static void applyTint(View contents, int tint) {
        if (contents == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(contents, "onDarkChanged",
                    new Rect(), Float.valueOf(0f), Integer.valueOf(tint));
            XposedHelpers.callMethod(contents, "refreshTintOnLayout");
        } catch (Throwable t) {
            Port.log(TAG + ": applyTint failed", t);
        }
    }

    // ------------------------------------------------------------------ clip

    /** 半层/栏外移裁剪保护: 栏/contents/父容器/层宿主 (祖父) clipChildren+clipToPadding 全开. */
    private static void disableClip(View bar) {
        restoreClip();
        try {
            if (bar instanceof ViewGroup) {
                addClip((ViewGroup) bar);
            }
            View pc = getPanelContents(bar);
            if (pc instanceof ViewGroup) {
                addClip((ViewGroup) pc);
            }
            ViewParent p = bar.getParent();
            if (p instanceof ViewGroup) {
                addClip((ViewGroup) p);
                ViewParent gp = p.getParent(); // 层宿主 (mSubPanelContainer)
                if (gp instanceof ViewGroup) {
                    addClip((ViewGroup) gp);
                }
            }
        } catch (Throwable t) {
            Port.log(TAG + ": disableClip failed", t);
        }
    }

    private static void addClip(ViewGroup g) {
        sClipViews.add(g);
        sClipChildOrig.add(g.getClipChildren());
        sClipPadOrig.add(g.getClipToPadding());
        g.setClipChildren(false);
        g.setClipToPadding(false);
    }

    private static void restoreClip() {
        for (int i = 0; i < sClipViews.size(); i++) {
            ViewGroup g = sClipViews.get(i);
            Boolean child = i < sClipChildOrig.size() ? sClipChildOrig.get(i) : Boolean.TRUE;
            Boolean pad = i < sClipPadOrig.size() ? sClipPadOrig.get(i) : Boolean.TRUE;
            g.setClipChildren(child.booleanValue());
            g.setClipToPadding(pad.booleanValue());
        }
        sClipViews.clear();
        sClipChildOrig.clear();
        sClipPadOrig.clear();
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

    /**
     * 展开 settle (进度=100 提交点, 手指可能未抬): 跟手贯穿 — 随后的
     * onMove(SETTLING) 继续手指-进度映射, 真实 CC 栏仅在进度100 (p≥0.999)
     * 由 applyProgress 内联接替, 与 stock 卡片进入动画完全解耦 (不等待其结束).
     * 返回空占位动画: 阻断 stock 原生 alpha 0→1 抖动; 手指已抬的程序化展开
     * 由占位 update (线性 0→1) 驱动同一进度模型.
     */
    private static void onSettle(final View bar, XC_MethodHook.MethodHookParam param) {
        if (sState != FOLLOWING && sState != SETTLING && sState != DROPPING
                && sState != WAITING_REAL) {
            return; // 未跟手 (锁屏 shade / 补偿路径), stock 淡入
        }
        sState = SETTLING;
        cancelPhaseAnim();
        bar.removeCallbacks(sCollapseWatch);
        bar.removeCallbacks(sSwapPoll);
        bar.setVisibility(View.VISIBLE); // 同帧对抗 stock 前置 GONE
        showLayers();
        ValueAnimator hold = ValueAnimator.ofFloat(0f, 1f);
        hold.setDuration(400L); // ≈首卡片时长: 若成为 stock 总集 get(0) 不压缩卡片动画
        hold.setInterpolator(springIp(400f, SPRING_K)); // spring 曲线推进
        final float settleP0 = sProgress; // 占位进度源起点 (不回跳)
        hold.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                // 手指未抬: onMove 映射优先 (此处不干预); 手指已抬 (程序化
                // 展开): 占位即进度源, 从 settle 时进度线性推进至 100.
                if (!sFingerDown && sState == SETTLING && !sRevealed) {
                    float f = ((Float) a.getAnimatedValue()).floatValue();
                    applyProgress(bar, settleP0 + (1f - settleP0) * f);
                }
            }
        });
        sPhaseAnim = hold;
        param.setResult(hold);
        Port.log(TAG + ": settle (finger-mapped) ty=" + bar.getTranslationY()
                + " fingerDown=" + sFingerDown + " bmp=" + sBmpOk);
    }

    /**
     * 收起一次性: 整栏 ty→−offset; 阈值交接 1 帧硬切 (无透明度 — 用户要求):
     * 位图半层瞬时接管 (alpha=1), CC 栏瞬时隐去 (alpha=0); 位图像素本身即
     * 真实色, 无 tint 硬切闪; 位置骑乘 (ty/组移/层 tx-ty) 保留,
     * 交接瞬间两态同位无缝; 结束停靠进入收尾轮询.
     */
    private static void onCollapse(final View bar, XC_MethodHook.MethodHookParam param) {
        if (sState != FOLLOWING && sState != SETTLING && sState != SETTLED
                && sState != COLLAPSING && sState != WAITING_REAL) {
            return; // 未接管, stock 原地淡出
        }
        // 横竖屏新鲜度: CC 开着旋转 → 位图/基准/位移全是旧方向几何 (真实栏 GONE
        // 无法重测), 硬走漂移必横移错位 — 清场交还 stock 原生收起 (新方向自洽).
        if (sGeomW != 0
                && sGeomW != bar.getResources().getDisplayMetrics().widthPixels) {
            cancelPhaseAnim();
            bar.removeCallbacks(sSwapPoll);
            resetGroupShifts(bar);
            restoreContentsAlpha(bar);
            removeLayers();
            restoreClip();
            restoreForcedVis();
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
            sState = IDLE;
            Port.log(TAG + ": collapse degrade (orientation changed)");
            return; // 不 setResult → stock 自建动画原样跑
        }
        // 静态手势 (旋转首拉) 收起兜底: 面板已展开重排, 几何可信即重测
        // 并把复制层刷到 settle 位 (reveal 后未刷成时) — 本次收起即正确
        refreshStaticGeometry(bar);
        sState = COLLAPSING;
        cancelPhaseAnim();
        sSpringRunning = false;
        bar.setVisibility(View.VISIBLE);
        showLayers();
        View pc = getPanelContents(bar);
        // 1 帧硬切: 位图半层瞬时接管, CC 栏瞬时隐去
        if (sBmpOk) {
            bar.setAlpha(0f);
        } else {
            applyTint(pc, sRealTint); // 降级: 无位图, 硬切回真实色 (二轮行为)
        }
        // 收起漂移固定时长 0.5s (用户定), spring 曲线 (dur+k 全局可调)
        long driftMs = COLLAPSE_DRIFT_MS;
        TimeInterpolator ip = springIp(driftMs, SPRING_K);
        ArrayList<Animator> anims = new ArrayList<Animator>();
        if (!sBmpOk) {
            // 降级: CC 栏 (contents 可见) 本体位移
            anims.add(objAnim(bar, "translationY", bar.getTranslationY(), -sOffset,
                    driftMs, ip));
            addGroupShiftAnims(bar, anims, false, driftMs, ip); // CC 栏横向滑出面板→真实几何
        }
        // 位图可用: 仅复制层漂移, CC 栏不位移
        ImageView l = sLeftLayer.get();
        if (l != null) {
            l.setAlpha(1f);
            anims.add(objAnim(l, "translationX", l.getTranslationX(), sBaseLeftTx,
                    driftMs, ip));
            anims.add(objAnim(l, "translationY", l.getTranslationY(), sBaseTy,
                    driftMs, ip));
        }
        ImageView r = sRightLayer.get();
        if (r != null) {
            r.setAlpha(1f);
            anims.add(objAnim(r, "translationX", r.getTranslationX(), sBaseRightTx,
                    driftMs, ip));
            anims.add(objAnim(r, "translationY", r.getTranslationY(), sBaseTy,
                    driftMs, ip));
        }
        AnimatorSet set = new AnimatorSet();
        set.playTogether(anims);
        set.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator a) {
                showLayers(); // stock 子遍历隐藏后再确保可见
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
        // 自启: stock 外层 setDuration(卡片300ms) 会递归覆盖子 AnimatorSet 时长
        // → 漂移被拖成 300ms 与 stock 卡片动画错拍. 自启保住设计时长, 不受外层摆布.
        set.start();
        AnimatorSet dummy = new AnimatorSet();
        dummy.setDuration(driftMs); // 占位: 外层 get(0) 兜底时长, 播放无害空集
        param.setResult(dummy);
        bar.removeCallbacks(sCollapseWatch);
        bar.postDelayed(sCollapseWatch, driftMs + 150L); // 停滞保险
        ImageView lg = sLeftLayer.get();
        Port.log(TAG + ": collapse drift to " + (-sOffset) + " layerA="
                + (lg == null ? "none" : String.valueOf(lg.getAlpha()))
                + " " + layerChainState());
    }

    /** 收起漂移结束: 停靠真实栏位置, 启动收尾轮询 (重截窗口 + 重现探针). */
    private static void finishCollapse(View bar) {
        if (sState == COLLAPSING) {
            sState = WAITING_REAL;
            showLayers(); // fling 收尾遍历可能隐藏过位图层, 停靠即复显
            startSwapPoll(bar);
            Port.log(TAG + ": parked at -" + sOffset + " " + layerChainState());
        }
    }

    /** 停靠期面板重开: 停靠替身过时, 拆层复位交还 stock 原生重开 (无连贯). */
    private static void abandonParked() {
        View bar = sTarget.get();
        if (bar != null) {
            bar.removeCallbacks(sSwapPoll);
            bar.removeCallbacks(sCollapseWatch);
            // 复位为 stock 可接管态: 重开面板中栏应可见 (stock 展开动画自管),
            // 非 hideBar 的 GONE (stock 原生重开不会解除我们写的 GONE).
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(1f);
            bar.setTranslationY(0f);
            restoreContentsAlpha(bar);
            resetGroupShifts(bar);
            removeLayers();
            restoreClip();
            restoreForcedVis();
        }
        sState = IDLE;
        Port.log(TAG + ": parked abandoned (panel reopened)");
    }

    /** 点6: 收尾轮询启动 (sResyncDone 重置 → 隐私胶囊重截窗口开启). */
    private static void startSwapPoll(View bar) {
        sPollCount = 0;
        sResyncDone = false;
        bar.removeCallbacks(sSwapPoll);
        bar.post(sSwapPoll); // 首拍立即: 尽快对抗收尾遍历的隐藏
    }

    /**
     * 真实栏内容重现探针: 时钟/电池 isShown 且有效 alpha≥0.99.
     * 有效 alpha = 沿祖先链乘积 — stock 淡入打在 contents 容器上, 子 View
     * 自身 alpha 恒 1, 只查子曾致淡入初期误判 → 提前拆层 = 空缺.
     */
    private static boolean realBarShown() {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return false;
            }
            View clock = sRealClockRef.get();
            if (clock == null && sClockId != 0) {
                clock = realBar.findViewById(sClockId);
            }
            if (clock != null && clock.isShown() && effectiveAlpha(clock) >= 0.99f) {
                return true;
            }
            View bat = findBattery(realBar);
            return bat != null && bat.isShown() && effectiveAlpha(bat) >= 0.99f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 真实栏内容开始重现 (布局已刷新, 淡入中): 隐私胶囊重截窗口触发条件 (3). */
    private static boolean realShowFading() {
        try {
            View realBar = sRealBar.get();
            if (realBar == null) {
                return false;
            }
            View clock = sRealClockRef.get();
            if (clock == null && sClockId != 0) {
                clock = realBar.findViewById(sClockId);
            }
            if (clock != null && clock.getVisibility() == View.VISIBLE
                    && effectiveAlpha(clock) > 0.01f) {
                return true;
            }
            View bat = findBattery(realBar);
            return bat != null && bat.getVisibility() == View.VISIBLE
                    && effectiveAlpha(bat) > 0.01f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 有效绘制 alpha: 自身×祖先链乘积 (容器淡入时子 alpha 恒 1 的盲区修复). */
    private static float effectiveAlpha(View v) {
        float a = 1f;
        View c = v;
        while (c != null) {
            a *= c.getAlpha();
            if (a <= 0.005f) {
                return 0f;
            }
            ViewParent p = c.getParent();
            c = p instanceof View ? (View) p : null;
        }
        return a;
    }

    /** 真实栏已重现: 硬切收尾 — 栏复位 stock 隐藏态, 半层/裁剪/强制可见全清. */
    private static void swapNow(View bar) {
        bar.removeCallbacks(sSwapPoll);
        bar.removeCallbacks(sCollapseWatch);
        // 拆层前记录: 触发原因 + 真实栏重现状态 (判 "拆层后顶栏空缺" 用)
        Port.log(TAG + ": swap shown=" + realBarShown() + " fading=" + realShowFading()
                + " poll=" + sPollCount + " " + layerChainState());
        hideBar(bar);
        restoreForcedVis();
        sState = IDLE;
    }

    // ------------------------------------------------------------- drop-back

    private static void onFingerUp(XC_MethodHook.MethodHookParam param) {
        sFingerDown = false;
        boolean enabled = Port.on(Port.KEY_CC_STATUSBAR_CONTINUITY);
        if (sState == WAITING_REAL) {
            // 松手循环停靠态: 手指已抬 → 重启收尾轮询 (此前只停靠不 swap)
            View parked = sTarget.get();
            if (parked != null && enabled) {
                startSwapPoll(parked);
            }
            return;
        }
        // settle 提交后松手 (fling 展开: 未达 settle 位但达速度要求, 同帧
        // hook 顺序 onSettle 先于本 hook); 或 settle hook 未及触发的边路
        // (FOLLOWING + showingType!=0): 剩余进度按手指速率续接 (恒速线性)
        // 到 100 才真栏接替 — 不突变.
        if (sState == SETTLING
                || (sState == FOLLOWING && showingType(param.thisObject) != 0)) {
            final View sBar = sTarget.get();
            if (sBar == null) {
                sState = IDLE;
                return;
            }
            if (!enabled) {
                hideBar(sBar);
                sState = IDLE;
                return;
            }
            cancelPhaseAnim(); // 停 settle 占位 update (防进度回落回退接替)
            sSpringRunning = false; // 停跟手 spring 帧循环 (续接动画接管)
            if (sProgress >= 0.999f) {
                // 已达进度100 (真栏已接替): 收尾静态刷新
                applyProgress(sBar, 1f);
                if (!sStaticGesture) {
                    refreshOffset(sBar);
                }
                refreshTintStatics(sBar);
                sState = SETTLED;
                Port.log(TAG + ": settled (finger up) progress=" + sProgress);
                return;
            }
            // 剩余进度续接: 时长 = 剩余纵向距离/|手指速率| (栏/层同距), spring 曲线
            sState = SETTLING;
            long contMs = velDuration(sOffset * Math.max(0f, 1f - sProgress));
            final ValueAnimator cont = ValueAnimator.ofFloat(sProgress, 1f);
            cont.setInterpolator(springIp(contMs, SPRING_K));
            cont.setDuration(contMs);
            cont.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    if (sState == SETTLING) {
                        applyProgress(sBar, ((Float) a.getAnimatedValue()).floatValue());
                    }
                }
            });
            cont.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator a) {
                }

                @Override
                public void onAnimationEnd(Animator a) {
                    if (sPhaseAnim == cont && sState == SETTLING) {
                        // 续接到 100: 真栏接替 + 静态刷新 (cancel 不入)
                        applyProgress(sBar, 1f);
                        if (!sStaticGesture) {
                            refreshOffset(sBar);
                        }
                        refreshTintStatics(sBar);
                        sState = SETTLED;
                        Port.log(TAG + ": settle continue done");
                    }
                }

                @Override
                public void onAnimationCancel(Animator a) {
                }

                @Override
                public void onAnimationRepeat(Animator a) {
                }
            });
            sPhaseAnim = cont;
            cont.start();
            Port.log(TAG + ": settle continue from " + sProgress);
            return;
        }
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
        // drop-back: 位图保持不透明随行回位 (无淡入), 结束停靠进入收尾轮询.
        // 松手续接: 时长 = 剩余距离/|手指速率|, spring 曲线.
        sState = DROPPING;
        cancelPhaseAnim();
        sSpringRunning = false;
        long dropMs = velDuration(sOffset * sProgress); // 剩余纵向距离 (栏/层同距)
        TimeInterpolator dip = springIp(dropMs, SPRING_K);
        ArrayList<Animator> anims = new ArrayList<Animator>();
        if (!sBmpOk) {
            // 降级: CC 栏 (contents 可见) 本体位移
            anims.add(objAnim(bar, "translationY", bar.getTranslationY(), -sOffset,
                    dropMs, dip));
            addGroupShiftAnims(bar, anims, false, dropMs, dip); // 分组同步骑回真实位
        }
        // 位图可用: 仅复制层回落, CC 栏不位移
        ImageView l = sLeftLayer.get();
        if (l != null) {
            anims.add(objAnim(l, "translationX", l.getTranslationX(), sBaseLeftTx,
                    dropMs, dip));
            anims.add(objAnim(l, "translationY", l.getTranslationY(), sBaseTy,
                    dropMs, dip));
        }
        ImageView r = sRightLayer.get();
        if (r != null) {
            anims.add(objAnim(r, "translationX", r.getTranslationX(), sBaseRightTx,
                    dropMs, dip));
            anims.add(objAnim(r, "translationY", r.getTranslationY(), sBaseTy,
                    dropMs, dip));
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
            sState = WAITING_REAL;
            startSwapPoll(bar);
            Port.log(TAG + ": drop parked, polling real bar");
        }
    }

    // ---------------------------------------------------------- 收尾宿主 (点6)

    private static boolean inEndgame() {
        return sState == COLLAPSING || sState == DROPPING || sState == WAITING_REAL;
    }

    private static void forceVisible(View host) {
        if (host.getVisibility() == View.VISIBLE) {
            return; // stock 本就显示: 无对抗, 不记录 (swap 不得把 stock 的 VISIBLE 打回隐藏)
        }
        if (sForcedVis.contains(host)) {
            host.setVisibility(View.VISIBLE); // 已记录: 只复显, 保留首次原值
            return;
        }
        sForcedVisOrig.add(Integer.valueOf(host.getVisibility()));
        sForcedVis.add(host);
        host.setVisibility(View.VISIBLE);
    }

    /** stock 自身已复显宿主: 撤销对抗记录 (swap 不再回置, 防止覆盖 stock 的显示意图). */
    private static void unforceVisible(View host) {
        int idx = sForcedVis.indexOf(host);
        if (idx >= 0) {
            sForcedVis.remove(idx);
            sForcedVisOrig.remove(idx);
        }
    }

    /** 轮询期间反复对抗 stock 的 INVISIBLE 下发 (面板/shade 根 + 位图层双保险). */
    private static void keepHostsVisible(View bar) {
        for (int i = 0; i < sForcedVis.size(); i++) {
            View host = sForcedVis.get(i);
            if (host != null && host.getVisibility() != View.VISIBLE) {
                host.setVisibility(View.VISIBLE);
            }
        }
        ImageView l = sLeftLayer.get();
        if (l != null && l.getVisibility() != View.VISIBLE) {
            l.setVisibility(View.VISIBLE); // 层挂祖父容器, stock 子遍历已扫不到, 双保险
        }
        ImageView r = sRightLayer.get();
        if (r != null && r.getVisibility() != View.VISIBLE) {
            r.setVisibility(View.VISIBLE);
        }
    }

    /** 交还 stock: 各宿主按被强制前的原值恢复 (PanelBar L269 / applyVisibility L303). */
    private static void restoreForcedVis() {
        for (int i = 0; i < sForcedVis.size(); i++) {
            View host = sForcedVis.get(i);
            if (host != null) {
                host.setVisibility(sForcedVisOrig.get(i).intValue());
            }
        }
        sForcedVis.clear();
        sForcedVisOrig.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static void cancelPhaseAnim() {
        Animator a = sPhaseAnim;
        if (a != null) {
            sPhaseAnim = null;
            try {
                a.cancel();
            } catch (Throwable t) {
                Port.log(TAG + ": cancel phase failed", t);
            }
        }
    }

    /** 复位 stock 隐藏态 (GONE + alpha 1 + ty 0 + contents/分组复位 + 半层/裁剪清除). */
    private static void hideBar(View bar) {
        bar.setVisibility(View.GONE);
        bar.setAlpha(1f);
        bar.setTranslationY(0f);
        sSpringRunning = false;
        restoreContentsAlpha(bar);
        resetGroupShifts(bar);
        removeLayers();
        restoreClip();
    }

    private static ObjectAnimator objAnim(View v, String prop, float from, float to, long ms) {
        return objAnim(v, prop, from, to, ms, FOSI);
    }

    /** spring 曲线位移 (续接/漂移/回落): dur+k 两个全局可调值. */
    private static ObjectAnimator objAnim(View v, String prop, float from, float to,
            long ms, TimeInterpolator ip) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, prop, from, to);
        a.setInterpolator(ip);
        a.setDuration(ms);
        return a;
    }

    /** 松手续接时长: 剩余距离 / |手指速率|, 夹取 [80,200]ms — 与跟手同速到达. */
    private static long velDuration(float remainPx) {
        if (remainPx <= 1f) {
            return 80L;
        }
        float v = Math.abs(sVelY);
        float ms = v > 0.05f ? remainPx / v : 200f;
        return (long) Math.max(80f, Math.min(200f, ms));
    }

    /**
     * spring 插值 (动画用), 仅两个可调入参: durMs=时标(ms), k=整体系数.
     * 欠阻尼二阶弹簧归一化阶跃 h(τ)=1−e^(−ζωτ)·(cos(ωdτ)+ζω/ωd·sin(ωdτ)),
     * ω=2π·k·1000/dur, 阻尼 ζ=0.8 (内置, 微弹).
     */
    private static TimeInterpolator springIp(final float durMs, final float k) {
        return new TimeInterpolator() {
            @Override
            public float getInterpolation(float t) {
                float omega = 2f * (float) Math.PI * 1000f * k / durMs;
                float zeta = 0.8f;
                float tau = t * durMs / 1000f;
                float wd = omega * (float) Math.sqrt(1f - zeta * zeta);
                if (wd <= 0f) {
                    return t;
                }
                return 1f - (float) Math.exp(-zeta * omega * tau)
                        * ((float) Math.cos(wd * tau)
                        + (zeta * omega / wd) * (float) Math.sin(wd * tau));
            }
        };
    }

    /** 当前旋转 (0/1/2/3), 取不到返回 -1 (不参与旋转检测). */
    private static int currentRotation(View bar) {
        try {
            android.view.Display d = bar.getDisplay();
            return d == null ? -1 : d.getRotation();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static View getRealContents(View realBar) {
        ensureIds(realBar.getContext());
        return sContentsId == 0 ? null : realBar.findViewById(sContentsId);
    }

    private static View getPanelContents(View bar) {
        ensureIds(bar.getContext());
        return sContentsId == 0 ? null : bar.findViewById(sContentsId);
    }

    private static void ensureIds(Context ctx) {
        if (sIdsReady) {
            return;
        }
        Resources r = ctx.getResources();
        String pkg = "com.android.systemui";
        sBatteryId = r.getIdentifier("battery", "id", pkg);
        sClockId = r.getIdentifier("clock", "id", pkg);
        sIconAreaId = r.getIdentifier("system_icon_area", "id", pkg);
        sContentsId = r.getIdentifier("status_bar_contents", "id", pkg);
        sOperatorId = r.getIdentifier("operator_name_container", "id", pkg);
        sUnstableId = r.getIdentifier("system_icon_unstable", "id", pkg);
        sIdsReady = true;
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
