"""Build the Windows player ZIP. Uses Python and a JDK only on the build machine."""
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

mod = Path(__file__).resolve().parent
root = mod.parents[1]
work = root / "build" / "area-export"
stage = work / "package" / "Client" / "mods"
classes = work / "classes"
classes.mkdir(parents=True, exist_ok=True)
stage.mkdir(parents=True, exist_ok=True)
def jdk(name):
    return str(Path(os.environ["JAVA_HOME"])/"bin"/name) if os.environ.get("JAVA_HOME") else name
subprocess.run([jdk("javac"), "--release","17","-encoding","UTF-8","-d",str(classes),
    *map(str,(root/"sdk/api").rglob("*.java")), *map(str,(mod/"src").rglob("*.java"))],check=True)
subprocess.run([jdk("jar"),"cf",str(stage/"area-export.jar"),"-C",str(classes),"areaexport","-C",str(mod),"stalcraft-mod.properties"],check=True)
converter=mod/"converter"
data=[]
for path in sorted(converter.glob("*.json")):
    data.extend(["--add-data",str(path)+os.pathsep+"."])
subprocess.run([sys.executable,"-m","PyInstaller","--noconfirm","--clean","--onedir","--windowed","--name","AreaExport",
    "--specpath",str(work),"--distpath",str(work/"dist"),"--workpath",str(work/"freeze"),*data,str(converter/"menu.py")],check=True)
dest=stage/"area-export"
if dest.exists(): shutil.rmtree(dest)
shutil.copytree(work/"dist/AreaExport",dest)
shutil.copyfile(mod/"README.md",work/"package/README.md")
out=root/"build/SCServer-AreaExport-Windows.zip"
with zipfile.ZipFile(out,"w",zipfile.ZIP_DEFLATED) as archive:
    for p in sorted((work/"package").rglob("*")):
        if p.is_file(): archive.write(p,p.relative_to(work/"package"))
print(out)
