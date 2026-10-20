#!/bin/bash
# KSU 成功后自动安装管理器（run_root.command / run_root.bat 共用）：
#   1. KernelSU 管理器 APK（已装则跳过）
#   2. ZygiskNext 模块（KSU 内核态无内置 Zygisk，LSPosed 依赖其 API）
#   3. LSPosed(zygisk) 模块（管理器内嵌，激活后经通知栏快捷方式进入）
# 模块安装走 `ksud module install` 官方流程（执行 customize.sh：二进制
# 补丁/权限/sepolicy 校验），绝不裸 unzip 解压——裸解压缺安装期处理，
# 且 zygiskd 状态回写 module.prop 会与哈希校验形成 "Module files
# corrupted" 死循环（FNE/LSA 实测）。装进 modules_update，下次 KSU
# 加载（下轮 grind）自动挂载生效。
cd "$(dirname "$0")/../.." || exit 1
T30=""; T180=""
command -v timeout >/dev/null 2>&1 && { T30="timeout 30"; T180="timeout 180"; }
DEPLOY=deploy

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

# --- 2. KSU 模块（ksud 官方安装） ---
install_mod() { # $1=zip
    local zip=$1 zid stage
    zid=$(basename "$zip" .zip)
    stage=/data/local/tmp/.gl_mod_$zid.zip
    if ! $T180 adb push "$zip" $stage >/dev/null 2>&1; then
        echo "      [警告] $zid 中转推送失败"
        return 1
    fi
    # ksud module install: 走完整 customize.sh(校验/补丁/权限), 装入
    # modules_update, 下次模块挂载(下轮 grind 成功后 ksud 启动)生效
    if $T180 adb shell "su 0 /data/adb/ksud module install $stage" >/dev/null 2>&1; then
        echo "      模块 $zid 官方流程安装成功（下次 KSU 加载生效）"
    else
        echo "      [警告] 模块 $zid 安装失败"
    fi
    $T30 adb shell "su 0 rm -f $stage" >/dev/null 2>&1
}

echo "      安装 KSU 模块（ZygiskNext / LSPosed，官方安装流程）..."
install_mod "$DEPLOY/ZygiskNext.zip"
install_mod "$DEPLOY/LSPosed-zygisk.zip"
echo "      提示: 模块随下次 KSU 加载（下轮 grind）自动挂载生效；"
echo "            LSPosed 管理器入口为模块激活后通知栏快捷方式（无桌面图标）。"
