#!/usr/bin/env python3
"""Assemble target boot partition from an incremental OTA (v915183), verify against new_hash."""
import sys, struct, subprocess, hashlib, brotli

def curl_range(url, s, e, tries=4):
    for a in range(tries):
        p = subprocess.run(['curl', '-sS', '-m', '300', '-r', f'{s}-{e}', url], capture_output=True)
        if p.returncode == 0 and len(p.stdout) >= e - s + 1:
            return p.stdout[:e - s + 1]
    return None

def varint(b, i):
    x = 0; s = 0
    while True:
        c = b[i]; i += 1; x |= (c & 0x7f) << s
        if not c & 0x80:
            return x, i
        s += 7

def fields(b):
    i = 0; n = len(b)
    while i < n:
        key, i = varint(b, i); fn = key >> 3; wt = key & 7
        if wt == 0: v, i = varint(b, i)
        elif wt == 1: v = b[i:i+8]; i += 8
        elif wt == 2: ln, i = varint(b, i); v = b[i:i+ln]; i += ln
        elif wt == 5: v = b[i:i+4]; i += 4
        else: raise ValueError(wt)
        yield fn, wt, v

def extents_of(v):
    ex = []
    for efn, ewt, ev in fields(v):
        d = {}
        for f2, w2, v2 in fields(ev): d[f2] = v2
        ex.append((d.get(1, 0), d.get(2, 0)))
    return ex

def bsdf2_decode(patch, src):
    assert patch[:5] == b'BSDF2', patch[:8].hex()
    A, B, C = struct.unpack('<QQQ', patch[8:32])
    ctrl = brotli.decompress(patch[32:32+A])
    diff = brotli.decompress(patch[32+A:32+A+B])
    extra = brotli.decompress(patch[32+A+B:])
    out = bytearray(C)
    ci = di = ei = old = new = 0
    while new < C:
        x, y, z = struct.unpack('<qqq', ctrl[ci:ci+24]); ci += 24
        if x:
            if old < 0 or old + x > len(src):
                raise ValueError(f'src underrun old={old} x={x} srclen={len(src)}')
            d = diff[di:di+x]; di += x
            s = src[old:old+x]
            for i in range(x):
                out[new+i] = (s[i] + d[i]) & 0xFF
            old += x; new += x
        if y:
            out[new:new+y] = extra[ei:ei+y]; ei += y; new += y
        if z < 0: old -= z + 2**63
        else: old += z
    assert di == len(diff) and ei == len(extra), (di, len(diff), ei, len(extra))
    return bytes(out)

def main(vid, old_path, out_path):
    import xml.etree.ElementTree as ET
    flist_dir = os.environ.get('FNE_FILELIST_DIR',
                              '/tmp/deveco/fne_scan/filelists')
    fl = open(f'{flist_dir}/v{vid}.xml', 'rb').read()
    root = ET.fromstring(fl)
    poff = int(root.find('VirtualAB').findtext('payloadOffset'))
    url = f'https://update.hihonorcdn.com/TDS/data/bl/files/v{vid}/f1/full/' + root.find('vendorInfo').get('package')
    hdr = curl_range(url, poff, poff + 23)
    msz = struct.unpack('>Q', hdr[12:20])[0]
    ssz = struct.unpack('>I', hdr[20:24])[0]
    man = curl_range(url, poff + 24, poff + 24 + msz - 1)
    data_base = poff + 24 + msz + ssz
    boot = None
    for fn, wt, v in fields(man):
        if fn == 13 and wt == 2:
            name = None; f6 = f7 = None; ops = []
            for pfn, pwt, pv in fields(v):
                if pfn == 1: name = pv.decode()
                elif pfn == 6:
                    for sfn, swt, sv in fields(pv):
                        if sfn == 2: f6 = sv.hex()
                elif pfn == 7:
                    for sfn, swt, sv in fields(pv):
                        if sfn == 2: f7 = sv.hex()
                elif pfn == 8:
                    op = {'t': None, 'off': None, 'len': None, 'src': [], 'dst': []}
                    for ofn, owt, ov in fields(pv):
                        if ofn == 1: op['t'] = ov
                        elif ofn == 2: op['off'] = ov
                        elif ofn == 3: op['len'] = ov
                        elif ofn == 4 and owt == 2:
                            d = {}
                            for f2, w2, v2 in fields(ov): d[f2] = v2
                            op['src'].append((d.get(1, 0), d.get(2, 0)))
                        elif ofn == 6 and owt == 2:
                            d = {}
                            for f2, w2, v2 in fields(ov): d[f2] = v2
                            op['dst'].append((d.get(1, 0), d.get(2, 0)))
                    ops.append(op)
            if name == 'boot':
                boot = {'ops': ops, 'old_hash': f6, 'new_hash': f7}
    old = open(old_path, 'rb').read()
    print('old sha', hashlib.sha256(old).hexdigest())
    print('manifest old', boot['old_hash'], 'new', boot['new_hash'], 'nops', len(boot['ops']))
    assert hashlib.sha256(old).hexdigest() == boot['old_hash'], 'source hash mismatch'
    bs = 4096
    new = bytearray(old)
    for i, op in enumerate(boot['ops']):
        t = op['t']
        if t == 6:
            for sb, c in op['dst']:
                new[sb*bs:(sb+c)*bs] = b'\x00' * (c*bs)
        elif t == 4:
            src = b''.join(old[sb*bs:(sb+c)*bs] for sb, c in op['src'])
            pos = 0
            for sb, c in op['dst']:
                ln = c * bs
                new[sb*bs:sb*bs+ln] = src[pos:pos+ln]; pos += ln
            assert pos == len(src), (pos, len(src))
        elif t == 10:
            blob = curl_range(url, data_base + op['off'], data_base + op['off'] + op['len'] - 1)
            src = b''.join(old[sb*bs:(sb+c)*bs] for sb, c in op['src'])
            out = bsdf2_decode(blob, src)
            pos = 0
            for sb, c in op['dst']:
                ln = c * bs
                new[sb*bs:sb*bs+ln] = out[pos:pos+ln]; pos += ln
            assert pos == len(out), (pos, len(out))
        else:
            print('op', i, 'unhandled type', t); continue
    h = hashlib.sha256(new).hexdigest()
    print('new sha', h, 'match:', h == boot['new_hash'])
    open(out_path, 'wb').write(new)

if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2], sys.argv[3])
