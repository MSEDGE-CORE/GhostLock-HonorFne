#!/bin/bash
# KSU 成功后自动安装管理器（run_root.command / run_root.bat 共用）：
#   1. KernelSU 管理器 APK（已装则跳过）
#   2. ZygiskNext 模块（KSU 内核态无内置 Zygisk，LSPosed 依赖其 API）
#   3. LSPosed(zygisk) 模块（管理器内嵌，激活后经通知栏快捷方式进入）
# 模块解压在主机完成（读取 module.prop 取 id），推入 /data/adb/modules/<id>；
# 无条件覆盖同步（与 netfix_packet 同策略，保证 deploy/ 更新始终下发）。
cd "$(dirname "$0")/../.." || exit 1
T30=""; T180=""
command -v timeout >/dev/null 2>&1 && { T30="timeout 30"; T180="timeout 180"; }
DEPLOY=deploy
MODDIR=/data/adb/modules

# --- 1. KernelSU 管理器 APK ---
if $T30 adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -q me.weishu.kernelsu; then
    echo "      KernelSU 管理器已安装，跳过"
else
    echo "      安装 KernelSU 管理器 ..."
    if $T180 adb install -r "$DEPLOY/KernelSU-manager.apk" >/dev/null 2>&1; then
        echo "      KernelSU 管理器安装成功"
    else
        echo "      [警告] KernelSU 管理器安装失败"
    fi
fi

# --- 2. KSU 模块 ---
# 关键: adb 是 shell 用户(uid 2000), 直接 push /data/adb 必然 Permission
# denied (Win 用户实测失败根因)。正确路径: zip 整包推 /data/local/tmp
# 中转, 设备端 root 解压入 modules/ —— 单次 push 更快, 且不依赖主机
# 端 unzip(Git Bash 无 unzip 也能工作)。
install_mod() { # $1=zip
    local zip=$1 zid id stage
    zid=$(basename "$zip" .zip)
    stage=/data/local/tmp/.gl_mod_$zid.zip
    if ! $T180 adb push "$zip" $stage >/dev/null 2>&1; then
        echo "      [警告] $zid 中转推送失败"
        return 1
    fi
    # module.prop 取 id (设备端 unzip -p, 免主机解压)
    id=$($T30 adb shell "su 0 unzip -p $stage module.prop 2>/dev/null" 2>/dev/null \
         | sed -n 's/^id=//p' | head -n1 | tr -d '\r')
    [ -n "$id" ] || id=$zid
    $T30 adb shell "su 0 mkdir -p $MODDIR/$id" >/dev/null 2>&1
    if ! $T180 adb shell "su 0 unzip -o -q $stage -d $MODDIR/$id" >/dev/null 2>&1; then
        echo "      [警告] 模块 $id 设备端解压失败"
        $T30 adb shell "rm -f $stage" >/dev/null 2>&1
        return 1
    fi
    # toybox unzip 不恢复 zip 权限位, 二进制/脚本缺执行位会
    # "zygiskd: inaccessible or not found"(LSA 实测), chmod -R 755 补齐
    $T30 adb shell "su 0 chmod -R 755 $MODDIR/$id" >/dev/null 2>&1
    # 清 update/disable/remove 标记: 无条件覆盖策略, 保证 deploy/ 版本始终生效
    $T30 adb shell "su 0 sh -c 'rm -f $MODDIR/$id/update $MODDIR/$id/disable $MODDIR/$id/remove; rm -f $stage'" >/dev/null 2>&1
    echo "      模块 $id 已同步（含执行位修复）"
}

echo "      同步 KSU 模块（ZygiskNext / LSPosed）..."
install_mod "$DEPLOY/ZygiskNext.zip"
install_mod "$DEPLOY/LSPosed-zygisk.zip"
echo "      提示: 模块随下次 KSU 加载生效；LSPosed 管理器入口为模块"
echo "            激活后通知栏快捷方式（无桌面图标）。"
