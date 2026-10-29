#!/bin/bash
# activate_zygisk.sh —— KSU 就绪后激活 ZygiskNext/LSPosed 注入。
#
# 配方来源(FNE 12:40 实测全链路打通, 16:47 反证链内时机不可行):
#   1. magiskpolicy 放行 permissive kernel/zygote/system_server + 应用模块
#      sepolicy.rule —— zn-daemon(kernel:s0) ptrace 注入 zygote 在 enforce
#      下会被真拒(getenforce 的 Permissive 是内核伪装, AVC permissive=0)。
#   2. 合并 modules_update -> modules: 链上 ksud 阶段不合并 modules_update
#      (FNE 实测跨整个 boot 不合并), 挂载副本停留裸解压状态(bin/ 无
#      zygiskd, service.sh 报 "No such file or directory" 不注入)。
#   3. 杀旧 daemon, 清失败标记(.injecting/.abort_msg/disable/update) +
#      无条件还原 module.prop。daemon 失败时把状态写进 module.prop, 实测
#      两种格式: a) 29B 裸标记 "[❌ Last injection failed!]"(替换整个文件);
#      b) 完整 prop 但 description="[❌ Module files corrupted]"(00:18 实测)。
#      而 verify1 = 模块文件哈希自检(module.prop 对 module.prop.sha256,
#      FNE 00:30 实测 42 文件全对账仅 module.prop 不匹配), module.prop 一旦
#      被改写, 后续每次 daemon 启动必挂 "Module files corrupted" 死亡螺旋,
#      且 module.prop.orig 也可能被连带改坏 —— 因此必须用嵌入的 zip 原文
#      (211B, sha256 前 16 位 2f8cdf9a676ed7b8)同时还原两个文件, 不能只认
#      head -c1="[" 的旧格式。严禁移动/删除 /data/adb/zygisksu 状态目录:
#      daemon 启动时校验完整状态现场(bugreports/ 等), 状态目录被清后必报
#      "Module files corrupted" 并写 disable 自禁(FNE 23:38/23:42 实测,
#      恢复现场后 23:47 一次成功)。
#   4. verify1 真相(FNE 02:18 kprobe 实证): daemon 失败前只 open 过
#       /data/adb/zygisksu/.magic, 完全不碰模块文件 —— verify1 = .magic
#       内容校验, 与 module.prop 无关; 失败路径会用随机值"毒丸化".magic 并
#       再生新值。02:08/02:11 两次以 zygisksu.bak/.magic 通过 verify1。
#       10/29 反证+修正: zygisksu.bak 已不存在, 用 live 随机值(22B)起
#       daemon 照样通过 verify1 并完成注入 —— .magic 内容并非已知值才有效,
#       .bak 恢复降级为"有则恢复", 缺失只提示不判死。
#   5. RescueParty 才是所谓"荣耀看门狗"(02:42 dumpsys 实证): 每次开机
#      ~7min 后逐级升级(UNTRUSTED_DEFAULTS→CHANGES→TRUSTED→WARM_REBOOT,
#      每 12min 一级, ~42min 温重启整机), 23:46 甚至执行过 FACTORY_RESET
#      级救援(/data 幸存属侥幸)。root 后立即禁用, persist 属性永久止血。
#   6. kill zygote64 的历史教训与有条件恢复: 旧版 [5/5] 盲目两连 kill 诱发
#      整机硬重启(22:45/23:24)而移除; 10/29 实证根因是 kill 时 daemon 缺席
#      (init execve hook 未挂, 纯框架重启)。现仅在 daemon 确认存活(hook
#      已挂)时 kill 一次: 实测 ~10s 注入落位, 框架软重启 ~1min, 数据无损。
# 前置: KSU 已就绪(su 0 可用); zygisksu/zygisk_lsposed 模块已挂载。
# zygote pid 解析必须用 pidof: pgrep -x zygote64 不匹配(comm 为 main),
# pgrep -f 会误匹配 shell 自身命令行(FNE 实测三者输出互不相同)。
# daemon 起动后 prctl 改名 comm=zn-daemon(cmdline 仍 zygiskd64 daemon),
# pkill -f zn-daemon 匹配 cmdline 失效, 必须用 comm 匹配 pkill zn-daemon。
cd "$(dirname "$0")/../.." || exit 1
T15=""; T30=""
command -v timeout >/dev/null 2>&1 && { T15="timeout 15"; T30="timeout 30"; }
# MSYS2/Git Bash 路径转换防护(与 install_managers.sh 同规则): 裸 /data
# push 目标会被 MSYS 层转成 Windows 路径传给原生 adb; mac/Linux 不受影响。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) export MSYS2_ARG_CONV_EXCL="/data" ;;
esac
MOD=/data/adb/modules/zygisksu

