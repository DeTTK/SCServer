"""Build the player-only shared runtime; never copy a development tree verbatim."""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,re,shutil,zipfile,collections

RESEARCH={'_reference-3009','_fp-numeric-new','_fp-numeric-old','_fp-javap-new','anim-analysis','zstd'}
def runtime_entry(name):
    p=Path(name)
    return not any(part in RESEARCH or part.startswith('_fp-')for part in p.parts) and p.suffix.lower()not in ('.ref','.java','.c','.cpp','.h','.py','.log','.pdb','.bak','.md')
def jar(source,destination):
    selected=[];excluded=0
    for directory,dirs,names in os.walk(source):
        dirs[:]=[d for d in dirs if d not in RESEARCH and not d.startswith('_fp-')]
        for name in names:
            p=Path(directory)/name
            if runtime_entry(p.relative_to(source).as_posix()):selected.append(p)
            else:excluded+=1
    selected.sort()
    def load(p):
        if p.is_symlink()or getattr(p.stat(),'st_file_attributes',0)&0x400:raise ValueError('Linked runtime file')
        return p,p.read_bytes()
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED,compresslevel=6)as z:
        with concurrent.futures.ThreadPoolExecutor(max_workers=24)as pool:
            pending=collections.deque();items=iter(selected)
            for _ in range(72):
                try:pending.append(pool.submit(load,next(items)))
                except StopIteration:break
            count=0
            while pending:
                p,body=pending.popleft().result();entry=zipfile.ZipInfo(p.relative_to(source).as_posix(),(2020,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;z.writestr(entry,body);count+=1
                if count%10000==0:print(destination.name,count,'/',len(selected),flush=True)
                try:pending.append(pool.submit(load,next(items)))
                except StopIteration:pass
    return {'entries':len(selected),'excludedFiles':excluded,'excludedDirectories':sorted(RESEARCH),'bytes':destination.stat().st_size}
def prepare(canonical,bundle,map_root,output,resume=False):
    if output.exists()and(not resume or not(output/'.player-runtime-owned').is_file()):raise ValueError('Use a new owned output directory')
    output.mkdir(parents=True,exist_ok=resume);(output/'.player-runtime-owned').write_text('player-runtime-v1');shared=output/'Bootstrap/runtime';shared.mkdir(parents=True,exist_ok=resume)
    report={}
    for folder,name in [('classes','game.jar'),('bootstrap','bootstrap.jar'),('libs','libraries.jar'),('imgui-compat','imgui-compat.jar')]:
        report[name]=jar(canonical/folder,shared/name)
    for name in ('dependencies','natives'):
        shutil.copytree(canonical/name,shared/name,dirs_exist_ok=resume)
    for component in ('Client','Server'):
        target=output/component;target.mkdir(exist_ok=resume)
        for p in (bundle/component).iterdir():
            allowed={'compat','mods'} if component=='Client' else {'server.jar','server.template.properties','lib','fixtures','source-resources','results','registries','resources'}
            if p.name not in allowed:continue
            if p.is_dir():shutil.copytree(p,target/p.name,dirs_exist_ok=resume)
            else:shutil.copyfile(p,target/p.name)
    shutil.copytree(map_root,output/'Map',dirs_exist_ok=resume)
    # Compiled game bytes are identical; package boundaries do not normalize classes.
    with zipfile.ZipFile(shared/'game.jar')as z:
        for name in ('1262384494','212440292','108847398','1536296909'):
            expected=(canonical/'classes'/f'{name}.class').read_bytes()
            if z.read(f'{name}.class')!=expected:raise ValueError('Accessory physics changed')
        if any('.ref'==Path(n).suffix or any(p in RESEARCH for p in Path(n).parts)for n in z.namelist()):raise ValueError('Research files leaked')
    count=sum(1 for p in output.rglob('*')if p.is_file());report.update(files=count,sharedRuntime=True,accessoryPhysicsPreserved=True)
    (output/'player-runtime-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--canonical',type=Path,required=True);p.add_argument('--bundle',type=Path,required=True);p.add_argument('--map',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--resume-owned',action='store_true');a=p.parse_args();prepare(a.canonical.resolve(),a.bundle.resolve(),a.map.resolve(),a.output.resolve(),a.resume_owned)
