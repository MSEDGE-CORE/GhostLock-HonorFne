#!/usr/bin/env python3
"""Extract probe_vals[]/probe_names[] from the built probe.ko (statically,
no device needed) and compare against the running-kernel gold standard
(src_ghostlock/src/devices/fne/offsets.h + STRUCT_OFFSETS_5_4).

NOTE: a .ko is ET_REL — section sh_addr is 0 and symbol st_value is an
offset within its st_shndx section. We read via section file offsets."""
import re
import struct
import sys

# Gold standard: running 5.4.289-qgki-gf6cb1f712a6e.
# Sources: (D) = verified by disassembling kernel_880302.elf;
#          (W) = verified at runtime by the W1/W2 exploit chain.
# Corrections vs the generic STRUCT_OFFSETS_5_4 table (which comes from
# other devices and is WRONG for FNE on these fields):
#   task.tasks    0x4A0 -> 0x5C0  (D: __do_SAK init_task+0x5C0 walk)
#   task.seccomp  0x770 -> 0x948  (D: __secure_computing [x8,#0x948])
#   mm.owner      dropped (unverified generic value; unused by KSU)
GOLD = {
    "task.prio": 0x11C, "task.normal_prio": 0x124,
    "task.rt_priority": 0x128, "task.static_prio": 0x120,
    "task.on_rq": 0x118, "task.policy": 0x548, "task.sched_class": 0x130,
    "task.sched_task_group": 0x438,
    "task.pi_lock": 0x96C, "task.pi_waiters": 0x978,
    "task.pi_top_task": 0x988,
    "task.pid": 0x6C0, "task.tgid": 0x6C4,
    "task.real_parent": 0x6D0,
    "task.atomic_flags": 0x688,
    "task.real_cred": 0x878, "task.cred": 0x880,
    "task.comm": 0x890, "task.tasks": 0x5C0,
    "task.seccomp": 0x948, "task.usage": 0x30,
    "task.mm": 0x610, "task.signal": 0x8D8,
    "task.thread_group": 0x770, "task.thread_node": 0x780,
    "task.pid_links": 0x740,
    "signal.thread_head": 0x10, "signal.nr_threads": 0x8,
    "signal.sigcnt": 0x0, "signal.live": 0x4,
    "cred.uid": 0x4, "cred.securebits": 0x24,
    "cred.cap_inheritable": 0x28, "cred.security": 0x78,
    "fops.llseek": 0x08, "fops.read": 0x10, "fops.write": 0x18,
    "fops.read_iter": 0x20, "fops.write_iter": 0x28,
    "fops.unlocked_ioctl": 0x50, "fops.compat_ioctl": 0x58,
    "fops.mmap": 0x60, "fops.open": 0x70, "fops.release": 0x80,
    "fops.splice_read": 0xC8, "fops.show_fdinfo": 0xE0,
}


def parse_elf(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:4] == b"\x7fELF" and data[4] == 2, "not ELF64"
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        hdr = e_shoff + i * e_shentsize
        sh_name, sh_type = struct.unpack_from("<II", data, hdr)
        sh_link = struct.unpack_from("<I", data, hdr + 0x28)[0]
        sh_entsize = struct.unpack_from("<Q", data, hdr + 0x38)[0]
        sh_offset, sh_size = struct.unpack_from("<QQ", data, hdr + 24)
        sections.append((sh_name, sh_type, sh_offset, sh_size,
                         hdr, sh_link, sh_entsize))
    return data, sections


def find_symbol(data, sections, name):
    """Return (st_shndx, st_value, st_size) for a named symbol in ET_REL."""
    for idx, sec in enumerate(sections):
        sh_type, sh_offset, sh_size, sh_link, sh_entsize = \
            sec[1], sec[2], sec[3], sec[5], sec[6]
        if sh_type != 2:  # SHT_SYMTAB
            continue
        _, _, strtab_off, strtab_size = sections[sh_link][:4]
        entsize = sh_entsize or 24
        for i in range(sh_size // entsize):
            e = sh_offset + i * entsize
            st_name = struct.unpack_from("<I", data, e)[0]
            st_shndx = struct.unpack_from("<H", data, e + 6)[0]
            st_value, st_size = struct.unpack_from("<QQ", data, e + 8)
            if st_shndx == 0 or st_name >= strtab_size:
                continue
            end = data.index(b"\x00", strtab_off + st_name)
            if data[strtab_off + st_name:end].decode() == name:
                return st_shndx, st_value, st_size
    return None, None, None


def read_symbol(data, sections, shndx, value, size):
    _, _, sh_offset, sh_size = sections[shndx][:4]
    assert value + size <= sh_size, \
        f"symbol {value:#x}+{size:#x} exceeds section size {sh_size:#x}"
    return data[sh_offset + value:sh_offset + value + size]


def main():
    path = sys.argv[1]
    data, sections = parse_elf(path)
    v_shndx, v_val, v_size = find_symbol(data, sections, "probe_vals")
    n_shndx, n_val, n_size = find_symbol(data, sections, "probe_names")
    assert v_shndx is not None, "probe_vals not found in symtab"
    assert n_shndx is not None, "probe_names not found in symtab"
    raw = read_symbol(data, sections, v_shndx, v_val, v_size)
    names = [n for n in
             read_symbol(data, sections, n_shndx, n_val, n_size)
             .split(b"\x00") if n]
    vals = [v[0] for v in struct.iter_unpack("<Q", raw)]
    assert len(vals) == len(names), \
        f"entry mismatch: {len(vals)} vals vs {len(names)} names"
    assert vals[0] == 0x0000393832454E46, f"bad magic {vals[0]:#x}"

    m = re.search(rb"vermagic=([^\x00]+)", data)
    print(f"probe.ko: {len(vals) - 1} offsets"
          + (f", vermagic={m.group(1).decode()}" if m else ""))

    print(f"{'name':32s} {'probe(147+cfg)':>14s} {'gold(289)':>10s}  verdict")
    drift = checked = 0
    for name, val in zip(names[1:], vals[1:]):
        name = name.decode()
        g = GOLD.get(name)
        if g is None:
            print(f"{name:32s} {val:#14x} {'-':>10s}  (no gold)")
        else:
            checked += 1
            if val == g:
                print(f"{name:32s} {val:#14x} {g:#10x}  MATCH")
            else:
                drift += 1
                print(f"{name:32s} {val:#14x} {g:#10x}"
                      f"  *** DRIFT {val - g:+d} ***")
    print("---")
    print(f"checked {checked} gold entries, {drift} drift")
    if drift == 0:
        print("STRUCT DRIFT: ZERO on all probed fields — 147 source + device "
              "config reproduces the running 289 layout.")
    else:
        print("STRUCT DRIFT DETECTED — see *** lines above.")
        sys.exit(1)


if __name__ == "__main__":
    main()
