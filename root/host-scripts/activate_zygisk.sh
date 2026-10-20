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
#   3. 杀旧 daemon, 清 stale 状态标记, 重跑 zygisksu service.sh 拉起全新
#      daemon。注意: daemon 会把失败状态(29B 标记)直接写进模块的
#      module.prop(原文存 module.prop.orig), 见标记即静默 exit(1)——
#      所以 [1/4] 的 modules_update 合并(覆盖回干净 module.prop)是
#      清除该标记的关键, 清 /data/adb/zygisksu 状态目录无效。
#   4. 90s 未注入只输出诊断, 不再 kill zygote: FNE 实测 [5/5] 兜底 kill
#      两次诱发整机硬重启(22:45/23:24), 已移除。
# 前置: KSU 已就绪(su 0 可用); zygisksu/zygisk_lsposed 模块已挂载。
# zygote pid 解析必须用 pidof: pgrep -x zygote64 不匹配(comm 为 main),
# pgrep -f 会误匹配 shell 自身命令行(FNE 实测三者输出互不相同)。
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

echo "      [3/4] 重置 daemon 状态并重启注入链..."
$T30 adb shell "su 0 sh -c 'pkill -9 -f zn-daemon 2>/dev/null; pkill -9 -f zn-zygisk 2>/dev/null; pkill -9 lspd 2>/dev/null; sleep 1; rm -f /data/adb/zygisksu/.abort_msg /data/adb/zygisksu/.injecting 2>/dev/null; sh $MOD/service.sh'" >/dev/null 2>&1

echo "      [4/4] 等待注入落位(最长 90s)..."
ok=0
for i in $(seq 1 18); do
    sleep 5
    if injected; then ok=1; break; fi
    echo "        ... 第 $((i*5))s, zygote 尚未注入"
done

if [ $ok -eq 1 ]; then
    echo "      Zygisk 注入成功: zygote 已加载 LSPosed zygisk 模块"
    echo "      (lspd 日志: /data/adb/lspd/log/ ; LSPosed 入口在通知栏)"
    exit 0
else
    echo "      [警告] 注入未确认。诊断信息:"
    echo "        --- module.prop 状态标记(若为 [❌...] 说明 daemon 上次失败) ---"
    $T15 adb shell "su 0 cat $MOD/module.prop" 2>/dev/null | tr -d '\r' | head -2 | sed 's/^/          /'
    echo "        --- daemon 内核日志(末 15 行, 缓冲可能已被冲掉) ---"
    $T30 adb shell "su 0 sh -c 'dmesg 2>/dev/null'" 2>/dev/null | grep -a "zn-daemon" | tail -15 | LC_ALL=C sed 's/^/          /'
    echo "        --- daemon_out.log ---"
    $T15 adb shell "su 0 cat /data/adb/zygisksu/daemon_out.log 2>/dev/null" 2>/dev/null | tr -d '\r' | tail -5 | LC_ALL=C sed 's/^/          /'
    echo "        可重跑本脚本; 手动日志: /data/adb/lspd/log/verbose_*.log"
    exit 1
fi
