import importlib.util,tempfile,unittest,subprocess,os
from pathlib import Path

spec=importlib.util.spec_from_file_location('delivery',Path(__file__).resolve().parents[1]/'delivery.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
class DeliveryTests(unittest.TestCase):
    def test_partial_index_commit_does_not_require_unchanged_foundation_blob(self):
        with tempfile.TemporaryDirectory()as root:
            root=Path(root)
            def git(*args):return subprocess.run(['git','-C',str(root),*args],check=True,capture_output=True,text=True).stdout.strip()
            git('init');git('config','user.name','Test');git('config','user.email','test@example.invalid')
            old=root/'unchanged.pack';old.write_bytes(b'old immutable runtime');git('add','--',old.name);git('commit','-m','base');parent=git('rev-parse','HEAD');blob=git('rev-parse','HEAD:unchanged.pack')
            old.unlink();(root/'.git/objects'/blob[:2]/blob[2:]).unlink()
            (root/'new.json').write_text('{}');git('add','--','new.json')
            commit=d.commit_index(root,'publish indexed map',os.environ)
            self.assertEqual(git('rev-parse','HEAD'),commit);self.assertEqual(git('rev-parse','HEAD^'),parent)
            self.assertEqual(git('rev-parse','HEAD:unchanged.pack'),blob);self.assertEqual(git('show','HEAD:new.json'),'{}')
    def test_paths_reject_escaping_or_windows_prefixes(self):
        for p in ['../server.jar','server/../file','/file','C:/file','client\\file','server//file']:
            with self.assertRaises(ValueError):d.safe_path(p)
    def test_inventory_only_tracks_approved_payload_and_bundled_mod_target(self):
        with tempfile.TemporaryDirectory()as root:
            root=Path(root);p=root/'mods/command-completion/command-completion.jar';p.parent.mkdir(parents=True);p.write_bytes(b'test')
            rows=d.file_inventory(root);self.assertEqual(rows[0]['target'],'client/mods/command-completion.jar')
            q=root/'server/private.pem';q.parent.mkdir();q.write_text('private')
            with self.assertRaises(ValueError):d.file_inventory(root)
    def test_jar_output_is_deterministic(self):
        with tempfile.TemporaryDirectory()as root:
            a=Path(root)/'a.jar';b=Path(root)/'b.jar';d.deterministic_jar(a,{'b':b'2','a':b'1'});d.deterministic_jar(b,{'a':b'1','b':b'2'});self.assertEqual(a.read_bytes(),b.read_bytes())
