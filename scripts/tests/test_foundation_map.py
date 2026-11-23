import hashlib,importlib.util,json,sys,tempfile,unittest,zipfile,zlib
from pathlib import Path
SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
from replace_foundation_map import replace_map
from export_foundation import digest,encoded

class FoundationMapTests(unittest.TestCase):
    def test_replaces_only_map_and_keeps_runtime_references(self):
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory);old=root/'old';(old/'client').mkdir(parents=True);(old/'bootstrap').mkdir()
            runtime=b'unchanged runtime';h=digest(runtime)
            rows=[{'path':'client/runtime/test.class','size':len(runtime),'sha256':h,'pieces':[{'object':h,'offset':0,'length':len(runtime),'decodedSize':len(runtime),'codec':'raw'}]}]
            raw=encoded(rows);body=zlib.compress(raw);(old/'client/index-0000.json.z').write_bytes(body)
            index={'path':'client/index-0000.json.z','sha256':digest(body),'size':len(body),'decodedSize':len(raw),'files':1,'component':'client'}
            (old/'bootstrap/foundation.json').write_bytes(encoded({'schemaVersion':1,'legacyBaseId':'test','fileCount':1,'expandedBytes':len(runtime),'indices':[index],'objects':[{'sha256':h,'size':len(runtime)}]}))
            info={'format':'test','date':'2026-11-21','stats':{'regions':1},'missingTerrainChunks':[],'productionSavesUsed':False,'sourceData':'must not be published'}
            files={'Map/map-info.json':encoded(info),'Map/registry.json':b'{}','Map/object-codecs.json':b'{}','Map/region/r.0.0.szr':b'fixture','Map/import-provenance/raw.bin':b'private provenance','Map/audit/sources.json':b'private source paths'}
            archive=root/'map.zip'
            with zipfile.ZipFile(archive,'w')as z:
                for n,b in files.items():z.writestr(n,b)
                z.writestr('SHA256.json',encoded([{'file':n,'sha256':digest(b)}for n,b in files.items()]))
            out=root/'new';report=replace_map(old,archive,out)
            descriptor=json.loads((out/report['descriptorPath']).read_bytes())
            self.assertEqual(descriptor['indices'][0],index)
            self.assertIn({'sha256':h,'size':len(runtime)},descriptor['objects'])
            self.assertFalse((out/'bootstrap/foundation.json').exists(),'canonical descriptor changes only in signed publication')
            self.assertEqual(report['mapFiles'],4)
            map_index=descriptor['indices'][1];self.assertTrue(map_index['path'].startswith('map/index-'))
            rows=json.loads(zlib.decompress((out/map_index['path']).read_bytes()))
            self.assertEqual({r['path']for r in rows},{'map/map-info.json','map/registry.json','map/object-codecs.json','map/region/r.0.0.szr'})
            row=next(r for r in rows if r['path']=='map/map-info.json');piece=row['pieces'][0];pack=(out/('bootstrap/objects/'+piece['object']+'.pack')).read_bytes();body=pack[piece['offset']:piece['offset']+piece['length']]
            if piece['codec']=='zlib':body=zlib.decompress(body)
            self.assertNotIn('sourceData',json.loads(body));self.assertEqual(digest(body),row['sha256'])

if __name__=='__main__':unittest.main()
