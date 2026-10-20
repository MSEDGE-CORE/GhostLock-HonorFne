#!/bin/bash
# activate_zygisk.sh —— KSU 就绪后激活 ZygiskNext/LSPosed 注入。
#
# 配方来源(FNE 12:40 实测全链路打通, 16:47 反证链内时机不可行):
#   1. magiskpolicy 放行 permissive kernel/zygote/system_server + 应用模块
#      sepolicy.rule —— zn-daemon(kernel:s0) ptrace 注入 zygote 在 enforce
#      下会被真拒(getenforce 的 Permissive 是内核伪装, AVC permissive=0)。
#   2. 合并 modules_update -> modules: 链上 ksud 阶段不合并 modules_update
#      (FNE 实测跨整个 boot 不合并), 挂载副本停留裸解压状态(bin/ 无
#      zygiskd, service.sh 报 "No such file or directory" 不注入)。
#   3. 杀旧 daemon, 清失败标记(.injecting/.abort_msg/disable/update) +
#      无条件还原 module.prop。daemon 失败时把状态写进 module.prop, 实测
#      两种格式: a) 29B 裸标记 "[❌ Last injection failed!]"(替换整个文件);
#      b) 完整 prop 但 description="[❌ Module files corrupted]"(00:18 实测)。
#      而 verify1 = 模块文件哈希自检(module.prop 对 module.prop.sha256,
#      FNE 00:30 实测 42 文件全对账仅 module.prop 不匹配), module.prop 一旦
#      被改写, 后续每次 daemon 启动必挂 "Module files corrupted" 死亡螺旋,
#      且 module.prop.orig 也可能被连带改坏 —— 因此必须用嵌入的 zip 原文
#      (211B, sha256 前 16 位 2f8cdf9a676ed7b8)同时还原两个文件, 不能只认
#      head -c1="[" 的旧格式。严禁移动/删除 /data/adb/zygisksu 状态目录:
#      daemon 启动时校验完整状态现场(bugreports/ 等), 状态目录被清后必报
#      "Module files corrupted" 并写 disable 自禁(FNE 23:38/23:42 实测,
#      恢复现场后 23:47 一次成功)。
#   4. verify1 真相(FNE 02:18 kprobe 实证): daemon 失败前只 open 过
#       /data/adb/zygisksu/.magic, 完全不碰模块文件 —— verify1 = .magic
#       内容校验, 与 module.prop 无关; 失败路径会用随机值"毒丸化".magic 并
#       再生新值。唯一已知有效值 = /data/adb/zygisksu.bak/.magic(02:08/02:11
#       两次实证过 verify1), 必须在起 daemon 前恢复。
#   5. RescueParty 才是所谓"荣耀看门狗"(02:42 dumpsys 实证): 每次开机
#      ~7min 后逐级升级(UNTRUSTED_DEFAULTS→CHANGES→TRUSTED→WARM_REBOOT,
#      每 12min 一级, ~42min 温重启整机), 23:46 甚至执行过 FACTORY_RESET
#      级救援(/data 幸存属侥幸)。root 后立即禁用, persist 属性永久止血。
#   6. 90s 未注入只输出诊断, 不再 kill zygote: FNE 实测 [5/5] 兜底 kill
#      两次诱发整机硬重启(22:45/23:24), 已移除。
# 前置: KSU 已就绪(su 0 可用); zygisksu/zygisk_lsposed 模块已挂载。
# zygote pid 解析必须用 pidof: pgrep -x zygote64 不匹配(comm 为 main),
# pgrep -f 会误匹配 shell 自身命令行(FNE 实测三者输出互不相同)。
# daemon 起动后 prctl 改名 comm=zn-daemon(cmdline 仍 zygiskd64 daemon),
# pkill -f zn-daemon 匹配 cmdline 失效, 必须用 comm 匹配 pkill zn-daemon。
cd "$(dirname "$0")/../.." || exit 1
T15=""; T30=""
command -v timeout >/dev/null 2>&1 && { T15="timeout 15"; T30="timeout 30"; }
MOD=/data/adb/modules/zygisksu

