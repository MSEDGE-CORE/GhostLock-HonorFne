package com.wxz.hnlauncherport

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Feature-toggle GUI for HnLauncherPort（Jetpack Compose Material 3，
 * 与 MyBookkeeping 新版设置页同构）。写入 launcher 侧 hook 读取的
 * Settings.Global key。需要 WRITE_SECURE_SETTINGS：
 * "pm grant com.wxz.hnlauncherport android.permission.WRITE_SECURE_SETTINGS"
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        applyImmersiveSystemBars()
        setContent {
            HnPortTheme {
                SettingsScreen()
            }
        }
    }

    /** 沉浸式系统栏（与 MyBookkeeping 一致）：状态栏/导航栏全透明，关闭对比度遮罩 */
    private fun applyImmersiveSystemBars() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = if (isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
    }
}

@Composable
private fun HnPortTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private data class ToggleRow(
    val title: String,
    val subtitle: String,
    val key: String,
    val mergedKey: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val secure = remember {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                PackageManager.PERMISSION_GRANTED
    }
    var tab by remember { mutableIntStateOf(0) }
    var showRestartDialog by remember { mutableStateOf(false) }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("重启桌面") },
            text = { Text("确定要重启桌面吗？重启后设置立即生效。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    restartLauncher(context)
                }) { Text("重启") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) { Text("取消") }
            }
        )
    }

    // 动效
    val motionRows = remember { listOf(
        ToggleRow("应用启动关闭动效优化", "新版模型，连贯曲线，横屏修复",
            Port.KEY_MOTION, Port.KEY_ICON_HIDE),
        ToggleRow("连续切换应用", "立即停止上一个窗口", Port.KEY_PARALLEL),
        ToggleRow("高级模糊", "启用高端机型的模糊效果，优化部分速率曲线", Port.KEY_BLUR),
        ToggleRow("锚点优化", "窗口向0.2移动缩小", Port.KEY_WIN_SCALE),
        ToggleRow("高级解锁动画", "分组扩散，渐进弹簧", Port.KEY_ADV_UNLOCK_ANIM)
    ) }
    // 手势与行为
    val gestureRows = remember { listOf(
        ToggleRow("小窗/分屏阈值优化", "防止大幅度滑动意外地进入小窗",
            Port.KEY_ONESTEP_THRESHOLD),
        ToggleRow("负一屏/资源库保持", "手势返回不自动退出负一屏/资源库",
            Port.KEY_KEEP_HIBOARD),
        ToggleRow("禁用图标上滑卡片", "建议同时关闭卡片提示条",
            Port.KEY_DISABLE_ICON_CARD)
    ) }

    // 外观
    val lookRows = remember { listOf(
        ToggleRow("图标无字", "隐藏桌面图标文本",
            Port.KEY_ICON_NO_LABEL)
    ) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(when (tab) {
                        0 -> "HN系统提升"
                        1 -> "桌面"
                        else -> "SystemUI"
                    })
                },
                actions = {
                    IconButton(onClick = { showRestartDialog = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重启桌面")
                    }
                }
            )
        },
        bottomBar = {
            Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0),
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("主页") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                        label = { Text("桌面") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Android, contentDescription = null) },
                        label = { Text("SystemUI") }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(
                    WindowInsets.displayCutout.only(
                        WindowInsetsSides.Left + WindowInsetsSides.Right
                    )
                )
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when (tab) {
                0 -> {
                    val appVersion = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                ?: "unknown"
                        } catch (e: Exception) {
                            "unknown"
                        }
                    }
                    val launcherVersion = remember {
                        try {
                            context.packageManager.getPackageInfo("com.hihonor.android.launcher", 0)
                                .versionName ?: "unknown"
                        } catch (e: Exception) {
                            "unknown"
                        }
                    }
                    SettingsSection("信息") {
                        InfoItem("系统版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        InfoItem("桌面版本", launcherVersion)
                    }
                    SettingsSection("关于") {
                        Text(
                            "名称：HN系统提升\n版本：$appVersion\n作者：MSEDGE",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                1 -> {
                    if (!secure) {
                        Text(
                            "缺少 WRITE_SECURE_SETTINGS 权限，开关无法保存。\n请执行：\n" +
                                    "pm grant com.wxz.hnlauncherport android.permission.WRITE_SECURE_SETTINGS",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    SettingsSection("动效") {
                        motionRows.forEach { row -> SettingsSwitchItem(row, secure) }
                    }
                    SettingsSection("手势与行为") {
                        gestureRows.forEach { row -> SettingsSwitchItem(row, secure) }
                    }
                    SettingsSection("外观") {
                        lookRows.forEach { row -> SettingsSwitchItem(row, secure) }
                    }
                }
                else -> {
                    SettingsSection("SystemUI") {
                        Text(
                            "暂无可配置项",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun SettingsSwitchItem(row: ToggleRow, secure: Boolean) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(readKey(context, row.key)) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                row.subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = { on ->
                val ok = writeKey(context, row.key, on) &&
                        (row.mergedKey == null || writeKey(context, row.mergedKey, on))
                if (ok) {
                    checked = on
                } else {
                    Toast.makeText(context, "写入失败（缺少权限）", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = secure
        )
    }
}

private fun readKey(context: android.content.Context, key: String): Boolean =
    try {
        Settings.Global.getInt(context.contentResolver, key, 0) == 1
    } catch (e: Exception) {
        false
    }

private fun writeKey(context: android.content.Context, key: String, on: Boolean): Boolean =
    try {
        Settings.Global.putInt(context.contentResolver, key, if (on) 1 else 0)
    } catch (e: Exception) {
        false
    }

/** 通过 root 重启桌面 (KernelSU 首次会弹授权). */
private fun restartLauncher(context: android.content.Context) {
    Toast.makeText(context, "正在重启桌面…", Toast.LENGTH_SHORT).show()
    Thread {
        var ok = false
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("su", "0", "sh", "-c", "killall com.hihonor.android.launcher"))
            ok = p.waitFor() == 0
        } catch (e: Exception) {
            ok = false
        }
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(
                context,
                if (ok) "桌面已重启" else "重启失败（无 root 授权）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }.start()
}
