"""One-time import of immutable game dependencies into ordinary Git input files.

This is NOT the build command. scripts/build_player.py builds from these Git files
without access to an installed developer workspace or a previous local-game bundle.
"""
from pathlib import Path
import argparse,hashlib,json,re,struct,zipfile

ROOT=Path(__file__).resolve().parents[1]
PART=16*1024*1024
def sha(raw):return hashlib.sha256(raw).hexdigest()
def run(runtime,server,map_root,java,game,packed):
    if (ROOT/'bootstrap/input-manifest.json').exists():raise ValueError('Input manifest already exists; review an intentional revision instead')
    files={};objects={}
    def add(logical,body,kind):
        rows=[]
        target_base=('map/data/'+logical[4:])if logical.startswith('map/')else'bootstrap/inputs/'+logical
        for at in range(0,len(body),PART):
            name=target_base if len(body)<=PART else target_base+f'.part-{at//PART:04}'
            part=body[at:at+PART];p=ROOT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(part)
            files[name]={'size':len(part),'sha256':sha(part),'kind':kind};rows.append(name)
        objects[logical]={'size':len(body),'sha256':sha(body),'parts':rows}
    def file(logical,path,kind):add(logical,path.read_bytes(),kind)
    for name in ('game.jar','libraries.jar'):
        file('runtime/'+name,packed/name,'immutable-game-abi')
    for p in sorted((runtime/'dependencies').glob('*.jar')):
        if p.name.startswith('imgui-java-')and'1.82.2'not in p.name:continue
        file('runtime/dependencies/'+p.name,p,'vendor-library')
    for p in sorted((runtime/'natives').glob('*.dll')):
        if any(mark in p.name for mark in ('.before-','.reshade-','.maven-')):continue
        file('runtime/natives/'+p.name,p,'vendor-native')
    for p in sorted((runtime/'natives/fmod/x64').glob('*.dll')):file('runtime/natives/fmod/x64/'+p.name,p,'vendor-native')
    for dest,origin in {'eos/x64/EOSSDK-Win64-Shipping.dll':'EOSSDK-Win64-Shipping.dll','eos/x64/ald.dll':'ald.dll','stove/StoveC.dll':'StoveC.dll'}.items():
        objects['runtime/natives/'+dest]=dict(objects['runtime/natives/'+origin])
    for p in sorted(java.rglob('*')):
        if p.is_file():file('java17/'+p.relative_to(java).as_posix(),p,'vendor-java-runtime')
    for name in ('configs.xeon','boxes.xeon'):file('assets/'+name,game/'modassets/assets/bundle'/name,'original-game-resource-archive')
    # Only fixture files addressed by production Java are eligible. No fixture maps,
    # replay/oracle captures or historical registry copies are included.
    text='\n'.join(p.read_text(encoding='utf-8-sig')for p in (ROOT/'server/src/main/java').rglob('*.java'))
    names=set(re.findall(r'([A-Za-z0-9][A-Za-z0-9_.-]*\.(?:json(?:\.gz|\.zst)?|tsv|bin|b64))',text))
    for context in (server/'fixtures').glob('native-scene-*-context.json'):
        value=json.loads(context.read_bytes())
        for row in value.get('sources',[value.get('repairItemSource')]):
            if row is not None:names.add(row['path'].replace('\\','/').rsplit('/',1)[-1])
    names.update(json.loads((server/'fixtures/native-gameplay-manifest.json').read_bytes())['files_sha256'])
    fixture_files=[p for p in (server/'fixtures').iterdir()if p.is_file()and p.name in names]
    for directory in ('native-resources','native-weather-resources'):
        fixture_files += [p for p in (server/'fixtures'/directory).rglob('*')if p.is_file()]
    for p in sorted(fixture_files):
        body=p.read_bytes()
        if p.name in ('native-scene-incident-context.json','native-scene-encaged-context.json','native-scene-clock-context.json'):
            data=json.loads(body)
            for row in data.get('sources',[data.get('repairItemSource')]):
                if row is None:continue
                leaf=row['path'].replace('\\','/').rsplit('/',1)[-1]
                row['path']=leaf if leaf=='native-scene-incident-weapon.json'else'../source-resources/'+leaf
            body=json.dumps(data,ensure_ascii=False,indent=2).encode()
        add('server/fixtures/'+p.relative_to(server/'fixtures').as_posix(),body,'server-runtime-metadata')
    for directory in ('registries','source-resources'):
        for p in sorted((server/directory).iterdir()):
            if p.is_file():file('server/'+directory+'/'+p.name,p,'server-runtime-metadata')
    manifest=json.loads((server/'results/runtime-manifest.json').read_bytes());manifest['client_runtime']='runtime';manifest['fixture_source']='registries/client-current-item-states.json';manifest['release']='canonical-standard-jvm-abi'
    add('server/results/runtime-manifest.json',json.dumps(manifest,indent=2).encode(),'server-runtime-metadata')
    for p in sorted((ROOT/'.local/server-resources').rglob('*')):
        if p.is_file():file('server-resources/'+p.relative_to(ROOT/'.local/server-resources').as_posix(),p,'game-derived-server-data')
    with zipfile.ZipFile(ROOT/'.local/delivery-inputs/base-resource-agent.jar')as z:
        for n in z.namelist():
            if n.startswith('gamecompat/')and n.endswith(('.properties','.tsv','.txt')):add('agent-resources/'+n,z.read(n),'native-abi-metadata')
    for p in sorted((map_root/'region').glob('r.*.*.szr')):file('map/region/'+p.name,p,'default-map-region')
    for name in ('registry.json','object-codecs.json'):
        file('map/'+name,map_root/name,'default-map-metadata')
    info=json.loads((map_root/'map-info.json').read_bytes());public={k:info[k]for k in ('format','date','stats','missingTerrainChunks','mapBoundsChunks','productionSavesUsed','deduplication','terrainSelection','objectDigest')if k in info}
    add('map/map-info.json',json.dumps(public,ensure_ascii=False,indent=2).encode(),'default-map-metadata')
    # Preserve only compatibility differences, rather than the unpacked archives.
    from Crypto.Cipher import AES
    import lz4.block
    for filename,kind in [('configs.xeon','configs'),('boxes.xeon','boxes')]:
        raw=(game/'modassets/assets/bundle'/filename).read_bytes();dec=AES.new(bytes.fromhex('1d6fd3d1f32b78d71e4758fd10c764ac'),AES.MODE_CBC,iv=bytes.fromhex('6d57c656e19a02e9c86d02e4fdb1975d')).decrypt(raw);padding=dec[-1]
        if not 1<=padding<=16 or dec[-padding:]!=bytes([padding])*padding:raise ValueError('Archive padding')
        dec=dec[:-padding];start,count=struct.unpack_from('>ii',dec);at=8;entries={}
        for _ in range(count):
            n=struct.unpack_from('>H',dec,at)[0];at+=2;name=dec[at:at+n].decode();at+=n;size,packed_size,offset=struct.unpack_from('>iii',dec,at);at+=12;entries[name]=(size,packed_size,offset)
        for p in sorted((server/'resources'/kind).rglob('*')):
            if not p.is_file()or p.name in ('_manifest.json','configs.xeon'):continue
            name=p.relative_to(server/'resources'/kind).as_posix();body=p.read_bytes();entry=entries.get(name)
            if entry is not None:
                size,compressed,offset=entry;original=lz4.block.decompress(dec[start+offset:start+offset+compressed],uncompressed_size=size)
                if sha(body)==sha(original):continue
            add('asset-overrides/'+kind+'/'+name,body,'explicit-resource-compatibility-override')
    value={'schemaVersion':1,'runtimeLayout':'shared-jars-xeon-v1','originalGameCode':'Immutable normalized game ABI, not source for the proprietary game. Our modules are compiled from this repository.','files':files,'objects':objects}
    p=ROOT/'bootstrap/input-manifest.json';p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'gitInputFiles':len(files),'objects':len(objects),'bytes':sum(v['size']for v in files.values()),'runtimeFixtureFiles':len(fixture_files)},indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for n in ('runtime','server','map','java','game','packed'):p.add_argument('--'+n,type=Path,required=True)
    a=p.parse_args();run(a.runtime.resolve(),a.server.resolve(),getattr(a,'map').resolve(),a.java.resolve(),a.game.resolve(),a.packed.resolve())
