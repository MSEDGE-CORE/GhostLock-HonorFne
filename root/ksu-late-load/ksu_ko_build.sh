#!/bin/sh
# Build a custom kernelsu.ko against the Honor 70 (FNE-AN00) kernel:
#   MagicUI 6.1 opensource tree (5.4.147, sublevel forged to 289) + the
#   device's MagicOS 8.0 /proc/config.gz, in a native arm64 Debian
#   container via colima.
#
# Everything except KERNEL_SRC is auto-staged into .build/:
#   .config           <- this dir's kernel.config (device config.gz)
#   ksu/              <- auto-cloned KernelSU v3.3.0 (with .git, patches applied)
#   kallsyms_names.txt<- symbol names from the running 5.4.289 ELF (validation)
#
# Output: .build/kernelsu.ko
set -eu

container_name="fne-ksu-build"
repo_dir="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
stage_dir="${KSU_BUILD_DIR:-$repo_dir/.build}"
mkdir -p "$stage_dir"

# KERNEL_SRC: the FNE-AN00 MagicUI 6.1 opensource kernel tree
# (fne_src/Code_Opensource/kernel). Source is 5.4.147; running kernel is
# 5.4.289 — build_inner.sh forges the release, empty_versions.py bypasses
# MODVERSIONS; struct drift is checked by the offsets probe.
if [ -z "${KERNEL_SRC:-}" ]; then
    KERNEL_SRC="$repo_dir/../fne_src/Code_Opensource/kernel"
fi
kernel_src="$(CDPATH= cd -- "$KERNEL_SRC" && pwd)"

cp "${KSU_DEVICE_CONFIG:-$repo_dir/kernel.config}" "$stage_dir/.config"
cp "${KSU_KALLSYMS_NAMES:-$repo_dir/kallsyms_names}" "$stage_dir/kallsyms_names.txt" 2>/dev/null || true
cp "$repo_dir/build_inner.sh" "$repo_dir/empty_versions.py" "$stage_dir/"

# KernelSU v3.3.0 checkout. .git must be present and at the tag so
# KSU_VERSION = 30000 + git rev-list --count HEAD (the manager
# version-checks this number).
if [ ! -d "$stage_dir/ksu/.git" ]; then
    rm -rf "$stage_dir/ksu"
    git clone https://github.com/tiann/KernelSU "$stage_dir/ksu"
    git -C "$stage_dir/ksu" checkout -q v3.3.0
fi
# Reset every file the patches touch to pristine, then apply in order.
for f in kernel/core/init.c kernel/selinux/sepolicy.c kernel/selinux/rules.c \
         kernel/supercall/dispatch.c uapi/supercall.h kernel/feature/sucompat.c \
         kernel/infra/file_wrapper.c kernel/hook/arm64/patch_memory.c \
         kernel/infra/seccomp_cache.c kernel/manager/pkg_observer.c \
         kernel/policy/allowlist.c kernel/policy/app_profile.c \
         kernel/runtime/ksud_integration.c kernel/feature/selinux_hide.c \
         kernel/sulog/event.c kernel/supercall/supercall.c \
         kernel/infra/su_mount_ns.c kernel/feature/kernel_umount.c; do
    git -C "$stage_dir/ksu" checkout -- "$f" 2>/dev/null || true
done
for p in init-bootid.patch sepolicy-dyn-len.patch selinux-hide-backup.patch \
         fne-5.4-compat.patch; do
    if ! git -C "$stage_dir/ksu" apply --check "$repo_dir/$p" 2>/dev/null; then
        echo "ERROR: $p does not apply to the KernelSU checkout" >&2
        exit 1
    fi
    git -C "$stage_dir/ksu" apply "$repo_dir/$p"
done

docker rm -f "$container_name" >/dev/null 2>&1 || true

docker run --rm \
    --name "$container_name" \
    --hostname "$container_name" \
    --stop-timeout 10 \
    --mount "type=bind,src=$kernel_src,dst=/kernel-src,readonly" \
    --mount "type=bind,src=$stage_dir,dst=/build" \
    debian:bookworm \
    sh /build/build_inner.sh
