#!/bin/bash
# GhostLock-HonorFne 一键临时 root（macOS）
# 双击运行；若被 Gatekeeper 拦截，右键 -> 打开，或终端执行 bash run_root.command
cd "$(dirname "$0")" || exit 1

echo "=================================================="
echo "   GhostLock-HonorFne  一键临时 root（macOS）"
echo "   目标设备: 荣耀70 FNE-AN00（Bootloader 锁定）"
echo "=================================================="
echo

exit_push() {
    echo "[错误] 文件推送失败。请检查连接与 USB 调试授权后重试。"
    read -p "按回车键退出..."
    exit 1
}

# netfix_packet 模块安装（KSU 就绪后调用）。
# /data/adb 为 root 目录，adb 直推不行：先经 /data/local/tmp 中转，su 0 合并同步装入。
# 无条件同步（cp -rf 合并，保留运行时 netfix.log）：保证模块修复/更新始终生效。
# 规则在 ksud late-load 时读取注入，故更新自下一次提权/重启起自动激活。
ensure_module() {
    "$ADB" shell "su 0 sh -c 'mkdir -p /data/adb/modules/netfix_packet && cp -rf /data/local/tmp/netfix_packet/. /data/adb/modules/netfix_packet/'" >/dev/null 2>&1
    if "$ADB" shell "su 0 sh -c '[ -f /data/adb/modules/netfix_packet/sepolicy.rule ]'" >/dev/null 2>&1; then
        echo "      netfix_packet 模块已同步（网络/RRO/状态栏/导航栏/AOD 修复）"
    else
        echo "      [警告] netfix_packet 模块同步失败"
    fi
}

# ---------- 1/5 依赖检查 ----------
echo "[1/5] 检查依赖..."
ADB="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB" ]; then
    for p in "$HOME/Library/Android/sdk/platform-tools/adb" /usr/local/bin/adb /opt/homebrew/bin/adb; do
        if [ -x "$p" ]; then ADB="$p"; break; fi
    done
fi
if [ -z "$ADB" ]; then
    echo "[错误] 未找到 adb。"
    echo "       安装：brew install --cask android-platform-tools"
    echo "       或将 adb 所在目录加入 PATH 后重试。"
    read -p "按回车键退出..."
    exit 1
fi
export PATH="$(dirname "$ADB"):$PATH"   # run_forever.sh 内部裸调 adb
echo "      OK: adb 就绪（$ADB）"
echo

# ---------- 2/5 连接设备 ----------
echo "[2/5] 连接设备"
echo "  [1] USB 数据线"
echo "  [2] WiFi（手机与电脑同一局域网，端口 5555）"
read -p "请选择 1 或 2: " choice
case "$choice" in
    2)
        read -p "请输入手机 IP 地址: " IP
        if [ -z "$IP" ]; then
            echo "[错误] 未输入 IP 地址。"
            read -p "按回车键退出..."
            exit 1
        fi
        echo "      正在连接 $IP:5555 ..."
        "$ADB" connect "$IP:5555"
        ;;
esac
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "      等待设备接入（请插好 USB 并允许 USB 调试授权）..."
    "$ADB" wait-for-device
fi
echo "      已连接:"
"$ADB" devices
echo

# ---------- 3/5 root 状态检查 ----------
echo "[3/5] 检查 KernelSU 状态..."
if "$ADB" shell "grep -q kernelsu /proc/modules" >/dev/null 2>&1; then
    echo "      KernelSU 已在运行，无需再次提权。"
    ensure_module
    echo
    read -p "按回车键退出..."
    exit 0
fi
echo "      KernelSU 未加载，继续。"
echo

# ---------- 4/5 部署检查 ----------
echo "[4/5] 检查设备端部署..."
NEED_DEPLOY=0
"$ADB" shell "[ -x /data/local/tmp/e ]" >/dev/null 2>&1 || NEED_DEPLOY=1
"$ADB" shell "[ -f /data/local/tmp/ksu_fne/chain_hook.sh ]" >/dev/null 2>&1 || NEED_DEPLOY=1
if [ "$NEED_DEPLOY" = "0" ]; then
    echo "      已部署，跳过。"
else
    echo "      首次使用，推送文件（约 20MB，请稍候）..."
    "$ADB" shell "mkdir -p /data/local/tmp/ksu_fne" || exit_push
    "$ADB" push "root/exploit/ghostlock" /data/local/tmp/e || exit_push
    "$ADB" shell "chmod 755 /data/local/tmp/e"
    for f in chain_hook.sh kernelsu.ko ksud magiskpolicy ksu_rules load_ko kmsg_dumper fne_syms.txt; do
        "$ADB" push "root/ksu-late-load/$f" /data/local/tmp/ksu_fne/ || exit_push
    done
    "$ADB" shell "chmod 755 /data/local/tmp/ksu_fne/chain_hook.sh /data/local/tmp/ksu_fne/load_ko /data/local/tmp/ksu_fne/kmsg_dumper"
    echo "      部署完成。"
fi

# netfix_packet 模块体积小、迭代快：不做存在校验，每次都重新传输，
# 确保模块修复（sepolicy 规则 / LSPosed tmpfs 补丁）总能同步到设备。
echo "      同步 netfix_packet 模块..."
"$ADB" shell "rm -rf /data/local/tmp/netfix_packet" || exit_push
"$ADB" push "root/ksu-modules/netfix_packet" /data/local/tmp/ || exit_push
echo

# ---------- 5/5 运行 ----------
echo "[5/5] 开始 per-boot 提权（W1 - W2 - KSU 晚加载）"
echo "      * 全自动运行，含多次设备重启，可能耗时较长"
echo "      * 请勿关闭本窗口；勿同时运行其他 grind"
echo "      * 成功后脚本自动停止"
echo
bash "root/host-scripts/run_forever.sh"
echo

# root 落地后 detached chain_hook 还需数十秒完成 KSU 晚加载，
# 立即检查 /proc/modules 会误报失败——轮询等待（最长 180 秒）。
echo "等待 KSU 晚加载链完成（最长 180 秒）..."
KSU_UP=0
for _ in $(seq 1 36); do
    if "$ADB" shell "grep -q kernelsu /proc/modules" >/dev/null 2>&1; then KSU_UP=1; break; fi
    sleep 5
done
ensure_module
if [ "$KSU_UP" = "1" ]; then
    echo "=================================================="
    echo "   [成功] KernelSU 已加载，临时 root 就绪！"
    echo "=================================================="
else
    echo "=================================================="
    echo "   [未成功] 本次 grind 未落地，可重新运行再试。"
    echo "=================================================="
fi
echo
read -p "按回车键退出..."
