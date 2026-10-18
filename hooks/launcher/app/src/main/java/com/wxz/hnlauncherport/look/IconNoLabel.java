package com.wxz.hnlauncherport.look;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.wxz.hnlauncherport.Port;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 图标无字: 隐藏桌面图标文本.
 *
 * 原理: 桌面图标 label 由 BubbleTextView 渲染. Honor 桌面自身对
 * hotseat (-101)/快捷弹窗 (-105) 即用 setText("") 隐藏文本 (见 V14
 * BubbleTextView.setShortcutTitle), 布局对空文本天然兼容, 故同法
 * 将工作区图标文本置空即可, 无需改 measure/visibility.
 *
 * Hook 点: 框架 TextView.setText(CharSequence, BufferType) 汇聚点
 * (所有 setText 路径必经, V14 BubbleTextView 未覆写 onTextChanged,
 * exact hook 会 NoSuchMethodError) + onAttachedToWindow (兜底晚于
 * 文本绑定的 attach 情况).
 *
 * 作用范围: 仅 CellLayout 祖先下的视图 (工作区/热栏/文件夹格),
 * 全部应用列表 (RecyclerView, 非 CellLayout) 不受影响.
 *
 * 递归安全: setText("") 再次经过汇聚点, 空文本早退.
 * 生效时机: 开关切换后需重启桌面 (设置页提供重启按钮) 生效.
 */
public final class IconNoLabel {
    private static final String BTV_CLS =
            "com.hihonor.android.launcher.BubbleTextView";
    private static final String CELL_LAYOUT_CLS =
            "com.hihonor.android.launcher.CellLayout";

    public static void install(ClassLoader cl) {
        final Class<?> btv = XposedHelpers.findClass(BTV_CLS, cl);
        final Class<?> cellLayout = XposedHelpers.findClass(CELL_LAYOUT_CLS, cl);

        XC_MethodHook hideHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!Port.on(Port.KEY_ICON_NO_LABEL)) {
                    return;
                }
                try {
                    maybeHideText((TextView) param.thisObject, btv, cellLayout);
                } catch (Throwable t) {
                    Port.log("IconNoLabel handle failed", t);
                }
            }
        };

        XposedHelpers.findAndHookMethod(TextView.class, "setText",
                CharSequence.class, TextView.BufferType.class, hideHook);
        XposedHelpers.findAndHookMethod(btv, "onAttachedToWindow", hideHook);

        Port.log("IconNoLabel installed");
    }

    private static void maybeHideText(TextView view, Class<?> btv, Class<?> cellLayout) {
        if (view == null || !btv.isInstance(view)
                || !hasCellLayoutAncestor(view, cellLayout)) {
            return;
        }
        CharSequence text = view.getText();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        view.setText("");
    }

    /** 沿父链找 CellLayout (含 WorkspaceCellLayout/HotseatCellLayout 等子类). */
    private static boolean hasCellLayoutAncestor(View view, Class<?> cellLayout) {
        Object parent = view.getParent();
        int depth = 0;
        while (parent != null && depth < 16) {
            if (cellLayout.isInstance(parent)) {
                return true;
            }
            if (!(parent instanceof View)) {
                return false;
            }
            parent = ((View) parent).getParent();
            depth++;
        }
        return false;
    }

    private IconNoLabel() {}
}
