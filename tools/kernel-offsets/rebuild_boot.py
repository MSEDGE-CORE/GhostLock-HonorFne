#!/usr/bin/env python3
"""Rebuild a boot partition image from a full OTA payload, verify against manifest hash."""
import sys, os, struct, subprocess, hashlib, gzip, bz2, lzma

def curl_range(url, s, e, tries=4):
    for a in range(tries):
        p = subprocess.run(['curl', '-sS', '-m', '180', '-r', f'{s}-{e}', url], capture_output=True)
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

def decompress_auto(data):
    if data[:6] == b'\xfd7zXZ\x00': return lzma.decompress(data)
    if data[:3] == b'BZh': return bz2.decompress(data)
    if data[:2] == b'\x1f\x8b': return gzip.decompress(data)
    return data

def main(vid, out_path):
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
    parts = {}
    for fn, wt, v in fields(man):
        if fn == 13 and wt == 2:
            name = None; ops = []; f7 = None; psize = None
            for pfn, pwt, pv in fields(v):
                if pfn == 1: name = pv.decode('utf8', 'replace')
                elif pfn == 7 and pwt == 2:
                    for sfn, swt, sv in fields(pv):
                        if sfn == 2: f7 = sv.hex()
                        elif sfn == 1: psize = sv
                elif pfn == 8:
                    op = {'t': None, 'off': None, 'len': None, 'src': [], 'dst': []}
                    for ofn, owt, ov in fields(pv):
                        if ofn == 1: op['t'] = ov
                        elif ofn == 2: op['off'] = ov
                        elif ofn == 3: op['len'] = ov
                        elif ofn == 4:
                            ex = {}
                            for efn, ewt, ev in fields(ov): ex[efn] = ev
                            op['src'].append(ex)
                        elif ofn == 6:
                            ex = {}
                            for efn, ewt, ev in fields(ov): ex[efn] = ev
                            op['dst'].append(ex)
                    ops.append(op)
            parts[name] = {'ops': ops, 'new_hash': f7, 'size': psize}
    p = parts['boot']
    print('boot size', p['size'], 'new_hash', p['new_hash'], 'nops', len(p['ops']))
    bs = 4096
    img = bytearray(p['size'])
    covered = set()
    ops = sorted(p['ops'], key=lambda o: (o['dst'][0].get(1, 0) if o['dst'] else 0))
    for idx, o in enumerate(ops):
        t = o['t']
        dlen = sum(e.get(2, 0) * bs for e in o['dst'])
        if t == 7 or (t is not None and o['len'] in (None, 0) and not o['dst']):
            print(f'op{idx} t={t} ZERO? dst={o["dst"]}')
            for e in o['dst']:
                for b in range(e.get(1,0), e.get(1,0)+e.get(2,0)): covered.add(b)
            continue
        data = curl_range(url, data_base + o['off'], data_base + o['off'] + o['len'] - 1)
        if data is None:
            print(f'op{idx} FETCH FAIL'); continue
        try:
            raw = decompress_auto(data)
        except Exception as ex:
            print(f'op{idx} decomp fail: {ex} magic={data[:4].hex()}'); continue
        if len(raw) != dlen:
            print(f'op{idx} t={t} WARN raw={len(raw)} dstlen={dlen}')
        pos = 0
        for e in o['dst']:
            off = e.get(1, 0) * bs; ln = e.get(2, 0) * bs
            seg = raw[pos:pos+ln]
            if len(seg) < ln: seg = seg + b'\x00' * (ln - len(seg))
            img[off:off+ln] = seg
            for b in range(e.get(1,0), e.get(1,0)+e.get(2,0)): covered.add(b)
            pos += ln
    total_blocks = p['size'] // bs
    missing = total_blocks - len(covered)
    print('blocks covered', len(covered), '/', total_blocks, 'missing', missing)
    h = hashlib.sha256(img).hexdigest()
    print('sha256(rebuilt):', h)
    print('match:', h == p['new_hash'])
    open(out_path, 'wb').write(img)

if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2])
