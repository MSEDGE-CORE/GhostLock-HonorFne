#!/bin/bash
# Outer loop: relaunch run_w2.sh until root lands. Unattended overnight grind.
# NOTE: the driver log is APPENDED across invocations, so success detection
# must only look at lines written by the CURRENT run_w2.sh invocation.
cd "$(dirname "$0")"
touch run_w2_driver.log
while true; do
  MARK=$(wc -l < run_w2_driver.log | tr -d ' ')
  MARK=$((MARK + 1))
  bash run_w2.sh >> run_w2_driver.log 2>&1
  if tail -n +"$MARK" run_w2_driver.log | grep -q "W2 ROOT ACHIEVED\|KSU READY"; then
    echo "=== OUTER LOOP: SUCCESS $(date +%T) - stopping ===" >> run_w2_driver.log
    break
  fi
  if tail -n +"$MARK" run_w2_driver.log | grep -q "root-no-ksu\|ROOT ACHIEVED"; then
    echo "=== OUTER LOOP: root-no-ksu $(date +%T) - stopping ===" >> run_w2_driver.log
    break
  fi
  echo "=== OUTER LOOP: relaunching runner $(date +%T) ===" >> run_w2_driver.log
  sleep 10
done
