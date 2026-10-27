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
# MSYS2/Git Bash 路径转换防护: 本脚本在 Git Bash 下运行时, 裸 /data push
# 目标会被 MSYS 层转成 "C:\Program Files\Git\data\..." 传给原生 adb
# (Win T480 实测: "remote secure_mkdirs failed", 两个模块中转推送全挂;
# adb shell "..." 引号整串不受影响, 故仅此一处)。排除 /data 前缀的
# 转换; mac/Linux 无此机制, 不受影响。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) export MSYS2_ARG_CONV_EXCL="/data" ;;
esac
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
# 前置守卫: 设备若正在重启(ksud 报 "Error: Android is Booting!")或 root
# 已随重启丢失, 等启动完成再复检 root —— 都不行则明确报错而非静默失败。
n=0
bc=$($T30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
while [ "$bc" != "1" ] && [ $n -lt 36 ]; do
    echo "      等待系统完成启动 (boot_completed=$bc, $((n*5))s)..."
    sleep 5; n=$((n+1))
    bc=$($T30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
done
# root 复检改为轮询: 模块可能已加载而 su(allowlist 注入)尚未就绪,
# 单次检查会误判 root 丢失 —— 最长等 120s
n=0
while ! $T30 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; do
    if [ $n -ge 24 ]; then
        echo "      [警告] root 不可用(su 未就绪/设备重启后 KSU 已丢), 模块安装跳过"
        echo "      提示: 重新运行一键脚本 grind 即可恢复"
        exit 1
    fi
    echo "      等待 su 就绪 ($((n * 5 + 5))s)..."
    sleep 5; n=$((n + 1))
done

# ksud 路径解析: /data/adb/ksud 只在管理器首次打开时由 APK 拷入, 全自动
# 流程管理器从未启动过就不存在(LSA "模块安装失败" 首要嫌疑) —— 兜底用
# chain 部署目录里的同一份 ksud。
KSUD=$($T30 adb shell "su 0 sh -c 'for k in /data/adb/ksud /data/local/tmp/ksu_fne/ksud /data/local/tmp/*/ksud; do [ -x \"\$k\" ] && echo \"\$k\" && break; done'" 2>/dev/null | tr -d '\r' | head -1)
if [ -z "$KSUD" ]; then
    echo "      [警告] 设备上未找到 ksud(管理器未运行过且部署目录缺失), 模块安装跳过"
    exit 1
fi
echo "      使用 ksud: $KSUD"

install_mod() { # $1=zip
    local zip=$1 zid stage out rc m
    zid=$(basename "$zip" .zip)
    case $zid in
      ZygiskNext) m=zygisksu ;;
      *) m=zygisk_lsposed ;;
    esac
    stage=/data/local/tmp/.gl_mod_$zid.zip
    if ! $T180 adb push "$zip" $stage >/dev/null 2>&1; then
        echo "      [警告] $zid 中转推送失败"
        return 1
    fi
    # 安装前快照 modules/ 与 modules_update/ 现树: ksud 安装失败会把 MU
    # 清空、甚至把裸 zip 树直接冲进 modules(grind_reboot4 实证: ZN
    # customize.sh 的 check_sepolicy 即 `ksud sepolicy check`, 逐 boot
    # 抖动), 失败时用快照还原, 绝不让坏树顶替好树。
    $T30 adb shell "su 0 sh -c 'rm -rf /data/adb/.gl_modbak/\$m*; mkdir -p /data/adb/.gl_modbak; [ -d /data/adb/modules/\$m ] && cp -a /data/adb/modules/\$m /data/adb/.gl_modbak/\$m; [ -d /data/adb/modules_update/\$m ] && cp -a /data/adb/modules_update/\$m /data/adb/.gl_modbak/\$m.mu; true'" >/dev/null 2>&1
    out=$($T180 adb shell "su 0 $KSUD module install $stage" 2>&1 | tr -d '\r')
    rc=$?
    # adb shell 不一定可靠回传设备退出码, 以 "successfully" 文本双保险
    if [ $rc -ne 0 ] || ! echo "$out" | grep -q successfully; then
        echo "      [警告] 模块 $zid 安装失败 (rc=$rc), 隔 10s 重试一次:"
        echo "$out" | tail -n 6 | sed 's/^/        /'
        sleep 10
        out=$($T180 adb shell "su 0 $KSUD module install $stage" 2>&1 | tr -d '\r')
        rc=$?
    fi
    if [ $rc -ne 0 ] || ! echo "$out" | grep -q successfully; then
        echo "      [警告] 模块 $zid 重试仍失败, 还原安装前快照:"
        echo "$out" | tail -n 6 | sed 's/^/        /'
        $T30 adb shell "su 0 sh -c 'rm -rf /data/adb/modules/\$m /data/adb/modules_update/\$m; [ -d /data/adb/.gl_modbak/\$m ] && cp -a /data/adb/.gl_modbak/\$m /data/adb/modules/\$m; [ -d /data/adb/.gl_modbak/\$m.mu ] && cp -a /data/adb/.gl_modbak/\$m.mu /data/adb/modules_update/\$m; true'" >/dev/null 2>&1
        $T30 adb shell "rm -f $stage" >/dev/null 2>&1
        return 1
    fi
    echo "      模块 $zid 官方流程安装成功（下次 KSU 加载生效）"
    $T30 adb shell "su 0 rm -f $stage" >/dev/null 2>&1
}

