"""Export a complete immutable game foundation as indexed, deduplicated small objects."""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,zlib,concurrent.futures,collections

BLOCK=4*1024*1024
PACK=24*1024*1024
SHARD=1600
def digest(raw):return hashlib.sha256(raw).hexdigest()
def encoded(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
def safe(name):
    parts=name.replace('\\','/').split('/')
    if any(p in ('','.','..')or ':'in p for p in parts):raise ValueError('Unsafe game path')
    return '/'.join(parts)
def files(root,component):
    for directory,dirs,names in os.walk(root,followlinks=False):
        dirs.sort()
        folder=Path(directory)
        for d in list(dirs):
            p=folder/d
            if p.is_symlink()or getattr(p.lstat(),'st_file_attributes',0)&0x400:raise ValueError('Linked game directory')
            if folder==root and d.lower()in ('jre','data','logs','saves','reports','cache','.git','temp'):dirs.remove(d)
        for name in sorted(names):
            p=folder/name
            relative=p.relative_to(root).as_posix()
            if component=='client' and relative.split('/')[0]not in ('compat','mods'):continue
            if component=='server' and relative.split('/')[0]not in ('server.jar','server.template.properties','lib','fixtures','source-resources','results','registries','resources'):continue
            if component=='bootstrap/runtime' and relative.startswith('dependencies/imgui-java-') and '1.82.2'not in relative:continue
            if p.is_symlink()or getattr(p.lstat(),'st_file_attributes',0)&0x400:raise ValueError('Linked game file')
            if name.lower()in ('admin-token.txt','agent.properties','profile.json','files.sha256.json')or p.suffix.lower()in('.pem','.key','.pfx','.log','.sjournal','.dmp'):continue
            yield component+'/'+safe(p.relative_to(root).as_posix()),p
def export(bundle,java,bridge,output,resume=False):
    if not (bundle/'Bootstrap/runtime/game.jar').is_file():raise ValueError('Prepare a player-only shared jar runtime before exporting')
    if output.exists()and not resume:raise ValueError('Foundation output must be a new empty owned directory')
    output.mkdir(parents=True,exist_ok=resume)
    pack=bytearray();pending=[];objects=[];unique={};entries=[]
    def flush():
        if not pack:return
        h=digest(pack);p=output/'bootstrap/objects'/f'{h}.pack';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(pack)
        objects.append({'sha256':h,'size':len(pack)})
        for row in pending:row['object']=h
        pack.clear();pending.clear()
    def piece_data(h,body,size,codec):
        if h in unique:return unique[h]
        if len(pack)+len(body)>PACK:flush()
        row={'object':'','offset':len(pack),'length':len(body),'decodedSize':size,'codec':codec}
        pack.extend(body);pending.append(row);unique[h]=row;return row
    def load(item):
        logical,p=item;h=hashlib.sha256();pieces=[];size=0
        with p.open('rb')as stream:
            while raw:=stream.read(BLOCK):
                h.update(raw);size+=len(raw);zipped=zlib.compress(raw,6);body=zipped if len(zipped)<len(raw)else raw;pieces.append((digest(raw),body,len(raw),'zlib'if body is zipped else'raw'))
        return logical,h.hexdigest(),size,pieces
    roots=[('bootstrap/java17',java),('bootstrap/runtime',bundle/'Bootstrap/runtime'),('client',bundle/'Client'),('server',bundle/'Server'),('map',bundle/'Map')]
    for component,root in roots:
        if not root.is_dir():raise ValueError('Missing foundation component: '+component)
        with concurrent.futures.ThreadPoolExecutor(max_workers=24)as pool:
            source=iter(files(root,component));pending_files=collections.deque()
            for _ in range(72):
                try:pending_files.append(pool.submit(load,next(source)))
                except StopIteration:break
            while pending_files:
                logical,h,size,chunks=pending_files.popleft().result();entries.append({'path':logical,'size':size,'sha256':h,'pieces':[piece_data(*v)for v in chunks]})
                try:pending_files.append(pool.submit(load,next(source)))
                except StopIteration:pass
                if len(entries)%20000==0:print('Indexed',len(entries),'files',flush=True)
    raw=bridge.read_bytes();zipped=zlib.compress(raw,6);entries.append({'path':'bootstrap/bscp-bridge.jar','size':len(raw),'sha256':digest(raw),'pieces':[piece_data(digest(raw),zipped,len(raw),'zlib')]})
    flush();indices=[]
    for component in ['bootstrap','client','server','map']:
        rows=sorted((r for r in entries if r['path'].startswith(component+'/')),key=lambda r:r['path'])
        for at in range(0,len(rows),SHARD):
            selected=rows[at:at+SHARD];raw=encoded(selected);body=zlib.compress(raw,9);name=f'{component}/index-{digest(raw)[:20]}-{at//SHARD:04}.json.z';p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(body)
            indices.append({'path':name,'sha256':digest(body),'size':len(body),'decodedSize':len(raw),'files':len(selected),'component':component})
    seed={'schemaVersion':1,'legacyBaseId':bundle.name,'fileCount':len(entries),'expandedBytes':sum(x['size']for x in entries),'indices':indices,'objects':sorted(objects,key=lambda x:x['sha256'])}
    identity='foundation-'+digest(encoded(seed))[:24];descriptor={'id':identity,**seed};raw=encoded(descriptor)
    (output/'bootstrap/foundation.json').write_bytes(raw)
    lock={'id':identity,'path':'bootstrap/foundation.json','sha256':digest(raw),'size':len(raw)}
    (output/'foundation-export.json').write_text(json.dumps({'foundation':lock,'files':len(entries),'expandedBytes':seed['expandedBytes'],'transferBytes':sum(x['size']for x in objects)+sum(x['size']for x in indices),'objects':len(objects)},indent=2),'utf8')
    print(json.dumps({'foundation':lock,'files':len(entries),'expandedBytes':seed['expandedBytes'],'transferBytes':sum(x['size']for x in objects)+sum(x['size']for x in indices),'objects':len(objects)},indent=2));return lock
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--bridge',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--resume-owned',action='store_true');a=p.parse_args();export(a.bundle.resolve(),a.java.resolve(),a.bridge.resolve(),a.output.resolve(),a.resume_owned)
