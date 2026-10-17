package com.wxz.hnlauncherport.click;

import android.animation.Animator;
import android.view.View;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides the desktop icon when launching an app from a large folder
 * (CardFolder) - user issue 22.
 *
 * Stock V14 FloatingIconView.onAnimationStart (:701-716) skips the hide for
 * any BubbleTextView inside a card folder:
 *   z = (view instanceof BubbleTextView)
 *       && (CardFolderUtils.isInCardFolder(view) || isInFolder(...));
 *   if ((!mIsOpening && !mIsHideOriginal) || z || z2) return;   // <- z path
 *   showOrHideView(view2, false, mLauncher);                    // hide skipped
 * so while the floating icon animates into the app window, the original cell
 * in the folder keeps drawing the icon (visible double icon). Normal icons
 * outside folders are hidden; the card-folder guard is the only difference.
 *
 * This hook re-applies the hide for exactly the skipped case. Conditions
 * mirror the stock hide minus the card-folder guard:
 *   - icon load finished (same early return as stock :703-706),
 *   - mOriginalIcon is a BubbleTextView inside a card folder,
 *   - (mIsOpening || mIsHideOriginal), same as stock :712.
 *
 * Restore needs no extra code:
 *   - mEndRunnable (installed for every getFloatingIconView, :783-788)
 *     restores when hideOriginal was set (lambda$getFloatingIconView$1 :798),
 *   - and the app-open animation itself restores via
 *     AppLaunchAnimation.resetOriginView (:1197/:1214
 *     -> showOrHideView(mOriginView, true, ...)),
 *   - FloatingIconView.showIcon already special-cases card-folder icons
 *     (:860-863), evidence the restore path was designed for them.
 */
public final class CardFolderIconHide {

    private static final String FIV =
            "com.hihonor.android.launcher.views.FloatingIconView";
    private static final String CFU =
            "com.hihonor.android.launcher.cardstyle.cardfolder.CardFolderUtils";
    private static final String BTV =
            "com.hihonor.android.launcher.BubbleTextView";

    public static void install(ClassLoader cl) {
        final Class<?> fiv = XposedHelpers.findClass(FIV, cl);
        final Class<?> cfu = XposedHelpers.findClass(CFU, cl);
        final Class<?> btv = XposedHelpers.findClass(BTV, cl);

        XposedHelpers.findAndHookMethod(fiv, "onAnimationStart",
                Animator.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_ICON_HIDE)) {
                    return;
                }
                try {
                    Object self = param.thisObject;
                    // stock early-returns before hiding when the icon is not
                    // loaded yet; mirror that
                    Object loadResult =
                            XposedHelpers.getObjectField(self, "mIconLoadResult");
                    if (loadResult == null
                            || !XposedHelpers.getBooleanField(loadResult, "isIconLoaded")) {
                        return;
                    }
                    View icon = (View) XposedHelpers.getObjectField(self, "mOriginalIcon");
                    if (icon == null || !btv.isInstance(icon)) {
                        return;
                    }
                    // only the case stock V14 skips: BubbleTextView in a card folder
                    boolean inCard = (Boolean) XposedHelpers.callStaticMethod(
                            cfu, "isInCardFolder", icon);
                    if (!inCard) {
                        return;
                    }
                    boolean isOpening = XposedHelpers.getBooleanField(self, "mIsOpening");
                    boolean isHideOriginal =
                            XposedHelpers.getBooleanField(self, "mIsHideOriginal");
                    if (!isOpening && !isHideOriginal) {
                        return;
                    }
                    Object launcher = XposedHelpers.getObjectField(self, "mLauncher");
                    XposedHelpers.callStaticMethod(
                            fiv, "showOrHideView", icon, Boolean.FALSE, launcher);
                    Port.log("CardFolderIconHide hid card-folder icon");
                } catch (Throwable t) {
                    Port.log("CardFolderIconHide failed", t);
                }
            }
        });
        Port.log("CardFolderIconHide installed");
    }

    private CardFolderIconHide() {}
}
