import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent/'deps'))
import numpy as np, zstandard, struct
from map_tiles import decode_tiles

def read_chunk(path,cx,cz):
    source=path.read_bytes()
    mask,add,nraw=struct.unpack_from(">III",source)
    if not 0 < zstandard.frame_content_size(source[12:]) <= 1 << 24:
        raise ValueError("Некорректный размер распакованного чанка")
    d=zstandard.ZstdDecompressor().decompress(source[12:],max_output_size=1<<24)
    k=mask.bit_count()
    assert nraw==k*12288+add.bit_count()*2048+256 and nraw<=len(d)
    data=np.frombuffer(d,dtype=np.uint8);ids=np.zeros((256,16,16),np.uint16);meta=np.zeros(ids.shape,np.uint8)
    levels=[i for i in range(16) if mask>>i&1]
    def nibs(start):
        v=data[start:start+2048]
        return np.stack((v&15,v>>4),axis=1).reshape(16,16,16)
    j=0
    for i,lev in enumerate(levels):
        sl=slice(lev*16,lev*16+16)
        ids[sl]=data[i*4096:(i+1)*4096].reshape(16,16,16)
        meta[sl]=nibs(k*4096+i*2048)
        if add>>lev&1:
            ids[sl]+=nibs(k*12288+j*2048).astype(np.uint16)*256;j+=1
    tiles, end = decode_tiles(d, nraw, cx, cz)
    if end != len(d):
        raise ValueError(f"Unexpected trailing tile data in chunk {cx},{cz}")
    return ids, meta, tiles
