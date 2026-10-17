# GhostLock-HonorFne
Honor 70 (FNE-AN00) privilege escalation PoC: GhostLock (CVE-2026-43499) + custom KernelSU module loading

## 目录结构

```
GhostLock-HonorFne/
├── run_root.bat               # Windows 一键脚本（依赖检查→连接→部署→提权→结果报告）
├── root/                       # 临时 root（per-boot，重启失效）
│   ├── exploit/                #   GhostLock 提权 exploit 源码与二进制（CVE-2026-43499）
│   │                           #   构建产物 e / ghostlock；main.c 内嵌 .ghostlock_root.sh
│   ├── host-scripts/           #   macOS 端驱动脚本
│   │   ├── run_forever.sh      #     外循环：反复拉起 run_w2.sh 直到 root 落地
│   │   ├── run_w2.sh           #     W1/W2 cred-write runner（settle → W1 --write1 → W2）
│   │   └── gl_*.sh / shift*.sh #     早期 grind 实验脚本（MCAST_SHIFT 扫描等）
│   └── ksu-late-load/          #   KernelSU 晚加载链（root 子进程执行）
│       ├── chain_hook.sh       #     主链：fake kallsyms → magiskpolicy → insmod kernelsu.ko → ksud 分阶段启动
│       ├── kernelsu.ko / ksud / magiskpolicy / ksu_rules   # 设备端物料（从设备拉回）
│       ├── fne_syms.txt        #     运行时 KASLR 重定基的符号表源（生成 fake_ksyms.txt）
│       ├── load_ko / kmsg_dumper                       # insmod loader 与 kmsg 捕获工具
│       └── *.patch / build_inner.sh / ksu_ko_build.sh  # .ko 构建与 patch
├── hooks/
│   ├── launcher/               # 桌面 hook：HnLauncherPort LSPosed 模块
│   │                           #   （V17 特性移植：跟手缩放/物理落桌/连续切换/负一屏保持等）
│   └── systemui/               # SystemUI hook（占位，待开发）
├── deploy/                     # 一次性安装物料（KSU Manager / LSPosed / ZygiskNext）
└── docs/
    └── README_分析说明.md      # 详细分析（提权原理、W1/W2、KSU 晚加载）
```

## 使用方法

### 0. 前置条件

