#!/system/bin/sh
# netfix_packet post-fs-data —— LSPosed tmpfs-on-resource-cache 修复（关键路径）
#
# 根因：LSPosed daemon 脚本（/data/adb/modules/zygisk_lsposed/daemon）内的
#   mount tmpfs -t tmpfs /data/resource-cache
# 无条件执行：daemon 每次启动都在 RRO idmap 目录上叠一层 tmpfs →
#   - 全部 Runtime Resource Overlay 陷入 STATE_NO_IDMAP，SystemUI/框架回退
#     base 资源（状态栏高度偏小、导航栏三键高度/横屏位置异常）
#   - idmap2d 对被遮蔽目录 crash loop → 主题/AOD 失效
# 本脚本在 ksud post-fs-data 阶段运行（模块 id 字母序 netfix_packet <
# zygisk_lsposed，先于 LSPosed 首次拉起 daemon），原地补丁 + 清理已叠层。
# 幂等，可重复运行。
MODDIR=${0%/*}
LOG=$MODDIR/netfix.log
ts() { date '+%m-%d %H:%M:%S'; }

# 补丁两份副本：modules（当次生效）+ modules_update（activate [1/4]/下轮
# ksud 合并会用 MU 重建 modules 树——MU 不补丁则重建后补丁被覆盖,
# grind_reboot8 实测 12:38:04 补丁被 [1/4] 于 12:38:08 用未补丁 MU 覆盖）。
for LSPD in /data/adb/modules/zygisk_lsposed/daemon /data/adb/modules_update/zygisk_lsposed/daemon; do
    if [ -f "$LSPD" ]; then
        # 旧版补丁曾把 mount 行替换成裸文本 "[netfix_packet] patched: ..."
        # (shell 每次执行到报 command not found), 先修复为注释行
        if grep -q '^\[netfix_packet\] patched:' "$LSPD"; then
            sed -i 's|^\[netfix_packet\] patched:|# [netfix_packet] patched:|' "$LSPD" \
                && echo "$(ts) post-fs-data: fixed bare-text patch line in $LSPD" >> "$LOG"
        fi
        if grep -q '^mount tmpfs -t tmpfs /data/resource-cache' "$LSPD"; then
            sed -i 's|^mount tmpfs -t tmpfs /data/resource-cache|# [netfix_packet] patched: stacked tmpfs shadows all RRO idmaps|' "$LSPD" \
                && echo "$(ts) post-fs-data: patched $LSPD (tmpfs on /data/resource-cache)" >> "$LOG"
        fi
    fi
done

n=0
while grep -q " /data/resource-cache " /proc/1/mountinfo 2>/dev/null; do
    umount /data/resource-cache 2>/dev/null \
        || umount -l /data/resource-cache 2>/dev/null \
        || break
    n=$((n + 1))
done
[ "$n" -gt 0 ] && echo "$(ts) post-fs-data: unmounted $n stacked tmpfs on /data/resource-cache" >> "$LOG"
exit 0
