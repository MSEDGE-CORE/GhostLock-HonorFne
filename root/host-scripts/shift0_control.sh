#!/bin/zsh
# shift0 rate measurement: run fixed count of fresh-process runs, log each
# outcome without breaking on route-reached.
# Per-run verdict:
#  - crash, tail at/after "sync pre-stamp"    -> LANDING (walk during stamp)
#  - crash, tail before route phase           -> censored (KS / M6 window)
#  - clean exit (Write 1 failed)              -> no walk observed
LOGDIR=/Users/wxz/Downloads/HonorFennie/logs
CTRL=$LOGDIR/shift0_control.log
N=53
MAXN=58
echo "### rate-measurement pass start $(date '+%m-%d %H:%M:%S')" >> $CTRL
while (( N <= MAXN )); do
  echo "=== run$N start $(date '+%m-%d %H:%M:%S') ===" >> $CTRL
  adb wait-for-device 2>/dev/null
  boot=""
  for i in {1..60}; do
    boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [[ "$boot" == "1" ]] && break
    sleep 5
  done
  if [[ "$boot" != "1" ]]; then
    echo "run$N boot wait failed" >> $CTRL
    N=$((N+1)); continue
  fi
  # wait for load to settle (1-min load < 8), max 10 min
  waited=0
  load=""
  while (( waited < 600 )); do
    load=$(adb shell 'cut -d" " -f1 /proc/loadavg' 2>/dev/null | tr -d '\r')
    if [[ -z "$load" ]]; then sleep 10; waited=$((waited+10)); continue; fi
    awk -v l="$load" 'BEGIN{exit !(l+0 < 8)}' && break
    sleep 30; waited=$((waited+30))
  done
  echo "run$N load=$load settled after ${waited}s" >> $CTRL
  sleep 30
  adb shell "cd /data/local/tmp/a && rm -f run$N.log && MCAST_SHIFT=0 KSNITCH_COLLISIONS=12 GL_STAMP_SYNC=1 ./e" \
    > $LOGDIR/gl_run${N}.host.log 2>&1
  rc=$?
  echo "run$N adb rc=$rc end $(date '+%H:%M:%S')" >> $CTRL
  sleep 15
  fdt=""
  if adb get-state >/dev/null 2>&1; then
    fdt=$(adb shell 'tail -1 /data/log/reliability/faultdetect/history.log' 2>/dev/null | tr -d '\r')
    reason=$(echo "$fdt" | grep -o 'reason \[[a-z_]*\]')
    enf=$(adb shell 'cat /sys/fs/selinux/enforce' 2>/dev/null | tr -d '\r')
    echo "run$N enforce=$enf $reason" >> $CTRL
  else
    echo "run$N device offline after run" >> $CTRL
  fi
  hl=$LOGDIR/gl_run${N}.host.log
  if [[ -f $hl ]]; then
    if grep -q "sync pre-stamp begin" $hl; then
      last=$(tail -1 $hl | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-110)
      if [[ $rc -ne 0 ]]; then
        echo "run$N LANDING-CRASH tail: $last" >> $CTRL
      else
        echo "run$N route-reached clean-exit tail: $last" >> $CTRL
      fi
    else
      last=$(tail -1 $hl | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-110)
      echo "run$N CENSORED tail: $last" >> $CTRL
    fi
  fi
  echo "faultdetect: $fdt" | cut -c1-150 >> $CTRL
  N=$((N+1))
done
echo "### rate-measurement pass finished $(date '+%H:%M:%S')" >> $CTRL
