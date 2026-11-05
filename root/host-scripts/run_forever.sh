#!/bin/bash
# Outer loop: relaunch run_w2.sh until root lands. Unattended overnight grind.
#
# 2026-11-05 修复（run_root.command / run_root.bat 共用，macOS + Windows Git Bash）：
#   旧实现 `bash run_w2.sh 2>&1 | tee -a run_w2_driver.log` 会永久卡死。
#   tee 必须读到管道 EOF 才退出，而 EOF 要求「所有持有写端的进程」全部退出；
#   run_w2.sh 里 run_to() 用 & 起的 `tail -f` 与看门狗子进程都继承了该写端，
#   只要残留一个，管道就永不关闭 —— 实测 root 已成功后日志停在 FINAL 行，
#   "OUTER LOOP: SUCCESS" 永不落盘，run_root.command 因此走不到
#   wait_ksu.sh / install_managers.sh（root 成功但三件套没装）。
#   现改为：子进程直接 >> 日志文件，父进程按行号增量读取回显。
#   全程无管道、无长命 tail，结构上不可能再因残留 fd 卡住。
#
# NOTE: the driver log is APPENDED across invocations, so success detection
# must only look at lines written by the CURRENT run_w2.sh invocation.
cd "$(dirname "$0")" || exit 1
LOG=run_w2_driver.log
touch "$LOG"

DRAIN_AT=0
# 打印 (DRAIN_AT, 当前末行] 的增量并推进 DRAIN_AT。
# 用一次性 tail -n +N（BSD/GNU 同语义），不驻留任何跟随进程。
drain() {
    local total
    total=$(wc -l < "$LOG" 2>/dev/null | tr -d ' ')
    case "$total" in ''|*[!0-9]*) total=$DRAIN_AT ;; esac
    if [ "$total" -gt "$DRAIN_AT" ]; then
        tail -n "+$((DRAIN_AT + 1))" "$LOG" | head -n $((total - DRAIN_AT))
        DRAIN_AT=$total
    fi
}

while true; do
  MARK=$(wc -l < "$LOG" | tr -d ' ')
  case "$MARK" in ''|*[!0-9]*) MARK=0 ;; esac
  DRAIN_AT=$MARK

  # 子进程直接写日志（无管道）；父进程轮询增量回显
  bash run_w2.sh >> "$LOG" 2>&1 &
  RPID=$!
  # jobs -pr 而非 kill -0：已退出但未被回收的僵尸不会被误判为存活，避免空转。
  while jobs -pr 2>/dev/null | grep -qx "$RPID"; do
    drain
    sleep 1
  done
  wait "$RPID" 2>/dev/null
  drain

  # 用 grep -E（ERE）而非 "a\|b"：\| 是 GNU/BSD 的 BRE 扩展，toybox/busybox
  # 的 grep 不认（实测返回 1），会让成功判定永远不命中。
  if tail -n "+$((MARK + 1))" "$LOG" | grep -qE "W2 ROOT ACHIEVED|KSU READY"; then
    echo "=== OUTER LOOP: SUCCESS $(date +%T) - stopping ===" | tee -a "$LOG"
    break
  fi
  if tail -n "+$((MARK + 1))" "$LOG" | grep -qE "root-no-ksu|ROOT ACHIEVED"; then
    echo "=== OUTER LOOP: root-no-ksu $(date +%T) - stopping ===" | tee -a "$LOG"
    break
  fi
  echo "=== OUTER LOOP: relaunching runner $(date +%T) ===" | tee -a "$LOG"
  sleep 10
done
