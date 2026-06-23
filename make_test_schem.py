#!/usr/bin/env python3
"""
make_test_schem.py  —  Synthetic Sponge Schematic generator + verifier for map2mc.

WHY THIS EXISTS
---------------
Phase 2 of the map2mc mod is a SchematicReader for Sponge Schematic v2/v3. Its test
gate must NOT depend on a downloaded (copyrighted) landmark or a flaky external site.
This script generates a tiny, self-made 5x5x5 `.schem` you can:
  * load in WorldEdit / Litematica / the mod to confirm the reader works,
  * commit as a test resource (it's your own creation — no license issues),
  * read as a byte-accurate reference for the exact format the Java reader must parse.

It can also VERIFY any `.schem` (yours or a converted landmark) by parsing it back and
printing the palette + a bottom-up ASCII view, so it doubles as a reference parser.

SPONGE SCHEMATIC FORMAT (the parts that matter)
-----------------------------------------------
A `.schem` is GZIP-compressed, big-endian NBT.

  v2:  root compound is NAMED "Schematic" and holds the fields directly:
         Version(int=2), DataVersion(int), Width/Height/Length(short),
         Offset(int[3], optional), PaletteMax(int), Palette(compound),
         BlockData(byte[]), BlockEntities(list), Metadata(compound, optional)

  v3:  root compound is UNNAMED ("") and holds ONE child, "Schematic", which holds:
         Version(int=3), DataVersion(int), Width/Height/Length(short),
         Offset(int[3], optional), Metadata(compound, optional),
         and a "Blocks" compound = { Palette(compound), Data(byte[]), BlockEntities(list) }
       (Note the renames vs v2: data moved under "Blocks", "BlockData" -> "Data",
        "PaletteMax" dropped.)

  Palette: maps a FULL blockstate string -> int id, e.g.
           "minecraft:air" -> 0, "minecraft:oak_log[axis=y]" -> 3
  Block data: one VARINT (LEB128, 7 bits/byte, MSB = continue) per block, in order
           index = (y * Length + z) * Width + x      # x fastest, then z, then y
  Dimensions: Width = X span, Height = Y span, Length = Z span.

Your Java reader in the mod should mirror exactly this: gunzip -> NbtIo.read ->
detect v2 vs v3 by presence of "Blocks" -> read palette + varint data in this order.
"""

import argparse
import gzip
import struct
import sys

# ── NBT tag type ids ──────────────────────────────────────────────────────────
TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 0, 1, 2, 3, 4
TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING = 5, 6, 7, 8
TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = 9, 10, 11, 12

# ── The synthetic build: a 5x5x5 "mini tower" that reads clearly bottom-up ─────
#   y=0           : full base plate           (polished diorite)
#   y=1..3        : four corner legs          (waxed exposed copper)
#   y=4 (top)     : platform                  (waxed exposed copper)
#                   + 1 center block          (oak_log[axis=y]) to exercise a
#                                              palette entry that carries a property
# Palette deliberately echoes the real Eiffel palette seen in community builds.
W = H = L = 5
DATA_VERSION = 3953  # 1.21. Not load-bearing for a synthetic fixture; edit to match
                     # your target version if a strict reader complains.

AIR = "minecraft:air"
BASE = "minecraft:polished_diorite"
METAL = "minecraft:waxed_exposed_copper"
LOG = "minecraft:oak_log[axis=y]"


def block_at(x: int, y: int, z: int) -> str:
    if y == 0:
        return BASE
    if y == H - 1:
        return LOG if (x == 2 and z == 2) else METAL
    if x in (0, W - 1) and z in (0, L - 1):
        return METAL
    return AIR


# ── tiny NBT WRITER (payload builders return raw bytes) ───────────────────────
def _name(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def named(tag: int, name: str, payload: bytes) -> bytes:
    """A named tag = type byte + name + payload (the form used inside compounds)."""
    return bytes([tag]) + _name(name) + payload


def p_int(v):   return struct.pack(">i", v)
def p_short(v): return struct.pack(">h", v)
def p_string(s): return _name(s)
def p_byte_array(b): return struct.pack(">i", len(b)) + bytes(b)
def p_int_array(a): return struct.pack(">i", len(a)) + b"".join(struct.pack(">i", x) for x in a)
def p_compound(named_tags): return b"".join(named_tags) + bytes([TAG_END])
def p_list(elem_tag, payloads): return bytes([elem_tag]) + struct.pack(">i", len(payloads)) + b"".join(payloads)


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | 0x80 if n else b)
        if not n:
            return bytes(out)