# 设备端解析 magiskpolicy 路径(chain 部署目录)
MP=$($T15 adb shell "su 0 sh -c 'for p in /data/local/tmp/ksu_fne/magiskpolicy /data/local/tmp/*/magiskpolicy; do [ -x \"\$p\" ] && echo \"\$p\" && break; done'" 2>/dev/null | tr -d '\r' | head -1)

have_root() { $T15 adb shell "su 0 id" 2>/dev/null | grep -q "uid=0"; }
injected() {
    $T15 adb shell "su 0 sh -c 'for Z in \$(pidof zygote64); do grep -q zygisk_lsposed /proc/\$Z/maps 2>/dev/null && exit 0; done; exit 1'" 2>/dev/null
}
daemon_alive() {
    $T15 adb shell "su 0 sh -c 'pidof zn-daemon >/dev/null 2>&1'" 2>/dev/null
}
# zip 内 pristine module.prop (211B, sha256=2f8cdf9a...) 的 base64。
# daemon 两种失败改写格式(29B 裸标记 / description 嵌入)都会破坏
# verify1 哈希自检, module.prop.orig 也可能被连带改坏, 故无条件双还原。
PRISTINE_B64='aWQ9enlnaXNrc3UKbmFtZT1aeWdpc2sgTmV4dAp2ZXJzaW9uPTEuNS4yICg4NjQtYTRhNzJjZS1yZWxlYXNlKQp2ZXJzaW9uQ29kZT04NjQKYXV0aG9yPUxTUG9zZWQgRGV2ZWxvcGVycwpkZXNjcmlwdGlvbj1TdGFuZGFsb25lIGltcGxlbWVudGF0aW9uIG9mIFp5Z2lzay4KdXBkYXRlSnNvbj1odHRwczovL2xzcG9zZWQuemlwL3p5Z2lzay1uZXh0L3VwZGF0ZS5qc29uCg=='
# 杀旧 daemon + 双还原 module.prop + 清失败标记 + 恢复 .magic + 拉起注入
# 链(见头部注释 3/4)。daemon 失败会污染 module.prop/失败标记, 每次拉起
# 前必须全套还原; [3/4] 与 [4/4] 超时重拉共用本函数。
#
# 10/29 关键修正: daemon 的唯一拉起入口是 post-fs-data.sh 的
# `zygiskd daemon`(zygiskd 自 fork: 父进程 setup+hook init 后退出, 子进程
# prctl 改名 zn-daemon 常驻)。service.sh 的 `zygiskd service-stage` **不会
# 自启 daemon** —— daemon 缺席时它 RC=1 静默退出。当天实测: chain 的
# ksud post-fs-data 因彼时模块树损坏报 "./bin/zygiskd: not found"
# (stage_postfs.log 末行), daemon 从未启动, 老版 relaunch 只跑 service.sh,
# [4/4] 对着空系统干等 240s 超时 —— "zygote 部署不上"的根因。
start_daemon() {
    $T30 adb shell "su 0 sh -c 'sh $MOD/post-fs-data.sh'" >/dev/null 2>&1
    n=0
    while [ $n -lt 10 ]; do
        daemon_alive && return 0
        sleep 2; n=$((n + 1))
    done
    return 1
}
relaunch_daemon() {
    # 已注入且 daemon 存活 = 系统健康, 不重启 daemon(重启会在 init 的
    # execve/wait4 上叠加第二层 inline hook, 双重 patch 未验证过安全性)
    if injected && daemon_alive; then return 0; fi
    $T30 adb shell "su 0 sh -c 'pkill -9 zn-daemon 2>/dev/null; pkill -9 zygiskd64 2>/dev/null; pkill -9 lspd 2>/dev/null; sleep 1; echo $PRISTINE_B64 | base64 -d > $MOD/module.prop.orig; cp $MOD/module.prop.orig $MOD/module.prop; rm -f /data/adb/zygisksu/.abort_msg /data/adb/zygisksu/.injecting $MOD/disable $MOD/update; if [ -f /data/adb/zygisksu.bak/.magic ]; then cp /data/adb/zygisksu.bak/.magic /data/adb/zygisksu/.magic; chmod 666 /data/adb/zygisksu/.magic; fi'" >/dev/null 2>&1
    start_daemon || echo "      [警告] ZN daemon 启动失败(模块树可能损坏), verify/注入将无法进行"
    $T30 adb shell "su 0 sh -c 'sh $MOD/service.sh'" >/dev/null 2>&1
}

