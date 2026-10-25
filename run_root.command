#!/bin/bash
# GhostLock-HonorFne One-Click Temporary Root (macOS)
# Double-click to run; if blocked by Gatekeeper, right-click -> Open, or run in terminal: bash run_root.command
cd "$(dirname "$0")" || exit 1

echo "=================================================="
echo "   GhostLock-HonorFne  One-Click Temporary Root (macOS)"
echo "   Target: HONOR 70 FNE-AN00 (Bootloader Locked)"
echo "=================================================="
echo

# ---------- Mode selection: deploy / uninstall ----------
echo "Please select mode:"
echo "  [1] Deploy / One-click temporary root (default)"
echo "  [2] Uninstall (clean device-side files and module staging; does not uninstall manager/app)"
MODE=1
read -p "Enter 1 or 2 and press Enter: " MODE
case "$MODE" in
    2)
        ADB="$(command -v adb 2>/dev/null || true)"
        if [ -z "$ADB" ]; then
            for p in "$HOME/Library/Android/sdk/platform-tools/adb" /usr/local/bin/adb /opt/homebrew/bin/adb; do
                if [ -x "$p" ]; then ADB="$p"; break; fi
            done
        fi
        if [ -z "$ADB" ]; then
            echo "[ERROR] adb not found."
            read -p "Press Enter to exit..."
            exit 1
        fi
        export PATH="$(dirname "$ADB"):$PATH"
        bash "root/host-scripts/uninstall.sh"
        echo
        read -p "Press Enter to exit..."
        exit 0
        ;;
esac
echo

exit_push() {
    echo "[ERROR] File push failed. Check connection and USB debugging authorization and retry."
    read -p "Press Enter to exit..."
    exit 1
}

# netfix_packet module installation (called after KSU ready).
# /data/adb is a root directory, adb direct push won't work: stage via /data/local/tmp, then su 0 merge-sync install.
# Unconditional sync (cp -rf merge, preserve runtime netfix.log): ensure module fixes/updates always take effect.
# Rules are read and injected during ksud late-load, so updates activate automatically from next root/reboot.
ensure_module() {
    "$ADB" shell "su 0 sh -c 'mkdir -p /data/adb/modules/netfix_packet && cp -rf /data/local/tmp/netfix_packet/. /data/adb/modules/netfix_packet/'" >/dev/null 2>&1
    if "$ADB" shell "su 0 sh -c '[ -f /data/adb/modules/netfix_packet/sepolicy.rule ]'" >/dev/null 2>&1; then
        echo "      netfix_packet module synced (network/RRO/status bar/nav bar/AOD fix)"
    else
        echo "      [WARN] netfix_packet module sync failed"
    fi
}

# ---------- 1/5 Dependency check ----------
echo "[1/5] Checking dependencies..."
ADB="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB" ]; then
    for p in "$HOME/Library/Android/sdk/platform-tools/adb" /usr/local/bin/adb /opt/homebrew/bin/adb; do
        if [ -x "$p" ]; then ADB="$p"; break; fi
    done
fi
if [ -z "$ADB" ]; then
    echo "[ERROR] adb not found."
    echo "       Install: brew install --cask android-platform-tools"
    echo "       Or add adb directory to PATH and retry."
    read -p "Press Enter to exit..."
    exit 1
fi
export PATH="$(dirname "$ADB"):$PATH"   # run_forever.sh calls adb directly
echo "      OK: adb ready ($ADB)"
echo

# ---------- 2/5 Connect device ----------
echo "[2/5] Connecting device (USB)"
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "      Waiting for device - connect USB and allow USB debugging authorization..."
    "$ADB" wait-for-device
fi
echo "      Connected:"
"$ADB" devices
echo

# ---------- 3/5 Root status check ----------
echo "[3/5] Checking KernelSU status..."
# Wireless adb single-shell transient disconnect (empty response) may falsely
# report "not loaded" and trigger a full exploit run; repeating W2 within the
# same boot causes a slab_drain fork storm that may trigger a protective reboot
# (observed on 295 devices at 18:14). Max 2 checks to resist transient disconnect
# (same rule as run_root.bat / run_w2.sh / uninstall.sh: 5s interval, either
# check hitting = treat as loaded).
KSU_LOADED=0
for i in 1 2; do
    if "$ADB" shell "grep -q kernelsu /proc/modules" >/dev/null 2>&1; then
        KSU_LOADED=1
        break
    fi
    [ "$i" -lt 2 ] && { echo "      No response, retrying ($i/2)..."; sleep 5; }
done
if [ "$KSU_LOADED" = "1" ]; then
    echo "      KernelSU is already running, no need to re-root."
    # ksu_diag: cross-check /proc/modules against sysfs - when module is loaded
    # but upper-level checks (sysfs/su/boot_completed) fail, this locates the stuck stage.
    echo "      --- ksu_diag ---"
    "$ADB" shell "grep kernelsu /proc/modules" 2>/dev/null | tr -d '\r' | sed 's/^/        modules: /'
    SYSFS_KSU=$("$ADB" shell "ls -d /sys/module/kernelsu 2>/dev/null" 2>/dev/null | tr -d '\r')
    echo "        sysfs: ${SYSFS_KSU:-/sys/module/kernelsu not found}"
    "$ADB" shell "su 0 id" 2>/dev/null | tr -d '\r' | sed 's/^/        su 0 id: /'
    echo "        boot_completed: $("$ADB" shell "getprop sys.boot_completed" 2>/dev/null | tr -d '\r')"
    ensure_module
    # Previous run may have timed out and falsely reported "not landed"
    # (module loaded but su not ready): complete installation/activation,
    # rerun to fix.
    echo "      --- Completing KSU / LSP manager installation and Zygisk activation ---"
    bash "root/host-scripts/install_managers.sh"
    bash "root/host-scripts/activate_zygisk.sh"
    echo
    read -p "Press Enter to exit..."
    exit 0
