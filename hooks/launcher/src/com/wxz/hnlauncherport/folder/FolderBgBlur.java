package com.wxz.hnlauncherport.folder;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 任务27: 启用文件夹打开时"背景图标缩小 + 模糊" (归属模糊设置项 KEY_BLUR),
 * 且缩小与模糊时长缩为当前值的 0.6.
 *
 * Stock 链路 (背景图标缩小并被模糊):
 *   Folder.animateOpen -> FolderAnimationHelper.animateIn
 *     -> isDynamicBlurFolder
 *        = CardFolderAnimation.getUseSpaceHierarchyAnim()   <- 本机 false
 *          && Utilities.isEnableWindowBlur()                <- 本机 true
 *          && !editMode (+folder open, !accessibility)
 *   true 时:
 *     createScaleAnimator: workspace/hotseat/quicknav 缩至 0.9 (背景图标缩小)
 *     LevelAnimationMgr.changeScene(1,0,true,400): RenderEffect blur 0->109px
 *       施加于 Workspace/Hotseat/QuickNaviView/DesktopSearchEnter (背景模糊)
 *   false 时 (本机现状): 无缩小无模糊.
 *
 * 拦截:
 *  (1) CardFolderAnimation.getUseSpaceHierarchyAnim after -> KEY_BLUR 开启
 *      时强制 TRUE (启用整条链路);
 *  (2) FolderAnimationHelper.initOpenFolderAnimSet after -> mScaleAnimator
 *      (背景缩小) duration * 0.6;
 *  (3) LevelAnimationMgr.changeScene before -> folder 场景(scene==1)且
 *      enter (打开) 时 duration * 0.6 (400 -> 240).
 * 关闭文件夹路径 (enter=false) 保持原时长不动.
 */
public final class FolderBgBlur {
    private static final String CARD_ANIM_CLS =
            "com.hihonor.android.launcher.cardstyle.cardfolder.anim.CardFolderAnimation";
    private static final String HELPER_CLS =
            "com.hihonor.android.launcher.folder.FolderAnimationHelper";
    private static final String LEVEL_MGR_CLS =
            "com.hihonor.android.launcher.anim.LevelAnimationMgr";
    private static final String LAUNCHER_CLS =
            "com.hihonor.android.launcher.Launcher";
    private static final String FOLDER_ICON_CLS =
            "com.hihonor.android.launcher.FolderIcon";

    private static volatile boolean sEnabledLogged = false;

    public static void install(ClassLoader cl) {
        Class<?> cardAnimCls = XposedHelpers.findClass(CARD_ANIM_CLS, cl);
        Class<?> helperCls = XposedHelpers.findClass(HELPER_CLS, cl);
        Class<?> levelMgrCls = XposedHelpers.findClass(LEVEL_MGR_CLS, cl);
        Class<?> launcherCls = XposedHelpers.findClass(LAUNCHER_CLS, cl);
        Class<?> folderIconCls = XposedHelpers.findClass(FOLDER_ICON_CLS, cl);

        // (1) 启用文件夹动态模糊链路 (背景图标缩小 + RenderEffect 模糊).
        XposedHelpers.findAndHookMethod(cardAnimCls, "getUseSpaceHierarchyAnim",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        if (!Boolean.TRUE.equals(param.getResult())) {
                            param.setResult(Boolean.TRUE);
                            if (!sEnabledLogged) {
                                sEnabledLogged = true;
                                Port.log("FolderBgBlur: folder bg shrink+blur enabled");
                            }
                        }
                    }
                });

        // (2) 背景图标缩小时长 * 0.6.
        XposedHelpers.findAndHookMethod(helperCls, "initOpenFolderAnimSet",
                launcherCls, Rect.class, Drawable.class, boolean.class, folderIconCls,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        try {
                            Object scaleAnim = XposedHelpers.getObjectField(
                                    param.thisObject, "mScaleAnimator");
                            if (scaleAnim instanceof ValueAnimator) {
                                ValueAnimator anim = (ValueAnimator) scaleAnim;
                                long d = anim.getDuration();
                                if (d > 0) {
                                    long nd = (long) (d * 0.6f);
                                    anim.setDuration(nd);
                                    Port.log("FolderBgBlur: bg shrink duration " + d + " -> " + nd);
                                }
                            }
                        } catch (Throwable t) {
                            Port.log("FolderBgBlur scale duration failed", t);
                        }
                    }
                });

        // (3) 文件夹打开模糊时长 * 0.6 (仅 scene=folder 且 enter).
        XposedHelpers.findAndHookMethod(levelMgrCls, "changeScene",
                int.class, int.class, boolean.class, long.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!Port.on(Port.KEY_BLUR)) {
                            return;
                        }
                        int scene = (Integer) param.args[0];
                        boolean enter = (Boolean) param.args[2];
                        long dur = (Long) param.args[3];
                        if (scene == 1 && enter && dur > 0) {
                            param.args[3] = (long) (dur * 0.6f);
                            Port.log("FolderBgBlur: blur duration " + dur + " -> " + param.args[3]);
                        }
                    }
                });

        Port.log("FolderBgBlur installed");
    }

    private FolderBgBlur() {}
}
