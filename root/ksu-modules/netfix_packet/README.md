# netfix_packet —— 网络 / 状态栏 / AOD SELinux 修复（KSU 模块）

## 根因

KSU 晚加载链里 `magiskpolicy` 中途重载策略后，所有 netdev/secmark 的 SID 变为
unlabeled、部分 vendor 服务查询被拒，引发三类同源故障：

| 症状 | 直接原因 | 修复规则 |
|------|----------|----------|
| WiFi / 数据 / IMS 全断（发包全 denied） | netdev 丢 netif SID → unlabeled packet send/recv 被拒 | `allow domain unlabeled/netif packet ...` + `netif ingress/egress` + `peer recv`（14 行） |
| 状态栏 / AOD 异常 | system_server 持续 `denied find` vendor AIDL，状态栏/AOD 的 vendor 服务绑定失败 | `allow * default_android_service service_manager find`（1 行） |

状态栏/AOD 没有独立修复模块——与网络断流同源，都在本模块的 15 行
`sepolicy.rule` 里。

## 文件

- `module.prop` / `sepolicy.rule` / `service.sh` / `post-fs-data.sh` —— KSU 模块本体（`service.sh`、`post-fs-data.sh` 为空占位，规则全部走 `sepolicy.rule`）。
- `apply_netfix_now.sh` —— 历史尝试记录（参考，勿再依赖）：`ksud sepolicy patch` 在本机是 **no-op**（load mtime 不变）、`load_policy` 需 checkpolicy 不可用、setenforce 被 HISEC/KSU hook 拦截。**唯一可靠路径 = 模块 `sepolicy.rule` 随 ksud late-load 注入。**
- `sepolicy.rule.network_fix` —— 早期 10 行草案（带分号的 audit 语法），已被模块版取代，仅存档。
- `zygisksu_sepolicy.rule.merged` —— 合并变体（zygisksu 原 17 行 + netfix 15 行），**未部署**：设备 `/data/adb/modules/zygisksu/sepolicy.rule` 保持原版，netfix 规则由本模块独立提供。若想收敛为单模块可改用此文件。

## 部署（一次性，须在 root 会话内）

模块装进 `/data/adb/modules/`（root 目录，adb 直推不行，经中转 + `su 0 cp`）：

```sh
GL=~/Documents/GhostLockHonorFne/GhostLock-HonorFne
adb push $GL/root/ksu-modules/netfix_packet /data/local/tmp/netfix_packet
adb shell "su 0 cp -r /data/local/tmp/netfix_packet /data/adb/modules/netfix_packet"
```

## 激活（自动）

- 模块文件在 `/data`（重启不丢），但**规则只在 ksud late-load 时注入**——
  即每次开机重跑 grind（W1→W2→chain_hook→ksud 分阶段）时自动读取
  `/data/adb/modules/*/sepolicy.rule` 注入内核策略。
- 换言之：装一次，之后每次 per-boot root 成功即自动激活，无需手动操作。
- 已部署记录：2026-10-16 20:46 推入设备 `/data/adb/modules/netfix_packet/`。

## 验证

```sh
adb shell ping -c 1 192.168.1.1        # 网络通
adb shell "su 0 dmesg | grep 'avc:.*denied' | grep -E 'packet|service_manager' | tail"
# root 会话内无新增 denied 即已生效
```
