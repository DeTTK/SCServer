"""Build owned game overlays and publish immutable signed file manifests."""
from pathlib import Path,PurePosixPath
import argparse,base64,hashlib,importlib.util,json,os,re,shutil,subprocess,tempfile,urllib.request,zipfile

ROOT=Path(__file__).resolve().parents[1]
LOCK=json.loads((ROOT/'bootstrap/base.lock.json').read_text('utf8'))
OUT=ROOT/'build/delivery'

def run(*args,**kw):return subprocess.run(list(map(str,args)),check=True,**kw)
def digest(path):
    h=hashlib.sha256()
    with path.open('rb')as f:
        for block in iter(lambda:f.read(131072),b''):h.update(block)
    return h.hexdigest()
def source_tool():
    spec=importlib.util.spec_from_file_location('source_build',ROOT/'scripts/build.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def pinned(row):
    path=ROOT/'.local/delivery-inputs'/row['file'];path.parent.mkdir(parents=True,exist_ok=True)
    if path.is_file()and digest(path)==row['sha256']:return path
    part=path.with_suffix(path.suffix+'.part')
    req=urllib.request.Request(row['url'],headers={'User-Agent':'BSCPCRAFT-delivery/1'})
    with urllib.request.urlopen(req,timeout=60)as src,part.open('wb')as dst:
        count=0
        while block:=src.read(131072):
            count+=len(block)
            if count>64*1024*1024:raise ValueError('Pinned build input too large')
            dst.write(block)
    if digest(part)!=row['sha256']:raise ValueError('Build input hash mismatch: '+row['file'])
    part.replace(path);return path
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
        if p.suffix.lower()not in ('.jar','.properties'):raise ValueError('Unexpected payload type')
        if p.stat().st_size>99*1024*1024:raise ValueError('Payload exceeds ordinary Git limit')
        target='client/mods/command-completion.jar'if rel=='mods/command-completion/command-completion.jar'else rel
        rows.append({'path':rel,'target':target,'size':p.stat().st_size,'sha256':digest(p)})
    if not rows:raise ValueError('Empty delivery')
    return rows
def deterministic_jar(path,entries):
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED)as z:
        for name,raw in sorted(entries.items()):
            e=zipfile.ZipInfo(name,(2020,1,1,0,0,0));e.compress_type=zipfile.ZIP_DEFLATED;z.writestr(e,raw)
def build_agent(tools,base,asm,tree,kotlin):
    spec=importlib.util.spec_from_file_location('compat',ROOT/'client/resource-compat/build.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    deps=tools.dependencies();classes=tools.compile_java('delivery-resource-agent',(ROOT/'client/resource-compat/src').rglob('*.java'),[asm,tree,kotlin,base,*deps])
    with zipfile.ZipFile(base)as z:entries={n:z.read(n)for n in z.namelist()if not n.endswith('/')}
    for p in sorted(classes.rglob('*.class')):entries[p.relative_to(classes).as_posix()]=m.shade(p.read_bytes())
    for library in [asm,tree]:
        with zipfile.ZipFile(library)as z:
            for n in z.namelist():
                if n.startswith('org/objectweb/asm/')and n.endswith('.class'):entries[n.replace('org/objectweb/asm','gamecompat/shaded/asm')]=m.shade(z.read(n))
    path=ROOT/'build/game-resource-compat.jar';deterministic_jar(path,entries);return path
def prepare():
    tools=source_tool();(ROOT/'build').mkdir(exist_ok=True)
    resources=pinned(LOCK['serverResources']);agent=pinned(LOCK['clientAgent']);asm=pinned(LOCK['asm']);tree=pinned(LOCK['asmTree']);kotlin=pinned(LOCK['kotlin'])
    run(os.sys.executable,ROOT/'scripts/import_private_resources.py','--server-jar',resources)
    tools.server(True);tools.completion()
    run(os.sys.executable,ROOT/'client/startup/build.py','--asm',asm)
    run(os.sys.executable,ROOT/'client-compat/character-scale/build.py')
    compat=build_agent(tools,agent,asm,tree,kotlin)
    files={
        'server/server.jar':ROOT/'build/server.jar',
        'server/server.template.properties':ROOT/'desktop/server.template.properties',
        'client/compat/startup-guard.jar':ROOT/'build/startup-guard.jar',
        'client/compat/character-scale.jar':ROOT/'build/character-scale.jar',
        'client/compat/game-resource-compat.jar':compat,
        'mods/command-completion/command-completion.jar':ROOT/'build/command-completion.jar'}
    if OUT.exists():
        if not OUT.resolve().is_relative_to(ROOT/'build')or OUT.is_symlink():raise ValueError('Unsafe owned output')
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    for name,src in files.items():dst=OUT/name;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dst)
    rows=file_inventory(OUT)
    (ROOT/'build/delivery-verification.json').write_text(json.dumps({'files':rows,'bytes':sum(r['size']for r in rows),'baseId':LOCK['id']},indent=2),'utf8')
    print('Prepared',len(rows),'game files;',sum(r['size']for r in rows),'bytes; base',LOCK['id'])
