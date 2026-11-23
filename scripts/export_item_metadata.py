"""Merge genuine client and headless factory exports into the private startup snapshot."""
import argparse,gzip,hashlib,json
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['client','host','definitions','output']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();client=json.loads(a.client.read_text('utf8'));host=json.loads(a.host.read_text('utf8'))
    if client.get('errors'):raise SystemExit('Client export has errors; do not publish an incomplete snapshot')
    definitions=json.loads(a.definitions.read_text('utf8'))
    for name in ['native-builtin-definitions.json','native-block-item-definitions.json','native-developer-item-definitions.json','native-legacy-block-item-definitions.json']:
        path=a.definitions.with_name(name)
        if path.is_file():definitions.update(json.loads(path.read_text('utf8'))['items'])
    rows={**host['rows'],**client['rows']};missing=definitions.keys()-rows.keys()
    if missing:raise SystemExit('Native factories not exported: '+','.join(sorted(missing,key=int)))
    rows={k:rows[k] for k in sorted(definitions,key=int)}
    for key,row in rows.items():
        if set(row)!={'width','height','limit'} or any(type(v)is not int for v in row.values()) or not(1<=int(key)<=65535 and 1<=row['width']<=256 and 1<=row['height']<=256 and row['limit']>=1):raise SystemExit('Invalid native row: '+key)
    data={'version':1,'definitionsSha256':hashlib.sha256(a.definitions.read_bytes()).hexdigest(),'rows':rows}
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_bytes(gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0))
    print('Native rows:',len(rows),'SHA256:',hashlib.sha256(a.output.read_bytes()).hexdigest())
if __name__=='__main__':main()
