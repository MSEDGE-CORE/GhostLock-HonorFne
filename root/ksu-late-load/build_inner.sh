#!/bin/sh
# Runs INSIDE the debian:bookworm (native arm64) container.
# Builds KernelSU as an external module against the Honor 70 (FNE-AN00)
# MagicUI 6.1 kernel source (5.4.147) + the device's real MagicOS 8.0
# config (/proc/config.gz of 5.4.289-qgki-gf6cb1f712a6e).
#
# Version drift handling (source 5.4.147 vs running 5.4.289):
#   - SUBLEVEL patched 147 -> 289 and CONFIG_LOCALVERSION forged so
#     UTS_RELEASE == "5.4.289-qgki-gf6cb1f712a6e" (vermagic belt-and-
#     suspenders; same_magic(has_crcs) would skip it anyway).
#   - MODVERSIONS bypassed via empty Module.symvers + empty_versions.py
#     (5.4 check_version: missing entry -> pr_warn_once + PASS; sh_size=0
#     -> num_versions=0 -> all entries "missing").
#   - Struct-layout drift is quantified separately by the offsets probe.
set -eux

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    clang lld llvm \
    gcc gcc-aarch64-linux-gnu \
    make bc bison flex libelf-dev libssl-dev \
    python3 rsync git kmod xz-utils ca-certificates

# Fresh writable copies (kernel-src is a read-only bind mount).
rm -rf /kernel /ksu
mkdir -p /kernel /ksu
rsync -a /kernel-src/ /kernel/
rsync -a /build/ksu/ /ksu/          # keep .git -> KSU_VERSION derives from the tag (manager version check)
cp /build/.config /kernel/.config   # device /proc/config.gz, verbatim

cd /kernel
# Forge utsrelease: 5.4.147 -> 5.4.289-qgki-gf6cb1f712a6e.
sed -i 's/^SUBLEVEL = 147$/SUBLEVEL = 289/' Makefile
sed -i 's/^CONFIG_LOCALVERSION="-qgki"$/CONFIG_LOCALVERSION="-qgki-gf6cb1f712a6e"/' .config
sed -i 's/^CONFIG_LOCALVERSION_AUTO=y$/# CONFIG_LOCALVERSION_AUTO is not set/' .config
# LLVM=1 is REQUIRED: arch/Kconfig HAS_LTO_CLANG depends on $(success,test $(LLVM) -eq 1).
# Without it olddefconfig silently drops LTO_CLANG/CFI_CLANG/CFI_CLANG_SHADOW.
TOOLS="CC=clang LD=ld.lld AR=llvm-ar NM=llvm-nm OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump STRIP=llvm-strip HOSTCC=clang HOSTCXX=clang++ ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- LLVM=1 LLVM_IAS=1"

make $TOOLS olddefconfig

# FNE config has no CONFIG_TRIM_UNUSED_KSYMS: no stub whitelist needed.

make $TOOLS modules_prepare -j"$(nproc)"

# SELinux generated headers (flask.h / av_permissions.h) are produced only when
# the selinux dir is built; KSU includes headers under security/selinux.
make $TOOLS security/selinux/ -j"$(nproc)" || true

# No real Module.symvers (that would need a full vmlinux link). modpost then
# emits crc=0 entries into __versions; we empty the section post-build, which
# this kernel's check_version() treats as warn-once + PASS for every symbol
# (and same_magic() skips the release string when __versions exists).
: > /kernel/Module.symvers

# CONFIG_WERROR would make clang-14's new warnings fatal on old sources.
# Patched init.c: do NOT swap the live sepolicy and do NOT re-enable
# enforcing (loading flow does both manually).
make -C /kernel M=/ksu/kernel $TOOLS CONFIG_KSU=m KCFLAGS=-Wno-error modules -j"$(nproc)"

ls -la /ksu/kernel/kernelsu.ko

python3 /build/empty_versions.py /ksu/kernel/kernelsu.ko /build/kernelsu.ko
llvm-nm -u /build/kernelsu.ko 2>/dev/null | awk '{print $NF}' | sort -u > /build/ko_undefsyms.txt || true

echo "=== undefined symbols not present in device kallsyms (need injection) ==="
if [ -f /build/kallsyms_names.txt ]; then
    LC_ALL=C sort -u /build/kallsyms_names.txt > /build/ks_names.txt || true
    comm -23 /build/ko_undefsyms.txt /build/ks_names.txt > /build/ko_missing_syms.txt || true
    cat /build/ko_missing_syms.txt || true
else
    echo "(no device symbol-name list — skipped)"
fi

echo "=== BUILD DONE: /build/kernelsu.ko ==="