echo "      激活 Zygisk/LSPosed 注入..."
# su 可能晚于模块就绪(ksud 阶段注入 allowlist): 轮询等待, 最长 60s
n=0
while ! have_root; do
    if [ $n -ge 12 ]; then
        echo "      [警告] su 不可用, 跳过 Zygisk 激活"
        exit 1
    fi
    sleep 5; n=$((n + 1))
done
# RescueParty 止血(见头部注释 5): persist 属性跨重启永久生效。
# 属性名来自设备 services.jar 反查(FNE 02:56 strings 实证):
#   persist.sys.enable_rescue / persist.sys.disable_rescue(AOSP 命名,
#   无 _party 后缀); 另有 device_config configuration 命名空间开关
#   disable_rescue_party(_factory_reset), shell 无 root 即可设置,
#   已在部署流程更早处先行设置 —— 此处 root 下补 persist 属性双保险。
$T15 adb shell "su 0 sh -c 'setprop persist.sys.enable_rescue false; setprop persist.sys.disable_rescue true'" >/dev/null 2>&1
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

echo "      [1/4] 重建 modules 树为 modules_update 精确副本(清除 zip 冗余文件)..."
# 关键(rdt36/rdt37 实证): ZN daemon 的 verify1 是严格白名单校验,
# install_managers 裸 unzip 留下的 zip 冗余文件(错误 ABI 目录 bin/riscv64,
# bin/x86, lib/riscv64 等)会触发 "Module files corrupted" 注入中止。
# 故不能 cp -rf overlay 合并(旧文件残留), 必须整树重建。
# 但 ksud 安装失败会把 MU 清空、甚至留裸 zip 树(grind_reboot4 实证:
# check_sepolicy 即 ksud sepolicy check 逐 boot 抖动), 此时必须保留
# modules 原好树, 绝不拿无效 MU 覆盖。有效性判定:
#   zygisksu: module.prop + bin/zygiskd(customize 产物, 裸 zip 树无此链接)
#   zygisk_lsposed: module.prop + daemon.apk 且无 META-INF(裸 zip 有)
r=$($T30 adb shell "su 0 sh -c 'for m in zygisksu zygisk_lsposed; do mu=/data/adb/modules_update/\$m; ok=0; if [ -f \$mu/module.prop ]; then case \$m in zygisksu) [ -e \$mu/bin/zygiskd ] && ok=1 ;; zygisk_lsposed) [ -f \$mu/daemon.apk ] && [ ! -d \$mu/META-INF ] && ok=1 ;; esac; fi; if [ \$ok = 1 ]; then rm -rf /data/adb/modules/\$m; mkdir -p /data/adb/modules/\$m; cp -a \$mu/. /data/adb/modules/\$m/; else echo \"        [提示] modules_update/\$m 无效(安装失败或未装), 保留 modules 原树\"; fi; done'" 2>/dev/null | tr -d '\r')
[ -n "$r" ] && echo "$r"
if ! $T15 adb shell "su 0 test -e $MOD/bin/zygiskd" 2>/dev/null; then
    echo "      [警告] $MOD/bin/zygiskd 仍缺失(模块包不完整), 激活中止"
    exit 1
fi

echo "      [2/4] 注入 permissive 域 + 模块 sepolicy 规则..."
$T30 adb shell "su 0 $MP --live 'permissive kernel' 'permissive zygote' 'permissive system_server'" >/dev/null 2>&1
$T30 adb shell "su 0 sh -c 'for r in /data/adb/modules/*/sepolicy.rule; do [ -f \"\$r\" ] && $MP --apply \"\$r\" 2>/dev/null; done'" >/dev/null 2>&1