# 设备端解析 magiskpolicy 路径(chain 部署目录)
MP=$($T15 adb shell "su 0 sh -c 'for p in /data/local/tmp/ksu_fne/magiskpolicy /data/local/tmp/*/magiskpolicy; do [ -x \"\$p\" ] && echo \"\$p\" && break; done'" 2>/dev/null | tr -d '\r' | head -1)

have_root() { $T15 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; }
injected() {
    $T15 adb shell "su 0 sh -c 'for Z in \$(pidof zygote64); do grep -q zygisk_lsposed /proc/\$Z/maps 2>/dev/null && exit 0; done; exit 1'" 2>/dev/null
}

echo "      激活 Zygisk/LSPosed 注入..."
# su 可能晚于模块就绪(ksud 阶段注入 allowlist): 轮询等待, 最长 60s
n=0
while ! have_root; do
    if [ $n -ge 12 ]; then
        echo "      [警告] su 不可用, 跳过 Zygisk 激活"
        exit 1
    fi
    sleep 5; n=$((n + 1))
done
# RescueParty 止血(见头部注释 5): persist 属性跨重启永久生效。
# 两种命名都覆盖(AOSP/Honor 分支差异), root 下 setprop persist.* 必成。
$T15 adb shell "su 0 sh -c 'setprop persist.sys.enable_rescue_party false; setprop persist.sys.disable_rescue true'" >/dev/null 2>&1
if ! $T15 adb shell "su 0 test -f $MOD/service.sh" 2>/dev/null; then
    echo "      [提示] zygisksu 模块未挂载(可能未安装), 跳过"
    exit 0
fi
if [ -z "$MP" ]; then
    echo "      [警告] 设备上未找到 magiskpolicy, 跳过(需 chain 部署目录)"
    exit 1
