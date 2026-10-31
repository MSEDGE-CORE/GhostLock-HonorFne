package com.wxz.hnlauncherport

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

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
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showThresholdDialog by remember { mutableStateOf(false) }
    var showMotionDialog by remember { mutableStateOf(false) }

    if (showUnlockDialog) {
        UnlockAnimDialog(onDismiss = { showUnlockDialog = false })
    }
    if (showMotionDialog) {
        MotionPathDialog(onDismiss = { showMotionDialog = false })
    }
    if (showThresholdDialog) {
        OneStepThresholdDialog(onDismiss = { showThresholdDialog = false })
    }

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
        ToggleRow("应用启动关闭动效优化...", "新版模型，连贯曲线，横屏修复",
            Port.KEY_MOTION, listOf(Port.KEY_ICON_HIDE)),
        ToggleRow("锚点优化", "窗口向0.2移动缩小", Port.KEY_WIN_SCALE),
        ToggleRow("连续切换应用", "立即停止上一个窗口", Port.KEY_PARALLEL),
        ToggleRow("高级模糊", "启用高端机型的模糊效果，优化部分速率曲线", Port.KEY_BLUR),
        ToggleRow("高级图标入场...", "分组扩散，渐进弹簧（需重启桌面）", Port.KEY_ADV_UNLOCK_ANIM)
    ) }
    // 手势与行为
    val gestureRows = remember { listOf(
        ToggleRow("小窗/分屏阈值优化...", "防止大幅度滑动意外地进入小窗",
            Port.KEY_ONESTEP_THRESHOLD),
        ToggleRow("负一屏/资源库保持", "手势返回不自动退出负一屏/资源库",
            Port.KEY_KEEP_HIBOARD),
        ToggleRow("负一屏层级", "进入负一屏时桌面下沉",
            Port.KEY_HIBOARD_SINK),
        ToggleRow("禁用图标上滑卡片", "建议同时关闭卡片提示条",
            Port.KEY_DISABLE_ICON_CARD)
    ) }

    // 外观
    val lookRows = remember { listOf(
        ToggleRow("图标无字", "隐藏桌面图标文本（需重启桌面）",
            Port.KEY_ICON_NO_LABEL)
    ) }

    val landscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

    Row(Modifier.fillMaxSize()) {
        if (landscape) {
            SettingsNavRail(tab) { tab = it }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.statusBars,
                    title = {
                        Text(when (tab) {
                            0 -> "耀衍"
                            1 -> "桌面"
                            else -> "SystemUI"
                        })
                    },
                )
            },
            bottomBar = {
                if (!landscape) {
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
            }
        ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .windowInsetsPadding(
                    WindowInsets.displayCutout.only(
                        WindowInsetsSides.Right
                    )
                )
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            // 主页操作按钮: 归入内容流末尾 (不压底), 上方留 20dp 间距
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
            Crossfade(
                targetState = tab,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "tabFade"
            ) { currentTab ->
                Column {
            when (currentTab) {
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
                    val systemuiVersion = remember {
                        try {
                            context.packageManager.getPackageInfo("com.android.systemui", 0)
                                .versionName ?: "unknown"
                        } catch (e: Exception) {
                            "unknown"
                        }
                    }
                    val deviceName = remember { deviceDisplayName() }
                    val magicOsVersion = remember { magicOsVersionString() }
                    SettingsSection("") {
                        InfoItem("设备型号", Build.MODEL)
                        InfoItem("设备名称", deviceName)
                        InfoItem("系统版本", magicOsVersion)
                        InfoItem("安卓版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        InfoItem("桌面版本", launcherVersion)
                        InfoItem("SystemUI 版本", systemuiVersion)
                    }
                    SettingsSection("关于") {
                        InfoItem("版本", appVersion)
                        LinkItem("开发者", "MSEDGE", "https://github.com/MSEDGE-CORE")
                        LinkItem("Github 仓库", null, "https://github.com/MSEDGE-CORE/GhostLock-HonorFne")
                    }
                    Spacer(Modifier.height(16.dp))
                    ActionButton("重启 SystemUI") { pendingRestart = "systemui" }
                    Spacer(Modifier.height(8.dp))
                    ActionButton("重启桌面") { pendingRestart = "launcher" }
                    Spacer(Modifier.height(8.dp))
                    ActionButton("重启 AOD") { pendingRestart = "aod" }
                    Spacer(Modifier.height(16.dp))
                }
                1 -> {
                    if (!secure) {
                        Text(
                            "缺少 WRITE_SECURE_SETTINGS 权限，开关无法保存。\n请执行：\n" +
                                    "pm grant com.wxz.hnlauncherport android.permission.WRITE_SECURE_SETTINGS",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                    SettingsSection("动效") {
                        motionRows.forEach { row ->
                            SettingsSwitchItem(
                                row, secure,
                                onRowClick = when (row.key) {
                                    Port.KEY_ADV_UNLOCK_ANIM -> { { showUnlockDialog = true } }
                                    Port.KEY_MOTION -> { { showMotionDialog = true } }
                                    else -> null
                                }
                            )
                        }
                    }
                    SettingsSection("手势与行为") {
                        gestureRows.forEach { row ->
                            SettingsSwitchItem(
                                row, secure,
                                onRowClick = if (row.key == Port.KEY_ONESTEP_THRESHOLD) {
                                    { showThresholdDialog = true }
                                } else null
                            )
                        }
                    }
                    SettingsSection("外观") {
                        lookRows.forEach { row -> SettingsSwitchItem(row, secure) }
                    }
                }
                else -> {
                    SettingsSection("状态栏") {
                        SettingsSwitchItem(ToggleRow(
                            "灵动胶囊状态刷新", "打开对应应用时 灵动胶囊及时收起",
                            Port.KEY_CAPSULE_RECONCILE
                        ), true)
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
                            "状态栏连贯", "进入控制中心时，状态栏连贯移动",
                            Port.KEY_CC_STATUSBAR_CONTINUITY
                        ), true)
                        SettingsSwitchItem(ToggleRow(
                            "横滑切换区域优化", "通知/控制中心全屏范围横滑切换",
                            Port.KEY_CC_GLOBAL_SWIPE
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
                        SettingsSwitchItem(ToggleRow(
                            "单击亮屏", "轻触屏幕以唤醒",
                            Port.KEY_KG_SINGLE_TAP_WAKE
                        ), true)
                    }
                    SettingsSection("悬浮窗") {
                        SettingsSwitchItem(ToggleRow(
                            "手势优化", "小窗角落向内滑动快速挂起，贴边最小化",
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
                        SettingsSwitchItem(ToggleRow(
                            "旋转建议", "旋转锁定时显示建议悬浮球",
                            Port.KEY_ROTATION_SUGGEST
                        ), true)
                    }
                    SettingsSection("杂项") {
                        SettingsSwitchItem(ToggleRow(
                            "固定AOD位置", "禁用AOD自动下移，可能导致烧屏",
                            Port.KEY_AOD_FIX_POS
                        ), true)
                        RootDisableItem(
                            title = "禁用自动更新",
                            subtitle = "禁用系统自动更新服务（需ROOT权限）",
                            pkg = "com.hihonor.ouc"
                        )
                    }
                }
                }
            }
            }
            }
            }
        }
    }
    }
}

