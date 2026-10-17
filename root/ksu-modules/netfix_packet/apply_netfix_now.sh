#!/bin/bash
# Try multiple methods to apply packet allows on the CURRENT boot.
# Method 1: ksud sepolicy patch (verified NO-OP on this device - mtime unchanged)
# Method 2: load_policy with a crafted binary (needs checkpolicy - not available)
# Method 3: KSU module sepolicy.rule (applied on next ksud late-load)
# Method 4: W1 exploit already proved we can write kernel memory to flip enforcing;
#           but setenforce is now EACCES/EINVAL - HISEC or KSU hook blocks it.
#
# Conclusion: the reliable fix is Method 3 - install the KSU module and
# re-run late-load (or reboot + re-root). This script only documents attempts.

set -e
KSU=/data/adb/ksu/bin/ksud

echo "=== Attempt 1: ksud sepolicy patch ==="
$KSU sepolicy patch "allow domain unlabeled:packet send" || true
$KSU sepolicy patch "allow domain unlabeled:packet recv" || true

echo "=== Attempt 2: check load mtime (should change if patch worked) ==="
stat -c '%y %s' /sys/fs/selinux/load || ls -la /sys/fs/selinux/load

echo "=== Attempt 3: verify netdev SIDs (need netif, not unlabeled) ==="
# There is no direct /proc interface for netdev SID; infer from AVC or try ping
ping -c 1 -W 2 192.168.1.1 && echo PING_OK || echo PING_FAIL_still_broken

echo "=== Done. If PING_FAIL: reboot, re-run grind (run_forever.sh), ==="
echo "=== KSU will load modules including netfix_packet sepolicy.rule. ==="