fi
# 设备正在重启时不动 daemon(ksud 会报 "Android is Booting", 半启动状态
# 下重启注入链有整机重启风险) —— 等启动完成, 最长 180s。
n=0
bc=$($T15 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
while [ "$bc" != "1" ] && [ $n -lt 36 ]; do
    echo "        等待系统完成启动 (boot_completed=$bc, $((n*5))s)..."
    sleep 5; n=$((n+1))
    bc=$($T15 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
done

echo "      [1/4] 合并 modules_update 新装模块到挂载目录..."
$T30 adb shell "su 0 sh -c 'for m in zygisksu zygisk_lsposed; do [ -d /data/adb/modules_update/\$m ] && cp -rf /data/adb/modules_update/\$m/. /data/adb/modules/\$m/ 2>/dev/null; done'" >/dev/null 2>&1
if ! $T15 adb shell "su 0 test -e $MOD/bin/zygiskd" 2>/dev/null; then
    echo "      [警告] $MOD/bin/zygiskd 仍缺失(模块包不完整), 激活中止"
    exit 1
fi

echo "      [2/4] 注入 permissive 域 + 模块 sepolicy 规则..."
$T30 adb shell "su 0 $MP --live 'permissive kernel' 'permissive zygote' 'permissive system_server'" >/dev/null 2>&1
$T30 adb shell "su 0 sh -c 'for r in /data/adb/modules/*/sepolicy.rule; do [ -f \"\$r\" ] && $MP --apply \"\$r\" 2>/dev/null; done'" >/dev/null 2>&1

echo "      [3/4] 还原 module.prop + 清失败标记并重启注入链..."
# zip 内 pristine module.prop (211B, sha256=2f8cdf9a...) 的 base64。
# daemon 两种失败改写格式(29B 裸标记 / description 嵌入)都会破坏
# verify1 哈希自检, module.prop.orig 也可能被连带改坏, 故无条件双还原。
PRISTINE_B64='aWQ9enlnaXNrc3UKbmFtZT1aeWdpc2sgTmV4dAp2ZXJzaW9uPTEuNS4yICg4NjQtYTRhNzJjZS1yZWxlYXNlKQp2ZXJzaW9uQ29kZT04NjQKYXV0aG9yPUxTUG9zZWQgRGV2ZWxvcGVycwpkZXNjcmlwdGlvbj1TdGFuZGFsb25lIGltcGxlbWVudGF0aW9uIG9mIFp5Z2lzay4KdXBkYXRlSnNvbj1odHRwczovL2xzcG9zZWQuemlwL3p5Z2lzay1uZXh0L3VwZGF0ZS5qc29uCg=='
$T30 adb shell "su 0 sh -c 'pkill -9 zn-daemon 2>/dev/null; pkill -9 zygiskd64 2>/dev/null; pkill -9 lspd 2>/dev/null; sleep 1; echo $PRISTINE_B64 | base64 -d > $MOD/module.prop.orig; cp $MOD/module.prop.orig $MOD/module.prop; rm -f /data/adb/zygisksu/.abort_msg /data/adb/zygisksu/.injecting $MOD/disable $MOD/update; if [ -f /data/adb/zygisksu.bak/.magic ]; then cp /data/adb/zygisksu.bak/.magic /data/adb/zygisksu/.magic; chmod 666 /data/adb/zygisksu/.magic; fi; sh $MOD/service.sh'" >/dev/null 2>&1
h=$($T15 adb shell "su 0 sha256sum $MOD/module.prop" 2>/dev/null | tr -d '\r' | cut -c1-16)
if [ "$h" != "2f8cdf9a676ed7b8" ]; then
    echo "      [警告] module.prop 还原后哈希异常($h), verify1 大概率失败"
fi
if ! $T15 adb shell "su 0 sh -c 'cmp -s /data/adb/zygisksu.bak/.magic /data/adb/zygisksu/.magic'" 2>/dev/null; then
    echo "      [警告] .magic 未能恢复为已知有效值(zygisksu.bak 缺失或复制失败), verify1 必挂"
fi

echo "      [4/4] 等待注入落位(最长 90s)..."
ok=0
for i in $(seq 1 18); do
    sleep 5
    if injected; then ok=1; break; fi
    echo "        ... 第 $((i*5))s, zygote 尚未注入"
done

if [ $ok -eq 1 ]; then
    # lspd 启动/管理器打开时用 dex2oat 探针自检 sepolicy 规则是否加载,
    # 其启动链路可能冲掉 [2/4] 注入的模块规则——注入落位后补一遍,
    # 否则 LSPosed 管理器报 "SEPolicy 未被正确加载"(dex2oat AVC denied)。
    $T30 adb shell "su 0 sh -c 'for r in /data/adb/modules/*/sepolicy.rule; do [ -f \"\$r\" ] && $MP --apply \"\$r\" 2>/dev/null; done'" >/dev/null 2>&1
    echo "      Zygisk 注入成功: zygote 已加载 LSPosed zygisk 模块"
    echo "      (lspd 日志: /data/adb/lspd/log/ ; LSPosed 入口在通知栏)"
    exit 0
else
    echo "      [警告] 注入未确认。诊断信息:"
    echo "        --- module.prop(若 description=[❌...] 或为 29B 裸标记说明 daemon 失败) ---"
    $T15 adb shell "su 0 sh -c 'head -c1 $MOD/module.prop | grep -q \"\\[\" && cat $MOD/module.prop || grep -E \"^(description|version)=\" $MOD/module.prop'" 2>/dev/null | tr -d '\r' | head -3 | sed 's/^/          /'
    echo "        --- daemon 内核日志(末 15 行, 缓冲可能已被冲掉) ---"
    $T30 adb shell "su 0 sh -c 'dmesg 2>/dev/null'" 2>/dev/null | grep -a "zn-daemon" | tail -15 | LC_ALL=C sed 's/^/          /'
    echo "        --- daemon_out.log ---"
    $T15 adb shell "su 0 cat /data/adb/zygisksu/daemon_out.log 2>/dev/null" 2>/dev/null | tr -d '\r' | tail -5 | LC_ALL=C sed 's/^/          /'
    echo "        可重跑本脚本; 手动日志: /data/adb/lspd/log/verbose_*.log"
    exit 1
fi