# ── build the schematic ───────────────────────────────────────────────────────
def build(version: int) -> bytes:
    # 1) flatten blocks in (y, z, x) order and build the palette on the fly
    palette = {AIR: 0}
    indices = []
    for y in range(H):
        for z in range(L):
            for x in range(W):
                b = block_at(x, y, z)
                if b not in palette:
                    palette[b] = len(palette)
                indices.append(palette[b])
    data = b"".join(varint(i) for i in indices)

    pal_payload = p_compound([named(TAG_INT, k, p_int(v)) for k, v in palette.items()])
    meta = p_compound([named(TAG_STRING, "Name", p_string("map2mc_test_fixture"))])
    empty_be = p_list(TAG_COMPOUND, [])  # empty BlockEntities list

    if version == 3:
        blocks = p_compound([
            named(TAG_COMPOUND, "Palette", pal_payload),
            named(TAG_BYTE_ARRAY, "Data", p_byte_array(data)),
            named(TAG_LIST, "BlockEntities", empty_be),
        ])
        schem = p_compound([
            named(TAG_INT, "Version", p_int(3)),
            named(TAG_INT, "DataVersion", p_int(DATA_VERSION)),
            named(TAG_SHORT, "Width", p_short(W)),
            named(TAG_SHORT, "Height", p_short(H)),
            named(TAG_SHORT, "Length", p_short(L)),
            named(TAG_INT_ARRAY, "Offset", p_int_array([0, 0, 0])),
            named(TAG_COMPOUND, "Metadata", meta),
            named(TAG_COMPOUND, "Blocks", blocks),
        ])
        # v3: unnamed root compound wrapping a single "Schematic" child
        root = named(TAG_COMPOUND, "", p_compound([named(TAG_COMPOUND, "Schematic", schem)]))
    elif version == 2:
        schem = p_compound([
            named(TAG_INT, "Version", p_int(2)),
            named(TAG_INT, "DataVersion", p_int(DATA_VERSION)),
            named(TAG_SHORT, "Width", p_short(W)),
            named(TAG_SHORT, "Height", p_short(H)),
            named(TAG_SHORT, "Length", p_short(L)),
            named(TAG_INT_ARRAY, "Offset", p_int_array([0, 0, 0])),
            named(TAG_INT, "PaletteMax", p_int(len(palette))),
            named(TAG_COMPOUND, "Palette", pal_payload),
            named(TAG_BYTE_ARRAY, "BlockData", p_byte_array(data)),
            named(TAG_LIST, "BlockEntities", empty_be),
            named(TAG_COMPOUND, "Metadata", meta),
        ])
        # v2: root compound is named "Schematic"
        root = named(TAG_COMPOUND, "Schematic", schem)
    else:
        raise ValueError("version must be 2 or 3")

    return gzip.compress(root)


