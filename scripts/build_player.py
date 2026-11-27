"""Build the complete player payload from a Git checkout and pinned Maven tools.

No installed game, old release bundle, build branch or developer workspace is read.
The proprietary game ABI and original assets are declared immutable Git inputs;
all of our Java modules are compiled here from source.
"""
from pathlib import Path, PurePosixPath
import argparse,hashlib,importlib.util,json,os,shutil,subprocess,sys,zipfile
import build as tools

ROOT=tools.ROOT
EXPORT='--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED'

def safe(name):
    if not name or '\\' in name or ':' in name or any(x in ('','.','..') for x in name.split('/')):raise ValueError('Unsafe input path: '+name)
    return PurePosixPath(name)

def restore(directory):
    manifest=json.loads((ROOT/'bootstrap/input-manifest.json').read_text('utf8'))
    referenced=set()
    for name,row in manifest['objects'].items():
        safe(name);target=directory/name;target.parent.mkdir(parents=True,exist_ok=True)
        digest=hashlib.sha256();size=0
        with target.open('wb') as out:
            for part in row['parts']:
                safe(part)
                if not part.startswith(('bootstrap/inputs/','map/data/')):raise ValueError('Input prefix')
                item=manifest['files'][part];body=(ROOT/part).read_bytes();referenced.add(part)
                if len(body)!=item['size'] or hashlib.sha256(body).hexdigest()!=item['sha256']:raise ValueError('Git input checksum: '+part)
                out.write(body);digest.update(body);size+=len(body)
        if size!=row['size'] or digest.hexdigest()!=row['sha256']:raise ValueError('Reconstructed input checksum: '+name)
    if referenced!=set(manifest['files']):raise ValueError('Unreferenced Git input')
    return manifest

def copy(source,target):
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)

def pinned_input(row):
    path = ROOT / '.local/delivery-inputs' / row['file']
    if path.is_file() and tools.sha(path) == row['sha256']:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_suffix(path.suffix + '.part')
    part.write_bytes(tools.dependency_bytes(row))
    part.replace(path)
    return path

def entries(classes):return {p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}

