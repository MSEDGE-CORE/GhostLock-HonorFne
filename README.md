# GhostLock-HonorFne
Honor 70 (FNE-AN00) privilege escalation PoC: GhostLock (CVE-2026-43499) + custom KernelSU module loading
Honor MagicOS 8.0 Launcher hook port: Advanced Transition Animations + Gesture Experience optimization

## 目录结构

```
GhostLock-HonorFne/
├── run_root.bat               # Windows 一键脚本（依赖检查→USB连接→部署→提权→管理器安装→结果报告）
├── run_root.command           # macOS 一键脚本（同 run_root.bat，双击运行）
├── root/                       # 临时 root（per-boot，重启失效）
│   ├── exploit/                #   GhostLock 提权 exploit 源码与二进制（CVE-2026-43499）
│   │                           #   构建产物 e / ghostlock；main.c 内嵌 .ghostlock_root.sh
│   ├── host-scripts/           #   macOS 端驱动脚本
│   │   ├── run_forever.sh      #     外循环：反复拉起 run_w2.sh 直到 root 落地（tee 实时回显）
│   │   ├── run_w2.sh           #     W1/W2 cred-write runner（settle → W1 --write1 → W2，全程实时日志）
│   │   ├── wait_ksu.sh         #     KSU 晚加载等待：轮询 + chain_status/chain.log 增量实时显示
│   │   ├── install_managers.sh #     KSU 成功后自动安装 KernelSU 管理器 + ZygiskNext + LSPosed
│   │   └── gl_*.sh / shift*.sh #     早期 grind 实验脚本（MCAST_SHIFT 扫描等）
│   └── ksu-late-load/          #   KernelSU 晚加载链（root 子进程执行）
│       ├── chain_hook.sh       #     主链：real kallsyms 合并校验 → magiskpolicy → insmod kernelsu.ko
│       │                       #     → ksud 分阶段启动；支持 DRY_RUN=1 只读验证；slidemismatch 自动 dump
│       ├── kernelsu.ko / ksud / magiskpolicy / ksu_rules   # 设备端物料（从设备拉回）
│       ├── fne_syms.txt        #     运行时 KASLR 重定基的符号表源（生成 fake_ksyms.txt）
│       ├── load_ko / kmsg_dumper                       # insmod loader 与 kmsg 捕获工具
│       └── *.patch / build_inner.sh / ksu_ko_build.sh  # .ko 构建与 patch
│   └── ksu-modules/            #   KSU 模块（/data/adb/modules，重启保留）
│       └── netfix_packet/      #     网络/状态栏/导航栏/AOD 修复（16 行 sepolicy.rule
│                               #     + LSPosed daemon tmpfs 补丁，详见 §netfix_packet 模块）
├── hooks/
│   └── launcher/               # 桌面/SystemUI hook：HnLauncherPort LSPosed 模块
│                               #   （V17 特性移植 + 控制中心动效优化等 SystemUI hook）
├── deploy/                     # 自动安装物料（KSU Manager / LSPosed / ZygiskNext，KSU 成功后自动装）
└── docs/
    └── README_分析说明.md      # 详细分析（提权原理、W1/W2、KSU 晚加载）
```

## 使用方法

### 前置条件

- 荣耀 70（FNE-AN00），内核 `5.4.289-qgki-gf6cb1f712a6e`（MagicOS 8.0.214-221）或
  `5.4.295-qgki-*`（MagicOS 8.0.2xx，含 `g0fb2656b2b50`/8.0.224 实机内核，共 10 个已知构建）。