# ── tiny NBT READER (only the types we emit) — reference for the Java parser ───
class _R:
    def __init__(self, data): self.d, self.i = data, 0
    def u1(self): v = self.d[self.i]; self.i += 1; return v
    def s2(self): v = struct.unpack_from(">h", self.d, self.i)[0]; self.i += 2; return v
    def u2(self): v = struct.unpack_from(">H", self.d, self.i)[0]; self.i += 2; return v
    def s4(self): v = struct.unpack_from(">i", self.d, self.i)[0]; self.i += 4; return v
    def s8(self): v = struct.unpack_from(">q", self.d, self.i)[0]; self.i += 8; return v
    def f4(self): v = struct.unpack_from(">f", self.d, self.i)[0]; self.i += 4; return v
    def f8(self): v = struct.unpack_from(">d", self.d, self.i)[0]; self.i += 8; return v
    def string(self):
        n = self.u2(); s = self.d[self.i:self.i + n].decode("utf-8"); self.i += n; return s

    def payload(self, tag):
        if tag == TAG_BYTE: v = self.u1(); return v - 256 if v >= 128 else v
        if tag == TAG_SHORT: return self.s2()
        if tag == TAG_INT: return self.s4()
        if tag == TAG_LONG: return self.s8()
        if tag == TAG_FLOAT: return self.f4()
        if tag == TAG_DOUBLE: return self.f8()
        if tag == TAG_STRING: return self.string()
        if tag == TAG_BYTE_ARRAY:
            n = self.s4(); b = self.d[self.i:self.i + n]; self.i += n; return b
        if tag == TAG_INT_ARRAY:
            n = self.s4(); return [self.s4() for _ in range(n)]
        if tag == TAG_LONG_ARRAY:
            n = self.s4(); return [self.s8() for _ in range(n)]
        if tag == TAG_LIST:
            et = self.u1(); n = self.s4(); return [self.payload(et) for _ in range(n)]
        if tag == TAG_COMPOUND:
            out = {}
            while True:
                t = self.u1()
                if t == TAG_END: return out
                name = self.string()
                out[name] = self.payload(t)
        raise ValueError(f"unhandled tag {tag}")

    def root(self):
        t = self.u1(); assert t == TAG_COMPOUND, "root must be a compound"
        _ = self.string()  # root name ("" for v3, "Schematic" for v2)
        return self.payload(TAG_COMPOUND)


def verify(path: str) -> None:
    with open(path, "rb") as f:
        raw = gzip.decompress(f.read())
    root = _R(raw).root()
    schem = root.get("Schematic", root)  # v3 nests under "Schematic"; v2 is the root
    w, h, l = schem["Width"], schem["Height"], schem["Length"]
    if "Blocks" in schem:               # v3
        ver, blocks = 3, schem["Blocks"]
        palette, data = blocks["Palette"], blocks["Data"]
    else:                               # v2
        ver, palette, data = 2, schem["Palette"], schem["BlockData"]

    inv = {v: k for k, v in palette.items()}
    # decode varints
    idx, i, n = [], 0, w * h * l
    for _ in range(n):
        val, shift = 0, 0
        while True:
            b = data[i]; i += 1
            val |= (b & 0x7F) << shift
            if not (b & 0x80): break
            shift += 7
        idx.append(val)

    sym = {AIR: ".", BASE: "#", METAL: "C", LOG: "L"}
    print(f"  format     : Sponge v{ver}")
    print(f"  dimensions : {w} x {h} x {l}  (W x H x L)")
    print(f"  dataVersion: {schem.get('DataVersion')}")
    print(f"  palette    : " + ", ".join(f"{v}={k}" for k, v in sorted(palette.items(), key=lambda kv: kv[1])))
    counts = {}
    for v in idx:
        counts[inv[v]] = counts.get(inv[v], 0) + 1
    print("  block count: " + ", ".join(f"{sym.get(k, '?')}({k.split(':')[-1]})={c}" for k, c in counts.items()))
    print("  bottom-up layers (y=0 first):")
    for y in range(h):
        print(f"    y={y}")
        for z in range(l):
            row = "".join(sym.get(inv[idx[(y * l + z) * w + x]], "?") for x in range(w))
            print(f"      {row}")


def main():
    ap = argparse.ArgumentParser(description="Generate or verify a Sponge .schem test fixture.")
    ap.add_argument("--version", type=int, choices=(2, 3), default=3, help="Sponge schematic version to write (default 3)")
    ap.add_argument("--out", default="map2mc_test_fixture.schem", help="output path")
    ap.add_argument("--verify", metavar="FILE", help="verify/inspect an existing .schem instead of generating")
    args = ap.parse_args()

    if args.verify:
        print(f"Verifying {args.verify}")
        verify(args.verify)
        return

    blob = build(args.version)
    with open(args.out, "wb") as f:
        f.write(blob)
    print(f"Wrote {args.out}  (Sponge v{args.version}, {len(blob)} bytes gzipped)")
    print("Round-trip check:")
    verify(args.out)


if __name__ == "__main__":
    sys.exit(main())
