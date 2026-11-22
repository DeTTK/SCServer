"""Read a saved SCServer SZR1 map using the bounds and frame layout of RegionTerrain."""
import io
import json
import struct
import tempfile
import uuid
import zlib
from pathlib import Path

import zstandard
from export_area import save, scene


def take(stream, size):
    if not 0 <= size <= 32 << 20:
        raise ValueError("Недопустимый размер записи")
    data = stream.read(size)
    if len(data) != size:
        raise ValueError("Неполная запись карты")
    return data


def number(stream, fmt):
    return struct.unpack(fmt, take(stream, struct.calcsize(fmt)))[0]


def varint(stream):
    value = 0
    for shift in range(0, 35, 7):
        byte = number(stream, ">B")
        value |= (byte & 127) << shift
        if byte < 128:
            return value
    raise ValueError("Некорректная длина записи")


def frames(stream, container=False):
    count = number(stream, ">I") if container else varint(stream)
    if count > 65536:
        raise ValueError("Слишком много объектов в чанке")
    for _ in range(count):
        yield take(stream, varint(stream))


def scene_frames(stream):
    compressed = take(stream, varint(stream))
    if compressed:
        size = zstandard.frame_content_size(compressed)
        if not 0 < size <= 32 << 20:
            raise ValueError("Размер сцены вне диапазона")
        yield from frames(io.BytesIO(zstandard.ZstdDecompressor().decompress(compressed)))
    raw = number(stream, ">B")
    number(stream, ">B")
    if raw == 1:
        yield from frames(stream, True)
    elif raw != 0:
        raise ValueError("Некорректный контейнер сцены")


def object_row(frame):
    stream = io.BytesIO(frame)
    kind, flags, _ = struct.unpack(">HIB", take(stream, 7))
    if flags & 128:
        take(stream, 16)
    if flags & 0x8000:
        take(stream, number(stream, ">H"))
    for flag in (0x4000, 0x2000):
        if flags & flag:
            take(stream, 4)
    take(stream, 4)
    identity = uuid.UUID(bytes=take(stream, 16))
    affine = struct.unpack(">12f", take(stream, 48))
    matrix = [*affine[:3], 0, *affine[3:6], 0, *affine[6:9], 0, *affine[9:], 1]
    take(stream, 1)  # activation
    if flags & 0x1000:
        take(stream, number(stream, ">H"))
    if flags & 0x800:
        take(stream, 1)
    take(stream, 1)  # fixed collision mode
    variant = number(stream, ">B")
    return kind, identity, matrix, variant


def chunk(folder, x, z):
    rx, rz = x // 32, z // 32
    path = folder / "region" / f"r.{rx}.{rz}.szr"
    with path.open("rb") as f:
        if struct.unpack(">4sii", take(f, 12)) != (b"SZR1", rx, rz):
            raise ValueError(f"Повреждён заголовок {path.name}")
        f.seek(16 + ((z % 32) * 32 + x % 32) * 16)
        offset, size, crc = struct.unpack(">QII", take(f, 16))
        if offset < 16400:
            raise ValueError(f"В карте нет чанка {x}, {z}")
        f.seek(offset)
        record = take(f, size)
    if zlib.crc32(record) != crc or struct.unpack_from(">iiB", record) != (x, z, 1):
        raise ValueError(f"Ошибка проверки чанка {x}, {z}")
    for at in range(33, 37):
        if record[at+8:at+12] == b"\x28\xb5\x2f\xfd":
            raw, packed = struct.unpack_from(">II", record, at)
            stream = io.BytesIO(record[at+8:])
            terrain = record[9:17] + struct.pack(">I", raw) + take(stream, packed)
            return terrain, scene_frames(stream)
    raise ValueError(f"Не найдены блоки чанка {x}, {z}")


def convert(folder, a, b, output):
    low, high = tuple(map(min, zip(a,b))), tuple(map(max, zip(a,b)))
    w,h,l = (hi-lo+1 for lo,hi in zip(low,high))
    chunk_count = (high[0]//16-low[0]//16+1)*(high[2]//16-low[2]//16+1)
    if chunk_count > 4096:
        raise ValueError("Разделите выделение на области до 4096 чанков")
    if low[1]<0 or high[1]>255 or max(w,l)>32767 or w*h*l>16777216:
        raise ValueError("Проверьте границы области (Y 0–255, до 16 млн блоков)")
    registry = json.loads((folder/"registry.json").read_bytes())
    names = {e["network_id"]:e["stable_name"] for e in registry["entries"]}
    seen = set()
    with tempfile.TemporaryDirectory(prefix="sc-area-") as temp:
        job = Path(temp)
        with (job/"objects.tsv").open("w", encoding="utf8") as objects:
            for x in range(low[0]//16, high[0]//16+1):
                for z in range(low[2]//16, high[2]//16+1):
                    terrain, objects_in_chunk = chunk(folder,x,z)
                    (job/f"{x}_{z}.chunk").write_bytes(terrain)
                    for frame in objects_in_chunk:
                        kind, identity, matrix, variant = object_row(frame)
                        if identity in seen or not all(lo<=v<hi+1 for lo,v,hi in zip(low,matrix[12:15],high)):
                            continue
                        name = names.get(kind, f"unknown_{kind}")
                        objects.write(f"{name}\t{json.dumps(matrix)}\t{identity}\t{variant}\n")
                        seen.add(identity)
        bounds = (*low,*high)
        return save(job,bounds,scene(job,bounds),output,output.stem,False)
