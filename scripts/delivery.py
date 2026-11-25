"""Build owned game overlays and publish immutable signed file manifests."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,tempfile,zipfile

ROOT=Path(__file__).resolve().parents[1]
LOCK=json.loads((ROOT/'bootstrap/base.lock.json').read_text('utf8'))
OUT=ROOT/'build/delivery-player'

def run(*args,**kw):return subprocess.run(list(map(str,args)),check=True,**kw)
def digest(path):
    h=hashlib.sha256()
    with path.open('rb')as f:
        for block in iter(lambda:f.read(131072),b''):h.update(block)
    return h.hexdigest()
def source_tool():
    spec=importlib.util.spec_from_file_location('source_build',ROOT/'scripts/build.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def safe_path(name):
    p=PurePosixPath(name)
    if not name or p.is_absolute()or any(x in ('','.','..')for x in name.split('/'))or '\\'in name or ':'in name:raise ValueError('Unsafe delivery path')
    return p
def file_inventory(root):
    rows=[]
    for p in sorted(root.rglob('*')):
        if not p.is_file():continue
        rel=p.relative_to(root).as_posix();safe_path(rel)
        if rel.split('/')[0]not in ('server','client','mods'):raise ValueError('Unexpected component')
        if p.suffix.lower()not in ('.jar','.properties') and rel!='client/compat/bscp-game.exe':raise ValueError('Unexpected payload type')
        if p.stat().st_size>99*1024*1024:raise ValueError('Payload exceeds ordinary Git limit')
        target=('client/mods/'+rel.split('/')[1]+'.jar') if rel.startswith('mods/') and len(rel.split('/'))==3 and rel.endswith('.jar') else rel
        rows.append({'path':rel,'target':target,'size':p.stat().st_size,'sha256':digest(p)})
    if not rows:raise ValueError('Empty delivery')
    return rows
def mod_catalogue(root):
    config=json.loads((ROOT/'delivery/mods.json').read_text('utf8'))
    rows={r['path']:r for r in file_inventory(root)}
    output=[];ids=set()
    for item in config:
        ident=item['id']
        if not re.fullmatch('[a-z][a-z0-9_-]{0,63}',ident) or ident in ids or item['side']!='client':raise ValueError('Invalid catalogue mod')
        ids.add(ident);safe_path(item['source'])
        descriptor=ROOT/item['source']/'stalcraft-mod.properties'
        props=dict(line.split('=',1) for line in descriptor.read_text('utf8').splitlines() if '='in line and not line.startswith('#'))
        if props['id']!=ident or props['api']!='1':raise ValueError('Mod descriptor differs')
        path=f'mods/{ident}/{ident}.jar'
        row=rows[path]
        with zipfile.ZipFile(root/path) as z:
            data=z.read('stalcraft-mod.properties').decode('utf8')
            if 'id='+ident not in data:raise ValueError('Built mod id differs')
        result={k:item[k] for k in ('id','name','description','side','required')}
        result.update(version=props['version'],path=path)
        if not item['required']:result['files']=[row]
        output.append(result)
    return output


def deterministic_jar(path,entries):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED)as z:
        for name,raw in sorted(entries.items()):
            e=zipfile.ZipInfo(name,(2020,1,1,0,0,0));e.compress_type=zipfile.ZIP_DEFLATED;z.writestr(e,raw)

def commit_index(destination,message,env):
    # git commit refreshes a partial working tree and can lazy-fetch all absent
    # foundation blobs. Commit the prepared index directly instead.
    env=dict(env,GIT_NO_LAZY_FETCH='1')
    tree=run('git','-C',destination,'write-tree','--missing-ok',env=env,capture_output=True,text=True).stdout.strip()
    parent=subprocess.run(['git','-C',str(destination),'rev-parse','--verify','HEAD'],env=env,capture_output=True,text=True)
    args=['git','-C',destination,'commit-tree',tree,'-m',message]
    if parent.returncode==0:args+=['-p',parent.stdout.strip()]
    commit=run(*args,env=env,capture_output=True,text=True).stdout.strip()
    run('git','-C',destination,'update-ref','HEAD',commit,env=env,capture_output=True)
    return commit
def build_vr_mod(tools,source,classpath,runtime):
    classes=tools.compile_java('catalogue-stalcraft-vr',(source/'src').rglob('*.java'),classpath)
    generators=tools.compile_java('vr-adapters',(source/'tools').glob('*.java'),classpath)
    generator_cp=os.pathsep.join(map(str,[generators,*classpath]))
    for generator in ('GenerateFpAdapter','GenerateSceneCopy'):
        run(tools.tool('java'),'-cp',generator_cp,generator,classes)

    files=[(source/'stalcraft-mod.properties','stalcraft-mod.properties')]
    for path in sorted((source/'resources').iterdir()):
        files.append((path,path.name))
    jar=tools.jar('catalogue-stalcraft-vr',classes,files)
    test_cp=[jar,runtime/'bootstrap.jar',*classpath]
    tests=tools.compile_java('vr-mod-tests',(source/'tests').glob('*.java'),test_cp)
    for name in ('VrTest','NativeContracts','VrReloadProtocolProbe','MasterMechanicsProbe','PhysicalActionsProbe','OpticGeometryProbe'):
        run(tools.tool('java'),'-cp',os.pathsep.join(map(str,[tests,*test_cp])),'vr.'+name,cwd=tests)
    return jar


def prepare():
    tools=source_tool();(ROOT/'build').mkdir(exist_ok=True)
    spec=importlib.util.spec_from_file_location('player_build',ROOT/'scripts/build_player.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    player=module.build(ROOT/'build/player-delivery')
    spec=importlib.util.spec_from_file_location('foundation_export',ROOT/'scripts/export_foundation.py');exporter=importlib.util.module_from_spec(spec);spec.loader.exec_module(exporter)
    foundation=exporter.export(player,player/'Bootstrap/java17',player/'Bootstrap/bscp-bridge.jar',ROOT/'build/foundation-delivery')
    files={
        'server/server.jar':player/'Server/server.jar',
        'server/server.template.properties':player/'Server/server.template.properties',
        **{'client/compat/'+name:player/'Client/compat'/name for name in ('startup-guard.jar','character-scale.jar','game-resource-compat.jar','weapon-events.jar','bscp-game.exe')},
        'mods/command-completion/command-completion.jar':player/'Client/mods/command-completion.jar'}
    OUT.mkdir(parents=True,exist_ok=False)
    for name,src in files.items():dst=OUT/name;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
    # Optional mods are compiled from ordinary Git sources and published separately.
    api=ROOT/'build/mod-api.jar'
    runtime=player/'Bootstrap/runtime'
    classpath=[api,*tools.dependencies(),runtime/'game.jar',runtime/'libraries.jar',*sorted((runtime/'dependencies').glob('*.jar'))]
    for item in json.loads((ROOT/'delivery/mods.json').read_text('utf8')):
        if item['required']:continue
        source=ROOT/item['source'];ident=item['id']
        if ident=='stalcraft-vr':
            jar=build_vr_mod(tools,source,classpath,runtime)
        else:
            classes=tools.compile_java('catalogue-'+ident,(source/'src').rglob('*.java'),classpath)
            jar=tools.jar('catalogue-'+ident,classes,[(source/'stalcraft-mod.properties','stalcraft-mod.properties')])
        dst=OUT/f'mods/{ident}/{ident}.jar';dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(jar,dst)
    rows=file_inventory(OUT)
    (ROOT/'build/delivery-verification.json').write_text(json.dumps({'files':rows,'bytes':sum(r['size']for r in rows),'foundation':foundation},indent=2),'utf8')
    print('Prepared source-built game and complete Git foundation',foundation['id'])

def sign(raw,key):
    with tempfile.TemporaryDirectory(prefix='bscp-delivery-sign-')as tmp:
        folder=Path(tmp);private=folder/'key.pem';private.write_text(key,'utf8');private.chmod(0o600)
        public=run('openssl','pkey','-in',private,'-pubout',capture_output=True).stdout
        if public.strip()!=(ROOT/'delivery/public-key.txt').read_bytes().strip():raise ValueError('Signing key does not match embedded public key')
        source=folder/'manifest';source.write_bytes(raw);signature=folder/'signature'
        run('openssl','dgst','-sha256','-sign',private,'-out',signature,source,capture_output=True)
        return signature.read_bytes()
def publication_source():
    channel=os.environ.get('GITHUB_REF_NAME','')
    if channel not in ('main','test','launtest'):raise ValueError('Only main, test and launtest publication is enabled')
    source=os.environ['GITHUB_SHA']
    if not re.fullmatch('[0-9a-f]{40}',source):raise ValueError('Invalid source commit')
    return channel,source

def source_is_current(channel,source):
    ref='refs/heads/'+channel
    output=run('git','ls-remote','--exit-code','origin',ref,capture_output=True,text=True).stdout
    latest,separator,reported_ref=output.strip().partition('\t')
    if not separator or reported_ref!=ref or not re.fullmatch('[0-9a-f]{40}',latest):
        raise ValueError(f'Invalid remote response for {ref}')
    if latest==source:
        return True
    # Pushes that only change Markdown do not start their own build.
    run('git','fetch','--no-tags','--depth=1','--filter=blob:none','origin',latest,capture_output=True)
    changed=run('git','diff','--name-only','--no-renames',source,latest,'--','.',
                ':(exclude,glob)**/*.md',capture_output=True).stdout
    if not changed:
        return True
    print(f'Skipping publication for {channel}: {source[:12]} was superseded by {latest[:12]}')
    return False

def publish():
    channel,source=publication_source()
    if not source_is_current(channel,source):
        return False
    manifest={'schemaVersion':2,'minimumLauncherVersion':'0.4.1','channel':channel,'sourceCommit':source,'sequence':int(os.environ['GITHUB_RUN_NUMBER']),
        'baseId':LOCK['id'],'files':file_inventory(OUT),'changelog':run('git','log','-1','--format=%s',capture_output=True,text=True).stdout.strip(),
        'mods':mod_catalogue(OUT)}
    optional={f['path'] for mod in manifest['mods'] if not mod['required'] for f in mod['files']}
    manifest['files']=[f for f in manifest['files'] if f['path']not in optional]
    export=json.loads((ROOT/'build/foundation-delivery/foundation-export.json').read_text('utf8'))
    foundation=export['foundation'];manifest['foundation']=foundation;manifest['baseId']=foundation['id']
    foundation_output=ROOT/'build/foundation-delivery'
    foundation_payload=(foundation_output/'bootstrap/foundation.json').read_bytes()
    default_map=ROOT/'map/default.json'
    if default_map.is_file():manifest['defaultMap']=json.loads(default_map.read_text('utf8'))
    raw=json.dumps(manifest,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()
    signature=sign(raw,os.environ['DELIVERY_PRIVATE_KEY'])
    destination=ROOT/'build/publish-worktree';destination.mkdir(exist_ok=False)
    run('git','init',destination,capture_output=True)
    run('git','-C',destination,'config','user.name','BSCPCRAFT Build')
    run('git','-C',destination,'config','user.email','build@users.noreply.github.com')
    remote=run('git','remote','get-url','origin',capture_output=True,text=True).stdout.strip()
    run('git','-C',destination,'remote','add','origin',remote)
    # checkout's extraheader is process-local: no token is written into output or remote URLs.
    header=subprocess.run(['git','config','--get','http.https://github.com/.extraheader'],capture_output=True,text=True).stdout.strip()
    env=dict(os.environ)
    if header:env.update(GIT_CONFIG_COUNT='1',GIT_CONFIG_KEY_0='http.https://github.com/.extraheader',GIT_CONFIG_VALUE_0=header)
    branch='build-'+channel
    run('git','-C',destination,'config','remote.origin.promisor','true')
    run('git','-C',destination,'config','remote.origin.partialclonefilter','blob:none')
    fetched=subprocess.run(['git','-C',str(destination),'fetch','--filter=blob:none','--depth=1','origin',branch],env=env,capture_output=True)
    if fetched.returncode==0:
        run('git','-C',destination,'update-ref','refs/heads/'+branch,'FETCH_HEAD',env=env)
        run('git','-C',destination,'symbolic-ref','HEAD','refs/heads/'+branch,env=env)
        run('git','-C',destination,'read-tree','FETCH_HEAD',env=env)
        previous_raw=subprocess.run(['git','-C',str(destination),'show','FETCH_HEAD:delivery.json'],env=env,capture_output=True)
        if previous_raw.returncode==0:
            previous=json.loads(previous_raw.stdout);current={r['path']for r in file_inventory(OUT)}
            for row in [*previous['files'],*[f for m in previous.get('mods',[]) for f in m.get('files',[])]]:
                safe_path(row['path'])
                if row['path']not in current:run('git','-C',destination,'rm','--cached','--ignore-unmatch','--',row['path'],env=env,capture_output=True)
    else:run('git','-C',destination,'symbolic-ref','HEAD','refs/heads/'+branch,env=env)
    shutil.copytree(OUT,destination,dirs_exist_ok=True)
    foundation_paths=[]
    for source_file in foundation_output.rglob('*'):
        if source_file.is_file() and source_file.name!='foundation-export.json':
            relative=source_file.relative_to(foundation_output).as_posix();safe_path(relative);target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source_file,target);foundation_paths.append(relative)
    (destination/'delivery.json').write_bytes(raw);(destination/'delivery.json.sig').write_bytes(signature)
    metadata={'bootstrap/base.json':json.dumps({'id':manifest['baseId'],'mode':'complete-indexed-foundation'if'foundation'in manifest else'installed-foundation'}),'map/base.json':json.dumps({'mode':'indexed-foundation'if'foundation'in manifest else'existing-world-base','defaultMap':manifest.get('defaultMap'),'existingWorldsRebased':False}),'launcher/README.md':'Launcher EXE updates belong to GitHub Releases.\n','README.md':'Complete generated game delivery. Develop in the source branch; do not edit this generated branch.\n'}
    # Canonical descriptor and its signed manifest change in the same Git commit.
    if foundation_payload is not None:metadata[manifest['foundation']['path']]=foundation_payload.decode('utf8')
    for name,body in metadata.items():
        p=destination/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,'utf8')
    run('git','-C',destination,'add','--',*[r['path']for r in file_inventory(OUT)],'delivery.json','delivery.json.sig',*metadata,*foundation_paths,env=env)
    commit_index(destination,f'Build {channel} from {source[:12]}',env)
    if not source_is_current(channel,source):
        return False
    try:
        run('git','-C',destination,'push','--porcelain','origin','HEAD:refs/heads/'+branch,env=env,stdout=subprocess.PIPE,text=True)
    except subprocess.CalledProcessError as error:
        print(error.stdout,end='')
        rejected=any(line.startswith('!\t') and line.endswith(('[rejected] (fetch first)','[rejected] (non-fast-forward)')) for line in error.stdout.splitlines())
        if rejected and not source_is_current(channel,source):
            return False
        raise
    print('Published signed',branch,'from',source)
    return True

def workflow_output(name,value):
    if output:=os.environ.get('GITHUB_OUTPUT'):
        with open(output,'a',encoding='utf8') as file:
            file.write(f'{name}={str(value).lower()}\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['check-current','prepare','publish']);a=p.parse_args()
    if a.command=='check-current':
        workflow_output('current',source_is_current(*publication_source()))
    elif a.command=='prepare':
        prepare()
    else:
        workflow_output('published',publish())