echo "      安装 KSU 模块（ZygiskNext / LSPosed，官方安装流程）..."
install_mod "$DEPLOY/ZygiskNext.zip"
install_mod "$DEPLOY/LSPosed-zygisk.zip"

# --- 2.5 netfix_packet 模块同步（兜底） ---
# 一键脚本(run_root.*)有 ensure_module, 但手动 grind/单独跑本脚本时
# netfix 可能尚未安装(全新设备/清理后), 下面的补丁重打会因模块缺失而
# 跳过。无条件合并同步(与 run_root.command ensure_module 同规则)。
echo "      同步 netfix_packet 模块..."
$T30 adb shell "rm -rf /data/local/tmp/netfix_packet" >/dev/null 2>&1
if $T180 adb push root/ksu-modules/netfix_packet /data/local/tmp/ >/dev/null 2>&1 \
   && $T30 adb shell "su 0 sh -c 'mkdir -p /data/adb/modules/netfix_packet && cp -rf /data/local/tmp/netfix_packet/. /data/adb/modules/netfix_packet/'" >/dev/null 2>&1; then
    echo "      netfix_packet 模块已同步（网络/RRO/状态栏/导航栏/AOD 修复）"
else
    echo "      [警告] netfix_packet 模块同步失败"
fi

# --- 3. 安装后重打 netfix RRO 补丁 ---
# 模块安装会把 zygisk_lsposed/daemon 重置为未打补丁版本(内含
# `mount tmpfs /data/resource-cache`, 会把全部 RRO idmap 遮成
# STATE_NO_IDMAP → 状态栏/导航栏布局回退 base 资源)。
# netfix_packet 的补丁只在 ksud 开机阶段运行(早于本脚本), 故每次
# 安装后必须原地重打补丁 + 清理已叠层 + 拉起 idmap2d, 否则本轮
# grind 的 RRO 必坏(grind_reboot7 实测 57 层 tmpfs 叠挂)。
NF=/data/adb/modules/netfix_packet
if $T30 adb shell "su 0 test -f $NF/post-fs-data.sh" >/dev/null 2>&1; then
    $T30 adb shell "su 0 sh -c 'sh $NF/post-fs-data.sh; sh $NF/service.sh'" >/dev/null 2>&1 \
        && echo "      已重打 netfix 补丁（LSPosed daemon tmpfs / RRO idmap 修复）" \
        || echo "      [警告] netfix 补丁重打失败，状态栏布局可能异常"
else
    echo "      [提示] 未找到 $NF/post-fs-data.sh，跳过 netfix 补丁重打"
fi
echo "      提示: 模块随下次 KSU 加载（下轮 grind）自动挂载生效；"
echo "            LSPosed 管理器入口为模块激活后通知栏快捷方式（无桌面图标）。"