def jar(path,values):
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as out:
        for name,body in sorted(values.items()):
            safe(name);entry=zipfile.ZipInfo(name,(2020,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;out.writestr(entry,body)

def compile_internal(name,sources,build_root):
    output=build_root/(name+'-classes');output.mkdir(parents=True,exist_ok=False)
    subprocess.run([tools.tool('javac'),'-source','17','-target','17',EXPORT,'-proc:none','-encoding','UTF-8','-d',str(output),*map(str,sorted(sources))],check=True)
    return output

def build(output):
    output=output.resolve()
    if not output.is_relative_to(ROOT/'build') or output.exists():raise ValueError('Use a new output directory inside checkout/build')
    output.mkdir(parents=True);inputs=output/'inputs';manifest=restore(inputs)
    player=output/'player';runtime=player/'Bootstrap/runtime'
    shutil.copytree(inputs/'runtime',runtime)
    shutil.copytree(inputs/'java17',player/'Bootstrap/java17')
    shutil.copytree(inputs/'map',player/'Map')
    shutil.copytree(inputs/'server',player/'Server')
    shutil.copytree(inputs/'assets',player/'Server/resources')
    shutil.copytree(inputs/'asset-overrides',player/'Server/resources/overrides')
    for p in (inputs/'server-resources').rglob('*'):
        if p.is_file():copy(p,ROOT/'.local/server-resources'/p.relative_to(inputs/'server-resources'))
    server_classes, server_deps = tools.server(True)
    subprocess.run(
        [tools.tool('java'), '-Xmx1024m', '-cp', os.pathsep.join(map(str, [server_classes, *server_deps])),
         'szserver.world.MapSceneNormalizer', str(player/'Map'),
         str(player/'Server/registries/live-gameobjects.json'),
         str(player/'Server/registries/live-object-codecs.json')],
        check=True,
    )
    tools.completion()
    copy(ROOT/'build/server.jar',player/'Server/server.jar')
    copy(ROOT/'config/server.template.properties',player/'Server/server.template.properties')
    for dep in tools.dependencies():copy(dep,player/'Server/lib'/dep.name)
    copy(ROOT/'build/command-completion.jar',player/'Client/mods/command-completion.jar')
    game_exe=player/'Client/compat/bscp-game.exe'
    game_exe.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run(['x86_64-w64-mingw32-gcc','-std=c11','-Os','-s',
                    '-Wl,--no-insert-timestamp',str(ROOT/'client/windows/game-launcher.c'),
                    '-o',str(game_exe)],check=True)
    lock=json.loads((ROOT/'bootstrap/base.lock.json').read_text('utf8'))
    asm,tree,kotlin=[pinned_input(lock[name]) for name in ('asm','asmTree','kotlin')]
    imgui={}
    for dependency in (runtime/'dependencies').glob('imgui-java-*-1.82.2.jar'):
        with zipfile.ZipFile(dependency) as z:
            for name in z.namelist():
                if name.startswith('imgui/') and name.endswith('.class'):imgui[name]=z.read(name)
    if not imgui:raise ValueError('Missing pinned ImGui ABI')
    jar(runtime/'imgui-compat.jar',imgui)
    dependencies=sorted((runtime/'dependencies').glob('*.jar'))
    # Match the launcher: choose the pinned ImGui binding ahead of the game's fork.
    libraries=[runtime/'game.jar',runtime/'imgui-compat.jar',
               *(p for p in dependencies if not p.name.startswith('imgui-java-binding-')),
               runtime/'libraries.jar']
    boot=tools.compile_java('player-bootstrap',[*(ROOT/'client/bootstrap').glob('*.java'),*(ROOT/'client/bootstrap-compat').rglob('*.java'),*(ROOT/'client/mod-runtime/src').rglob('*.java')],libraries)
    generators=tools.compile_java('player-bootstrap-generators',(ROOT/'client/bootstrap-tools').glob('*.java'),[asm])
    for generator in ('GenerateLegacyAdapter','GenerateCommandPackets','InstallFpvPackets','InstallVehiclePackets','InstallFunnelImpact'):
        subprocess.run([tools.tool('java'),'-cp',os.pathsep.join(map(str,[generators,asm])),generator,str(boot)],check=True)
    bootstrap = entries(boot)
    bootstrap['szcustom/loading.gif'] = (ROOT/'client/bootstrap/resources/szcustom/loading.gif').read_bytes()
    for asset in sorted((inputs/'client-assets').rglob('*')):
        if asset.is_file():
            bootstrap['assets/'+asset.relative_to(inputs/'client-assets').as_posix()] = asset.read_bytes()
    jar(runtime/'bootstrap.jar',bootstrap)
    code=subprocess.check_output([tools.tool('javap'),'-c','-classpath',
                                  str(runtime/'bootstrap.jar'),'StandardJvmBootstrap'],text=True)
    if 'imgui/ImGui.getFontSize:()I' not in code:
        raise ValueError('Bootstrap must call the packaged ImGui font size ABI')
    verify=tools.compile_java('player-bootstrap-verify',[ROOT/'client/bootstrap-tools/tests/BootstrapAbiTest.java'],[runtime/'bootstrap.jar',*libraries])
    subprocess.run([tools.tool('java'),'-cp',os.pathsep.join(map(str,[verify,runtime/'bootstrap.jar',*libraries])),'BootstrapAbiTest'],check=True)
    item_checks=('NativeBoltFactoryChecks','NativeMedicineResourceChecks','NativeArtifactResourceChecks','NativeConfigSourceChecks')
    item_tests=tools.compile_java('player-item-verify',
        [ROOT/f'server/src/test/java/szserver/client/{name}.java' for name in (*item_checks,'NativeCosmeticTests')],[server_classes,*server_deps])
    # Use the delivered JARs and the same dependencies as the Windows launcher.
    item_test_cp=os.pathsep.join(map(str,[item_tests,player/'Server/server.jar',
        player/'Server/lib/gson-2.13.2.jar',runtime/'dependencies/zstd-jni-1.5.0-5.jar']))
    for name in item_checks:
        subprocess.run([tools.tool('java'),'-Xmx512m','-cp',item_test_cp,
                        'szserver.client.'+name,str(player/'Server'),str(runtime)],check=True)
    subprocess.run([tools.tool('java'),'-Xmx512m','-cp',item_test_cp,
                    'szserver.client.NativeCosmeticTests',str(runtime),str(player/'Server')],check=True)
    subprocess.run([sys.executable,str(ROOT/'client/startup/build.py'),'--asm',str(asm)],check=True)
    subprocess.run([sys.executable,str(ROOT/'client-compat/character-scale/build.py')],check=True)
    for name in ('startup-guard','character-scale'):copy(ROOT/'build'/(name+'.jar'),player/'Client/compat'/(name+'.jar'))
    spec=importlib.util.spec_from_file_location('compat',ROOT/'client/resource-compat/build.py');compat=importlib.util.module_from_spec(spec);spec.loader.exec_module(compat)
    classes=tools.compile_java('player-resource-compat',[*(ROOT/'client/resource-compat/src').rglob('*.java'),*(ROOT/'shared/voice/src').rglob('*.java')],[asm,tree,kotlin,*libraries,*tools.dependencies()])
    cp=os.pathsep.join(map(str,[classes,inputs/'agent-resources',asm,tree,*libraries]))
    subprocess.run([tools.tool('java'),'-cp',cp,'gamecompat.ResourceSchemaTransformer',str(classes/'gamecompat/MultisetArtefact.class')],check=True)
    values={n:compat.shade(body) for n,body in entries(classes).items()}
    for p in (inputs/'agent-resources').rglob('*'):
        if p.is_file():values[p.relative_to(inputs/'agent-resources').as_posix()]=p.read_bytes()
    for library in [asm,tree]:
        with zipfile.ZipFile(library) as z:
            for n in z.namelist():
                if n.startswith('org/objectweb/asm/') and n.endswith('.class'):values[n.replace('org/objectweb/asm','gamecompat/shaded/asm')]=compat.shade(z.read(n))
    values['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\nPremain-Class: gamecompat.ResourceCompatibilityAgent\n\n'
    values['META-INF/LICENSE-ASM.txt']=(ROOT/'client/resource-compat/ASM-LICENSE.txt').read_bytes()
    resource_compat=player/'Client/compat/game-resource-compat.jar'
    scale_agent=player/'Client/compat/character-scale.jar'
    jar(resource_compat,values)
    relations_check=tools.compile_java('player-relations',
        [ROOT/'client/resource-compat/tests/PlayerRelationsProbe.java'])
    subprocess.run([tools.tool('java'),'-Xverify:all',f'-javaagent:{resource_compat}',
                    '-cp',os.pathsep.join(map(str,[relations_check,*libraries])),
                    'PlayerRelationsProbe'],check=True)
    camera_check=tools.compile_java('player-camera-chain',
        [ROOT/'client-compat/character-scale/tools/CameraAgentChainCheck.java'],[scale_agent])
    subprocess.run([tools.tool('java'),EXPORT,'-Xverify:all',
                    f'-javaagent:{resource_compat}',f'-javaagent:{scale_agent}',
                    '-cp',os.pathsep.join(map(str,[camera_check,runtime/'bootstrap.jar',*libraries])),
                    'playerscale.CameraAgentChainCheck'],check=True)
    weapon=compile_internal('player-weapon-events',(ROOT/'client/weapon-events/src').glob('*.java'),output);values=entries(weapon)
    values['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\nPremain-Class: ReloadEventAgent\nAgent-Class: MeleeBodyAgent\nCan-Retransform-Classes: true\n\n'
    weapon_agent=player/'Client/compat/weapon-events.jar'
    jar(weapon_agent,values)
    probe=compile_internal('player-melee-test',[ROOT/'client/weapon-events/tests/MeleeInventoryProbe.java'],output)
    probe_entries=entries(probe)
    probe_entries['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\nPremain-Class: MeleeInventoryProbe\n\n'
    probe_agent=output/'melee-inventory-test.jar'
    jar(probe_agent,probe_entries)
    subprocess.run([tools.tool('java'),EXPORT,'-Xverify:all',f'-javaagent:{probe_agent}',f'-javaagent:{weapon_agent}',
                    '-cp',os.pathsep.join(map(str,[probe_agent,runtime/'bootstrap.jar',*libraries])),'MeleeInventoryProbe'],check=True)
    bridge=compile_internal('player-bridge',(ROOT/'launcher/modules/bridge/src').rglob('*.java'),output);values=entries(bridge)
    values['META-INF/MANIFEST.MF']=(ROOT/'launcher/modules/bridge/manifest.mf').read_bytes();jar(player/'Bootstrap/bscp-bridge.jar',values)
    verify=tools.compile_java('player-bridge-verify',[ROOT/'launcher/modules/bridge/tests/ServerShapeTest.java'],[bridge])
    subprocess.run([tools.tool('java'),EXPORT,'-cp',os.pathsep.join(map(str,[verify,bridge])),'bscp.next.ServerShapeTest',str(player/'Server/server.jar')],check=True)
    files=[p for p in player.rglob('*') if p.is_file()]
    if any(p.suffix in ('.class','.ref','.aabb') and 'overrides' not in p.parts for p in files):raise ValueError('Loose research/extracted runtime in payload')
    report={'runtimeLayout':manifest['runtimeLayout'],'minimumLauncherVersion':'0.4.1','files':len(files),'bytes':sum(p.stat().st_size for p in files),'gitInputFiles':len(manifest['files']),'outsideWorkspaceInputs':False,'prebuiltLocalGameBundles':False}
    (output/'build-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8');print(json.dumps(report,indent=2),flush=True)
    return player

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=ROOT/'build/player-git');args=parser.parse_args();build(args.output)