/** 横屏左侧导航栏: M3 NavigationRail 默认风格 (胶囊指示器自带形变动画). */
@Composable
private fun SettingsNavRail(currentTab: Int, onSelectTab: (Int) -> Unit) {
    // 外层 Box 铺满背景 (延伸到状态栏/挖孔/导航栏背后), 内部 rail 透明并避开安全区
    Box(
        Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        NavigationRail(
            containerColor = Color.Transparent,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Left))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
        NavigationRailItem(
            selected = currentTab == 0,
            onClick = { onSelectTab(0) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("主页") }
        )
        NavigationRailItem(
            selected = currentTab == 1,
            onClick = { onSelectTab(1) },
            icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
            label = { Text("桌面") }
        )
        NavigationRailItem(
            selected = currentTab == 2,
            onClick = { onSelectTab(2) },
            icon = { Icon(Icons.Filled.Android, contentDescription = null) },
            label = { Text("SystemUI") }
        )
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
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
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        content()
    }
}

/** 可点击的链接行: value 非空时右侧显示文本, showChevron 时显示 > 箭头. */
@Composable
private fun LinkItem(label: String, value: String?, url: String) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    row: ToggleRow,
    secure: Boolean,
    onRowClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(readKey(context, row.key)) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onRowClick != null && checked) { onRowClick?.invoke() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
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
                    Toast.makeText(context, "写入失败", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = secure
        )
    }
}

/**
 * 运动路径优化 (应用启动关闭动效优化行点击弹出):
 *  - 曲线风格 radio: 劲度 (path 关) / 轻快 (path 默认, ζ 0.86) /
 *    漂浮 (scale 弹簧随目标 Y 插值) / 自定义弹簧 (滑条)
 */
