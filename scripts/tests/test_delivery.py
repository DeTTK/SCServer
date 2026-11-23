import importlib.util,tempfile,unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('delivery',Path(__file__).resolve().parents[1]/'delivery.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
class DeliveryTests(unittest.TestCase):
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
