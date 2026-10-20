#!/usr/bin/env python3
"""LuxGram Fix 63: verify every .so inside an APK/AAB is 16KB page-size aligned.

Google Play hard requirement (since Nov 2025, apps targeting Android 15+):
all LOAD segments of every native library must be 16KB-aligned.
"""
import struct
import sys
import zipfile


def minalign(d):
    if d[:4] != b"\x7fELF":
        return None
    is64 = d[4] == 2
    if is64:
        phoff = struct.unpack_from("<Q", d, 0x20)[0]
        phentsize = struct.unpack_from("<H", d, 0x36)[0]
        phnum = struct.unpack_from("<H", d, 0x38)[0]
        off_fmt, al_fmt, al_off = "<I", "<Q", 0x30
    else:
        phoff = struct.unpack_from("<I", d, 0x1C)[0]
        phentsize = struct.unpack_from("<H", d, 0x2A)[0]
        phnum = struct.unpack_from("<H", d, 0x2C)[0]
        off_fmt, al_fmt, al_off = "<I", "<I", 0x1C
    worst = None
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type = struct.unpack_from(off_fmt, d, base)[0]
        if p_type != 1:  # PT_LOAD
            continue
        p_align = struct.unpack_from(al_fmt, d, base + al_off)[0]
        worst = p_align if worst is None else min(worst, p_align)
    return worst


bad = 0
for path in sys.argv[1:]:
    zf = zipfile.ZipFile(path)
    sos = [n for n in zf.namelist() if n.endswith(".so")]
    print(f"{path}: {len(sos)} native libs")
    for n in sos:
        a = minalign(zf.read(n))
        if a is None:
            continue
        ok = a >= 16384
        bad += 0 if ok else 1
        print(f"  {'OK ' if ok else 'BAD'}  {n}  align={a}")
assert bad == 0, f"{bad} native libs are NOT 16KB-aligned — Play upload would be rejected"
print("16KB alignment: ALL PASS")
