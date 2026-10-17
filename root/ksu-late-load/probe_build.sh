#!/bin/sh
# Host driver: build the offsets probe .ko in the colima container, then
# extract probe_vals[]/probe_names[] statically on the host and compare
# against the running-kernel gold standard (ghostlock fne/offsets.h).
#   KERNEL_SRC=...  override the kernel tree (default: ../fne_src/...)
# Output: .build/probe.ko + drift report on stdout.
set -eu

container_name="fne-ksu-probe"
repo_dir="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
stage_dir="${KSU_BUILD_DIR:-$repo_dir/.build}"
mkdir -p "$stage_dir"

if [ -z "${KERNEL_SRC:-}" ]; then
    KERNEL_SRC="$repo_dir/../fne_src/Code_Opensource/kernel"
fi
kernel_src="$(CDPATH= cd -- "$KERNEL_SRC" && pwd)"

cp "${KSU_DEVICE_CONFIG:-$repo_dir/kernel.config}" "$stage_dir/.config"
cp "$repo_dir/probe_inner.sh" "$repo_dir/empty_versions.py" "$stage_dir/"
mkdir -p "$stage_dir/probe"
cp "$repo_dir/probe/probe.c" "$repo_dir/probe/Makefile" "$stage_dir/probe/"

docker rm -f "$container_name" >/dev/null 2>&1 || true

docker run --rm \
    --name "$container_name" \
    --hostname "$container_name" \
    --stop-timeout 10 \
    --mount "type=bind,src=$kernel_src,dst=/kernel-src,readonly" \
    --mount "type=bind,src=$stage_dir,dst=/build" \
    debian:bookworm \
    sh /build/probe_inner.sh

python3 "$repo_dir/probe_read.py" "$stage_dir/probe.ko"