- Host：macOS 或 Windows + adb；重建 exploit 另需 Android NDK r27c（自备）。
  - **Windows**：安装 [Git for Windows](https://git-scm.com/download/win)（提供 Git Bash）+ [platform-tools](https://developer.android.com/tools/releases/platform-tools)（adb 加入 PATH）。
  - **连接方式：仅 USB**（WiFi/网络 adb 已移除）。
- KSU 成功后自动安装的管理器物料（在本仓库 `deploy/`，脚本自动装，无需手动）：
  - `deploy/KernelSU-manager.apk`——KSU 管理器（已装则跳过）。
  - `deploy/LSPosed-zygisk.zip` + `deploy/ZygiskNext.zip`——经 `/data/adb/modules` 自动部署
    （主机解压读 module.prop 取 id 后推送，无条件覆盖同步）；LSPosed 管理器内嵌于模块，
    激活后经通知栏快捷方式进入。

### per-boot root

root 不会持久化——每次重启后重新 grind。

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
- **不要更换 KernelSU 管理器版本 或 尝试升级 KernelSU 内核**

### 一键脚本（Windows / macOS）和资源手动部署

#### 一键脚本（推荐）

- **Windows**：仓库根目录**双击 `run_root.bat`**（或 cmd 中运行）。
- **macOS**：仓库根目录**双击 `run_root.command`**；被 Gatekeeper 拦截时右键 → 打开，或终端 `bash run_root.command`。

脚本按提示走完全程：

```
[1/5] 检查依赖        adb（Windows 另需 Git Bash）是否就绪
[2/5] 连接设备        USB 连接（等待设备接入并授权）
[3/5] root 状态检查   KernelSU 已加载则同步 netfix_packet 后直接退出，不重复提权
[4/5] 部署            无条件覆盖推送 e、ksu_fne 全套物料（约 20MB）与 netfix_packet
                      模块——不做存在校验，任何文件迭代即装即生效
[5/5] 运行提权        bash run_forever.sh（W1 → W2 → KSU 晚加载），全程实时显示日志：
                      W1/W2 exploit 输出增量回显 + 阶段横幅 + settle/重启状态；
                      提权后等待 KSU 晚加载链（最长 180s），实时增量显示设备端
                      chain_status 与 chain.log，超时自动附 chain/kmsg 末尾日志
[+]  成功收尾         KSU 就绪后自动安装管理器（KernelSU 管理器 APK + ZygiskNext +
                      LSPosed 模块，见 install_managers.sh），随后询问是否安装
                      桌面修改（HnLauncherPort.apk，默认跳过，不写任何设置）
```

- **不要并发跑两个 grind**。
- **不要更换 KernelSU 管理器版本 或 尝试升级 KernelSU 内核**

#### 资源手动部署

手动方式（等效）：USB 连接后，在 `root\host-scripts` 目录右键 **"Git Bash Here"**，运行 `bash run_forever.sh`。

首次使用须先推送全套物料：

```sh
GL=GhostLock-HonorFne
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

KSU 模块 `netfix_packet`（网络/状态栏/导航栏/AOD 修复，见下文）也须在此 root 会话内部署一次；**模块有更新时同样用合并同步**（保留运行时 netfix.log）：

```sh
adb shell "rm -rf /data/local/tmp/netfix_packet"
adb push $GL/root/ksu-modules/netfix_packet /data/local/tmp/
adb shell "su 0 sh -c 'mkdir -p /data/adb/modules/netfix_packet && cp -rf /data/local/tmp/netfix_packet/. /data/adb/modules/netfix_packet/'"
```

### netfix_packet 模块（网络/状态栏/导航栏/AOD 修复，root/ksu-modules）

修复两个独立根因：

1. **SELinux 丢 SID / 拒查询**（16 行 `sepolicy.rule`，随 ksud late-load 自动注入）：
   `magiskpolicy` 重载策略后 netdev SID 变 unlabeled → 网络断流（14 行 packet 规则）；
   vendor service/hwservice `find` 被拒 → 持续 hwcinterface denial
   （`allow * default_android_service service_manager find` +
   `allow * default_android_hwservice hwservice_manager find`）。
2. **LSPosed daemon 叠 tmpfs 遮蔽 RRO idmap**（`post-fs-data.sh` + `service.sh` 兜底）：
   LSPosed daemon 每次启动都 `mount tmpfs /data/resource-cache`（单 boot 实测叠 67+ 层），
   遮蔽全部 Runtime Resource Overlay → 状态栏偏小/导航栏三键几何错误/AOD 死亡、
   idmap2d crash loop。脚本在 LSPosed 之前 sed 原地补丁 daemon 的 mount 行并
   umount 清理已叠层，再兜底保活 idmap2d；日志写 `$MODDIR/netfix.log`。

- 部署：run_root 一键脚本**每次运行都重新传输并合并同步**该模块（不做存在校验），
  手动部署/更新见上文「资源手动部署」末尾命令。
- 激活：规则在下一次提权/重启时注入；脚本在 ksud 脚本阶段自动运行，已 root 会话内可
  `su 0 sh /data/adb/modules/netfix_packet/post-fs-data.sh` 立即执行。
- 验证：`ping` 通；`grep -c ' /data/resource-cache ' /proc/1/mountinfo` 为 0；
  `cmd overlay list | grep -c '\[x\]'` RRO 已激活；`getprop init.svc.idmap2d` 为 running。
- **AOD**：idmap2d crash loop 可能导致 AOD 失效，需手动重新启用。
- 注意：`ksud sepolicy patch` 在本机是 no-op，勿走该路径（详见
  `root/ksu-modules/netfix_packet/README.md` 与 `apply_netfix_now.sh` 记录）。

### 桌面 hook（hooks/launcher）

前提：per-boot root 已就绪、LSPosed 已启用。

```sh
cd ~/Documents/GhostLockHonorFne/GhostLock-HonorFne/hooks/launcher
sh build.sh                                   # 产物 out/HnLauncherPort.apk
adb install -r out/HnLauncherPort.apk
# LSPosed Manager → 模块 → HnLauncherPort → 启用，作用域勾选「荣耀桌面」+「系统界面」
adb shell "su 0 sh -c 'kill \$(pidof com.hihonor.android.launcher)'"   # 重载桌面使模块生效
```

一键脚本 KSU 成功后也会询问是否自动安装该 APK。

### 重新构建（可选，仓库已含全部预编译产物）

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

## 致谢

- [GhostLock-H80GT](https://github.com/yakidango-official/GhostLock-H80GT)
- [KernelSU](https://github.com/tiann/KernelSU) 

## 许可证

- `exploit/`与文档：**Apache License 2.0**（见[LICENSE](LICENSE)），与上游PoC一致。
- `ksu/`：**GPL-2.0**（见[ksu/LICENSE](ksu/LICENSE)），`init-bootid.patch`与`ksu_rules.annotated`衍生自KernelSU的`kernel/`目录。
