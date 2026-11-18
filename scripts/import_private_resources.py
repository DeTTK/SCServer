"""Import four pinned resources from your compatible private server JAR, never its classes."""
from pathlib import Path
import argparse,hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--server-jar',type=Path,required=True)
a=p.parse_args()
lock=json.loads((ROOT/'config/private-resources.lock.json').read_text('utf8'))
verified={}
with zipfile.ZipFile(a.server_jar) as source:
    for name,digest in lock.items():
        entries=[entry for entry in source.infolist() if entry.filename==name]
        if len(entries)!=1 or entries[0].file_size>4*1024*1024:
            p.error('Missing, duplicate or oversized resource: '+name)
        raw=source.read(entries[0])
        if hashlib.sha256(raw).hexdigest()!=digest:p.error('Incompatible private resource: '+name)
        verified[name]=raw
# Validate every entry before writing anything; paths come only from the checked-in lock.
for name,raw in verified.items():
    target=ROOT/'.local/server-resources'/name
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(raw)
print('Imported 4 pinned private resources into ignored .local/server-resources; no classes copied')
