#!/bin/bash
# KSU 成功后自动安装管理器（run_root.command / run_root.bat 共用）：
#   1. KernelSU 管理器 APK（已装则跳过）
#   2. ZygiskNext 模块（KSU 内核态无内置 Zygisk，LSPosed 依赖其 API）
#   3. LSPosed(zygisk) 模块（管理器内嵌，激活后经通知栏快捷方式进入）
# 模块解压在主机完成（读取 module.prop 取 id），推入 /data/adb/modules/<id>；
# 无条件覆盖同步（与 netfix_packet 同策略，保证 deploy/ 更新始终下发）。
cd "$(dirname "$0")/../.." || exit 1
DEPLOY=deploy
MODDIR=/data/adb/modules

# --- 1. KernelSU 管理器 APK ---
if adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -q me.weishu.kernelsu; then
    echo "      KernelSU 管理器已安装，跳过"
else
    echo "      安装 KernelSU 管理器 ..."
    if adb install -r "$DEPLOY/KernelSU-manager.apk" >/dev/null 2>&1; then
        echo "      KernelSU 管理器安装成功"
    else
        echo "      [警告] KernelSU 管理器安装失败"
    fi
fi

# --- 2. KSU 模块 ---
install_mod() { # $1=zip
    local zip=$1 tmp id
    tmp=$(mktemp -d) || return 1
    if ! unzip -q "$zip" -d "$tmp" 2>/dev/null; then
        echo "      [警告] $zip 解析失败"
        rm -rf "$tmp"; return 1
    fi
    id=$(sed -n 's/^id=//p' "$tmp/module.prop" 2>/dev/null | head -n1 | tr -d '\r')
    [ -n "$id" ] || id=$(basename "$zip" .zip)
    adb shell "su 0 mkdir -p $MODDIR/$id" >/dev/null 2>&1
    if ! adb push "$tmp/." "$MODDIR/$id/" >/dev/null 2>&1; then
        echo "      [警告] 模块 $id 推送失败"
        rm -rf "$tmp"; return 1
    fi
    adb shell "su 0 sh -c 'rm -f $MODDIR/$id/update'" >/dev/null 2>&1
    echo "      模块 $id 已同步"
    rm -rf "$tmp"
}

echo "      同步 KSU 模块（ZygiskNext / LSPosed）..."
install_mod "$DEPLOY/ZygiskNext.zip"
install_mod "$DEPLOY/LSPosed-zygisk.zip"
echo "      提示: 模块随下次 KSU 加载生效；LSPosed 管理器入口为模块"
echo "            激活后通知栏快捷方式（无桌面图标）。"
