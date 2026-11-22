"""Build the startup compatibility agent with a pinned ASM JAR; no game files needed."""
from pathlib import Path
import argparse,hashlib,os,shutil,struct,subprocess,tempfile,zipfile

def relocate(body):
    count=struct.unpack_from('>H',body,8)[0]
    output=bytearray(body[:10]); position=10; index=1
    sizes={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
    while index<count:
        tag=body[position]; position+=1; output.append(tag)
        if tag==1:
            length=struct.unpack_from('>H',body,position)[0]; position+=2
            value=body[position:position+length].replace(b'org/objectweb/asm',b'sz/playguard/asm').replace(b'org.objectweb.asm',b'sz.playguard.asm'); position+=length
            output.extend(struct.pack('>H',len(value))); output.extend(value)
        else:
            length=sizes[tag]; output.extend(body[position:position+length]); position+=length
            if tag in (5,6): index+=1
        index+=1
    output.extend(body[position:]); return output

def main():
    root=Path(__file__).resolve().parents[2]
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--asm',type=Path,required=True,help='ASM 9.7.1 JAR')
    parser.add_argument('--output',type=Path,default=root/'build/startup-guard.jar')
    args=parser.parse_args()
    if hashlib.sha256(args.asm.read_bytes()).hexdigest()!='8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281':parser.error('ASM 9.7.1 checksum mismatch')
    home=os.environ.get('JAVA_HOME');javac=str(Path(home)/'bin'/('javac.exe'if os.name=='nt'else'javac'))if home else shutil.which('javac')
    if not javac:parser.error('JDK 17 required')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='startup-compile-',dir=args.output.parent)as scratch:
        subprocess.run([javac,'--release','17','-proc:none','-encoding','UTF-8','-cp',str(args.asm.resolve()),'-d',scratch,str(Path(__file__).with_name('StartupTextureAgent.java'))],check=True)
        entries={'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\nPremain-Class: StartupTextureAgent\n\n','META-INF/LICENSE-ASM.txt':(root/'client/resource-compat/ASM-LICENSE.txt').read_bytes()}
        for path in Path(scratch).glob('StartupTextureAgent*.class'):entries[path.name]=relocate(path.read_bytes())
        with zipfile.ZipFile(args.asm)as library:
            for name in library.namelist():
                if name.startswith('org/objectweb/asm/')and name.endswith('.class'):entries[name.replace('org/objectweb/asm','sz/playguard/asm')]=relocate(library.read(name))
        with zipfile.ZipFile(args.output,'w',zipfile.ZIP_DEFLATED)as target:
            for name,body in sorted(entries.items()):
                entry=zipfile.ZipInfo(name,(2020,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;target.writestr(entry,body)
    print(args.output)

if __name__=='__main__':main()
