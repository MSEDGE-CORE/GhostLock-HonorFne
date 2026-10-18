package com.wxz.hnlauncherport;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    private static final String LAUNCHER_PKG = "com.hihonor.android.launcher";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!LAUNCHER_PKG.equals(lpparam.packageName)) {
            return;
        }
        Port.log("loaded in process=" + lpparam.processName);
        try {
            com.wxz.hnlauncherport.returnhome.MotionReturnHome.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("MotionReturnHome install failed", t);
        }
        try {
            com.wxz.hnlauncherport.click.ParallelClick.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("ParallelClick install failed", t);
        }
        try {
            com.wxz.hnlauncherport.click.CardFolderIconHide.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("CardFolderIconHide install failed", t);
        }
        try {
            com.wxz.hnlauncherport.blur.ForceBlur.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("ForceBlur install failed", t);
        }
        try {
            com.wxz.hnlauncherport.swipe.WindowFollowScale.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("WindowFollowScale install failed", t);
        }
        try {
            com.wxz.hnlauncherport.swipe.DisableEnterRecentsAnim.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("DisableEnterRecentsAnim install failed", t);
        }
        try {
            com.wxz.hnlauncherport.keep.KeepHiboardReturn.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("KeepHiboardReturn install failed", t);
        }
        try {
            com.wxz.hnlauncherport.onestep.OneStepThresholds.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("OneStepThresholds install failed", t);
        }
        try {
            com.wxz.hnlauncherport.card.IconCardBlocker.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("IconCardBlocker install failed", t);
        }
        try {
            com.wxz.hnlauncherport.unlock.AdvUnlockAnim.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("AdvUnlockAnim install failed", t);
        }
        try {
            com.wxz.hnlauncherport.folder.FolderBgBlur.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("FolderBgBlur install failed", t);
        }
        try {
            com.wxz.hnlauncherport.folder.FolderOpenSpeed.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("FolderOpenSpeed install failed", t);
        }
        try {
            com.wxz.hnlauncherport.look.IconNoLabel.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("IconNoLabel install failed", t);
        }
        try {
            com.wxz.hnlauncherport.warm.StartupWarmup.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("StartupWarmup install failed", t);
        }
        try {
            com.wxz.hnlauncherport.press.ForceIconPressScale.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("ForceIconPressScale install failed", t);
        }
        try {
            com.wxz.hnlauncherport.press.ForceRecentsPressScale.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("ForceRecentsPressScale install failed", t);
        }
        try {
            com.wxz.hnlauncherport.back.IconBackSpringTune.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("IconBackSpringTune install failed", t);
        }
        try {
            com.wxz.hnlauncherport.back.IconBackAdaptiveIcon.install(lpparam.classLoader);
        } catch (Throwable t) {
            Port.log("IconBackAdaptiveIcon install failed", t);
        }
    }
}