@Composable
private fun MotionPathDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pathOn = readKey(context, Port.KEY_MOTION_PATH)
    val styleKey = Settings.Global.getInt(
        context.contentResolver, Port.KEY_MP_STYLE, 0).coerceIn(0, 2)
    // UI 风格序: 0=劲度 1=轻快 2=漂浮 3=自定义弹簧
    var styleSel by remember {
        mutableIntStateOf(if (pathOn) styleKey + 1 else 0)
    }
    var dur by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_MP_DURATION, 1.0f).coerceIn(0.5f, 1.5f))
    }
    var inertia by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_MP_INERTIA, 1f).coerceIn(0f, 10f))
    }
    var zetaXY by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_MP_ZETA_XY, 0.86f).coerceIn(0.5f, 1.5f))
    }
    var zetaScale by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_MP_ZETA_SCALE, 0.86f).coerceIn(0.5f, 1.5f))
    }
    var zetaWH by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_MP_ZETA_WH, 0.82f).coerceIn(0.5f, 1.5f))
    }

    fun putF(key: String, v: Float): Boolean {
        return Settings.Global.putFloat(context.contentResolver, key, v)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("应用启动关闭动效优化")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    dur = 1.0f
                    inertia = 1f
                    zetaXY = 0.86f
                    zetaScale = 0.86f
                    zetaWH = 0.82f
                }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "恢复默认")
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "曲线风格",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                listOf("劲度", "轻快", "漂浮", "自定义弹簧").forEachIndexed { i, name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { styleSel = i }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = styleSel == i,
                            onClick = { styleSel = i }
                        )
                        Text(name, fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (styleSel != 0) {
                    Spacer(Modifier.height(12.dp))
                    Text("时长系数：" + String.format("%.2f", dur))
                    Slider(
                        value = dur,
                        valueRange = 0.5f..1.5f,
                        steps = 19,
                        onValueChange = { dur = it }
                    )
                }
                if (styleSel == 3) {
                    Spacer(Modifier.height(12.dp))
                    Text("惯性系数：" + String.format("%.2f", inertia))
                    Slider(
                        value = inertia,
                        valueRange = 0f..2f,
                        steps = 19,
                        onValueChange = { inertia = it }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("平移弹簧：" + String.format("%.2f", zetaXY))
                    Slider(
                        value = zetaXY,
                        valueRange = 0.5f..1.5f,
                        steps = 99,
                        onValueChange = { zetaXY = it }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("缩放弹簧：" + String.format("%.2f", zetaScale))
                    Slider(
                        value = zetaScale,
                        valueRange = 0.5f..1.5f,
                        steps = 99,
                        onValueChange = { zetaScale = it }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("比例弹簧：" + String.format("%.2f", zetaWH))
                    Slider(
                        value = zetaWH,
                        valueRange = 0.5f..1.5f,
                        steps = 99,
                        onValueChange = { zetaWH = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val styleIdx = when (styleSel) {
                    1 -> 0 // 轻快
                    2 -> 1 // 漂浮
                    3 -> 2 // 自定义弹簧
                    else -> 0 // 劲度 (path 关, style 无意义)
                }
                var ok = writeKey(context, Port.KEY_MOTION_PATH, styleSel != 0) &&
                        Settings.Global.putInt(
                            context.contentResolver, Port.KEY_MP_STYLE, styleIdx)
                if (styleSel != 0) {
                    ok = ok && putF(Port.KEY_MP_DURATION, dur)
                }
                if (styleSel == 3) {
                    ok = ok && putF(Port.KEY_MP_INERTIA, inertia) &&
                            putF(Port.KEY_MP_ZETA_XY, zetaXY) &&
                            putF(Port.KEY_MP_ZETA_SCALE, zetaScale) &&
                            putF(Port.KEY_MP_ZETA_WH, zetaWH)
                }
                Toast.makeText(
                    context,
                    if (ok) "已保存" else "写入失败",
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 自定义解锁动画参数 (高级解锁动画行点击弹出):
 *  - 起始尺寸 (0.5-3): 覆盖 stock startScaleRatio (默认 1.8)
 *  - 缩放锚点 (0-1): 扩散中心在屏高上的位置 (默认 0.3)
 *  - 恢复默认: 锚点 0.3 + 起始尺寸 1.8; 取消不保存; 确定写入后下次解锁生效
 */
@Composable
private fun UnlockAnimDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ADV_UNLOCK_SCALE, 1.8f))
    }
    var anchor by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ADV_UNLOCK_ANCHOR, 0.3f))
    }
    var delay by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ADV_UNLOCK_DELAY, 30f).coerceIn(0f, 60f))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("高级图标入场")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    scale = 1.8f
                    anchor = 0.3f
                    delay = 30f
                }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "恢复默认")
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("起始尺寸：" + String.format("%.1f", scale))
                Slider(
                    value = scale,
                    valueRange = 0.5f..4f,
                    steps = 34,
                    onValueChange = { scale = it }
                )
                Spacer(Modifier.height(12.dp))
                Text("缩放锚点：" + String.format("%.1f", anchor))
                Slider(
                    value = anchor,
                    valueRange = 0f..1f,
                    steps = 9,
                    onValueChange = { anchor = it }
                )
                Spacer(Modifier.height(12.dp))
                Text("分组透明度延时：" + String.format("%.0f", delay) + "ms")
                Slider(
                    value = delay,
                    valueRange = 0f..60f,
                    steps = 5,
                    onValueChange = { delay = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = Settings.Global.putFloat(
                    context.contentResolver, Port.KEY_ADV_UNLOCK_SCALE, scale) &&
                        Settings.Global.putFloat(
                            context.contentResolver, Port.KEY_ADV_UNLOCK_ANCHOR, anchor) &&
                        Settings.Global.putFloat(
                            context.contentResolver, Port.KEY_ADV_UNLOCK_DELAY, delay)
                Toast.makeText(
                    context,
                    if (ok) "已保存" else "写入失败）",
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 自定义小窗/分屏阈值 (小窗/分屏阈值优化行点击弹出):
 *  - 竖屏X/横屏X (0.1-0.5): 椭圆热区横半轴比例 (默认 0.4)
 *  - 竖屏Y/横屏Y (0.1-0.6): 椭圆热区纵半轴比例 (默认 0.55/0.5)
 *  - 恢复默认: 0.4/0.55/0.4/0.5; 取消不保存; 确定写入后实时生效
 */
@Composable
private fun OneStepThresholdDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var px by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ONESTEP_PX, 0.4f).coerceIn(0.1f, 0.5f))
    }
    var py by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ONESTEP_PY, 0.55f).coerceIn(0.1f, 0.6f))
    }
    var lx by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ONESTEP_LX, 0.4f).coerceIn(0.1f, 0.5f))
    }
    var ly by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver, Port.KEY_ONESTEP_LY, 0.5f).coerceIn(0.1f, 0.6f))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("小窗/分屏阈值优化")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    px = 0.4f
                    py = 0.55f
                    lx = 0.4f
                    ly = 0.5f
                }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "恢复默认")
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("竖屏X：" + String.format("%.2f", px))
                Slider(
                    value = px,
                    valueRange = 0.1f..0.5f,
                    steps = 7,
                    onValueChange = { px = it }
                )
                Spacer(Modifier.height(12.dp))
                Text("竖屏Y：" + String.format("%.2f", py))
                Slider(
                    value = py,
                    valueRange = 0.1f..0.6f,
                    steps = 9,
                    onValueChange = { py = it }
                )
                Spacer(Modifier.height(12.dp))
                Text("横屏X：" + String.format("%.2f", lx))
                Slider(
                    value = lx,
                    valueRange = 0.1f..0.5f,
                    steps = 7,
                    onValueChange = { lx = it }
                )
                Spacer(Modifier.height(12.dp))
                Text("横屏Y：" + String.format("%.2f", ly))
                Slider(
                    value = ly,
                    valueRange = 0.1f..0.6f,
                    steps = 9,
                    onValueChange = { ly = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = Settings.Global.putFloat(
                    context.contentResolver, Port.KEY_ONESTEP_PX, px) &&
                        Settings.Global.putFloat(
                            context.contentResolver, Port.KEY_ONESTEP_PY, py) &&
                        Settings.Global.putFloat(
                            context.contentResolver, Port.KEY_ONESTEP_LX, lx) &&
                        Settings.Global.putFloat(
                            context.contentResolver, Port.KEY_ONESTEP_LY, ly)
                Toast.makeText(
                    context,
                    if (ok) "已保存" else "写入失败",
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
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
            .padding(horizontal = 20.dp, vertical = 12.dp)
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
                                else -> "操作失败（无 ROOT 授权）"
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

private fun readKey(context: android.content.Context, key: String): Boolean =    try {
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
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

private fun readSystemProp(key: String): String? = try {
    val sp = Class.forName("android.os.SystemProperties")
    val get = sp.getMethod("get", String::class.java)
    val v = get.invoke(null, key) as String
    v.ifEmpty { null }
} catch (e: Exception) {
    null
}

/** 设备名称: 营销名 (如 HONOR 70, ro.config.marketing_name), 失败回退 Build.MODEL. */
private fun deviceDisplayName(): String =
    readSystemProp("ro.config.marketing_name") ?: Build.MODEL

/** 系统版本: 定制系统版本 (如 MagicOS 8.0.0.221).
 *  ro.build.version.magic = "MagicOS_8.0.0" 取前缀,
 *  Build.VERSION.INCREMENTAL = "8.0.0.221C00E220R10P7" 取数字段. */
private fun magicOsVersionString(): String {
    val magic = readSystemProp("ro.build.version.magic")
    val name = if (magic.isNullOrEmpty()) "MagicOS" else magic.substringBefore('_')
    val inc = Build.VERSION.INCREMENTAL
    val ver = inc.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
    return if (ver.isNotEmpty()) "$name $ver" else inc
}
