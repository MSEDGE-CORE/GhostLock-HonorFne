#!/bin/bash
# activate_zygisk.sh —— KSU 就绪后激活 ZygiskNext/LSPosed 注入。
#
# 配方来源(FNE 12:40 实测全链路打通, 16:47 反证链内时机不可行):
#   1. magiskpolicy 放行 permissive kernel/zygote/system_server + 应用模块
#      sepolicy.rule —— zn-daemon(kernel:s0) ptrace 注入 zygote 在 enforce
#      下会被真拒(getenforce 的 Permissive 是内核伪装, AVC permissive=0)。
#   2. 杀掉旧 zn-daemon/lspd/companion, 重置 /data/adb/zygisksu 状态目录
#      (清 stale .abort_msg, 防 "Module files corrupted" 死循环)。
#   3. 重跑 zygisksu service.sh 拉起全新 daemon; lspd 自带 system_server
#      重启逻辑, 注入随 zygote 重启落位。
#   4. 兜底: 90s 未注入则 kill zygote 触发 init 重启, zn-daemon 注入新 zygote。
# 前置: KSU 已就绪(su 0 可用); zygisksu/zygisk_lsposed 模块已挂载。
cd "$(dirname "$0")/../.." || exit 1
T15=""; T30=""
command -v timeout >/dev/null 2>&1 && { T15="timeout 15"; T30="timeout 30"; }
MOD=/data/adb/modules/zygisksu

# 设备端解析 magiskpolicy 路径(chain 部署目录)
MP=$($T15 adb shell "su 0 sh -c 'for p in /data/local/tmp/ksu_fne/magiskpolicy /data/local/tmp/*/magiskpolicy; do [ -x \"\$p\" ] && echo \"\$p\" && break; done'" 2>/dev/null | tr -d '\r' | head -1)

have_root() { $T15 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; }
injected() {
    $T15 adb shell "su 0 sh -c 'for Z in \$(pgrep -x zygote64); do grep -q zygisk_lsposed /proc/\$Z/maps 2>/dev/null && exit 0; done; exit 1'" 2>/dev/null
}

echo "      激活 Zygisk/LSPosed 注入..."
if ! have_root; then
    echo "      [警告] su 不可用, 跳过 Zygisk 激活"
    exit 1
fi
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

echo "      [1/4] 注入 permissive 域 + 模块 sepolicy 规则..."
$T30 adb shell "su 0 $MP --live 'permissive kernel' 'permissive zygote' 'permissive system_server'" >/dev/null 2>&1
$T30 adb shell "su 0 sh -c 'for r in /data/adb/modules/*/sepolicy.rule; do [ -f \"\$r\" ] && $MP --apply \"\$r\" 2>/dev/null; done'" >/dev/null 2>&1

echo "      [2/4] 重置 daemon 状态并重启注入链..."
$T30 adb shell "su 0 sh -c 'pkill -9 -f zn-daemon 2>/dev/null; pkill -9 -f zn-zygisk 2>/dev/null; pkill -9 lspd 2>/dev/null; sleep 1; [ -d /data/adb/zygisksu ] && mv /data/adb/zygisksu /data/adb/zygisksu.bak.\$(date +%s) 2>/dev/null; sh $MOD/service.sh'" >/dev/null 2>&1

echo "      [3/4] 等待注入落位(最长 90s)..."
ok=0
for i in $(seq 1 18); do
    sleep 5
    if injected; then ok=1; break; fi
    echo "        ... 第 $((i*5))s, zygote 尚未注入"
done

if [ $ok -eq 0 ]; then
    echo "      [4/4] 兜底: 重启 zygote 触发注入..."
    $T30 adb shell "su 0 pkill -9 -x zygote64" >/dev/null 2>&1
    for i in $(seq 1 12); do
        sleep 5
        if injected; then ok=1; break; fi
    done
fi

if [ $ok -eq 1 ]; then
    echo "      Zygisk 注入成功: zygote 已加载 LSPosed zygisk 模块"
    echo "      (lspd 日志: /data/adb/lspd/log/ ; LSPosed 入口在通知栏)"
    exit 0
else
    echo "      [警告] 注入未确认, 可重跑本脚本; 日志:"
    echo "        /data/adb/lspd/log/verbose_*.log / /data/adb/zygisksu*/daemon_out.log"
    exit 1
fi
