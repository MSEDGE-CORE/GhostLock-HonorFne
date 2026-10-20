#!/bin/bash
# KSU 晚加载等待 + 实时日志显示（run_root.command / run_root.bat 共用）。
# 成功判定（双信号，与入口脚本 step3 的 /proc/modules 口径统一）：
#   A. su 0 id 实测 —— 最可靠，但要等链上 ksud 阶段注入 allowlist，晚于
#      模块出现；
#   B. kernelsu 在 /proc/modules 且 sys.boot_completed=1 —— 早期兜底口径
#      （模块加载于已完成的启动 = 链已落地；垂死 boot 里 adb/模块撑不过
#      整个窗口，末次复检可排除假成功）。
# 窗口 300s：模块加载到 su 可用实测可超 180s（mac 端曾 180s 窗口内 su
# 一直未就绪，成功却显示失败）。su 未就绪时以 B 口径放行，后续
# install_managers / activate_zygisk 会继续轮询 su。
# 退出码: 0 = KSU 已加载, 1 = 超时未加载。

D=/data/local/tmp/ksu_fne
T15=""; T30=""
command -v timeout >/dev/null 2>&1 && { T15="timeout 15"; T30="timeout 30"; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

LAST_LINES=0
LAST_STATUS=""

have_su() { $T15 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; }
mod_loaded() { $T15 adb shell "grep -q kernelsu /proc/modules" 2>/dev/null; }
boot_ok() { [ "$($T15 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

echo "等待 KSU 晚加载链完成（最长 300 秒，实时显示链日志）..."

MODULE_SEEN=0
i=0
while [ $i -lt 60 ]; do
    # A 口径: su 实测可用 -> 立即成功
    if have_su; then exit 0; fi

    if mod_loaded && boot_ok && [ "$MODULE_SEEN" = "0" ]; then
        MODULE_SEEN=1
        echo "  [进展] kernelsu 模块已加载, 等待 su 可用(ksud 阶段注入 allowlist)..."
    fi

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
    echo "  ... 第 $((i * 5))/300 秒，KSU 尚未加载"
    sleep 5
done

# su 全窗口未就绪: B 口径末次复检放行(与入口 step3 一致)
if mod_loaded && boot_ok; then
    echo "  KSU 模块已加载（su 未就绪，后续脚本继续轮询确认）"
    exit 0
fi

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
