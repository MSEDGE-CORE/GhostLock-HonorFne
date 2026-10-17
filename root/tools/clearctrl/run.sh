#!/bin/bash
# Push clearctrl.jar to device and clear the stale monkey controller as root.
# Verifies before/after via `dumpsys activity` (mControllerIsAMonkey).
# Requires a live root shell (per-boot exploit; see ../../host-scripts/run_w2.sh).
set -e
cd "$(dirname "$0")"

DEV=/data/local/tmp/clearctrl.jar
adb push clearctrl.jar "$DEV" >/dev/null

echo "--- before ---"
adb shell "su 0 sh -c 'dumpsys activity'" 2>/dev/null | grep -i monkey || echo "(no monkey flag)"

adb shell "su 0 sh -c 'CLASSPATH=$DEV app_process / ClearCtrl'"

echo "--- after ---"
adb shell "su 0 sh -c 'dumpsys activity'" 2>/dev/null | grep -i monkey || echo "(cleared: no monkey flag)"