echo "      [3/4] 还原 module.prop + 清失败标记并重启注入链..."
relaunch_daemon
h=$($T15 adb shell "su 0 sha256sum $MOD/module.prop" 2>/dev/null | tr -d '\r' | cut -c1-16)
if [ "$h" != "2f8cdf9a676ed7b8" ]; then
    echo "      [警告] module.prop 还原后哈希异常($h), verify1 大概率失败"
fi
if ! $T15 adb shell "su 0 sh -c '[ -s /data/adb/zygisksu/.magic ]'" 2>/dev/null; then
    echo "      [警告] /data/adb/zygisksu/.magic 缺失或为空, daemon verify1 可能失败"
fi

echo "      [4/4] 等待注入落位(最长 120s + 重生接管 180s)..."
# ZN 1.5.2 的注入机制(10/29 logcat 实证): daemon 启动时 ptrace attach
# init 并 inline hook 其 execve/wait4, 之后由 init (重)spawn 的 zygote
# 才会被注入 —— **daemon 不会注入已在跑的 zygote**(zygote_states 恒 0)。
# 因此两条路径:
#   a) boot 路径: W1/W2 崩溃重启后 root 落在开机途中, chain/daemon 抢在
#      zygote spawn 前 hook init, zygote 出生即带模块, 等待自然落位;
#   b) 干净 root 路径: W2 在已完全开机的系统上一次成功(zygote 早已在跑),
#      等 120s 也不会落位 —— 必须在 daemon 确认存活(hook 已挂)前提下
#      kill zygote64 触发 init 重生, execve hook 接住, ~10s 注入落位
#      (框架软重启约 1min, RescueParty 已禁用, 数据无损)。
# 安全约束: 仅 daemon 存活时才 kill(无 hook 的 kill 是纯框架重启, 旧版
# [5/5] 兜底两连 kill 诱发硬重启的根因), 且全程只 kill 一次。
ok=0
for i in $(seq 1 24); do
    sleep 5
    if injected; then ok=1; break; fi
done

if [ $ok -ne 1 ] && daemon_alive; then
    echo "      [提示] zygote 早于 daemon 启动(干净 root 场景), kill zygote64 触发重生接管..."
    $T15 adb shell "su 0 sh -c 'kill -9 \$(pidof zygote64) 2>/dev/null'" >/dev/null 2>&1
    for i in $(seq 1 36); do
        sleep 5
        injected && { ok=1; break; }
    done
fi

if [ $ok -ne 1 ] && have_root; then
    # 兜底: daemon 可能已死(chain post-fs-data 失败/daemon 启动失败)。
    # 等 su 通路连续 2 次稳定后重拉 daemon(重走全套还原), 再给第二窗口。
    echo "      [4/4] 未落位, 等 su/KSU 通路稳定后重拉 daemon(最长 120s)..."
    n=0; stable=0
    while [ $n -lt 24 ]; do
        if have_root; then stable=$((stable+1)); else stable=0; fi
        [ $stable -ge 2 ] && break
        sleep 5; n=$((n+1))
    done
    if [ $stable -ge 2 ]; then
        echo "        通路已稳, 重拉 daemon 并等待第二窗口(最长 180s)..."
        relaunch_daemon
        for i in $(seq 1 36); do
            sleep 5
            injected && { ok=1; break; }
        done
        if [ $ok -ne 1 ] && daemon_alive; then
            echo "        [提示] 仍未见注入, kill zygote64 触发重生接管(仅此一次)..."
            $T15 adb shell "su 0 sh -c 'kill -9 \$(pidof zygote64) 2>/dev/null'" >/dev/null 2>&1
            for i in $(seq 1 36); do
                sleep 5
                injected && { ok=1; break; }
            done
        fi
    fi
fi

