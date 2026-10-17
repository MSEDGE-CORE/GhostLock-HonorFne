package com.wxz.hnlauncherport.folder;

import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 进入文件夹时加快图标移动 (归属模糊设置项 KEY_BLUR):
 *  (1) 图标移动/缩放/透明度曲线改为"前期更快":
 *      stock = CubicBezier(0.2, 0, 0.2, 1) (缓入缓出, 起步慢),
 *      改为 CubicBezier(0, 0, 0.15, 1) (起步即最快, 快速到位);
 *      覆盖 CellAnimItem.cellAnimItemInterpolar / cellAlphaAnimInterpolar
 *      与 CardFolderOpenAndCloseAnim.transAndScaleInterpolator (文件夹容器).
 *  (2) 打开(进入)路径所有动画时长 -100ms, 下限 100ms:
 *      CardFolderAnimation: backGroundTransDuration 500->400,
 *        backGroundOpenAlphaDuration 200->100, textAlphaDuration 250->150;
 *      CellAnimItem: iconTransDuration/iconScaleDuration/iconShowAlphaDuration
 *        500->400, iconHideAlphaDuration 150->100, frameOpenAlphaDuration
 *        400->300. 关闭路径 BackDuration 字段不动.
 *
 * 时机: Launcher 启动时 Launcher.java:1452 updateCardFolderAnimPara() 一次性
 * 读 AnimScene 云参数(本机无云 -> 保持默认)写静态字段; 随后每次开文件夹走
 * FolderIcon.createOwnAnimator -> CardFolderAnimation.openCardFolder, 在其
 * before 时机应用一次(快照防重复扣减); 若云参数刷新(updateCardFolderAnimPara
 * 再次执行)则使快照失效, 下次开文件夹重新推导.
 */
public final class FolderOpenSpeed {
    private static final String CARD_ANIM_CLS =
            "com.hihonor.android.launcher.cardstyle.cardfolder.anim.CardFolderAnimation";
    private static final String CELL_ITEM_CLS =
            "com.hihonor.android.launcher.cardstyle.cardfolder.anim.CellAnimItem";
    private static final String BG_ANIM_CLS =
            "com.hihonor.android.launcher.cardstyle.cardfolder.anim.CardFolderOpenAndCloseAnim";
    private static final String REV_CURVE_CLS =
            "com.hihonor.android.launcher.cardstyle.cardfolder.anim.ReverseableFrictionCurseInterpolator";
    private static final String BEZIER_CLS =
            "com.hihonor.android.launcher.CubicBezierInterpolator";
    private static final String UTILS_CLS =
            "com.hihonor.android.launcher.cardstyle.cardfolder.CardFolderUtils";
    private static final String HELPER_CLS =
            "com.hihonor.android.launcher.folder.FolderAnimationHelper";
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";
    private static final String FOLDER_ICON_CLS =
            "com.hihonor.android.launcher.FolderIcon";

    private static volatile boolean sApplied = false;

