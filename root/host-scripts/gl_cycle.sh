#!/bin/zsh
# GhostLock normal-plant iteration loop (v2).
# usage: gl_cycle.sh START_N MAX_RUNS
set -u
SN=ABJK022A17000051
WS=~/Downloads/HonorFennie
BIN=$WS/src_ghostlock/ghostlock
LOGS=$WS/logs
START=${1:-119}
MAXRUNS=${2:-5}
SHIFT=${3:-24}
KNOBS="GL_HOLD_CHAIN=1 GL_STAMP_SYNC=1 GL_CANARY_SEQ=0 GL_CANARY_PAGE=0 GL_CANARY_RATCHET=0 GL_ROUTE_ATTEMPTS=8 M4_TIMEOUT_MS=1000 MCAST_SHIFT=$SHIFT GL_CHAIN_BAIL=0 GL_OWNER_HANDOFF=0 GL_PUNCH_IN_WINDOW=0"
echo "config: MCAST_SHIFT=$SHIFT"
MD5EXPECT=$(md5 -q "$BIN")

far_query() {
  adb -s $SN pull /data/log/log.db /tmp/gl_cycle_log.db >/dev/null 2>&1 || { echo "    (log.db pull failed)"; return 1; }
  python3 << 'EOF'
import sqlite3, json, datetime
try:
    c = sqlite3.connect('/tmp/gl_cycle_log.db')
    rows = c.execute("SELECT last_occurrence_time, extra FROM event_info WHERE extra LIKE '%paging request%' OR extra LIKE '%PANIC%' ORDER BY last_occurrence_time DESC LIMIT 3").fetchall()
    for t, e in rows:
        j = json.loads(e)
        print("    FAR", datetime.datetime.fromtimestamp(t), j.get('F3NAME', ''))
except Exception as ex:
    print("    FAR query err:", ex)
EOF
}

for (( n=START; n<START+MAXRUNS; n++ )); do
  LOG=$LOGS/gl_run${n}.host.log
  echo "=== run${n}: waiting for device"
  adb -s $SN wait-for-device
  sleep 6
  ENF=$(adb -s $SN shell getenforce 2>/dev/null | tr -d '\r')
  if [ "$ENF" = "Permissive" ]; then echo "!!! W1 HIT (pre-run check): Permissive"; exit 0; fi
  adb -s $SN shell "pkill -9 -x e" 2>/dev/null
  echo "=== run${n}: push+md5"
  adb -s $SN push "$BIN" /data/local/tmp/a/e >/dev/null 2>&1 || { echo "push failed"; exit 1; }
  MD5GOT=$(adb -s $SN shell "md5sum /data/local/tmp/a/e" 2>/dev/null | awk '{print $1}' | tr -d '\r')
  if [ "$MD5GOT" != "$MD5EXPECT" ]; then echo "md5 mismatch got=$MD5GOT want=$MD5EXPECT"; exit 1; fi
  echo "=== run${n}: launch"
  nohup adb -s $SN shell "cd /data/local/tmp/a && $KNOBS ./e --write1" > "$LOG" 2>&1 &
  PID=$!
  # primary: adb client exits when remote ./e exits or device crashes
  for (( t=0; t<900; t++ )); do
    kill -0 $PID 2>/dev/null || break
    sleep 2
  done
  if kill -0 $PID 2>/dev/null; then
    echo "=== run${n}: 30min timeout -> killing"
    adb -s $SN shell "pkill -9 -x e" 2>/dev/null
    sleep 2
    kill $PID 2>/dev/null
  fi
  echo "=== run${n}: ended (log $(wc -l < "$LOG" | tr -d ' ') lines, mtime $(stat -f %Sm "$LOG"))"
  tail -3 "$LOG" | sed 's/^/    | /'
  adb -s $SN wait-for-device
  sleep 3
  echo "--- FAR:"
  far_query
  ENF=$(adb -s $SN shell getenforce 2>/dev/null | tr -d '\r')
  echo "    enforce=$ENF"
  if [ "$ENF" = "Permissive" ]; then echo "!!! run${n} W1 HIT"; exit 0; fi
  if grep -q "route BOOTID-PROBE HIT\|ARM-T HIT" "$LOG" 2>/dev/null; then echo "!!! run${n} deep-walk verify HIT in log"; exit 0; fi
done
echo "=== cycle done (no W1 in $MAXRUNS runs)"
