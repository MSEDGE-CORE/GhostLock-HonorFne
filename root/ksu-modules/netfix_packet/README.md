# netfix_packet —— 网络 / 状态栏 / 导航栏 / AOD 修复（KSU 模块）

## 根因（两个独立问题）

### 1. KSU 晚加载 sepolicy 重载丢 SID / 拒服务查询

KSU 晚加载链里 `magiskpolicy` 中途重载策略后，所有 netdev/secmark 的 SID 变为
unlabeled、部分 vendor 服务查询被拒：

| 症状 | 直接原因 | 修复规则 |
|------|----------|----------|
| WiFi / 数据 / IMS 全断（发包全 denied） | netdev 丢 netif SID → unlabeled packet send/recv 被拒 | `allow domain unlabeled/netif packet ...` + `netif ingress/egress` + `peer recv`（14 行） |
| 持续 hwcinterface 等 hwservice denied | `hwservice_manager find` 被拒 | `allow * default_android_hwservice hwservice_manager find`（1 行） |
| vendor AIDL 服务绑定失败 | `service_manager find` 被拒 | `allow * default_android_service service_manager find`（1 行） |

以上共 16 行，全在 `sepolicy.rule`，随 ksud late-load 自动注入。

### 2. LSPosed daemon 叠 tmpfs 遮蔽全部 RRO idmap（2026-10-17 确认真根因）

`/data/adb/modules/zygisk_lsposed/daemon` 内无条件执行
`mount tmpfs -t tmpfs /data/resource-cache`，daemon 每次启动（约 30s 周期）就
在 RRO idmap 目录上再叠一层 tmpfs（实测单 boot 累计 67+ 层）：

- 全部 Runtime Resource Overlay 陷入 `STATE_NO_IDMAP` → SystemUI/框架回退
  base 资源：**状态栏高度偏小（72px 应为 90px）、导航栏三键高度（114px 应为
  102px）、横屏三键位置保留**；
- `idmap2d` 对被遮蔽目录 crash loop → **AOD（com.hihonor.aod）死亡、主题失效**。

修复（`post-fs-data.sh` + `service.sh` 兜底，幂等）：

1. sed 原地补丁 LSPosed daemon 的 mount 行（模块 id 字母序
   `netfix_packet` < `zygisk_lsposed`，post-fs-data 阶段先于 LSPosed 首次
   拉起 daemon）；
2. 按 mountinfo 循环 `umount` 清掉本 boot 已叠的层；
3. `service.sh` 兜底补一次补丁/卸载，并确保 `idmap2d` 存活（非 running 时
   `setprop ctl.start idmap2d`）。

若 LSPosed 更新覆盖了 daemon 脚本，bug 会复发，直至下次 root 会话中本模块
post-fs-data 重新打上补丁——run_root 每次都会同步本模块，一般无感恢复。

> **注意：** idmap2d crash loop 可能导致 AOD 失效，需手动重新启用。

## 文件

- `module.prop` / `sepolicy.rule` / `post-fs-data.sh` / `service.sh` ——
  KSU 模块本体：规则 16 行 + LSPosed tmpfs 补丁/清理 + idmap2d 保活，
  运行日志写 `$MODDIR/netfix.log`。
- `apply_netfix_now.sh` —— 历史尝试记录（参考，勿再依赖）：`ksud sepolicy patch` 在本机是 **no-op**、`load_policy` 需 checkpolicy 不可用、setenforce 被 HISEC/KSU hook 拦截。**唯一可靠路径 = 模块 `sepolicy.rule` 随 ksud late-load 注入。**
- `sepolicy.rule.network_fix` —— 早期 10 行草案，仅存档。
- `zygisksu_sepolicy.rule.merged` —— 合并变体，**未部署**，仅存档。

## 部署（root 会话内；run_root 已自动完成）

模块装进 `/data/adb/modules/`（root 目录，adb 直推不行，经中转 + `su 0 cp`）。
更新模块用**合并同步**（保留运行时 netfix.log）：

```sh
GL=~/Documents/GhostLockHonorFne/GhostLock-HonorFne
adb shell "rm -rf /data/local/tmp/netfix_packet"
adb push $GL/root/ksu-modules/netfix_packet /data/local/tmp/
adb shell "su 0 sh -c 'mkdir -p /data/adb/modules/netfix_packet && cp -rf /data/local/tmp/netfix_packet/. /data/adb/modules/netfix_packet/'"
```

## 激活

- `sepolicy.rule` 只在 ksud late-load 时注入——每次开机重跑 grind 后自动生效。
- `post-fs-data.sh` / `service.sh` 在**本次已 root 的会话**内即生效（下一次
  ksud 脚本阶段运行时；或手动 `su 0 sh .../post-fs-data.sh` 立即执行）。
- 已部署记录：2026-10-16 20:46 首次部署 v1.0；2026-10-17 同步 v1.1。

## 验证

```sh
adb shell ping -c 1 192.168.1.1        # 网络通
adb shell "su 0 cat /data/adb/modules/netfix_packet/netfix.log"          # 补丁/卸载日志
adb shell "su 0 grep -c ' /data/resource-cache ' /proc/1/mountinfo"      # 应为 0
adb shell "su 0 cmd overlay list | grep -c '\[x\]'"                      # RRO 已激活
adb shell "getprop init.svc.idmap2d"                                    # running
```
