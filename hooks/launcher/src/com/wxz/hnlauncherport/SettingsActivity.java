package com.wxz.hnlauncherport;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Feature-toggle GUI for HnLauncherPort. Writes Settings.Global keys that the
 * launcher-side hooks read. Requires WRITE_SECURE_SETTINGS (grant via
 * "pm grant com.wxz.hnlauncherport android.permission.WRITE_SECURE_SETTINGS").
 *
 * Layout mirrors the MyBookkeeping settings page style: flat scroll, plain
 * title + subtitle rows with a trailing switch, no card backgrounds.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("HnLauncherPort");
        title.setTextSize(22.0f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        boolean secure = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
        if (!secure) {
            TextView warn = new TextView(this);
            warn.setText("缺少 WRITE_SECURE_SETTINGS 权限，开关无法保存。\n请执行：\npm grant com.wxz.hnlauncherport android.permission.WRITE_SECURE_SETTINGS");
            warn.setTextSize(13.0f);
            warn.setTextColor(0xFFFFB300);
            warn.setPadding(0, 0, 0, dp(12));
            root.addView(warn);
        }

        // 大文件夹图标隐藏已合并进"应用启动关闭动效优化"（同一开关同时写入两个 key）。
        addRow(root, "应用启动关闭动效优化", "新版模型，连贯曲线，横屏修复",
                Port.KEY_MOTION, Port.KEY_ICON_HIDE);
        addRow(root, "连续切换应用", "立即停止上一个窗口", Port.KEY_PARALLEL, null);
        addRow(root, "高级模糊", "启用高端机型的模糊效果，优化部分速率曲线",
                Port.KEY_BLUR, null);
        addRow(root, "锚点优化", "窗口向0.2移动缩小", Port.KEY_WIN_SCALE, null);
        addRow(root, "小窗/分屏阈值优化", "防止大幅度滑动意外地进入小窗",
                Port.KEY_ONESTEP_THRESHOLD, null);
        addRow(root, "负一屏/资源库保持", "手势返回不自动退出负一屏/资源库",
                Port.KEY_KEEP_HIBOARD, null);
        addRow(root, "高级解锁动画", "分组扩散，渐进弹簧", Port.KEY_ADV_UNLOCK_ANIM, null);
        addRow(root, "禁用图标上滑卡片", "建议同时关闭卡片提示条",
                Port.KEY_DISABLE_ICON_CARD, null);

        TextView about = new TextView(this);
        about.setText("名称：HnLauncherPort\n版本：" + versionName());
        about.setTextSize(13.0f);
        about.setTextColor(0xFF757575);
        about.setPadding(0, dp(24), 0, 0);
        root.addView(about);

        setContentView(scroll);
    }

    /** Flat settings row (MyBookkeeping style): title + subtitle + trailing switch. */
    private void addRow(LinearLayout parent, String title, String subtitle,
                        final String key, final String mergedKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16.0f);
        text.addView(tv);

        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(13.0f);
        sub.setTextColor(0xFF757575);
        sub.setPadding(0, dp(2), 0, 0);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Switch sw = new Switch(this);
        sw.setChecked(read(key));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                boolean ok = write(key, isChecked);
                if (ok && mergedKey != null) {
                    write(mergedKey, isChecked);
                }
                if (!ok) {
                    buttonView.setChecked(!isChecked);
                    Toast.makeText(SettingsActivity.this, "写入失败（缺少权限）", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(sw);
        parent.addView(row);
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "unknown" : v;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private boolean read(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean write(String key, boolean on) {
        try {
            return Settings.Global.putInt(getContentResolver(), key, on ? 1 : 0);
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
