#!/bin/zsh
# MCAST_SHIFT downward sweep: one fresh run per shift.
# usage: gl_sweep.sh START_N "shift list"
set -u
WS=~/Downloads/HonorFennie
SN=ABJK022A17000051
N=${1:-121}
SHIFTS=${2:-"20 16 12 8 4 0"}
for s in ${(z)SHIFTS}; do
  echo "###### sweep run${N} shift=${s} ######"
  zsh $WS/gl_cycle.sh $N 1 $s
  RC=$?
  sleep 5
  ENF=$(adb -s $SN shell getenforce 2>/dev/null | tr -d '\r')
  if [ "$ENF" = "Permissive" ]; then echo "###### W1 HIT at run${N} shift=${s} ######"; exit 0; fi
  if [ $RC -ne 0 ]; then echo "###### abort: gl_cycle rc=$RC ######"; exit 1; fi
  N=$((N+1))
done
echo "###### sweep done, no W1 ######"
