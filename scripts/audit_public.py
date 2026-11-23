"""Conservative source-only preflight; not a proof of licensing or absence of all secrets."""
from pathlib import Path
import json,re,sys,os,hashlib
ROOT=Path(__file__).resolve().parents[1]
SKIP={'.git','.local','build','dist','__pycache__','target'}
BINARY={'.jar','.class','.dll','.exe','.zip','.7z','.xeon','.szenc','.szr','.szmap','.p12','.pfx','.pem','.key','.cer','.sjournal'}
TEXT={'.java','.py','.md','.cs','.rs','.slint','.lock','.mf','.bat','.json','.properties','.yml','.yaml','.toml','.xml','.ps1','.sh','.txt','.h','.c','.cpp','.hpp','.def','.svg'}
SPECIAL={'.gitignore','.gitattributes','.editorconfig','.java-version','LICENSE','NOTICE','Makefile'}
PATTERNS={
    'private_key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'telegram_bot_token':re.compile(r'\b[0-9]{8,12}:[A-Za-z0-9_-]{30,}\b'),
    'github_token':re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b'),
    'credential_literal':re.compile(r'(?i)\b(?:password|passwd|api_key|secret)\s*[:=]\s*["\'][^"\'\n]{6,}["\']'),
    'workstation_path':re.compile(r'\b[DE]:[/\\]'),
}
assets_path=ROOT/'launcher/source-assets.json'
assets=json.loads(assets_path.read_text('utf8'))if assets_path.is_file()else{}
input_path=ROOT/'bootstrap/input-manifest.json'
inputs=json.loads(input_path.read_text('utf8')).get('files',{}) if input_path.is_file() else {}
findings=[];files=0;total=0;seen_inputs=set()
for directory,dirs,names in os.walk(ROOT,followlinks=False):
    for name in list(dirs):
        path=Path(directory)/name
        if name in SKIP:dirs.remove(name)
        elif path.is_symlink() or getattr(path.lstat(),'st_file_attributes',0)&0x400:
            findings.append({'path':path.relative_to(ROOT).as_posix(),'kind':'linked_directory'});dirs.remove(name)
    for name in names:
        if name=='.git':continue # Git worktree administrative pointer is never a published source file.
        path=Path(directory)/name;rel=path.relative_to(ROOT)
        if path.is_symlink() or getattr(path.lstat(),'st_file_attributes',0)&0x400:findings.append({'path':rel.as_posix(),'kind':'linked_file'});continue
        files+=1;size=path.stat().st_size;total+=size
        supplied=inputs.get(rel.as_posix())
        if rel.as_posix().startswith(('bootstrap/inputs/','map/data/')):
            seen_inputs.add(rel.as_posix())
            if supplied is None or size>16*1024*1024 or size!=supplied['size'] or hashlib.sha256(path.read_bytes()).hexdigest()!=supplied['sha256']:findings.append({'path':rel.as_posix(),'kind':'undeclared_or_changed_game_input'})
            continue
        asset=assets.get(rel.as_posix())
        if asset is not None:
            if path.suffix.lower()not in('.png','.ttf','.webp')or not rel.as_posix().startswith('launcher/')or size>4*1024*1024 or size!=asset['size']or hashlib.sha256(path.read_bytes()).hexdigest()!=asset['sha256']:findings.append({'path':rel.as_posix(),'kind':'invalid_design_asset'})
            continue
        if path.suffix.lower() in BINARY or size>2*1024*1024 or (path.suffix.lower() not in TEXT and name not in SPECIAL):findings.append({'path':rel.as_posix(),'kind':'binary_or_unsupported'});continue
        raw=path.read_bytes()
        if b'\0' in raw:findings.append({'path':rel.as_posix(),'kind':'binary_content'});continue
        text=raw.decode('utf8',errors='replace')
        for kind,pattern in PATTERNS.items():
            for match in pattern.finditer(text):findings.append({'path':rel.as_posix(),'line':text.count('\n',0,match.start())+1,'kind':kind})
for missing in set(inputs)-seen_inputs:findings.append({'path':missing,'kind':'missing_game_input'})
report={'result':'FAIL' if findings else 'PASS','files':files,'sourceBytes':total,'findings':findings,'scope':'Pattern and file-type checks only; ownership and complete secret review remain manual'}
print(json.dumps(report,ensure_ascii=False,indent=2))
sys.exit(bool(findings))
