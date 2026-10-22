#!/bin/bash
# GhostLock-HonorFne 一键临时 root（macOS）
# 双击运行；若被 Gatekeeper 拦截，右键 -> 打开，或终端执行 bash run_root.command
cd "$(dirname "$0")" || exit 1

echo "=================================================="
echo "   GhostLock-HonorFne  一键临时 root（macOS）"
echo "   目标设备: 荣耀70 FNE-AN00（Bootloader 锁定）"
echo "=================================================="
echo

# ---------- 模式选择：部署 / 撤销 ----------
echo "请选择运行模式:"
echo "  [1] 部署 / 一键临时 root（默认）"
echo "  [2] 撤销部署（清理设备端传输文件与模块中转；不卸载管理器/app）"
MODE=1
read -p "输入 1 或 2 后回车: " MODE
case "$MODE" in
    2)
        ADB="$(command -v adb 2>/dev/null || true)"
        if [ -z "$ADB" ]; then
            for p in "$HOME/Library/Android/sdk/platform-tools/adb" /usr/local/bin/adb /opt/homebrew/bin/adb; do
                if [ -x "$p" ]; then ADB="$p"; break; fi
            done
        fi
        if [ -z "$ADB" ]; then
            echo "[错误] 未找到 adb。"
            read -p "按回车键退出..."
            exit 1
        fi
        export PATH="$(dirname "$ADB"):$PATH"
        bash "root/host-scripts/uninstall.sh"
        echo
        read -p "按回车键退出..."
        exit 0
        ;;
esac
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
echo "[2/5] 连接设备（USB）"
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "      等待设备接入（请插好 USB 并允许 USB 调试授权）..."
    "$ADB" wait-for-device
fi
echo "      已连接:"
"$ADB" devices
echo

# ---------- 3/5 root 状态检查 ----------
echo "[3/5] 检查 KernelSU 状态..."
# 无线 adb 单次 shell 瞬断(空响应)会把"已加载"误判为"未加载"而整跑
# exploit——同 boot 重复 W2 的 slab_drain fork 风暴会触发系统保护性
# 重启(295 用户 18:14 实测)。重试 3 次抗瞬断。
KSU_LOADED=0
for i in 1 2 3; do
    if "$ADB" shell "grep -q kernelsu /proc/modules" >/dev/null 2>&1; then
        KSU_LOADED=1
        break
    fi
    [ "$i" -lt 3 ] && { echo "      检测无响应，重试($i/3)..."; sleep 5; }
done
if [ "$KSU_LOADED" = "1" ]; then
    echo "      KernelSU 已在运行，无需再次提权。"
    ensure_module
    # 上次运行可能超时误判"未落地"(模块已加载但 su 未就绪): 补齐安装/激活,
    # 重跑即修复
    echo "      --- 补齐 KSU / LSP 管理器与 Zygisk 激活 ---"
    bash "root/host-scripts/install_managers.sh"
    bash "root/host-scripts/activate_zygisk.sh"
    echo
    read -p "按回车键退出..."
    exit 0
fi
echo "      KernelSU 未加载，继续。"
echo

# ---------- 4/5 部署（无条件覆盖） ----------
# 覆盖而非存在即跳过：chain_hook.sh / ko / syms 迭代频繁，存在校验会
# 让已部署设备拿不到新版（LSA merge 修复就卡在旧 chain_hook 上）。
# adb push 本身即覆盖写。
if [ ! -f "root/exploit/ghostlock" ]; then
    echo "[错误] 未找到 root/exploit/ghostlock（exploit 二进制）。"
    echo "       本仓库应自带预编译产物；若你通过 git clone 获取且缺失，请先更新到最新版本；"
    echo "       或参考 README \"重新构建\" 一节自备 Android NDK r27c 后执行:"
    echo "         cd root/exploit && ANDROID_NDK_HOME=<NDK路径> make"
    exit 1
