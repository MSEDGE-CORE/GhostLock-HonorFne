#!/bin/sh
# Runs INSIDE the debian:bookworm (native arm64) container.
# Builds the offsets probe module against the Honor 70 (FNE-AN00) MagicUI 6.1
# kernel source (5.4.147, SUBLEVEL forged to 289) + the device's real MagicOS
# 8.0 config — the exact same source/config pair as kernelsu.ko, so the probe
# reports precisely the layout the .ko was compiled against.
set -eux

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    clang lld llvm \
    gcc gcc-aarch64-linux-gnu \
    make bc bison flex libelf-dev libssl-dev \
    python3 rsync git kmod xz-utils ca-certificates

rm -rf /kernel /probe
mkdir -p /kernel /probe
rsync -a /kernel-src/ /kernel/
cp /build/.config /kernel/.config

cd /kernel
sed -i 's/^SUBLEVEL = 147$/SUBLEVEL = 289/' Makefile
sed -i 's/^CONFIG_LOCALVERSION="-qgki"$/CONFIG_LOCALVERSION="-qgki-gf6cb1f712a6e"/' .config
sed -i 's/^CONFIG_LOCALVERSION_AUTO=y$/# CONFIG_LOCALVERSION_AUTO is not set/' .config
TOOLS="CC=clang LD=ld.lld AR=llvm-ar NM=llvm-nm OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump STRIP=llvm-strip HOSTCC=clang HOSTCXX=clang++ ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- LLVM=1 LLVM_IAS=1"

make $TOOLS olddefconfig
make $TOOLS modules_prepare -j"$(nproc)"

: > /kernel/Module.symvers

cp /build/probe/probe.c /build/probe/Makefile /probe/
make -C /kernel M=/probe $TOOLS KCFLAGS=-Wno-error modules -j"$(nproc)"

python3 /build/empty_versions.py /probe/probe.ko /build/probe.ko
echo "=== PROBE BUILD DONE: /build/probe.ko ==="
