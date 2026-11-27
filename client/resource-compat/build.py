"""Overlay reviewed camera compatibility classes; retain every other agent entry."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,struct,subprocess,tempfile,zipfile

def shade(raw):
 assert raw[:4]==b'\xca\xfe\xba\xbe';count=struct.unpack_from('>H',raw,8)[0];at=10;i=1;out=bytearray(raw[:10])
 while i<count:
  tag=raw[at];at+=1;out.append(tag)
  if tag==1:
   n=struct.unpack_from('>H',raw,at)[0];at+=2;text=raw[at:at+n].replace(b'org/objectweb/asm',b'gamecompat/shaded/asm').replace(b'org.objectweb.asm',b'gamecompat.shaded.asm');at+=n;out.extend(struct.pack('>H',len(text)));out.extend(text)
  else:
   n={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}[tag];out.extend(raw[at:at+n]);at+=n
   if tag in (5,6):i+=1
  i+=1
 out.extend(raw[at:]);return bytes(out)

def main():
    root=Path(__file__).resolve().parents[2];here=Path(__file__).resolve().parent
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--base-agent',type=Path,required=True);parser.add_argument('--asm',type=Path,required=True);parser.add_argument('--asm-tree',type=Path,required=True);parser.add_argument('--native-camera',type=Path,help='Optional read-only native class for bytecode contract tests');parser.add_argument('--output',type=Path,default=root/'build/game-resource-compat-agent.jar');a=parser.parse_args()
    for f,expected in [(a.base_agent,'0ad625606c495904d22c9eb77bc9290dec9b741f0eeb1d45cf3440b2e50412b0'),(a.asm,'8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281'),(a.asm_tree,'9929881f59eb6b840e86d54570c77b59ce721d104e6dfd7a40978991c2d3b41f')]:
        if hashlib.sha256(f.read_bytes()).hexdigest()!=expected:parser.error('Checksum mismatch: '+str(f))
    home=os.environ.get('JAVA_HOME');tool=lambda n:str(Path(home)/'bin'/(n+('.exe'if os.name=='nt'else'')))if home else shutil.which(n)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    if a.output.resolve()==a.base_agent.resolve():parser.error('Output must not overwrite the input agent')
    with tempfile.TemporaryDirectory(prefix='camera-compile-',dir=a.output.parent)as scratch:
        manifest=json.loads((root/'bootstrap/input-manifest.json').read_text('utf8'))
        native=manifest['objects']['runtime/libraries.jar']
        libraries=Path(scratch)/'libraries.jar'
        libraries.write_bytes(b''.join((root/part).read_bytes() for part in native['parts']))
        if hashlib.sha256(libraries.read_bytes()).hexdigest()!=native['sha256']:parser.error('Checksum mismatch: runtime/libraries.jar')
        patched=('ResourceCompatibilityAgent','PerspectivePolicyTransformer','ContainerMotifIconTransformer','ContainerMotifModels','AmdAlphaTestTransformer','SceneSelectionTransformer','SceneVariantTransformer','SceneSafeTransformer','SceneDamageModelTransformer')
        sources=[here/'src/gamecompat'/f'{name}.java' for name in patched];cp=os.pathsep.join(str(f.resolve())for f in [a.asm,a.asm_tree,a.base_agent,libraries]);subprocess.run([tool('javac'),'--release','17','-proc:none','-encoding','UTF-8','-cp',cp,'-d',scratch,*map(str,sources)],check=True)
        if a.native_camera:
            tests=here/'tests/gamecompat/PerspectivePolicyTests.java';run_cp=scratch+os.pathsep+cp;subprocess.run([tool('javac'),'--release','17','-proc:none','-encoding','UTF-8','-cp',run_cp,'-d',scratch,str(tests)],check=True);subprocess.run([tool('java'),'-cp',run_cp,'gamecompat.PerspectivePolicyTests',str(a.native_camera)],check=True)
        changed=lambda n:n.startswith(tuple('gamecompat/'+name for name in patched))and n.endswith('.class')
        with zipfile.ZipFile(a.base_agent)as old,zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED)as out:
            for e in old.infolist():
                if not changed(e.filename):out.writestr(e,old.read(e.filename))
            for f in sorted(Path(scratch).rglob('*.class')):
                n=f.relative_to(scratch).as_posix()
                if changed(n):
                    e=zipfile.ZipInfo(n,(2020,1,1,0,0,0));e.compress_type=zipfile.ZIP_DEFLATED;out.writestr(e,shade(f.read_bytes()))
        with zipfile.ZipFile(a.base_agent)as old,zipfile.ZipFile(a.output)as new:
            for n in old.namelist():
                if not changed(n):assert old.read(n)==new.read(n),n
    print(a.output)

if __name__=='__main__':main()
