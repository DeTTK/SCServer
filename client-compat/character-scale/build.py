"""Build our optional built-in adapter; no installed game files are needed to compile."""
from pathlib import Path
import argparse,json,os,shutil,subprocess,tempfile,zipfile

root=Path(__file__).resolve().parent;repo=root.parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--runtime',type=Path,help='Optional canonical client runtime for native linkage verification');args=parser.parse_args()
def tool(name):
    home=os.environ.get('JAVA_HOME');path=Path(home)/'bin'/(name+('.exe' if os.name=='nt' else '')) if home else shutil.which(name)
    if not path or not Path(path).is_file():raise SystemExit('JDK 17 required: '+name)
    return str(path)
export='--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED'
output=repo/'build/character-scale.jar';output.parent.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='character-scale-',dir=repo/'build') as scratch:
    scratch=Path(scratch);classes=scratch/'classes';classes.mkdir();helpers=scratch/'tools';helpers.mkdir()
    sources=[*(root/'src').rglob('*.java'),*(repo/'shared/player-scale/src').rglob('*.java')]
    subprocess.run([tool('javac'),'-source','17','-target','17',export,'-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
    subprocess.run([tool('javac'),export,'-encoding','UTF-8','-cp',str(classes),'-d',str(helpers),*map(str,(root/'tools').glob('*.java'))],check=True)
    subprocess.run([tool('java'),export,'-cp',str(helpers),'BridgeBuilder',str(classes)],check=True)
    hashes=json.loads((root/'native-hashes.json').read_text('utf8'))
    (classes/'playerscale-native.properties').write_text(''.join(k+'='+v+'\n' for k,v in hashes.items()),'utf8')
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as jar:
        manifest=zipfile.ZipInfo('META-INF/MANIFEST.MF',(2020,1,1,0,0,0));manifest.compress_type=zipfile.ZIP_DEFLATED
        jar.writestr(manifest,'Manifest-Version: 1.0\nPremain-Class: playerscale.ScaleAgent\n\n')
        for file in sorted(classes.rglob('*')):
            if file.is_file():
                entry=zipfile.ZipInfo(file.relative_to(classes).as_posix(),(2020,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;jar.writestr(entry,file.read_bytes())
    tests=[*(repo/'shared/player-scale/src').rglob('*.java'),*(root/'tests').rglob('*.java')]
    subprocess.run([tool('javac'),'--release','17','-encoding','UTF-8','-d',str(helpers),*map(str,tests)],check=True)
    subprocess.run([tool('java'),'-cp',str(helpers),'playerscale.ScaleTests'],check=True)
    if args.runtime:subprocess.run([tool('java'),export,'-Xverify:all','-cp',str(helpers)+os.pathsep+str(output),'playerscale.NativeAdapterCheck',str(args.runtime.resolve()),str(output)],check=True)
print(output)
