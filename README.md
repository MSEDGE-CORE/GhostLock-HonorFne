# GhostLock-HonorFne
- 荣耀 70（FNE-AN00）临时 ROOT：GhostLock（CVE-2026-43499）+ 自定义 KernelSU 晚加载。
- MagicOS 8.0 桌面 & SystemUI 模块：LSPosed 修改 过渡动画 + 手势优化。

## 目录结构

```
GhostLock-HonorFne/
├── run_root.bat / run_root.command    # 一键脚本（Windows / macOS）
├── root/
│   ├── exploit/                       # GhostLock 提权源码与二进制
│   ├── host-scripts/                  # 驱动脚本
│   └── ksu-late-load/                 # KernelSU 晚加载链（chain_hook.sh + 物料）
│       └── ksu-modules/netfix_packet/ # 网络/状态栏/导航栏/AOD 修复模块
├── hooks/launcher/                    # HnLauncherPort LSPosed 模块
├── deploy/                            # KSU Manager / LSPosed / ZygiskNext 自动安装物料
└── docs
    └── README_1.md / 资料_分析说明.md   # 详细信息（仓库内容、提权原理、构建方式）
```

## 使用

### 前置条件

- **设备**：荣耀 70/60/60Pro（FNE/LSA/TNA），系统版本8.0.221/224，内核 5.4.289-qgki-gf6cb1f712a6e/5.4.295-qgki-g0fb2656b2b50|
- **主机**：macOS 或 Windows，需安装 [Platform-tools](https://developer.android.com/tools/releases/platform-tools) 和 [Git for Windows](https://git-scm.com/download/win) 

### 一键脚本（Windows / macOS）

- **Windows**：仓库根目录 命令提示符（右键 - 在终端中打开 - 输入 `cmd` / 地址栏 输入 `cmd`）`run_root.bat`。
- **macOS**：终端 cd 仓库根目录 `bash run_root.command`。

脚本按提示走完全程：

```
[1/5] 检查依赖        前置条件是否就绪
[2/5] 连接设备        USB 连接（设备接入并授权，建议手机端点击 "始终允许"）
[3/5] ROOT 状态检查   KSU 已加载则跳过
[4/5] 部署            覆盖推送 e、ksu_fne 物料、netfix_packet 模块
[5/5] 运行提权        run_forever.sh（W1 → W2 → KSU 晚加载），全程实时日志
[+]  成功收尾         自动装 KSU Manager / ZygiskNext / LSPosed，询问是否装 HnLauncherPort
```

- **不要并发跑两个 grind**。
- **不要更换 KernelSU 管理器版本 或 尝试升级 KernelSU 内核**


## 内置模块
### Netfix_packet 模块（网络/状态栏/导航栏/AOD 修复）
修复 ROOT 过程引入的两个独立问题：

1. **网络断流 / hwservice 被拒**    
16 行 sepolicy.rule，随 ksud late-load 注入。    
修复 magiskpolicy 重载后 netdev SID 变 unlabeled，vendor service find 被拒。

2. **状态栏导航栏错位 / AOD 失效**    
post-fs-data.sh + service.sh 补丁 daemon 的 mount 行 + 清理已叠层 + 保活 idmap2d。    
防止 LSPosed daemon 每次启动 mount tmpfs /data/resource-cache（单 boot 叠 67+ 层），遮蔽全部 RRO。


### 耀衍（桌面/SystemUI hook）
- 项目目录 & APK：
`/hooks/Launcher/` 
`/hooks/Launcher/out/HnLauncherPort.apk`

- **启用高级动效**  高级模糊、图标入场、状态栏连贯
- **重构惯性模型**  桌面应用手势惯性、控制中心横向切换
- **优化操作便捷性**  一步小窗阈值优化、最小化手势优化、控制中心全域横向滑动、单击亮屏
- **清理冗余控件**  隐藏手势提示条、禁用锁屏小工具


## 致谢

- [GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT)
- [KernelSU](https://github.com/tiann/KernelSU) 

## 许可证

- `exploit/`：**Apache License 2.0**（见[LICENSE](LICENSE)）
- `ksu/`：**GPL-2.0**（见[KernelSU/LICENSE](https://github.com/tiann/KernelSU/LICENSE)）
