"""Decode Stalonex carpenter records and translate supported attributes to Anvil NBT."""
from collections import Counter
from pathlib import Path
import struct
import sys
sys.path.insert(0, str(Path(__file__).parent / 'deps'))
import nbtlib as n


def decode_tiles(raw, offset, cx, cz):
    """Return (coordinate -> tile, end offset); offset points at the tile count."""
    p = offset
    def read(fmt):
        nonlocal p
        size = struct.calcsize(fmt)
        if p + size > len(raw):
            raise ValueError('truncated tile data at byte %d' % p)
        result = struct.unpack_from(fmt, raw, p)
        p += size
        return result
    count, = read('>I')
    if count > (len(raw) - p) // 20:
        raise ValueError('impossible tile count %d' % count)
    records = {}
    for _ in range(count):
        typ, x, y, z, flags = read('>BiiiB')
        if typ not in (0, 1) or x // 16 != cx or z // 16 != cz or not 0 <= y < 256:
            raise ValueError('invalid tile header %r' % ((typ, x, y, z, flags),))
        if flags & 128:
            raise ValueError('unknown tile flag 0x80')
        covers = list(read('>7H')) if flags & 8 else [0] * 6 + list(read('>H'))
        colors = list(read('>7H')) if flags & 2 else [65535] * 7
        exponent = read('>H')[0] if flags & 4 else 0
        overlays = list(read('>7B')) if flags & 1 else [0] * 7
        rot = read('>H')[0] if flags & 16 else 0
        pack = read('>B')[0] if flags & 32 else 0
        ctm = list(read('>6B')) if flags & 64 else [0] * 6
        data = read('>i')[0]
        ext = read('>i')[0] if typ == 1 else None
        key = (x, y, z)
        if key in records:
            raise ValueError('duplicate tile coordinate %r' % (key,))
        records[key] = dict(type=typ, x=x, y=y, z=z, flags=flags, covers=covers,
                            colors=colors, exponent=exponent, overlays=overlays,
                            rot=rot, packcovers=pack, ctm=ctm, data=data, ext=ext)
    return records, p


def _signed_short(value):
    return ((value + 32768) & 65535) - 32768


def donor_to_nbt(record, mappedcover, target_block_id=None, source_host=None):
    """Return (tile Compound, warning Counter). Cover lookup maps IDs to (ID,name).

    Source-only fields remain under StalclansDonor* tags for future conversion.
    The target block identity is chosen by the caller, not by the record type.
    """
    t = record
    warnings = Counter()
    out = n.Compound(id=n.String('TileEntityCarpentersProBuilder' if t['type'] == 1 else 'TileEntityCarpentersBlock'),
                     x=n.Int(t['x']), y=n.Int(t['y']), z=n.Int(t['z']), cbMetadata=n.Int(t['data']))
    if t['type'] == 1:
        out['data_ext'] = n.Int(t['ext'])
    attrs = []
    for face, value in enumerate(t['covers']):
        block, damage = value & 4095, value >> 12
        if not block:
            continue
        target = mappedcover.get(block)
        if target is None:
            warnings['unmapped_cover'] += 1
            continue
        item_id, name = target
        attrs.append(n.Compound(cbAttribute=n.Byte(face), id=n.Short(_signed_short(item_id)),
                                Damage=n.Short(damage), Count=n.Byte(1), cbUniqueId=n.String(name)))
    for face, rgb565 in enumerate(t['colors']):
        if rgb565 == 65535:
            continue
        # Donor converts each component to float then truncates RGB*255.
        red = int(((rgb565 >> 11) & 31) / 31 * 255)
        green = int(((rgb565 >> 5) & 63) / 63 * 255)
        blue = int((rgb565 & 31) / 31 * 255)
        color = (red << 16) | (green << 8) | blue
        attrs.append(n.Compound(cbAttribute=n.Byte(7 + face), id=n.Short(351), Damage=n.Short(0),
                                Count=n.Byte(1), cbUniqueId=n.String('minecraft:dye'),
                                tag=n.Compound(StalclansCarpenterColor=n.Int(color))))
    # Donor class_5618 registry and class_5844 overlay renderer.
    overlay_items = {1:(295,'wheat_seeds'), 2:(332,'snowball'), 3:(287,'string'),
                     4:(106,'vine'), 5:(296,'wheat'), 6:(39,'brown_mushroom')}
    for face,kind in enumerate(t['overlays']):
        if not kind: continue
        if kind not in overlay_items:
            warnings['unmapped_overlay'] += 1
            continue
        item,name = overlay_items[kind]
        attrs.append(n.Compound(cbAttribute=n.Byte(14+face), id=n.Short(item),
                     Count=n.Byte(1), Damage=n.Short(0), cbUniqueId=n.String('minecraft:'+name)))
    out['cbAttrList'] = n.List[n.Compound](attrs)
    # Donor combines base rotation with CTM's top bits for the six faces.
    rot = t['rot'] & 0x3fff
    for face, value in enumerate(t['ctm']):
        shift = face * 2
        effective = ((rot >> shift & 3) + (value >> 6 & 3)) & 3
        rot = (rot & ~(3 << shift)) | effective << shift
    if rot:
        out['StalclansCarpenterRot'] = n.Int(rot)
    if t['exponent']:
        out['StalclansCarpenterGain'] = n.Int(t['exponent'] & 0x3fff)
    # Keep original fields even where a lossy/partial translation exists.
    out['StalclansDonorCovers'] = n.IntArray(t['covers'])
    out['StalclansDonorColors'] = n.IntArray(t['colors'])
    out['StalclansDonorRot'] = n.Int(t['rot'])
    out['StalclansDonorCTM'] = n.IntArray(t['ctm'])
    out['StalclansDonorOverlays'] = n.IntArray(t['overlays'])
    out['StalclansDonorPackCovers'] = n.Int(t['packcovers'])
    if source_host is not None:
        out['StalclansDonorSource'] = n.String(str(source_host))

    if any(v & 63 for v in t['ctm']): warnings['unmapped_ctm'] += sum(bool(v & 63) for v in t['ctm'])
    if t['packcovers']:
        out['StalclansCarpenterFlat'] = n.Int(t['packcovers'] & 127)
    return out, warnings
