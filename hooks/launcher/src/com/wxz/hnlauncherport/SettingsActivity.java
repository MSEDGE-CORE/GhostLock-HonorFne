package com.wxz.hnlauncherport;

import android.app.Activity;
import android.content.Context;
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
 */
public class SettingsActivity extends Activity {

    private static final String[][] ROWS = {
            {"MotionEngine 返家", Port.KEY_MOTION},
            {"连续切换应用（点新图标立即停上一个）", Port.KEY_PARALLEL},
            {"大文件夹打开时隐藏对应图标", Port.KEY_ICON_HIDE},
            {"过渡模糊（全机型，修复松手后模糊消失）", Port.KEY_BLUR},
            {"窗口跟手缩放（V17 上滑轨迹）", Port.KEY_WIN_SCALE},
            {"返家保持负一屏/资源库（进后台才退出）", Port.KEY_KEEP_HIBOARD},
            {"小窗/分屏判定阈值（0.8/0.75，横0.3H竖0.5H，松手需停顿）", Port.KEY_ONESTEP_THRESHOLD},
            {"禁用桌面上滑图标弹卡片（资源库不受影响）", Port.KEY_DISABLE_ICON_CARD},
            {"解锁高级动画（图标圆形扩散入场）", Port.KEY_ADV_UNLOCK_ANIM},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("HnLauncherPort");
        title.setTextSize(20.0f);
        title.setPadding(0, 0, 0, dp(12));
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

        for (String[] row : ROWS) {
            root.addView(makeRow(row[0], row[1]));
        }
        setContentView(scroll);
    }

    private View makeRow(String label, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(16.0f);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Switch sw = new Switch(this);
        sw.setChecked(read(key));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                boolean ok = write(key, isChecked);
                if (!ok) {
                    buttonView.setChecked(!isChecked);
                    Toast.makeText(SettingsActivity.this, "写入失败（缺少权限）", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(sw);
        return row;
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
