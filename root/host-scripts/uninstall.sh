#!/bin/bash
# 撤销部署（run_root.command / run_root.bat 选"撤销"时调用）：
#   移除设备端 /data/local/tmp 下的传输文件与模块中转：
#     e / a（exploit）、ksu_fne（链部署+运行日志）、netfix_packet（模块中转）、
#     .gl_mod_*.zip（ksud 安装 stage 包）
#   不卸载 KernelSU 管理器与任何已装 app。
# 仅允许在"无 root"状态执行：su 仅在 KSU 模块加载后存在；root 激活期间
# 删除部署文件会破坏运行中的链与已挂载模块。临时 root 为 per-boot 性质，
# 重启设备即清除，届时再撤销即为干净状态。
cd "$(dirname "$0")/../.." || exit 1
T30=""
command -v timeout >/dev/null 2>&1 && T30="timeout 30"

# adb 定位兜底（独立运行时也可用；入口脚本已 export PATH 则直接命中）
if ! command -v adb >/dev/null 2>&1; then
    for p in "$HOME/Library/Android/sdk/platform-tools/adb" /usr/local/bin/adb /opt/homebrew/bin/adb; do
        if [ -x "$p" ]; then export PATH="$(dirname "$p"):$PATH"; break; fi
    done
fi
if ! command -v adb >/dev/null 2>&1; then
    echo "[错误] 未找到 adb。"
    exit 1
fi

echo "      撤销部署：清理设备端传输文件与模块中转"

# 设备在线（与一键脚本同一等待体验）
if ! $T30 adb get-state >/dev/null 2>&1; then
    echo "      等待设备接入（请插好 USB 并允许 USB 调试授权）..."
    adb wait-for-device
fi

# root 状态检测：处于 root 状态则中止。
# 最多 2 次, 间隔 5s, 任一次命中即判有 root —— 抗 adb 单次瞬断(空响应)
# 的假阴性: 瞬断会误判"无 root"并删掉运行中的链文件与已挂载模块。
# 与 run_root.command / run_root.bat / run_w2.sh 同规则。
ROOT_SEEN=0
for i in 1 2; do
    if $T30 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; then
        ROOT_SEEN=1
        break
    fi
    [ "$i" -lt 2 ] && sleep 5
done
if [ "$ROOT_SEEN" = "1" ]; then
    echo "      [中止] 设备当前处于 root 状态，不能撤销。"
    echo "       临时 root 为 per-boot 性质：请先重启设备，待 root 清除后再运行撤销。"
    exit 1
fi
echo "      设备未 root，开始清理..."

# tmp 传输文件与模块中转（均由 adb shell 用户创建，无需 root 即可删除）
$T30 adb shell "rm -rf /data/local/tmp/e /data/local/tmp/a /data/local/tmp/ksu_fne /data/local/tmp/netfix_packet"
$T30 adb shell "rm -f /data/local/tmp/.gl_mod_*.zip"

# 清理校验
left=$($T30 adb shell "ls -d /data/local/tmp/e /data/local/tmp/a /data/local/tmp/ksu_fne /data/local/tmp/netfix_packet /data/local/tmp/.gl_mod_*.zip" 2>/dev/null | tr -d '\r')
if [ -n "$left" ]; then
    echo "      [警告] 以下路径未能删除（可能被占用，可重启设备后重试）："
    echo "$left" | sed 's/^/        /'
    exit 1
fi
echo "      已清理: exploit(e/a) / 链部署(ksu_fne) / netfix 中转 / 模块 stage zip"

# /data/adb 惰性残留：无内核模块时不会被加载；无 root 不可删也无需删
echo "      说明: /data/adb 模块残留在无 root 时为惰性文件（不加载、不生效），"
echo "            如需彻底清除可在 root 状态下 ksud module uninstall，或恢复出厂。"
echo "      未动: KernelSU 管理器与已装 app。"
