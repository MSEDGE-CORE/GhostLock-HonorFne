#!/system/bin/sh
# netfix_packet service.sh —— 兜底：zygisk_lsposed 的 post-fs-data.sh 与
# 本阶段之间可能已抢先拉起过一次 daemon，此处再补一次补丁/卸载，
# 并确保 idmap2d（RRO idmap 生成器）存活。
MODDIR=${0%/*}
LOG=$MODDIR/netfix.log
ts() { date '+%m-%d %H:%M:%S'; }

LSPD=/data/adb/modules/zygisk_lsposed/daemon
if [ -f "$LSPD" ] && grep -q '^mount tmpfs -t tmpfs /data/resource-cache' "$LSPD"; then
    sed -i 's|^mount tmpfs -t tmpfs /data/resource-cache|[netfix_packet] patched: stacked tmpfs shadows all RRO idmaps|' "$LSPD" \
        && echo "$(ts) service: patched zygisk_lsposed/daemon (tmpfs on /data/resource-cache)" >> "$LOG"
fi

n=0
while grep -q " /data/resource-cache " /proc/1/mountinfo 2>/dev/null; do
    umount /data/resource-cache 2>/dev/null \
        || umount -l /data/resource-cache 2>/dev/null \
        || break
    n=$((n + 1))
done
[ "$n" -gt 0 ] && echo "$(ts) service: unmounted $n stacked tmpfs on /data/resource-cache" >> "$LOG"

if [ "$(getprop init.svc.idmap2d)" != "running" ]; then
    setprop ctl.start idmap2d 2>/dev/null \
        && echo "$(ts) service: (re)started idmap2d" >> "$LOG"
fi
exit 0