def sign(raw,key):
    with tempfile.TemporaryDirectory(prefix='bscp-delivery-sign-')as tmp:
        folder=Path(tmp);private=folder/'key.pem';private.write_text(key,'utf8');private.chmod(0o600)
        public=run('openssl','pkey','-in',private,'-pubout',capture_output=True).stdout
        if public.strip()!=(ROOT/'delivery/public-key.txt').read_bytes().strip():raise ValueError('Signing key does not match embedded public key')
        source=folder/'manifest';source.write_bytes(raw);signature=folder/'signature'
        run('openssl','dgst','-sha256','-sign',private,'-out',signature,source,capture_output=True)
        return signature.read_bytes()
def publish():
    channel=os.environ.get('GITHUB_REF_NAME','')
    if channel!='launtest':raise ValueError('Only launtest publication is enabled')
    source=os.environ['GITHUB_SHA']
    if not re.fullmatch('[0-9a-f]{40}',source):raise ValueError('Invalid source commit')
    latest=run('git','ls-remote','origin','refs/heads/'+channel,capture_output=True,text=True).stdout.split()[0]
    if latest!=source:print('Superseded source build; not publishing');return
    manifest={'schemaVersion':1,'channel':channel,'sourceCommit':source,'sequence':int(os.environ['GITHUB_RUN_NUMBER']),
        'baseId':LOCK['id'],'files':file_inventory(OUT),'changelog':run('git','log','-1','--format=%s',capture_output=True,text=True).stdout.strip(),
        'mods':[{'id':'command-completion','name':'Подсказки команд','side':'client','required':True,'path':'mods/command-completion/command-completion.jar'}]}
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
    fetched=subprocess.run(['git','-C',str(destination),'fetch','--depth=1','origin',branch],env=env,capture_output=True)
    if fetched.returncode==0:run('git','-C',destination,'checkout','-B',branch,'FETCH_HEAD',env=env,capture_output=True)
    else:run('git','-C',destination,'checkout','--orphan',branch,env=env,capture_output=True)
    if (destination/'delivery.json').exists():
        previous=json.loads((destination/'delivery.json').read_text('utf8'))
        for row in previous['files']:
            p=destination/safe_path(row['path'])
            if p.is_file():p.unlink()
    shutil.copytree(OUT,destination,dirs_exist_ok=True)
    (destination/'delivery.json').write_bytes(raw);(destination/'delivery.json.sig').write_bytes(signature)
    for name,body in {'bootstrap/base.json':json.dumps({'id':LOCK['id'],'mode':'installed-foundation'}),'map/base.json':json.dumps({'mode':'existing-world-base','replaced':False}),'launcher/README.md':'Launcher EXE updates belong to GitHub Releases.\n','README.md':'Generated launtest game files. Develop in launtest; do not edit this branch.\n'}.items():
        p=destination/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,'utf8')
    run('git','-C',destination,'add','--all',env=env)
    run('git','-C',destination,'commit','-m','Build launtest from '+source[:12],env=env,capture_output=True)
    latest=run('git','ls-remote','origin','refs/heads/'+channel,capture_output=True,text=True).stdout.split()[0]
    if latest!=source:print('Source advanced before activation; not publishing');return
    run('git','-C',destination,'push','origin','HEAD:refs/heads/'+branch,env=env)
    print('Published signed',branch,'from',source)
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['prepare','publish']);a=p.parse_args()
    {'prepare':prepare,'publish':publish}[a.command]()
