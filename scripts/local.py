"""Connect private portable bundles without copying their contents into the repository."""
from game_layout import resolve_game
from pathlib import Path
import argparse,json,os,subprocess
ROOT=Path(__file__).resolve().parents[1];SETTINGS=ROOT/'.local/paths.json'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['configure','doctor','open-client','open-server'])
for name in ['client-bundle','server-bundle','game','map']:p.add_argument('--'+name)
a=p.parse_args()
if a.action=='configure':
    values={key:getattr(a,key.replace('-','_')) for key in ['client-bundle','server-bundle','game','map']}
    if not all(values.values()):p.error('Set --client-bundle, --server-bundle, --game and --map')
    for key,value in values.items():
        if not Path(value).is_dir():p.error('Missing directory: '+key)
    try:values['game']=str(resolve_game(values['game'])['selected'])
    except ValueError as error:p.error(str(error))
    SETTINGS.parent.mkdir(exist_ok=True);SETTINGS.write_text(json.dumps({k:str(Path(v).resolve()) for k,v in values.items()},indent=2),'utf8');print('Local paths saved outside tracked source files')
else:
    if not SETTINGS.exists():p.error('Run configure first; see docs/QUICKSTART.md')
    values=json.loads(SETTINGS.read_text('utf8'))
    required={'client-bundle':['Launcher.exe','runtime/classes','compat/game-resource-compat.jar'],'server-bundle':['ServerManager.exe','server.jar','fixtures','runtime/classes'],'game':[],'map':['region','registry.json']}
    try:resolve_game(values['game'])
    except ValueError as error:raise SystemExit(str(error))
    missing=[]
    for kind,names in required.items():
        for name in names:
            if not (Path(values[kind])/name).exists():missing.append(kind+'/'+name)
    if missing:raise SystemExit('Missing private inputs: '+', '.join(missing))
    print('PASS: required private bundle paths exist. ABI/gameplay not verified.')
    if a.action.startswith('open-'):
        if os.name!='nt':raise SystemExit('Portable desktop bundles require Windows')
        role=a.action[5:];folder=Path(values[role+'-bundle']);name='Launcher.exe' if role=='client' else 'ServerManager.exe'
        subprocess.Popen([str(folder/name)],cwd=folder)
