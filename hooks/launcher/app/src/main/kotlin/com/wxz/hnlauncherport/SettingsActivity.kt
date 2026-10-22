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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.LaunchedEffect
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
    val mergedKeys: List<String> = emptyList()
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
    var pendingRestart by remember { mutableStateOf<String?>(null) }

    val restartTarget = pendingRestart
    if (restartTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRestart = null },
            title = { Text("重启" + restartLabel(restartTarget)) },
            text = { Text("确定要重启" + restartLabel(restartTarget) + "吗？") },
            confirmButton = {
                TextButton(onClick = {
                    restartProc(context, restartTarget)
                    pendingRestart = null
                }) { Text("重启") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestart = null }) { Text("取消") }
            }
        )
    }

    // 动效
    val motionRows = remember { listOf(
        ToggleRow("应用启动关闭动效优化", "新版模型，连贯曲线，横屏修复",
            Port.KEY_MOTION, listOf(Port.KEY_ICON_HIDE)),
        ToggleRow("锚点优化", "窗口向0.2移动缩小", Port.KEY_WIN_SCALE),
        ToggleRow("连续切换应用", "立即停止上一个窗口", Port.KEY_PARALLEL),
        ToggleRow("高级模糊", "启用高端机型的模糊效果，优化部分速率曲线", Port.KEY_BLUR),
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
                    SettingsSection("操作") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("重启 SystemUI") { pendingRestart = "systemui" }
                            ActionButton("重启桌面") { pendingRestart = "launcher" }
                            ActionButton("重启 AOD") { pendingRestart = "aod" }
                        }
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
                    SettingsSection("状态栏") {
                        SettingsSwitchItem(ToggleRow(
                            "禁用状态栏自动缩小", "灵动胶囊启用时，状态栏保持大尺寸",
                            Port.KEY_SB_NO_SHRINK
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "禁用状态栏元素自动隐藏", "灵动胶囊启用时，状态栏杂项图标不隐藏",
                            Port.KEY_SB_NO_HIDE
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "始终显示灵动胶囊", "胶囊不自动收缩成小图标，可能导致烧屏",
                            Port.KEY_CAPSULE_ALWAYS
                        ), true)
                    }
                    SettingsSection("控制中心") {
                        SettingsSwitchItem(ToggleRow(
                            "动效优化", "卡片入场优化，横滑切换惯性",
                            Port.KEY_CC_SPRING_VEL
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "高级模糊", "模糊半径跟手变化",
                            Port.KEY_CC_REAL_BLUR
                        ), true)
                    }
                    SettingsSection("锁屏") {
                        SettingsSwitchItem(ToggleRow(
                            "隐藏步数", "隐藏锁屏左下角的步数显示",
                            Port.KEY_KG_HIDE_STEPS
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "禁用锁屏工具", "禁用锁屏底部上滑的工具菜单",
                            Port.KEY_KG_NO_TOOLBOX
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "跳过面部解锁动画", "跳过密码界面面部解锁成功的笑脸动画，直接进入桌面",
                            Port.KEY_KG_SKIP_FACE_ANIM
                        ), true)
                    }
                    SettingsSection("悬浮窗") {
                        SettingsSwitchItem(ToggleRow(
                            "手势优化", "小窗角落内滑挂起，贴边最小化",
                            Port.KEY_FF_GESTURE
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "动效优化", "平滑最小化，全屏动效连贯",
                            Port.KEY_FF_ANIM
                        ), true)
                    }
                    SettingsSection("导航键") {
                        SettingsSwitchItem(ToggleRow(
                            "隐藏手势提示条", "隐藏手势提示条，保留底部抬升",
                            Port.KEY_NAV_HANDLE_HIDE
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "禁用手势提示条跟随", "禁用底部手势提示条跟手移动",
                            Port.KEY_NAV_HANDLE_NO_FOLLOW
                        ), true)
                    }
                    SettingsSection("杂项") {
                        SettingsSwitchItem(ToggleRow(
                            "固定AOD位置", "禁用AOD自动下移，可能导致烧屏",
                            Port.KEY_AOD_FIX_POS
                        ), true)
                        RootDisableItem(
                            title = "禁用自动更新",
                            subtitle = "禁用系统自动更新服务（需要ROOT权限）",
                            pkg = "com.hihonor.ouc"
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
                        row.mergedKeys.all { writeKey(context, it, on) }
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

/** 通过 root 整包禁用/恢复系统应用，开关状态实时查询 pm disabled 列表. */
@Composable
private fun RootDisableItem(title: String, subtitle: String, pkg: String) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(pkg) {
        checked = isPkgDisabled(pkg)
        loaded = true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = { on ->
                checked = on
                Toast.makeText(context, if (on) "正在禁用…" else "正在恢复…", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = runRootCmd(
                        if (on) "pm disable-user --user 0 $pkg" else "pm enable $pkg"
                    )
                    val disabled = isPkgDisabled(pkg)
                    android.os.Handler(context.mainLooper).post {
                        checked = disabled
                        Toast.makeText(
                            context,
                            when {
                                ok && disabled && on -> "已禁用 $pkg"
                                ok && !disabled && !on -> "已恢复 $pkg"
                                else -> "操作失败（无 root 授权）"
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            },
            enabled = loaded
        )
    }
}

private fun runRootCmd(cmd: String): Boolean = try {
    val p = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
    p.waitFor() == 0
} catch (e: Exception) {
    false
}

private fun isPkgDisabled(pkg: String): Boolean = try {
    val p = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", "pm list packages -d"))
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.contains("package:$pkg")
} catch (e: Exception) {
    false
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

/** 一行一个按钮 (参照 LatitudeLongitudeCamera 按钮样式: 圆角 24dp 全宽). */
@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

private fun restartLabel(target: String): String = when (target) {
    "systemui" -> " SystemUI"
    "aod" -> " AOD"
    else -> "桌面"
}

/** 通过 root 重启目标进程 (KernelSU 首次会弹授权). */
private fun restartProc(context: android.content.Context, target: String) {
    val label = restartLabel(target)
    val cmd = when (target) {
        "systemui" -> "killall com.android.systemui"
        "aod" -> "killall com.hihonor.aod"
        else -> "killall com.hihonor.android.launcher"
    }
    Toast.makeText(context, "正在重启" + label + "…", Toast.LENGTH_SHORT).show()
    Thread {
        var ok = false
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", cmd))
            ok = p.waitFor() == 0
        } catch (e: Exception) {
            ok = false
        }
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(
                context,
                if (ok) label + "已重启" else "重启失败（无 root 授权）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }.start()
}
