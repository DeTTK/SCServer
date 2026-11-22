"""Export an authoritative Stalonex selection for the Axiom legacy importer."""
from collections import Counter
from pathlib import Path
import json, math, os, struct, uuid
import numpy as np
import nbtlib as n
from map_tiles import donor_to_nbt
from terrain import read_chunk
from pose import placement

ROOT=Path(__file__).resolve().parent
BLOCKS={int(k):v for k,v in json.loads((ROOT/'block-map.json').read_bytes()).items()}
DECOR=json.loads((ROOT/'decor-map.json').read_bytes())
COVERS={i:(v[1],v[0]) for i,v in BLOCKS.items()}

def count_blocks(job, bounds):
    x0,y0,z0,x1,y1,z1 = bounds
    present = supported = 0
    for cx in range(x0//16, x1//16+1):
        for cz in range(z0//16, z1//16+1):
            ids, _, _ = read_chunk(job/f'{cx}_{cz}.chunk', cx, cz)
            cells = ids[y0:y1+1, max(0,z0-cz*16):min(16,z1-cz*16+1),
                        max(0,x0-cx*16):min(16,x1-cx*16+1)]
            values, counts = np.unique(cells, return_counts=True)
            for value, count in zip(values, counts):
                if value:
                    present += int(count)
                    if int(value) in BLOCKS:
                        supported += int(count)
    return present, supported

LIQUID_SIZE = {"liquid_plane_2x5": (2, 5), "liquid_plane_5x5": (5, 5), "liquid_plane_10x10": (10, 10)}

def liquid_pose(matrix, size, box):
    # Donor planes begin at local Z=0. Our panels are centred on their pivot.
    width, depth = size
    matrix = matrix.copy()
    matrix[3, :3] += matrix[2, :3] * (depth / 2)
    if box is None:
        return matrix
    # Cut horizontal, axis-aligned ponds at the selected block faces.
    axes = np.array([matrix[0,:3], matrix[2,:3]])
    main = np.argmax(abs(axes), axis=1)
    if set(main) != {0, 2} or any(np.max(np.abs(np.delete(a, i))) > 1e-5 * abs(a[i]) for a,i in zip(axes,main)):
        return matrix
    if not box[1] <= matrix[3,1] < box[4]+1:
        return None
    for local,world,span in zip((0,2),main,(width,depth)):
        half = abs(matrix[local,world]) * span / 2
        low = max(matrix[3,world]-half,box[world])
        high = min(matrix[3,world]+half,box[3+world]+1)
        if high <= low:
            return None
        matrix[3,world] = (low+high)/2
        matrix[local,:3] *= (high-low)/(2*half)
    return matrix

# Donor editor helpers are not scenery. Keep legacy catalogue IDs for older saves.
TECHNICAL_HELPERS = {
    "exclusive_player_collidable_2x2",
    "spot_light_source",
    "stair_spec_a_br_metalFence",
    "stair_spec_a_br_wood",
    "stair_spec_a_concrete",
    "stair_spec_a_metalFence",
    "stena_zapret_2x2",
    "stena_zapret_2x8",
    "stena_zapret_trace_through_2x4",
}

EXCLUDED_SCENERY = {
    "looting_bag_sports",
    "looting_jacket_stalker",
    "looting_med_bag",
    "looting_sapernabor",
    "looting_tainiki_bag",
    "looting_tech_cabinet",
    "sparks_kz_1",
}

# These legacy meshes are authored facing the opposite direction to their donor meshes.
# Compose the local half-turn before decomposing the instance, including on tilted poles.
LOCAL_HALF_TURN = frozenset((6009, 6010, 6012, 6013, 6220, 6224, 6226, 6230, 6324, 6326, 6334, 6335))

def scene(job, box=None):
    parts=[]
    paint_file = job / "appearance.tsv"
    paint = {}
    if paint_file.exists():
        for line in paint_file.read_text(encoding="utf8").splitlines():
            key, *channels = line.split("\t")
            paint[key] = ",".join(channels)
    variants_by_object = json.loads((ROOT / "paint-variants.json").read_bytes())
    for line in (job/'objects.tsv').read_text(encoding='utf8').splitlines():
        name,matrix,key,variant=line.split('\t')
        if name in TECHNICAL_HELPERS or name in EXCLUDED_SCENERY:
            continue
        variants=DECOR.get(name,{})
        entry=variants.get(variant)
        matrix=np.array(json.loads(matrix)).reshape(4,4)
        if entry in LOCAL_HALF_TURN:
            matrix[[0, 2], :3] *= -1
        if name in LIQUID_SIZE:
            matrix = liquid_pose(matrix, LIQUID_SIZE[name], box)
            if matrix is None:
                continue
        row=placement(matrix,{'id':entry},uuid.UUID(key).int % (2**63-1) or 1) if entry else None
        if row is not None and name in variants_by_object and key in paint:
            painted = variants_by_object[name].get(paint[key])
            if painted is not None:
                row["Entry"] = painted
        if row is None:
            name += ' — ' + ('нет соответствия модели или варианта' if not entry else 'форма вне возможностей клиента')
        parts.append((key,name,row))
    return parts

SLOPE_FORMS = (34, 32, 30, 28, 38, 36, 42, 40, 0, 3, 1, 2, 9, 10, 8, 11)
SLOPE_MATERIALS = (2, 3, 13, 12, 1, 110, 87, 49, 82)

def convert_slopes(ids, metadata):
    authored = ((ids >= 2415) & (ids <= 2423)) | ((ids >= 2445) & (ids <= 2453))
    for y, z, x in np.argwhere(authored):
        y, z, x = int(y), int(z), int(x)
        source = int(ids[y,z,x])
        md = int(metadata[y,z,x])
        form = SLOPE_FORMS[(md & 3) * 4 + (md >> 2 & 3)]
        double = source >= 2445
        material = SLOPE_MATERIALS[source - (2445 if double else 2415)]
        shapes = [(y, 2405 if double else 2401, 0 if double else form)]
        if double and y < 255 and ids[y+1,z,x] == 0:
            shapes.append((y+1, 2401, form))
        for height, block, shape in shapes:
            ids[height,z,x] = block
            metadata[height,z,x] = 0
            yield x,height,z,dict(type=0, x=x, y=height, z=z, covers=[0]*6+[material],
                colors=[65535]*7, exponent=0, overlays=[0]*7, rot=0, packcovers=0,
                ctm=[0]*6, data=shape, ext=None)

def atomic_save(path, tag):
    path.parent.mkdir(parents=True,exist_ok=True)
    tmp=path.with_name('.'+uuid.uuid4().hex+'.tmp')
    try:
        tag.save(tmp)
        n.load(tmp)
        os.replace(tmp,path)
    finally:
        tmp.unlink(missing_ok=True)

def part_tag(row,origin,group):
    t=n.Compound(EntryId=n.Int(row['Entry']))
    for i,k in enumerate('XYZ'):
        t[k]=n.Int(row['Cell'+k]-origin[i])
    if group:
        t.update(Registry=n.String(''),Placement=n.Byte(1),Collision=n.Int(0))
    else:
        t.update(id=n.String('StalclansDecorPlacement'),StalclansSchema=n.Int(1),DecorFree=n.Byte(1))
    for k in ('Yaw','Pitch','Roll','OffX','OffY','OffZ','Scale'):
        t[('' if group else 'Decor')+k]=n.Float(row[k])
    if row.get('Curve'):
        payload=n.ByteArray(np.frombuffer(row['Curve'],dtype='i1'))
        if group:t['Tile']=n.Compound(StalclansDecorCurve=payload)
        else:t['StalclansCurve']=payload
    return t

def save(job,box,objects,path,name,group=False):
    x0,y0,z0,x1,y1,z1=box
    w,h,l=(x1-x0+1,y1-y0+1,z1-z0+1)
    if max(w,h,l)>32767:
        raise ValueError("Сторона схемы превышает предел формата (32767 блоков).")
    origin=(x0+w//2,y0+h//2,z0+l//2)
    selected=[o[2] for o in objects if o[2]]
    warnings=Counter('Декор: '+o[1] for o in objects if not o[2])
    parts=n.List[n.Compound]([part_tag(row,origin,group) for row in selected])
    if group:
        if not (1<=len(parts)<=1024):raise ValueError('Для пресета выбери от 1 до 1024 доступных объектов.')
        atomic_save(path,n.File(dict(Version=n.Int(1),Name=n.String(name),Parts=parts),gzipped=True))
    else:
        arr=np.zeros((h,l,w),np.uint16); meta=np.zeros_like(arr,dtype='u1')
        tiles=[];mapping={}
        for cx in range(x0//16,x1//16+1):
            for cz in range(z0//16,z1//16+1):
                ids,md,records=read_chunk(job/f'{cx}_{cz}.chunk',cx,cz)
                for x,y,z,record in convert_slopes(ids,md):
                    records[cx*16+x,y,cz*16+z]=record
                xa,xb=max(x0,cx*16),min(x1+1,cx*16+16)
                za,zb=max(z0,cz*16),min(z1+1,cz*16+16)
                target=np.s_[:,za-z0:zb-z0,xa-x0:xb-x0]
                source=np.s_[y0:y1+1,za-cz*16:zb-cz*16,xa-cx*16:xb-cx*16]
                values=ids[source].copy()
                metadata=md[source].copy()
                # Vanilla fern is withdrawn on the shard; use the donor fern from the local flora catalogue.
                fern=(values==31) & (metadata==2)
                values[fern]=3808
                metadata[fern]=0
                original=values.copy()
                for raw in np.unique(values):
                    k=int(raw)
                    if not k:continue
                    block=BLOCKS.get(k)
                    if block:
                        previous=mapping.setdefault(block[0],k)
                        if previous!=k:values[original==k]=previous
                    else:
                        warnings[f'Блок {k}']+=int(np.count_nonzero(values==k));values[values==k]=0
                metadata[values==0]=0
                arr[target]=values;meta[target]=metadata
                for (x,y,z),rec in records.items():
                    if not(xa<=x<xb and y0<=y<=y1 and za<=z<zb):continue
                    host=int(ids[y,z%16,x%16])
                    if host not in BLOCKS:continue
                    tile,issues=donor_to_nbt(rec,COVERS,source_host=host)
                    warnings.update(issues);tile.update(x=n.Int(x-x0),y=n.Int(y-y0),z=n.Int(z-z0));tiles.append(tile)
        raw=arr.ravel();upper=(raw>>8).astype('u1');hi=upper[::2].copy();hi[:len(upper)//2]|=upper[1::2]<<4
        root=dict(Materials=n.String('Alpha'),Width=n.Short(w),Height=n.Short(h),Length=n.Short(l),Blocks=n.ByteArray(raw.astype('u1').view('i1')),AddBlocks=n.ByteArray(hi.view('i1')),Data=n.ByteArray(meta.ravel().view('i1')),TileEntities=n.List[n.Compound](tiles),Entities=n.List[n.Compound](),SchematicaMapping=n.Compound({k:n.Short(v) for k,v in mapping.items()}),StalclansAreaDecor=parts)
        atomic_save(path,n.File(root,root_name='Schematic',gzipped=True))
    return len(selected),warnings
