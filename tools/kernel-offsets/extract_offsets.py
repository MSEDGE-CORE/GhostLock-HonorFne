#!/usr/bin/env python3
"""Extract GhostLock offsets-entry fields from a boot.img / kernel Image.

Pipeline: boot.img (v3 hdr) -> kernel Image -> vmlinux-to-elf -> llvm-nm
-> kallsyms symbol table -> per-field offsets (VA - _text).

Usage:
  python3 extract_offsets.py <boot.img|kernel.bin> \
      [--vmlinux-to-elf <path>] [--llvm-nm <path>] [--keep-dir <dir>]

Outputs an OFFSETS_ENTRY-style field list on stdout. All values are
pre-relocation VAs minus _text (kimage_text_base). kallsyms names carry
"$<hash>" suffixes (CFI); .cfi_jt thunks are ignored, lowest VA wins.

Special derivations (verified against the 8.0.214/8.0.224 units):
  off_selinux_enforcing  = selinux_state + 1
  off_slide_loggers_0_1  = loggers + 0x10
  off_ashmem_compat_ioctl= off_ashmem_ioctl
  off_copy_splice_read   = copy_splice_read if present else
                           default_file_splice_read (5.4 has no
                           copy_splice_read)
  off_slide_boot_id      = sysctl boot_id data buffer, lives in .bss
                           (outside the Image file). Located via the
                           sysctl entry pattern: pointer to the
                           "boot_id" string, then +0x18 -> pointer
                           beyond the Image end (bss).
  off_cap_capable_active = 0 (no cap_capable gate on these builds)

Requires: brotli not needed here; vmlinux-to-elf (pip install
vmlinux-to-elf) and llvm-nm (NDK toolchain or llvm).
"""
import argparse
import os
import struct
import subprocess
import sys
import tempfile

KIMAGE_TEXT_BASE = None  # resolved from _text symbol


def parse_boot_v3(img):
    """Return (kernel_bytes, header_dict) for a boot v3 image, else None."""
    if len(img) < 4096 or img[:8] != b'ANDROID!':
        return None
    ks = struct.unpack('<I', img[8:12])[0]
    rs = struct.unpack('<I', img[12:16])[0]
    hs = struct.unpack('<I', img[20:24])[0]
    hv = struct.unpack('<I', img[40:44])[0]
    return img[4096:4096 + ks], {'kernel_size': ks, 'ramdisk_size': rs,
                                 'hdr_size': hs, 'hdr_ver': hv}


def find_v2e():
    """Return (site_packages_dir_or_None, script_path)."""
    cand = os.environ.get('VMLINUX_TO_ELF')
    if cand:
        return None, cand
    home = os.path.expanduser('~/Library/Python/3.9/lib/python/site-packages')
    for sp in (os.path.join(home, 'vmlinux_to_elf', 'scripts',
                            'vmlinux_to_elf.py'),
               os.path.join(home, 'scripts', 'vmlinux_to_elf.py')):
        if os.path.exists(sp):
            return os.path.dirname(os.path.dirname(os.path.dirname(sp))), sp
    try:
        import importlib.util
        spec = importlib.util.find_spec('vmlinux_to_elf')
        if spec:
            return None, spec.origin
    except Exception:
        pass
    sys.exit('vmlinux-to-elf not found; pip install vmlinux-to-elf '
             'or set VMLINUX_TO_ELF')


def run_v2e(kernel_path, out_elf, sp_dir, script):
    if sp_dir:
        # pip --user install for a different python minor version: shim it.
        code = ('import sys\n'
                f'sys.path.insert(0, {sp_dir!r})\n'
                'sys.argv = ["vmlinux-to-elf"] + sys.argv[1:]\n'
                f'exec(open({script!r}).read())\n')
        cmd = [sys.executable, '-c', code, kernel_path, out_elf]
    else:
        cmd = [sys.executable, script, kernel_path, out_elf]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if not os.path.exists(out_elf):
        sys.stderr.write(r.stdout + r.stderr)
        sys.exit('vmlinux-to-elf failed')
    return r.stdout