if [ $ok -eq 1 ]; then
    # lspd 启动/管理器打开时用 dex2oat 探针自检 sepolicy 规则是否加载,
    # 其启动链路可能冲掉 [2/4] 注入的模块规则——注入落位后补一遍,
    # 否则 LSPosed 管理器报 "SEPolicy 未被正确加载"(dex2oat AVC denied)。
    $T30 adb shell "su 0 sh -c 'for r in /data/adb/modules/*/sepolicy.rule; do [ -f \"\$r\" ] && $MP --apply \"\$r\" 2>/dev/null; done'" >/dev/null 2>&1
    echo "      Zygisk 注入成功: zygote 已加载 LSPosed zygisk 模块"
    echo "      (lspd 日志: /data/adb/lspd/log/ ; LSPosed 入口在通知栏)"

    # --- lspd 保活 + 作用域进程 rebridge ---
    # kill zygote64 重生接管路径下框架软重启会带走 lspd(10/29 实测: zygote
    # 重生后 lspd 死, 桥接无模块列表); 且 [3/4] relaunch 的 pkill lspd 在
    # boot 路径也会杀掉 chain 起的 lspd。lspd 缺席期间重生的作用域进程
    # 不会回补 hook, 须: 起回 lspd -> force-stop launcher/aod 让其带 bridge
    # 重生(10/29 实测 lspd 起回 + force-stop 后 hooks 立即恢复)。
    if ! $T15 adb shell "su 0 sh -c 'pgrep -f \"^lspd\$\" >/dev/null'" 2>/dev/null; then
        $T30 adb shell "su 0 sh -c 'sh /data/adb/modules/zygisk_lsposed/service.sh'" >/dev/null 2>&1
        n=0
        while [ $n -lt 12 ] && ! $T15 adb shell "su 0 sh -c 'pgrep -f \"^lspd\$\" >/dev/null'" 2>/dev/null; do
            sleep 5; n=$((n + 1))
        done
        $T30 adb shell "su 0 am force-stop com.hihonor.android.launcher; su 0 am force-stop com.hihonor.aod" >/dev/null 2>&1
    fi

    # --- LSPosed 作用域补齐 ---
    # 全新 lspd(配置被清/首次激活)不会自动发现 Xposed 模块: system_server
    # 被 LSPosed zygisk 默认跳过(bridge 模式), 无包管理 hook 触发扫描,
    # modules/scope 表全空 → 模块 hook 永不加载(10/27 两轮 grind 实测)。
    # 经 /data/local/tmp 中转拉取 modules_config.db, 本机 sqlite3 补写
    # 模块注册 + 作用域(等价管理器内启用并勾选作用域), 重启 lspd 生效。
    DB=/data/adb/lspd/config/modules_config.db
    STG=/data/local/tmp/.gl_lsp_db
    n=0
    while [ $n -lt 12 ] && ! $T15 adb shell "su 0 test -f $DB" >/dev/null 2>&1; do
        sleep 5; n=$((n+1))
    done
    if ! command -v sqlite3 >/dev/null 2>&1; then
        echo "      [提示] 本机无 sqlite3, 跳过 LSPosed 作用域自动补齐"
        echo "            (请在 LSPosed 管理器启用 HnLauncherPort 并勾选作用域)"
    elif ! $T15 adb shell "su 0 test -f $DB" >/dev/null 2>&1; then
        echo "      [提示] lspd 配置 db 未就绪, 跳过作用域补齐"
    else
        # 注入刚完成设备处于忙态(ZN/lspd/sepolicy 并发初始化), adb
        # sync/pm 瞬态空响应(round1/2 内嵌实测两轮稳定失败, 手动静息
        # 态必成功) —— 先等 15s 静息再拉取。
        sleep 15
        W=$(mktemp -d)
        # pull 瞬态失败重试(注入刚完成时 adb/lspd 初始化期偶发空结果)
        pull_retry() { # $1=device path $2=local path
            local i
            for i in 1 2 3; do
                adb pull "$1" "$2" >/dev/null 2>&1 && [ -s "$2" ] && return 0
                sleep 3
            done
            return 1
        }
        $T30 adb shell "su 0 sh -c 'cp $DB $STG; cp $DB-wal $STG-wal 2>/dev/null; cp $DB-shm $STG-shm 2>/dev/null; chmod 666 $STG*'" >/dev/null 2>&1
        pull_retry $STG "$W/db"
        pull_retry $STG-wal "$W/db-wal"
        pull_retry $STG-shm "$W/db-shm"
        APK=""
        for i in 1 2 3; do
            APK=$($T15 adb shell "pm path com.wxz.hnlauncherport" 2>/dev/null | tr -d '\r' | head -1 | sed 's/^package://')
            [ -n "$APK" ] && break
            sleep 3
        done
        if [ ! -s "$W/db" ] || [ -z "$APK" ]; then
            echo "      [提示] db 拉取失败或 HnLauncherPort 未安装, 跳过作用域补齐"
        else
            # wal 回放进主文件, 后续只写回 db 本体(设备端删 wal/shm)
            sqlite3 "$W/db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null 2>&1
            EMID=$(sqlite3 "$W/db" "SELECT mid FROM modules WHERE module_pkg_name='com.wxz.hnlauncherport' AND enabled=1;" 2>/dev/null)
            NSC=0
            [ -n "$EMID" ] && NSC=$(sqlite3 "$W/db" "SELECT count(*) FROM scope WHERE mid=$EMID AND app_pkg_name IN ('com.hihonor.android.launcher','com.hihonor.aod','com.android.systemui');" 2>/dev/null)
            if [ -n "$EMID" ] && [ "$NSC" = "3" ]; then
                echo "      LSPosed 作用域已配置(HnLauncherPort), 无需补齐"
            else
                sqlite3 "$W/db" "INSERT INTO modules(module_pkg_name,apk_path,enabled) VALUES('com.wxz.hnlauncherport','$APK',1) ON CONFLICT(module_pkg_name) DO UPDATE SET apk_path=excluded.apk_path, enabled=1; PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null 2>&1
                MID=$(sqlite3 "$W/db" "SELECT mid FROM modules WHERE module_pkg_name='com.wxz.hnlauncherport';" 2>/dev/null)
                if [ -n "$MID" ] \
                   && sqlite3 "$W/db" "INSERT OR IGNORE INTO scope VALUES($MID,'com.hihonor.android.launcher',0); INSERT OR IGNORE INTO scope VALUES($MID,'com.hihonor.aod',0); INSERT OR IGNORE INTO scope VALUES($MID,'com.android.systemui',0); PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null 2>&1 \
                   && adb push "$W/db" $STG >/dev/null 2>&1; then
                    # kill lspd(comm=main, 须按 cmdline 匹配 pgrep -f) → 备份
                    # 旧 db → 写回(删旧 wal/shm 防回放覆盖) → service.sh 重启 lspd
                    $T30 adb shell "su 0 sh -c 'cp $DB $DB.glbak 2>/dev/null; kill \$(pgrep -f \"^lspd\$\") 2>/dev/null; sleep 1; cp $STG $DB; rm -f $DB-wal $DB-shm; chmod 666 $DB; rm -f $STG $STG-wal $STG-shm; sh /data/adb/modules/zygisk_lsposed/service.sh'" >/dev/null 2>&1
                    # 等 lspd 回来, 再重启作用域进程让 hook 立即生效
                    n=0
                    while [ $n -lt 12 ] && ! $T15 adb shell "su 0 sh -c 'pgrep -f \"^lspd\$\" >/dev/null'" >/dev/null 2>&1; do
                        sleep 5; n=$((n+1))
                    done
                    $T30 adb shell "su 0 am force-stop com.hihonor.android.launcher; su 0 am force-stop com.hihonor.aod" >/dev/null 2>&1
                    echo "      LSPosed 作用域已补齐并重启 lspd (HnLauncherPort -> Launcher/AOD/SystemUI)"
                else
                    echo "      [警告] 作用域补齐失败, 请在 LSPosed 管理器手动启用模块并勾选作用域"
                fi
            fi
        fi
        rm -rf "$W"
    fi
    exit 0
else
    echo "      [警告] 注入未确认。诊断信息:"
    echo "        --- module.prop(若 description=[❌...] 或为 29B 裸标记说明 daemon 失败) ---"
    $T15 adb shell "su 0 sh -c 'head -c1 $MOD/module.prop | grep -q \"\\[\" && cat $MOD/module.prop || grep -E \"^(description|version)=\" $MOD/module.prop'" 2>/dev/null | tr -d '\r' | head -3 | sed 's/^/          /'
    echo "        --- daemon 内核日志(末 15 行, 缓冲可能已被冲掉) ---"
    $T30 adb shell "su 0 sh -c 'dmesg 2>/dev/null'" 2>/dev/null | grep -a "zn-daemon" | tail -15 | LC_ALL=C sed 's/^/          /'
    echo "        --- daemon_out.log ---"
    $T15 adb shell "su 0 cat /data/adb/zygisksu/daemon_out.log 2>/dev/null" 2>/dev/null | tr -d '\r' | tail -5 | LC_ALL=C sed 's/^/          /'
    echo "        可重跑本脚本; 手动日志: /data/adb/lspd/log/verbose_*.log"
    exit 1
fi
