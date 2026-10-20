#!/bin/bash
# KSU 晚加载等待 + 实时日志显示（run_root.command / run_root.bat 共用）。
# 轮询 /proc/modules 直到 kernelsu 出现（最长 180 秒）；
# 期间增量打印设备端 chain_status / chain.log 新行，失败时输出末尾日志辅助定位。
# 退出码: 0 = KSU 已加载, 1 = 超时未加载。

D=/data/local/tmp/ksu_fne
T15=""; T30=""
command -v timeout >/dev/null 2>&1 && { T15="timeout 15"; T30="timeout 30"; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

LAST_LINES=0
LAST_STATUS=""

echo "等待 KSU 晚加载链完成（最长 180 秒，实时显示链日志）..."

poll() {
    # adb 可能因设备重启瞬时不可用：失败只返回空，不中断轮询。
    # 成功判定必须实测 su —— 仅模块在 /proc/modules 不够: 设备重启瞬间
    # 垂死 boot 里模块仍在但 su 不可用, 会产生假成功(FNE 实测)。
    $T15 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0" && return 0
    return 1
}

i=0
while [ $i -lt 36 ]; do
    if poll; then exit 0; fi

    # --- chain_status 变化提示 ---
    st=$($T15 adb shell "cat $D/chain_status 2>/dev/null" 2>/dev/null | tr -d '\r')
    if [ -n "$st" ] && [ "$st" != "$LAST_STATUS" ]; then
        echo "  [状态] $st"
        LAST_STATUS="$st"
    fi

    # --- chain.log 增量打印 ---
    $T15 adb shell "cat $D/chain.log 2>/dev/null" 2>/dev/null | tr -d '\r' > "$TMP/chain.log"
    if [ -f "$TMP/chain.log" ]; then
        total=$(wc -l < "$TMP/chain.log")
        if [ "$total" -gt "$LAST_LINES" ]; then
            # chain.log 含内核二进制转储: macOS sed 遇非法字节会断言崩溃,
            # 必须 LC_ALL=C 按字节处理
            tail -n $((total - LAST_LINES)) "$TMP/chain.log" | LC_ALL=C sed 's/^/  [chain] /'
            LAST_LINES=$total
        fi
    fi

    i=$((i + 1))
    echo "  ... 第 $((i * 5))/180 秒，KSU 尚未加载"
    sleep 5
done

poll && exit 0

echo "  --------------------------------------------"
echo "  [超时] KSU 未加载。chain.log 末尾 30 行:"
$T30 adb shell "tail -n 30 $D/chain.log 2>/dev/null" 2>/dev/null | tr -d '\r' | LC_ALL=C sed 's/^/  [chain] /'
kcap=$($T30 adb shell "tail -n 15 $D/kmsg_cap.txt 2>/dev/null" 2>/dev/null | tr -d '\r')
if [ -n "$kcap" ]; then
    echo "  [内核] kmsg_cap.txt 末尾 15 行:"
    echo "$kcap" | sed 's/^/  [kmsg] /'
fi
echo "  --------------------------------------------"
exit 1