def run_nm(elf, nm_path):
    r = subprocess.run([nm_path, elf], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit('llvm-nm failed: ' + r.stderr)
    return r.stdout


def parse_syms(nm_out):
    """name -> lowest VA (strip $hash, skip .cfi_jt)."""
    syms = {}
    for line in nm_out.splitlines():
        parts = line.split()
        if len(parts) < 3 or len(parts[0]) != 16:
            continue
        try:
            va = int(parts[0], 16)
        except ValueError:
            continue
        name = parts[2].split('$')[0]
        if name.endswith('.cfi_jt'):
            continue
        if name not in syms or va < syms[name]:
            syms[name] = va
    return syms


def find_boot_id(kernel, base, img_end_va):
    """sysctl "boot_id" entry: str ptr +0x18 -> bss pointer."""
    start = 0
    while True:
        f = kernel.find(b'boot_id\x00', start)
        if f < 0:
            return None
        start = f + 1
        str_va = base + f
        pat = struct.pack('<Q', str_va)
        g = 0
        while True:
            g = kernel.find(pat, g)
            if g < 0:
                break
            vd = struct.unpack('<Q', kernel[g + 0x18:g + 0x20])[0]
            if img_end_va <= vd < base + 0x20000000:
                return vd - base
            g += 1
    return None


WANT = [
    'init_task', 'init_cred', 'init_uts_ns', 'empty_zero_page',
    'root_task_group', 'selinux_state', 'sig_enforce', 'kptr_restrict',
    'selinux_blob_sizes', 'security_hook_heads', 'kmalloc_caches',
    'anon_pipe_buf_ops', 'ashmem_misc', 'ashmem_fops', 'ashmem_ioctl',
    'ashmem_mmap', 'ashmem_open', 'ashmem_release', 'ashmem_show_fdinfo',
    'configfs_read_file', 'configfs_write_bin_file', 'copy_splice_read',
    'default_file_splice_read', 'noop_llseek', 'nfulnl_logger', 'loggers',
    'system_unbound_wq', 'call_usermodehelper_exec_work', '_text',
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('image', help='boot.img or raw kernel Image')
    ap.add_argument('--llvm-nm', default=os.environ.get('LLVM_NM', 'llvm-nm'))
    ap.add_argument('--keep-dir', default=None,
                    help='keep intermediate .bin/.elf here (default: tmp)')
    args = ap.parse_args()

    img = open(args.image, 'rb').read()
    parsed = parse_boot_v3(img)
    if parsed:
        kernel, hdr = parsed
        print(f'# boot v3: kernel_size={hdr["kernel_size"]} '
              f'ramdisk={hdr["ramdisk_size"]} hdr_ver={hdr["hdr_ver"]}',
              file=sys.stderr)
    else:
        kernel = img
        print('# input is a raw kernel Image', file=sys.stderr)

    base = 0xffffffc010080000  # provisional; overridden by _text
    img_end_va = None

    keep = args.keep_dir and os.path.isdir(args.keep_dir)
    tmpdir = args.keep_dir if keep else tempfile.mkdtemp(prefix='koff_')
    kpath = os.path.join(tmpdir, 'kernel_input.bin')
    elf = os.path.join(tmpdir, 'kernel.elf')
    open(kpath, 'wb').write(kernel)

    v2e_sp, v2e = find_v2e()
    print(f'# vmlinux-to-elf: {v2e}', file=sys.stderr)
    run_v2e(kpath, elf, v2e_sp, v2e)
    syms = parse_syms(run_nm(elf, args.llvm_nm))

    if '_text' in syms:
        base = syms['_text']
    img_end_va = base + len(kernel)
    print(f'# _text = {base:#x}  image end = {img_end_va:#x}', file=sys.stderr)

    out = {}
    missing = []
    for n in WANT:
        if n in syms:
            out[n] = syms[n] - base
        elif n != '_text':
            missing.append(n)

    for n in missing:
        print(f'# MISSING symbol: {n}', file=sys.stderr)

    io = out.get('ashmem_ioctl')
    fields = []
    fields.append(('off_init_task', out.get('init_task')))
    fields.append(('off_init_cred', out.get('init_cred')))
    fields.append(('off_init_uts_ns', out.get('init_uts_ns')))
    fields.append(('off_empty_zero_page', out.get('empty_zero_page')))
    fields.append(('off_root_task_group', out.get('root_task_group')))
    if 'selinux_state' in out:
        fields.append(('off_selinux_enforcing', out['selinux_state'] + 1))
    fields.append(('off_sig_enforce', out.get('sig_enforce')))
    fields.append(('off_kptr_restrict', out.get('kptr_restrict')))
    fields.append(('off_selinux_blob_sizes', out.get('selinux_blob_sizes')))
    fields.append(('off_security_hook_heads', out.get('security_hook_heads')))
    fields.append(('off_kmalloc_caches', out.get('kmalloc_caches')))
    fields.append(('off_anon_pipe_buf_ops', out.get('anon_pipe_buf_ops')))
    fields.append(('off_ashmem_misc_fops', out.get('ashmem_misc')))
    fields.append(('off_ashmem_fops', out.get('ashmem_fops')))
    fields.append(('off_ashmem_ioctl', io))
    fields.append(('off_ashmem_compat_ioctl', io))
    fields.append(('off_ashmem_mmap', out.get('ashmem_mmap')))
    fields.append(('off_ashmem_open', out.get('ashmem_open')))
    fields.append(('off_ashmem_release', out.get('ashmem_release')))
    fields.append(('off_ashmem_show_fdinfo', out.get('ashmem_show_fdinfo')))
    fields.append(('off_configfs_read_iter', out.get('configfs_read_file')))
    fields.append(('off_configfs_bin_write_iter',
                   out.get('configfs_write_bin_file')))
    fields.append(('off_copy_splice_read',
                   out.get('copy_splice_read',
                           out.get('default_file_splice_read'))))
    fields.append(('off_noop_llseek', out.get('noop_llseek')))
    fields.append(('off_cap_capable_active', 0))
    fields.append(('off_slide_nfulnl_logger', out.get('nfulnl_logger')))
    if 'loggers' in out:
        fields.append(('off_slide_loggers_0_1', out['loggers'] + 0x10))
    bid = find_boot_id(kernel, base, img_end_va)
    if bid is None:
        print('# WARNING: boot_id bss pattern not found', file=sys.stderr)
    else:
        fields.append(('off_slide_boot_id', bid))
    fields.append(('off_system_unbound_wq', out.get('system_unbound_wq')))
    fields.append(('off_call_usermodehelper_exec_work',
                   out.get('call_usermodehelper_exec_work')))

    print()
    for k, v in fields:
        if v is None:
            print(f'  .{k}=/* MISSING */,')
        else:
            print(f'  .{k}=0x{v:08X},')


if __name__ == '__main__':
    main()