fi
echo "[4/5] 部署设备端文件（约 20MB，覆盖同步，请稍候）..."
"$ADB" shell "mkdir -p /data/local/tmp/ksu_fne" || exit_push
"$ADB" push "root/exploit/ghostlock" /data/local/tmp/e || exit_push
"$ADB" shell "chmod 755 /data/local/tmp/e"
for f in chain_hook.sh kernelsu.ko ksud magiskpolicy ksu_rules load_ko kmsg_dumper fne_syms.txt; do
    "$ADB" push "root/ksu-late-load/$f" /data/local/tmp/ksu_fne/ || exit_push
done
"$ADB" shell "chmod 755 /data/local/tmp/ksu_fne/chain_hook.sh /data/local/tmp/ksu_fne/magiskpolicy /data/local/tmp/ksu_fne/ksud /data/local/tmp/ksu_fne/load_ko /data/local/tmp/ksu_fne/kmsg_dumper"
echo "      部署完成。"

# netfix_packet 模块体积小、迭代快：每次都重新传输，
# 确保模块修复（sepolicy 规则 / LSPosed tmpfs 补丁）总能同步到设备。
echo "      同步 netfix_packet 模块..."
"$ADB" shell "rm -rf /data/local/tmp/netfix_packet" || exit_push
"$ADB" push "root/ksu-modules/netfix_packet" /data/local/tmp/ || exit_push
echo

# ---------- 可选板块：安装桌面修改（Launcher/SystemUI hooks） ----------
# KSU/LSPosed 成功后调用。默认跳过，选 y 安装：
#   HnLauncherPort.apk (LSPosed 模块: 桌面布局 + 控制中心动效优化)
install_launcher_mods() {
    echo
    read -p "是否安装桌面修改（HnLauncherPort）? [y/N]: " ans
    case "$ans" in
        y|Y)
            if [ ! -f "hooks/launcher/out/HnLauncherPort.apk" ]; then
                echo "      [错误] 未找到 hooks/launcher/out/HnLauncherPort.apk"
                return 1
            fi
            echo "      安装 HnLauncherPort.apk ..."
            if ! "$ADB" install -r "hooks/launcher/out/HnLauncherPort.apk" >/dev/null 2>&1; then
                echo "      [错误] APK 安装失败"
                return 1
            fi
            echo "      APK 安装成功。"
            echo "      提示: 首次需在 LSPosed 管理器中启用模块并勾选"
            echo "            作用域（荣耀桌面 + 系统界面），重启对应应用后生效。"
            ;;
        *)
            echo "      已跳过桌面修改安装。"
            ;;
    esac
}

# ---------- 5/5 运行 ----------
echo "[5/5] 开始 per-boot 提权（W1 - W2 - KSU 晚加载）"
echo "      * 全自动运行，含多次设备重启，可能耗时较长"
echo "      * 请勿关闭本窗口；勿同时运行其他 grind"
echo "      * 成功后脚本自动停止"
echo
bash "root/host-scripts/run_forever.sh"
echo

# root 落地后 detached chain_hook 还需数十秒完成 KSU 晚加载，
# 立即检查 /proc/modules 会误报失败——轮询等待（最长 180 秒），
# 期间实时增量显示设备端 chain_status / chain.log（wait_ksu.sh）。
KSU_UP=0
if bash "root/host-scripts/wait_ksu.sh"; then KSU_UP=1; fi
ensure_module
if [ "$KSU_UP" = "1" ]; then
    echo "=================================================="
    echo "   [成功] KernelSU 已加载，临时 root 就绪！"
    echo "=================================================="
    # 自动安装管理器: KernelSU 管理器 + ZygiskNext + LSPosed(内嵌管理器)
    echo "      --- 自动安装 KSU / LSP 管理器 ---"
    bash "root/host-scripts/install_managers.sh"
    # Zygisk/LSPosed 注入激活（链后时机，12:40 已验证配方；链内跑会破坏模块）
    bash "root/host-scripts/activate_zygisk.sh"
    install_launcher_mods
else
    echo "=================================================="
    echo "   [未成功] 本次 grind 未落地，可重新运行再试。"
    echo "=================================================="
fi
echo
read -p "按回车键退出..."
