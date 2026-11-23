"""Replace only the default map in an indexed foundation; retain all runtime objects."""
from pathlib import Path
import argparse, concurrent.futures, hashlib, json, re, zipfile, zlib
from export_foundation import BLOCK, PACK, digest, encoded

def replace_map(previous, archive, output, resume=False):
    if output.exists():
        marker=output/'foundation-export.json'
        if not resume or not marker.is_file() or json.loads(marker.read_text('utf8')).get('defaultMap',{}).get('id')!='merged-netdecode-20261121':raise ValueError('Use a new owned output directory')
    descriptor=json.loads((previous/'bootstrap/foundation.json').read_bytes())
    indices=[p for p in descriptor['indices'] if p['component']!='map']
    objects={o['sha256']:o for o in descriptor['objects']}
    used=set()
    for pin in indices:
        raw=(previous/pin['path']).read_bytes()
        if digest(raw)!=pin['sha256']:raise ValueError('Previous index hash mismatch')
        for entry in json.loads(zlib.decompress(raw)):
            used.update(p['object'] for p in entry['pieces'])
    output.mkdir(parents=True,exist_ok=resume)
    rows=[];pack=bytearray();pending=[];new_objects=[]
    def flush():
        if not pack:return
        h=digest(pack);path=output/'bootstrap/objects'/f'{h}.pack';path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(pack)
        new_objects.append({'sha256':h,'size':len(pack)})
        for piece in pending:piece['object']=h
        pack.clear();pending.clear()
    archive_hash=hashlib.sha256()
    with archive.open('rb')as source:
        for block in iter(lambda:source.read(BLOCK),b''):archive_hash.update(block)
    with zipfile.ZipFile(archive)as z:
        info=json.loads(z.read('Map/map-info.json'))
        if info.get('productionSavesUsed') is not False:raise ValueError('Map is not confirmed capture-only')
        inventory={row['file']:row['sha256'] for row in json.loads(z.read('SHA256.json'))}
        # Retain data/compatibility metadata only, excluding installer code, raw provenance and local audit paths.
        names=sorted(n for n in z.namelist()if re.fullmatch(r'Map/region/r\.-?\d+\.-?\d+\.szr',n)or n in ('Map/registry.json','Map/object-codecs.json','Map/map-info.json'))
        if sum('/region/'in n for n in names)!=info['stats']['regions']:raise ValueError('Incomplete region inventory')
        def load(name):
            body=z.read(name)
            expected=inventory.get(name)
            if isinstance(expected,dict):expected=expected.get('sha256')
            if not expected or digest(body)!=expected:raise ValueError('Map file hash mismatch: '+name)
            if name=='Map/map-info.json':
                public={k:info[k]for k in ('format','date','stats','missingTerrainChunks','mapBoundsChunks','productionSavesUsed','deduplication','terrainSelection','objectDigest')if k in info}
                public.update(id='merged-netdecode-20261121',sourceArchiveSha256=archive_hash.hexdigest())
                body=encoded(public)
            chunks=[]
            for at in range(0,len(body),BLOCK):
                raw=body[at:at+BLOCK];compressed=zlib.compress(raw,6)
                chunks.append((compressed if len(compressed)<len(raw)else raw,len(raw),'zlib'if len(compressed)<len(raw)else'raw'))
            return name[4:],digest(body),len(body),chunks
        with concurrent.futures.ThreadPoolExecutor(max_workers=4)as pool:
            for name,h,size,chunks in pool.map(load,names):
                pieces=[]
                for body,decoded,codec in chunks:
                    if len(pack)+len(body)>PACK:flush()
                    p={'object':'','offset':len(pack),'length':len(body),'decodedSize':decoded,'codec':codec}
                    pack.extend(body);pending.append(p);pieces.append(p)
                rows.append({'path':'map/'+name,'sha256':h,'size':size,'pieces':pieces})
    flush()
    raw=encoded(rows);body=zlib.compress(raw,9);name='map/index-merged-netdecode-20261121-0000.json.z';path=output/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(body)
    indices.append({'path':name,'sha256':digest(body),'size':len(body),'decodedSize':len(raw),'files':len(rows),'component':'map'})
    retained=[objects[h]for h in used]
    seed={'schemaVersion':1,'legacyBaseId':descriptor['legacyBaseId'],'fileCount':sum(p['files']for p in indices),'expandedBytes':descriptor['expandedBytes'],'indices':indices,'objects':sorted(retained+new_objects,key=lambda x:x['sha256'])}
    old_map_bytes=0
    for pin in descriptor['indices']:
        if pin['component']=='map':old_map_bytes+=sum(r['size']for r in json.loads(zlib.decompress((previous/pin['path']).read_bytes())))
    seed['expandedBytes']+=sum(r['size']for r in rows)-old_map_bytes
    identity='foundation-'+digest(encoded(seed))[:24];raw=encoded({'id':identity,**seed});descriptor_path=f'bootstrap/foundations/{identity}.json';path=output/descriptor_path;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
    pin={'id':identity,'path':'bootstrap/foundation.json','sha256':digest(raw),'size':len(raw)}
    metadata={'id':'merged-netdecode-20261121','date':info['date'],'stats':info['stats'],'sourceArchiveSha256':archive_hash.hexdigest(),'registrySha256':next(r['sha256']for r in rows if r['path']=='map/registry.json'),'codecsSha256':next(r['sha256']for r in rows if r['path']=='map/object-codecs.json'),'missingTerrainChunks':len(info['missingTerrainChunks']),'existingWorldsRebased':False}
    (output/'map/default.json').write_bytes(encoded(metadata))
    report={'foundation':pin,'descriptorPath':descriptor_path,'files':seed['fileCount'],'expandedBytes':seed['expandedBytes'],'retainedRuntimeObjects':len(retained),'newMapObjects':len(new_objects),'newMapObjectBytes':sum(o['size']for o in new_objects),'mapFiles':len(rows),'mapBytes':sum(r['size']for r in rows),'transferBytes':sum(o['size']for o in seed['objects'])+sum(i['size']for i in indices),'defaultMap':metadata}
    (output/'foundation-export.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--previous',type=Path,required=True);p.add_argument('--archive',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--resume-owned',action='store_true');a=p.parse_args();replace_map(a.previous.resolve(),a.archive.resolve(),a.output.resolve(),a.resume_owned)
