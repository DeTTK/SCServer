"""Run the source-built server against private inputs, with a separate persistent development world."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess
from build import ROOT,tool,dependencies

def atomic_copy(source,target):
    target.parent.mkdir(parents=True,exist_ok=True)
    temporary=target.with_name(target.name+'.incoming')
    shutil.copyfile(source,temporary)
    temporary.replace(target)

def prop(path):
    value=str(path).replace('\\','/').replace(':','\\:')
    encoded=value.encode('utf-16-be')
    units=[int.from_bytes(encoded[i:i+2],'big') for i in range(0,len(encoded),2)]
    return ''.join(chr(unit) if unit<128 else '\\u%04x'%unit for unit in units)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle',type=Path,required=True,help='Compatible private Server folder')
    p.add_argument('--map',type=Path,required=True,help='Read-only original Map folder')
    p.add_argument('--state',type=Path,default=ROOT/'.local/server-state',help='Separate persistent state directory')
    p.add_argument('--host',default='127.0.0.1',help='Bind and advertised IP; default loopback')
    p.add_argument('--port',type=int,default=29544,help='Game HTTP port; gateway +1, world +2, admin +100, dev +104')
    a=p.parse_args();bundle=a.bundle.resolve();world=a.map.resolve()
    if not 1024<=a.port<=65431:p.error('Port must be 1024..65431')
    import ipaddress
    try:ipaddress.ip_address(a.host)
    except ValueError:p.error('--host must be an IP address')
    runtime=bundle.parent/'Bootstrap/runtime' if (bundle.parent/'Bootstrap/runtime/game.jar').is_file() else bundle/'runtime'
    for parent,names in [(bundle,['fixtures','source-resources','results/runtime-manifest.json','registries/client-current-item-states.json','registries/live-gameobjects.json','registries/live-object-codecs.json']),(world,['region','registry.json'])]:
        for name in names:
            if not (parent/name).exists():p.error('Missing private input: '+str(parent/name))
    jar=ROOT/'build/server.jar'
    if not jar.is_file():p.error('Build the server with --private-resources first')
    import zipfile
    with zipfile.ZipFile(jar) as archive:
        for name,digest in json.loads((ROOT/'config/private-resources.lock.json').read_text('utf8')).items():
            if name not in archive.namelist() or hashlib.sha256(archive.read(name)).hexdigest()!=digest:p.error('Rebuild: python scripts/build.py server --private-resources')
    state=a.state.resolve();state.mkdir(parents=True,exist_ok=True)
    # Hold an OS lock through preparation and the entire Java lifetime.
    with (state/'session.lock').open('a+b') as lock:
        lock.seek(0,2)
        if lock.tell()==0:lock.write(b'0');lock.flush()
        lock.seek(0)
        try:
            if os.name=='nt':
                import msvcrt
                msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError:p.error('Another source server is using .local/server-state')
        for name in ['fixtures','source-resources','results']:
            for source in (bundle/name).iterdir():
                if source.is_file():atomic_copy(source,state/name/source.name)
        for name in ['native-resources','native-weather-resources']:
            for source in (bundle/'fixtures'/name).rglob('*'):
                if source.is_file():atomic_copy(source,state/'fixtures'/name/source.relative_to(bundle/'fixtures'/name))
        for name in ['native-scene-incident-context.json','native-scene-encaged-context.json','native-scene-clock-context.json']:
            path=state/'fixtures'/name;value=json.loads(path.read_text('utf-8-sig'))
            for row in value.get('sources',[value.get('repairItemSource')]):
                leaf=row['path'].replace('\\','/').split('/')[-1]
                resource=state/('fixtures' if leaf=='native-scene-incident-weapon.json' else 'source-resources')/leaf
                if hashlib.sha256(resource.read_bytes()).hexdigest()!=row['sha256']:p.error('Private context source changed: '+leaf)
                row['path']=resource.as_posix()
            path.write_text(json.dumps(value,indent=2),encoding='utf8')
        config=(ROOT/'desktop/server.template.properties').read_text('utf8')
        replacements={'ROOT':prop(bundle),'MAP':prop(world),'HOST':a.host,'HTTP':str(a.port),'GAME':str(a.port+1),'WORLD':str(a.port+2),'ADMIN':str(a.port+100),'DEV':str(a.port+104)}
        for key,value in replacements.items():config=config.replace('@'+key+'@',value)
        config='\n'.join('client.runtime='+prop(runtime) if line.startswith('client.runtime=') else line for line in config.splitlines())+'\n'
        if (bundle/'resources/configs.xeon').is_file():
            config+='assets.configs='+prop(bundle/'resources/configs.xeon')+'\nassets.boxes='+prop(bundle/'resources/boxes.xeon')+'\nassets.overrides='+prop(bundle/'resources/overrides')+'\n'
        config=config.replace('game.bindAddress=0.0.0.0','game.bindAddress='+a.host)
        path=state/'server.properties';path.write_text(config,encoding='ascii')
        cp=os.pathsep.join(map(str,[jar]+dependencies()))
        print('Source-built server; persistent state: '+str(state/'data'),flush=True)
        print('Admin health: http://127.0.0.1:'+str(a.port+100)+'/health',flush=True)
        command=[tool('java'),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Xmx2g','-cp',cp,'szserver.ServerMain',str(path)]
        process=subprocess.Popen(command,cwd=state)
        try:code=process.wait()
        except KeyboardInterrupt:
            # Java receives the console interrupt and runs its save/shutdown hook.
            print('Waiting for Java to save and stop; keeping the state lock.',flush=True)
            while True:
                try:code=process.wait();break
                except KeyboardInterrupt:print('Still waiting for server shutdown.',flush=True)
        raise SystemExit(code)

if __name__=='__main__':main()
