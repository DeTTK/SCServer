from pathlib import Path
import sys,tempfile,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from game_layout import resolve_game

class GameLayoutTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'Steam game with spaces'
        (self.root/'assets').mkdir(parents=True);(self.root/'modassets').mkdir()
    def touch(self,path):
        f=self.root/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text('fixture')
    def variant(self,name):
        self.touch(name+'/classes.jar');self.touch(name+'/patchassets/assets/bundle/configs.xeon')
    def test_steam_global(self):
        self.variant('bin_global');v=resolve_game(self.root)
        self.assertEqual(v['variant'],'bin_global');self.assertEqual(v['root'],self.root)
        self.assertFalse((self.root/'classes.jar').exists())
    def test_variants_never_mix(self):
        self.variant('bin_global');self.touch('bin_ru/classes.jar')
        self.assertEqual(resolve_game(self.root)['variant'],'bin_global')
        self.variant('bin_ru');self.assertEqual(resolve_game(self.root)['variant'],'bin_ru')
        selected=resolve_game(self.root/'bin_global')['selected']
        self.assertEqual(resolve_game(selected)['variant'],'bin_global')
    def test_classic(self):
        self.touch('classes.jar');self.touch('modassets/assets/bundle/configs.xeon')
        self.assertEqual(resolve_game(self.root)['variant'],'launcher')
    def test_partial_rejected(self):
        self.touch('bin_ru/classes.jar');self.touch('bin_global/patchassets/assets/bundle/configs.xeon')
        with self.assertRaises(ValueError):resolve_game(self.root)
    def test_file_selection(self):
        self.variant('bin_ru');self.assertEqual(resolve_game(self.root/'bin_ru/classes.jar')['root'],self.root)

if __name__=='__main__':unittest.main()