fi
echo "      KernelSU not loaded, continuing."
echo

# ---------- 4/5 Deploy (unconditional overwrite) ----------
# Overwrite instead of skip-if-exists: chain_hook.sh / ko / syms iterate
# frequently; existence check would prevent already-deployed devices from
# getting new versions (LSA merge fix was stuck on old chain_hook).
# adb push itself overwrites.
if [ ! -f "root/exploit/ghostlock" ]; then
    echo "[ERROR] Missing root/exploit/ghostlock (exploit binary)."
    echo "       This repo should ship prebuilt artifacts; if you cloned via git and it's missing,"
    echo "       please update to the latest version; or refer to README \"Rebuild\" section with"
    echo "       Android NDK r27c:"
    echo "         cd root/exploit && ANDROID_NDK_HOME=<NDK_PATH> make"
    exit 1
fi
echo "[4/5] Deploying device-side files (~20MB, overwrite sync, please wait)..."
"$ADB" shell "mkdir -p /data/local/tmp/ksu_fne" || exit_push
"$ADB" push "root/exploit/ghostlock" /data/local/tmp/e || exit_push
"$ADB" shell "chmod 755 /data/local/tmp/e"
for f in chain_hook.sh kernelsu.ko ksud magiskpolicy ksu_rules load_ko kmsg_dumper fne_syms.txt; do
    "$ADB" push "root/ksu-late-load/$f" /data/local/tmp/ksu_fne/ || exit_push
done
"$ADB" shell "chmod 755 /data/local/tmp/ksu_fne/chain_hook.sh /data/local/tmp/ksu_fne/magiskpolicy /data/local/tmp/ksu_fne/ksud /data/local/tmp/ksu_fne/load_ko /data/local/tmp/ksu_fne/kmsg_dumper"
echo "      Deployment complete."

# netfix_packet module is small and iterates quickly: always re-transfer to
# ensure module fixes (sepolicy rules / LSPosed tmpfs patch) are synced to device.
echo "      Syncing netfix_packet module..."
"$ADB" shell "rm -rf /data/local/tmp/netfix_packet" || exit_push
"$ADB" push "root/ksu-modules/netfix_packet" /data/local/tmp/ || exit_push
echo

# ---------- Optional: install launcher mods (Launcher/SystemUI hooks) ----------
# Called after KSU/LSPosed success. Default skip, choose y to install:
#   HnLauncherPort.apk (LSPosed module: launcher layout + control center animation optimization)
install_launcher_mods() {
    echo
    read -p "Install launcher mods (HnLauncherPort)? [y/N]: " ans
    case "$ans" in
        y|Y)
            if [ ! -f "hooks/launcher/out/HnLauncherPort.apk" ]; then
                echo "      [ERROR] Missing hooks/launcher/out/HnLauncherPort.apk"
                return 1
            fi
            echo "      Installing HnLauncherPort.apk ..."
            if ! "$ADB" install -r "hooks/launcher/out/HnLauncherPort.apk" >/dev/null 2>&1; then
                echo "      [ERROR] APK installation failed"
                return 1
            fi
            echo "      APK installed successfully."
            echo "      Note: First time you need to enable the module in LSPosed Manager and check"
            echo "            scope (HONOR Launcher + SystemUI), then restart the app to take effect."
            ;;
        *)
            echo "      Skipped launcher mods installation."
            ;;
    esac
}

# ---------- 5/5 Run ----------
echo "[5/5] Starting per-boot root (W1 - W2 - KSU late-load)"
echo "      * Fully automatic, includes multiple device reboots, may take a long time"
echo "      * Do not close this window; do not run other grinds simultaneously"
echo "      * Script stops automatically on success"
echo
bash "root/host-scripts/run_forever.sh"
echo

# After root lands, detached chain_hook still needs tens of seconds to complete
# KSU late-load; checking /proc/modules immediately may falsely report failure.
# Poll and wait (max 180s), showing device-side chain_status / chain.log
# incrementally (wait_ksu.sh).
KSU_UP=0
if bash "root/host-scripts/wait_ksu.sh"; then KSU_UP=1; fi
ensure_module
if [ "$KSU_UP" = "1" ]; then
    echo "=================================================="
    echo "   [SUCCESS] KernelSU loaded, temporary root ready!"
    echo "=================================================="
    # Auto-install managers: KernelSU Manager + ZygiskNext + LSPosed (embedded manager)
    echo "      --- Auto-installing KSU / LSP managers ---"
    bash "root/host-scripts/install_managers.sh"
    # Zygisk/LSPosed injection activation (post-chain timing, verified recipe at 12:40;
    # running inside chain would break modules)
    bash "root/host-scripts/activate_zygisk.sh"
    install_launcher_mods
else
    echo "=================================================="
    echo "   [FAILED] This grind did not land, you can rerun and try again."
    echo "=================================================="
fi
echo
read -p "Press Enter to exit..."