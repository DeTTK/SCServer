import importlib.util,json,hashlib,sys,tempfile,unittest
from pathlib import Path
SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
spec=importlib.util.spec_from_file_location('player_build',SCRIPTS/'build_player.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)

class PlayerInputsTests(unittest.TestCase):
    def test_parts_are_verified_before_becoming_build_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'bootstrap/inputs').mkdir(parents=True)
            parts=['bootstrap/inputs/a.part-0000','bootstrap/inputs/a.part-0001']
            files={}
            for name,body in zip(parts,[b'first',b'second']):
                (root/name).write_bytes(body);files[name]={'size':len(body),'sha256':hashlib.sha256(body).hexdigest()}
            row={'size':11,'sha256':hashlib.sha256(b'firstsecond').hexdigest(),'parts':parts}
            (root/'bootstrap/input-manifest.json').write_text(json.dumps({'objects':{'runtime/game.jar':row},'files':files}))
            original=builder.ROOT
            try:
                builder.ROOT=root;builder.restore(root/'restored')
                self.assertEqual((root/'restored/runtime/game.jar').read_bytes(),b'firstsecond')
                (root/parts[1]).write_bytes(b'tamper')
                with self.assertRaisesRegex(ValueError,'checksum'):builder.restore(root/'rejected')
            finally:builder.ROOT=original

    def test_input_paths_cannot_escape_checkout(self):
        for value in ['../x','/absolute','a/../b','a//b','C:/file','a\\b']:
            with self.assertRaises(ValueError):builder.safe(value)