    public static void install(final ClassLoader cl) {
        Class<?> helperCls = XposedHelpers.findClass(HELPER_CLS, cl);
        Class<?> launcherCls = XposedHelpers.findClass(LAUNCHER_CLS, cl);
        Class<?> folderIconCls = XposedHelpers.findClass(FOLDER_ICON_CLS, cl);

        // (a) 标准"更多格"打开路径: Folder.animateOpen -> animateIn(folderIcon=null)
        //     -> initOpenFolderAnimSet -> mAnimatorSet(图标移动/缩放/透明度)
        //     duration=getCardFolderOpenDuration()(读 backGroundTransDuration),
        //     interpolator=INTERPOLATOR(Curves.STANDARD).
        //     before animateIn: 先缩静态时长, 使 setDuration 读到 -100ms 值.
        XposedHelpers.findAndHookMethod(helperCls, "animateIn",
                launcherCls, Rect.class, Drawable.class, boolean.class, folderIconCls,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        apply(cl);
                    }
                });

        // (b) after initOpenFolderAnimSet: mAnimatorSet 覆盖先快后慢曲线.
        XposedHelpers.findAndHookMethod(helperCls, "initOpenFolderAnimSet",
                launcherCls, Rect.class, Drawable.class, boolean.class, folderIconCls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        overrideSetCurve(cl, param.thisObject);
                    }
                });

        // (d) 关闭路径: animateOut -> initCloseFolderAnimSet, :316 同样
        //     mAnimatorSet.setInterpolator(INTERPOLATOR); 覆盖为同一先快后慢曲线,
        //     与打开速率统一.
        XposedHelpers.findAndHookMethod(helperCls, "initCloseFolderAnimSet",
                launcherCls, Drawable.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        overrideSetCurve(cl, param.thisObject);
                    }
                });

        // (c) 卡片文件夹直开/反转路径: openCardFolder before 应用一次.
        XposedHelpers.findAndHookMethod(CARD_ANIM_CLS, cl, "openCardFolder",
                LAUNCHER_CLS, FOLDER_ICON_CLS, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        apply(cl);
                    }
                });

        try {
            XposedHelpers.findAndHookMethod(UTILS_CLS, cl,
                    "updateCardFolderAnimPara", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sApplied = false;
                        }
                    });
        } catch (Throwable t) {
            Port.log("FolderOpenSpeed utils hook failed", t);
        }

        Port.log("FolderOpenSpeed installed");
    }

    private static void overrideSetCurve(ClassLoader cl, Object helper) {
        try {
            Object set = XposedHelpers.getObjectField(helper, "mAnimatorSet");
            if (set instanceof AnimatorSet) {
                ((AnimatorSet) set).setInterpolator(
                        (TimeInterpolator) XposedHelpers.newInstance(
                                XposedHelpers.findClass(BEZIER_CLS, cl),
                                0f, 0f, 0.15f, 1f));
            }
        } catch (Throwable t) {
            Port.log("FolderOpenSpeed interpolator failed", t);
        }
    }

    private static void apply(ClassLoader cl) {
        if (sApplied) {
            return;
        }
        try {
            Class<?> cardCls = XposedHelpers.findClass(CARD_ANIM_CLS, cl);
            Class<?> cellCls = XposedHelpers.findClass(CELL_ITEM_CLS, cl);
            Class<?> bgCls = XposedHelpers.findClass(BG_ANIM_CLS, cl);
            Class<?> bezCls = XposedHelpers.findClass(BEZIER_CLS, cl);
            Class<?> revCls = XposedHelpers.findClass(REV_CURVE_CLS, cl);

            // (1) 前期更快曲线: cubic-bezier(0,0,0.15,1), 三个独立实例.
            XposedHelpers.setStaticObjectField(cellCls, "cellAnimItemInterpolar",
                    newCurve(bezCls, revCls));
            XposedHelpers.setStaticObjectField(cellCls, "cellAlphaAnimInterpolar",
                    newCurve(bezCls, revCls));
            XposedHelpers.setStaticObjectField(bgCls, "transAndScaleInterpolator",
                    newCurve(bezCls, revCls));

            // (2) 打开路径动画时长 -100ms (下限 100ms, 不增长).
            shorten(cardCls, "backGroundTransDuration");
            shorten(cardCls, "backGroundOpenAlphaDuration");
            shorten(cardCls, "textAlphaDuration");
            shorten(cellCls, "iconTransDuration");
            shorten(cellCls, "iconScaleDuration");
            shorten(cellCls, "iconShowAlphaDuration");
            shorten(cellCls, "iconHideAlphaDuration");
            shorten(cellCls, "frameOpenAlphaDuration");

            sApplied = true;
            Port.log("FolderOpenSpeed: open-folder front-loaded curve + durations -100ms");
        } catch (Throwable t) {
            Port.log("FolderOpenSpeed apply failed", t);
        }
    }

    private static Object newCurve(Class<?> bezCls, Class<?> revCls) {
        TimeInterpolator bez = (TimeInterpolator) XposedHelpers.newInstance(
                bezCls, 0f, 0f, 0.15f, 1f);
        return XposedHelpers.newInstance(revCls, bez);
    }

    private static void shorten(Class<?> cls, String field) {
        float v = (Float) XposedHelpers.getStaticObjectField(cls, field);
        float nv = v - 100f;
        if (nv < 100f) {
            nv = 100f;
        }
        if (nv < v) {
            XposedHelpers.setStaticObjectField(cls, field, Float.valueOf(nv));
            Port.log("FolderOpenSpeed: " + field + " " + v + " -> " + nv);
        }
    }

    private FolderOpenSpeed() {}
}