- 荣耀 70（FNE-AN00），Bootloader 锁定（全程不解锁、不刷分区），内核 `5.4.289-qgki-gf6cb1b712a6e`（MagicOS 8.0）。
- Host：macOS 或 Windows + adb；重建 exploit 另需 Android NDK r27c（自备）。
  - **Windows**：安装 [Git for Windows](https://git-scm.com/download/win)（提供 Git Bash）+ [platform-tools](https://developer.android.com/tools/releases/platform-tools)（adb 加入 PATH）。
- 一次性安装（保留在设备上，重启不丢，物料在本仓库 `deploy/`）：
  - `deploy/KernelSU-manager.apk`——root 链用它定位 `libksud.so` 并设置 `persist.adb.tcp.port`；
  - `deploy/LSPosed-zygisk.zip` + `deploy/ZygiskNext.zip`（经 KSU 模块方式安装）——hooks 的载体。

### 1. 一次性部署（刷机/重置后）

```sh
GL=~/Documents/GhostLockHonorFne/GhostLock-HonorFne
D=/data/local/tmp/ksu_fne

# exploit 二进制（改名 e，run_w2.sh 固定找 /data/local/tmp/e）
adb push $GL/root/exploit/ghostlock /data/local/tmp/e
adb shell "chmod 755 /data/local/tmp/e"

# KSU 晚加载链（chain_hook.sh 所需全部物料，与脚本同目录）
adb shell "mkdir -p $D"
adb push $GL/root/ksu-late-load/chain_hook.sh \
           $GL/root/ksu-late-load/kernelsu.ko \
           $GL/root/ksu-late-load/ksud \
           $GL/root/ksu-late-load/magiskpolicy \
           $GL/root/ksu-late-load/ksu_rules \
           $GL/root/ksu-late-load/load_ko \
           $GL/root/ksu-late-load/kmsg_dumper \
           $GL/root/ksu-late-load/fne_syms.txt \
           $D/
adb shell "chmod 755 $D/chain_hook.sh $D/load_ko $D/kmsg_dumper"
```

### 2. 每次开机恢复 root（per-boot）

root 不会持久化——每次重启后重新 grind。

#### Windows：一键脚本（推荐）

仓库根目录**双击 `run_root.bat`**（或 cmd 中运行），脚本按提示走完全程：

```
[1/5] 检查依赖        adb 与 Git Bash 是否就绪
[2/5] 连接设备        选择 USB 或 WiFi（输入手机 IP，端口 5555）
[3/5] root 状态检查   KernelSU 已加载则直接退出，不重复提权
[4/5] 部署检查        首次使用自动推送 e 与 ksu_fne 全套物料
[5/5] 运行提权        bash run_forever.sh（W1 → W2 → KSU 晚加载），结束报告结果
```

手动方式（等效）：在 `root\host-scripts` 目录右键 **"Git Bash Here"**：

```sh
adb connect <手机IP>:5555    # WiFi 方式；USB 免此步
bash run_forever.sh
```

#### macOS

```sh
cd ~/Documents/GhostLockHonorFne/GhostLock-HonorFne/root/host-scripts
./run_forever.sh          # 无人值守：外循环拉起 run_w2.sh，成功自动停止
```

流程（全自动，含失败重启/参数爬坡）：

1. `settle`：杀后台应用，等 CPU 空闲 ≥85%（W1 对时序极敏感）。
2. **W1**：`env $SYNC e --write1` → 内核写 1 → SELinux Permissive（失败自动重启设备重来，`GL_W2_CRED_ADJ` 按 erase/uid 阶梯自动 +8）。
3. **W2**：cred 写 → root 子进程 → 执行 `e` 内嵌的 `.ghostlock_root.sh` → detached 拉起 `$D/chain_hook.sh`：
   fake kallsyms（按本次开机 slide 重定基，自校验）→ `magiskpolicy` 注入 ksu 规则 → `load_ko` insmod `kernelsu.ko` → `ksud` 分阶段启动（post-fs-data / services / boot-completed / install）→ 最后一步恢复 SELinux enforcing。
4. 成功标志（run_forever.sh 自动检测 `KSU READY`，手动验证）：

```sh
adb shell "su 0 grep kernelsu /proc/modules"    # 有输出即 KSU 已加载
```

- 常用诊断：`/data/local/tmp/.ghostlock_root.log`、`$D/chain.log`、`$D/chain_status`（`ok` = 链完成）。
- 可单独跑一轮：`./run_w2.sh`（不走外循环）；`gl_*.sh`、`shift*.sh` 为早期 shift 扫描实验脚本，正常流程用不到。
- **不要并发跑两个 grind**。

### 3. 桌面 hook（hooks/launcher）

前提：per-boot root 已就绪、LSPosed 已启用。

```sh
cd ~/Documents/GhostLockHonorFne/GhostLock-HonorFne/hooks/launcher
sh build.sh                                   # 产物 out/HnLauncherPort.apk
adb install -r out/HnLauncherPort.apk
# LSPosed Manager → 模块 → HnLauncherPort → 启用，作用域勾选「荣耀桌面」
adb shell "su 0 sh -c 'kill \$(pidof com.hihonor.android.launcher)'"   # 重载桌面使模块生效
```

功能开关（Settings.Global，实时读取，免重启模块）：

```sh
adb shell "su 0 sh -c 'settings put global hn_port_<key> 1'"
```

| key | 功能 |
|-----|------|
| `motion` | V17 物理弹簧落桌动画 |
| `parallel` | 连续切换应用（即时中断上一动画；canBreak 仅发射飞行中强制） |
| `blur` | V17 高斯模糊策略 |
| `icon_hide` | 拖拽时图标隐藏 |
| `win_scale` | 上滑跟手窗口缩放 |
| `keep_hiboard` | 返家保持负一屏/资源库 |
| `onestep_threshold` | OneStep 手势阈值调整 |
| `disable_icon_card` | 禁用图标卡片 |
| `win_debug` | 跟手诊断日志 |

改完杀桌面进程即可生效：`am force-stop` 无效，须用上面的 `kill`。

### 4. SystemUI hook（hooks/systemui）

待开发。结构与 launcher 同构（独立 LSPosed 模块，模块代码禁用 lambda，javac -source 8）。

### 5. 重新构建（可选，仓库已含全部预编译产物）

**exploit（e）**——NDK 自备：

```sh
cd root/exploit
ANDROID_NDK_HOME=<你的NDK路径> make           # 产物 ./ghostlock
```

**kernelsu.ko**——需完整 FNE 内核开源树（体积过大不入库，自备）：荣耀开源发布的
FNE-AN00 MagicUI 6.1 `Code_Opensource`（`fne_src/Code_Opensource/kernel`，内核 5.4.147，
构建时 sublevel 伪造为 289）。另需 docker/colima：

```sh
cd root/ksu-late-load
KERNEL_SRC=<Code_Opensource/kernel 路径> ./ksu_ko_build.sh
# 克隆 KernelSU v3.3.0 + 打 patch + 容器内构建 → .build/kernelsu.ko
```

详细分析见 `docs/README_分析说明.md` 与 `root/exploit/README.md`。
