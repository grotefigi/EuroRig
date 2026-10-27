import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from roadmap_progress import atomic_write, handler_for, snapshot, update


class ProgressTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        atomic_write(self.root/'plan.json', {'packages': [{'id': 'A1'}, {'id': 'A2'}], 'batches': [{'id': 'DS01'}]})
        for lane, ids in [('codex', ['A1', 'A2']), ('deepseek', ['DS01'])]:
            atomic_write(self.root/(lane+'.json'), {'lane': lane, 'updated_at': '2026-10-25T00:00:00+00:00',
                         'current': 'Not started', 'history': [], 'items': {i: {'status': 'pending', 'evidence': None, 'blocker': None} for i in ids}})

    def test_only_accepted_packages_change_overall_percentage(self):
        update(self.root, 'codex', 'A1', 'partial', current='Working')
        update(self.root, 'deepseek', 'DS01', 'submitted', evidence='candidate report')
        self.assertEqual(0, snapshot(self.root)['overall']['percent'])
        self.assertEqual(100, snapshot(self.root)['deepseek']['percent'])
        update(self.root, 'codex', 'A1', 'complete', evidence='independent receipt')
        self.assertEqual(50, snapshot(self.root)['overall']['percent'])

    def test_show_handles_romanian_text_on_a_legacy_console(self):
        update(self.root, 'codex', current='România: restricții')
        env = dict(os.environ, PYTHONIOENCODING='cp1252')
        command = [sys.executable, str(Path(__file__).with_name('roadmap_progress.py')), '--data-dir', str(self.root), 'show']
        result = subprocess.run(command, capture_output=True, env=env, check=True)
        self.assertEqual('România: restricții', json.loads(result.stdout)['lanes']['codex']['current'])

    def test_lane_updates_preserve_the_other_writer(self):
        original = (self.root/'codex.json').read_bytes()
        update(self.root, 'deepseek', 'DS01', 'in_progress', current='Indexing sources')
        self.assertEqual(original, (self.root/'codex.json').read_bytes())
        state = snapshot(self.root)['lanes']['deepseek']
        self.assertEqual('Indexing sources', state['current'])
        self.assertEqual('DS01', state['history'][-1]['item'])

    def test_completion_and_submission_require_fresh_evidence(self):
        for lane, item, status in [('codex', 'A1', 'complete'), ('deepseek', 'DS01', 'submitted')]:
            with self.subTest(lane=lane), self.assertRaises(ValueError):
                update(self.root, lane, item, status)
        self.assertEqual(0, snapshot(self.root)['overall']['accepted'])

    def test_worker_cannot_claim_accepted_completion(self):
        with self.assertRaises(ValueError):
            update(self.root, 'deepseek', 'DS01', 'complete', evidence='self report')

    def test_blockers_and_unknown_items_are_validated_without_writes(self):
        original = (self.root/'codex.json').read_bytes()
        for item, status in [('A1', 'blocked'), ('UNKNOWN', 'in_progress')]:
            with self.subTest(item=item), self.assertRaises(ValueError):
                update(self.root, 'codex', item, status)
        self.assertEqual(original, (self.root/'codex.json').read_bytes())
        update(self.root, 'codex', 'A1', 'blocked', blocker='Missing source artifact')
        self.assertEqual('Missing source artifact', snapshot(self.root)['lanes']['codex']['items']['A1']['blocker'])

    def test_optimistic_update_rejects_stale_writer(self):
        update(self.root, 'codex', current='First update')
        with self.assertRaises(ValueError):
            update(self.root, 'codex', current='Stale overwrite', expected_updated_at='2026-10-25T00:00:00+00:00')
        self.assertEqual('First update', snapshot(self.root)['lanes']['codex']['current'])

    def test_incomplete_or_corrupt_state_does_not_show_a_green_snapshot(self):
        state = json.loads((self.root/'codex.json').read_text())
        del state['items']['A2']
        atomic_write(self.root/'codex.json', state)
        with self.assertRaises(ValueError):
            snapshot(self.root)
        plan = json.loads((self.root/'plan.json').read_text())
        plan['packages'].append(plan['packages'][0])
        atomic_write(self.root/'plan.json', plan)
        with self.assertRaisesRegex(ValueError, 'Plan IDs'):
            snapshot(self.root)

    def test_live_endpoint_refreshes_and_serves_only_the_dashboard(self):
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(self.root))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = 'http://127.0.0.1:'+str(server.server_port)
            with urllib.request.urlopen(base+'/') as response:
                self.assertIn(b'EuroRig', response.read())
            with urllib.request.urlopen(base+'/api/progress') as response:
                self.assertEqual(0, json.load(response)['overall']['accepted'])
                self.assertEqual('no-store', response.headers['Cache-Control'])
            update(self.root, 'codex', 'A1', 'complete', evidence='receipt')
            with urllib.request.urlopen(base+'/api/progress') as response:
                self.assertEqual(1, json.load(response)['overall']['accepted'])
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(base+'/../codex.json')
            self.assertEqual(404, error.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__':
    unittest.main()
