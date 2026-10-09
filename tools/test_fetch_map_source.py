"""Tests for the resumable, verified source fetcher (a wrapper around curl).

A loopback HTTP server with deliberately awkward behaviour is the only network involved. Beyond the
original coverage (verify-then-promote, validated resume, refusals that preserve the prefix, name and
space preconditions), these regressions pin the behaviours that were broken:

  * a FRESH fetch probes before streaming, so an interrupted first attempt still has validators stored
  * Last-Modified survives that round trip (the key mismatch that silently dropped it)
  * a source that changes between the probe and the transfer is not promoted, and the prefix is restored
  * a resume sends If-Range, pinned to the probed validator
  * only the final HTTP header block is parsed, so a redirect's headers cannot leak
  * a .part that is already the full length is verified and promoted with NO request, and a corrupt one
    is refused explicitly rather than probed to death
  * free space is checked before the probe; the probe-headers path is part of the collision check
"""
import hashlib
import http.server
import json
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_map_source as fetcher  # noqa: E402

PAYLOAD = bytes(range(256)) * 20480          # 5 MiB, deterministic


def md5_of(data):
    return hashlib.md5(data).hexdigest()


class Server(http.server.BaseHTTPRequestHandler):
    payload = PAYLOAD
    etag = '"fixed-etag"'
    last_modified = 'Wed, 07 Oct 2026 00:51:44 GMT'
    honour_range = True
    lie_total = False
    cut_after = None
    status = 200
    changed_after_probe = False          # answer a resume transfer as a changed source (200, new body)
    transfer_payload = None
    redirect_from = None                 # e.g. '/redirect' -> 307 to '/source'
    requests = []

    def do_GET(self):
        type(self).requests.append({'range': self.headers.get('Range'), 'path': self.path,
                                    'if_range': self.headers.get('If-Range')})
        if self.redirect_from and self.path == self.redirect_from:
            self.send_response(307)
            self.send_header('Location', '/source')
            self.send_header('Content-Length', '268')          # stale header for the leak test
            self.send_header('ETag', '"stale-from-the-redirect"')
            self.end_headers()
            return
        if self.status != 200:
            self.send_response(self.status)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        asked = self.headers.get('Range')
        start, code, end = 0, 200, len(self.payload) - 1
        open_range = bool(asked and asked.endswith('-'))
        if self.changed_after_probe and asked and open_range:
            body, code, start, end = (self.transfer_payload or self.payload), 200, 0, len(self.payload) - 1
        elif self.honour_range and asked:
            first, _, last = asked.split('=')[1].partition('-')
            start = int(first)
            end = int(last) if last else len(self.payload) - 1
            if start >= len(self.payload):
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{len(self.payload)}')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            body, code = self.payload[start:end + 1], 206
        else:
            body = self.payload
        total = len(self.payload) + (4096 if self.lie_total else 0)
        self.send_response(code)
        self.send_header('Content-Length', str(len(body)))
        if self.etag:
            self.send_header('ETag', self.etag)
        self.send_header('Last-Modified', self.last_modified)
        self.send_header('Accept-Ranges', 'bytes')
        if code == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{total}')
        self.end_headers()
        if self.cut_after is not None:
            self.wfile.write(body[:self.cut_after])
            self.wfile.flush()
            self.close_connection = True
            return
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


class Base(unittest.TestCase):
    payload = PAYLOAD

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self._dir.name)
        Server.requests = []
        for attribute, value in (('payload', self.payload), ('etag', '"fixed-etag"'),
                                 ('last_modified', 'Wed, 07 Oct 2026 00:51:44 GMT'),
                                 ('honour_range', True), ('lie_total', False), ('cut_after', None),
                                 ('status', 200), ('changed_after_probe', False),
                                 ('transfer_payload', None), ('redirect_from', None)):
            setattr(Server, attribute, value)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Server)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self._dir.cleanup()

    @property
    def url(self):
        return f'http://127.0.0.1:{self.server.server_port}/source.osm.pbf'

    @property
    def destination(self):
        return self.root / 'source.osm.pbf'

    @property
    def partial(self):
        return self.root / 'source.osm.pbf.part'

    def run_tool(self, *extra, url=None):
        return fetcher.main(['--url', url or self.url, '--bytes', str(len(self.payload)),
                             '--md5', md5_of(self.payload), '--dir', str(self.root), *extra])

    def fetch(self, *extra, url=None):
        return self.run_tool('--fetch', '--retries', '0', *extra, url=url)

    def receipt(self):
        return json.loads((self.root / 'source.osm.pbf.fetch.json').read_text(encoding='utf-8'))

    def seed_part(self, data):
        self.partial.write_bytes(data)

    def seed_meta(self, **overrides):
        meta = {'url': self.url, 'expected_bytes': len(self.payload),
                'expected_md5': md5_of(self.payload), 'etag': Server.etag,
                'last_modified': Server.last_modified}
        meta.update(overrides)
        fetcher.save_meta(self.destination, meta)

    def meta(self):
        path = self.root / 'source.osm.pbf.part.json'
        return json.loads(path.read_text(encoding='utf-8')) if path.is_file() else {}


class BasicFetchTests(Base):
    def test_fresh_fetch_verifies_then_promotes(self):
        self.assertEqual(self.fetch(), 0)
        self.assertEqual(self.destination.read_bytes(), self.payload)
        self.assertFalse(self.partial.exists(), 'the .part must be gone after promotion')
        self.assertFalse((self.root / 'source.osm.pbf.part.json').exists())
        receipt = self.receipt()
        self.assertEqual(receipt['outcome'], 'ok')
        self.assertEqual((receipt['bytes'], receipt['md5'], receipt['sha256']),
                         (len(self.payload), md5_of(self.payload), hashlib.sha256(self.payload).hexdigest()))

    def test_resume_is_byte_exact(self):
        half = len(self.payload) // 2
        self.seed_part(self.payload[:half])
        self.assertEqual(self.fetch(), 0)
        self.assertEqual(self.destination.read_bytes(), self.payload)
        ranges = [request['range'] for request in Server.requests if request['range']]
        self.assertIn(f'bytes={half}-', ranges)
        self.assertEqual(self.receipt()['resumed_from'], half)

    def test_resume_sends_if_range_pinned_to_the_probed_validator(self):
        half = len(self.payload) // 2
        self.seed_part(self.payload[:half])
        self.assertEqual(self.fetch(), 0)
        transfers = [r for r in Server.requests if r['range'] and r['range'].endswith('-')]
        self.assertTrue(transfers, 'the resume transfer must have happened')
        self.assertEqual(transfers[0]['if_range'], Server.etag,
                         'If-Range must pin the transfer to the probed ETag')

    def test_resume_without_saved_validators_is_still_allowed(self):
        self.seed_part(self.payload[: 1024])
        self.assertEqual(self.fetch(), 0)
        self.assertEqual(self.destination.read_bytes(), self.payload)

    def test_md5_mismatch_keeps_the_part(self):
        code = fetcher.main(['--url', self.url, '--bytes', str(len(self.payload)),
                             '--md5', '0' * 32, '--dir', str(self.root), '--fetch', '--retries', '0'])
        self.assertEqual(code, 1)
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.partial.stat().st_size, len(self.payload))
        self.assertIn('md5 mismatch', self.receipt()['error'])

    def test_broken_connection_is_a_started_failure_that_keeps_the_part(self):
        Server.cut_after = 1 << 20
        self.assertEqual(self.fetch(), 1)
        receipt = self.receipt()
        self.assertEqual(receipt['outcome'], 'failed')
        self.assertTrue(receipt['started'], 'data was written, so the receipt must say so')
        self.assertGreater(receipt['received_bytes'], 0)
        self.assertTrue(self.partial.exists())
        self.assertFalse(self.destination.exists())

    def test_an_already_verified_file_is_not_refetched(self):
        self.assertEqual(self.fetch(), 0)
        Server.requests = []
        self.assertEqual(self.fetch(), 0)
        self.assertEqual(Server.requests, [])
        self.assertIn('already present', self.receipt()['note'])


class Regressions(Base):
    """Each of these fails on the revision before this one."""

    def test_fresh_fetch_probes_before_streaming_and_stores_validators(self):
        Server.etag = None                       # only Last-Modified is available
        Server.cut_after = 1 << 20               # interrupt the FIRST fetch
        self.assertEqual(self.fetch(), 1)
        self.assertTrue(self.partial.exists())
        meta = self.meta()
        self.assertTrue(meta, 'an interrupted first fetch must leave validators behind')
        self.assertEqual(meta.get('last_modified'), Server.last_modified)
        self.assertEqual(self.receipt()['probe']['status'], '206', 'the fresh fetch must probe byte 0')
        self.assertLess(self.partial.stat().st_size, len(self.payload))

    def test_interrupted_first_fetch_then_changed_last_modified_refuses(self):
        Server.etag = None
        Server.cut_after = 1 << 20
        self.assertEqual(self.fetch(), 1)
        prefix = self.partial.read_bytes()
        self.assertTrue(prefix, 'the interrupted fetch must have kept a prefix')
        Server.cut_after = None
        Server.last_modified = 'Fri, 09 Oct 2026 00:00:00 GMT'       # the source moved on
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.partial.read_bytes(), prefix, 'the prefix must be untouched')
        self.assertIn('last-modified', self.receipt()['error'])

    def test_a_change_between_probe_and_transfer_is_not_promoted(self):
        prefix = self.payload[:4096]
        self.seed_part(prefix)
        self.seed_meta()
        Server.changed_after_probe = True                            # the source changed mid-flight
        Server.transfer_payload = b'z' * len(self.payload)
        self.assertEqual(self.fetch(), 1)
        self.assertFalse(self.destination.exists(), 'a wrong-source body must never be promoted')
        self.assertEqual(self.partial.read_bytes(), prefix,
                         'the prefix must be restored, not left with a foreign body appended')
        receipt = self.receipt()
        self.assertEqual(receipt.get('rolled_back_to'), len(prefix))
        self.assertIn('rolled the .part back', receipt['error'])

    def test_only_the_final_redirect_block_is_parsed(self):
        dump = ('HTTP/1.1 307 Temporary Redirect\r\nLocation: /source\r\nContent-Length: 268\r\n'
                'ETag: "stale-from-the-redirect"\r\n\r\n'
                'HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-0/100\r\nETag: "live"\r\n\r\n')
        status, fields = fetcher.parse_final_headers(dump)
        self.assertEqual(status, '206')
        self.assertEqual(fields.get('etag'), '"live"')
        self.assertNotIn('content-length', fields,
                         "a redirect's Content-Length must not leak into the final block")

    def test_a_real_redirect_is_probed_on_its_final_response(self):
        Server.redirect_from = '/redirect'
        url = f'http://127.0.0.1:{self.server.server_port}/redirect'
        self.assertEqual(self.fetch('--name', 'source.osm.pbf', url=url), 0)
        self.assertEqual(self.destination.read_bytes(), self.payload)
        probe = self.receipt()['probe']
        self.assertEqual((probe['status'], probe['etag']), ('206', Server.etag))

    def test_complete_part_is_verified_and_promoted_without_a_request(self):
        self.seed_part(self.payload)
        Server.requests = []
        self.assertEqual(self.fetch(), 0)
        self.assertEqual(Server.requests, [], 'a complete .part is promoted without any HTTP request')
        self.assertEqual(self.destination.read_bytes(), self.payload)
        receipt = self.receipt()
        self.assertTrue(receipt.get('promoted_without_request'))
        self.assertEqual(receipt['md5'], md5_of(self.payload))

    def test_corrupt_complete_part_is_refused_explicitly(self):
        self.seed_part(b'q' * len(self.payload))
        Server.requests = []
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(Server.requests, [])
        self.assertTrue(self.partial.exists(), 'the bad partial is kept, never promoted')
        self.assertFalse(self.destination.exists())
        self.assertIn('exactly the expected size', self.receipt()['error'])

    def test_space_is_checked_before_the_probe(self):
        self.seed_part(b'p' * 1024)
        original = fetcher.free_bytes
        fetcher.free_bytes = lambda path: len(self.payload) - 1024 - 1     # just short of the resume need
        try:
            self.assertEqual(self.fetch(), 2)
        finally:
            fetcher.free_bytes = original
        self.assertEqual(Server.requests, [], 'no request may precede the space check')
        self.assertEqual(self.partial.read_bytes(), b'p' * 1024)

    def test_restart_needs_room_for_the_whole_file(self):
        self.seed_part(b'y' * 1024)
        original = fetcher.free_bytes
        fetcher.free_bytes = lambda path: len(self.payload) - 1
        try:
            self.assertEqual(self.fetch('--restart', '--reserve', '0'), 2)
        finally:
            fetcher.free_bytes = original
        self.assertEqual(Server.requests, [])
        self.assertIn('whole', self.receipt()['error'])

    def test_probe_header_path_is_part_of_the_collision_check(self):
        code = fetcher.main(['--url', self.url, '--bytes', str(len(self.payload)),
                             '--md5', md5_of(self.payload), '--dir', str(self.root),
                             '--receipt', str(self.root / 'source.osm.pbf.probe.headers')])
        self.assertEqual(code, 2)
        self.assertEqual(Server.requests, [])


class RefusalTests(Base):
    def test_server_ignoring_range_refuses_and_preserves_the_prefix(self):
        Server.honour_range = False
        prefix = b'x' * 1024
        self.seed_part(prefix)
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.partial.read_bytes(), prefix)
        self.assertFalse(self.destination.exists())
        receipt = self.receipt()
        self.assertEqual((receipt['outcome'], receipt['started']), ('refused', False))
        self.assertTrue(all(request['range'] for request in Server.requests),
                        'only the ranged probe may have run')

    def test_restart_replaces_the_prefix_after_rechecking_space(self):
        Server.honour_range = False
        self.seed_part(b'x' * 1024)
        self.assertEqual(self.fetch('--restart'), 0)
        self.assertEqual(self.destination.read_bytes(), self.payload)
        self.assertTrue(any('discarding' in event for event in self.receipt()['events']))

    def test_changed_etag_refuses_and_preserves_the_prefix(self):
        prefix = self.payload[:2048]
        self.seed_part(prefix)
        self.seed_meta(etag='"the-old-etag"')
        Server.etag = '"a-different-etag"'
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.partial.read_bytes(), prefix)
        self.assertIn('changed since the partial download', self.receipt()['error'])

    def test_changed_last_modified_refuses_when_no_etag(self):
        prefix = self.payload[:2048]
        self.seed_part(prefix)
        Server.etag = None
        self.seed_meta(etag=None, last_modified='Wed, 01 Jan 2020 00:00:00 GMT')
        Server.last_modified = 'Thu, 08 Oct 2026 00:00:00 GMT'
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.partial.read_bytes(), prefix)
        self.assertIn('last-modified', self.receipt()['error'])

    def test_invalid_range_total_refuses(self):
        prefix = self.payload[:2048]
        self.seed_part(prefix)
        Server.lie_total = True
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.partial.read_bytes(), prefix)
        self.assertIn('total', self.receipt()['error'])

    def test_a_fresh_fetch_refuses_when_the_pin_has_moved(self):
        Server.payload = self.payload + b'x' * 4096
        self.assertEqual(self.fetch(), 2)
        self.assertFalse(self.destination.exists())
        self.assertEqual(Server.requests and len(Server.requests), 1, 'only the probe should have run')
        self.assertIn('the pin has moved', self.receipt()['error'])

    def test_a_transfer_that_overshoots_its_declared_size_fails(self):
        # The probe declares the right size, the transfer then sends more: the pre-write cap catches it.
        original = Server.do_GET

        def overshoot(self_):
            if self_.headers.get('Range'):
                original(self_)                       # the probe stays honest
                return
            body = Server.payload + b'x' * 4096
            self_.send_response(200)
            self_.send_header('Content-Length', str(len(body)))   # declares more than expected
            self_.end_headers()
            self_.wfile.write(body)

        Server.do_GET = overshoot
        try:
            self.assertEqual(self.fetch(), 1)
        finally:
            Server.do_GET = original
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.receipt()['outcome'], 'failed')

    def test_a_body_longer_than_its_declaration_is_still_verified(self):
        # curl stops at the declared length, so the extra bytes are never written and the md5 decides.
        original = Server.do_GET

        def under_declare(self_):
            if self_.headers.get('Range'):
                original(self_)
                return
            self_.send_response(200)
            self_.send_header('Content-Length', str(len(Server.payload)))
            self_.end_headers()
            self_.wfile.write(Server.payload + b'x' * 4096)

        Server.do_GET = under_declare
        try:
            self.assertEqual(self.fetch(), 0)
        finally:
            Server.do_GET = original
        self.assertEqual(self.destination.read_bytes(), self.payload)

    def test_a_lying_transfer_range_rolls_the_part_back(self):
        prefix = self.payload[:4096]
        self.seed_part(prefix)
        original = Server.do_GET

        def dishonest(self_):
            asked = self_.headers.get('Range')
            if asked and asked.endswith('-'):        # an open range is the transfer, not the probe
                self_.send_response(206)
                self_.send_header('Content-Length', str(len(Server.payload)))
                self_.send_header('Content-Range', f'bytes 0-{len(Server.payload) - 1}/{len(Server.payload)}')
                self_.end_headers()
                self_.wfile.write(Server.payload)
                return
            original(self_)

        Server.do_GET = dishonest
        try:
            self.assertEqual(self.fetch(), 1)
        finally:
            Server.do_GET = original
        self.assertEqual(self.partial.read_bytes(), prefix, 'the .part must be rolled back to the prefix')
        self.assertIn('rolled the .part back', self.receipt()['error'])


class PreconditionTests(Base):
    def test_oversized_partial_needs_explicit_restart(self):
        before = self.payload + b'preserved evidence'
        self.seed_part(before)
        self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.partial.read_bytes(), before)
        self.assertEqual(Server.requests, [])
        self.assertIn('--restart', self.receipt()['error'])

    def test_probe_timeout_becomes_a_refusal_receipt(self):
        with patch('fetch_map_source.run_curl', side_effect=subprocess.TimeoutExpired('curl', 120)):
            self.assertEqual(self.fetch(), 2)
        self.assertEqual(self.receipt()['outcome'], 'refused')
        self.assertIn('timed out', self.receipt()['error'])

    def test_transfer_changed_last_modified_restores_prefix(self):
        Server.etag = None
        prefix = self.payload[:4096]
        self.seed_part(prefix)
        self.seed_meta()
        original = Server.do_GET
        def changed(handler):
            asked = handler.headers.get('Range', '')
            if asked.endswith('-'):
                Server.last_modified = 'Fri, 09 Oct 2026 00:00:00 GMT'
            original(handler)
        with patch.object(Server, 'do_GET', changed):
            self.assertEqual(self.fetch(), 1)
        self.assertEqual(self.partial.read_bytes(), prefix)
        self.assertIn('Last-Modified', self.receipt()['error'])

    def test_free_space_is_checked_before_any_request(self):
        original = fetcher.free_bytes
        fetcher.free_bytes = lambda path: 1024
        try:
            self.assertEqual(self.fetch(), 2)
        finally:
            fetcher.free_bytes = original
        self.assertEqual(Server.requests, [])
        self.assertIn('needs', self.receipt()['error'])

    def test_plan_mode_makes_no_request(self):
        self.assertEqual(self.run_tool(), 0)
        self.assertEqual(Server.requests, [])
        receipt = self.receipt()
        self.assertEqual(receipt['outcome'], 'plan')
        self.assertIn('restart_needs_bytes', receipt)

    def test_unsafe_names_and_collisions_are_refused(self):
        for extra in (['--name', '../escape.pbf'], ['--name', 'a.part'], ['--name', 'CON'],
                      ['--name', 'nul.pbf'], ['--name=-x'], ['--reserve', '-1']):
            with self.subTest(extra):
                self.assertEqual(self.run_tool(*extra), 2)
        self.assertEqual(Server.requests, [])

    def test_receipt_may_not_collide_with_the_output(self):
        code = fetcher.main(['--url', self.url, '--bytes', str(len(self.payload)),
                             '--md5', md5_of(self.payload), '--dir', str(self.root),
                             '--receipt', str(self.destination)])
        self.assertEqual(code, 2)

    def test_plain_http_to_a_real_host_and_credentials_are_refused(self):
        for url in ('http://download.geofabrik.de/x.osm.pbf',
                    'https://user:pass@download.geofabrik.de/x.osm.pbf',
                    'ftp://download.geofabrik.de/x.osm.pbf',
                    'https:///x.osm.pbf'):
            with self.subTest(url):
                self.assertEqual(fetcher.main(['--url', url, '--bytes', '10', '--md5', '0' * 32,
                                               '--dir', str(self.root)]), 2)

    def test_malformed_expected_md5_and_size_are_refused(self):
        for bad in ('abc', 'z' * 32, '0' * 31):
            with self.subTest(bad):
                self.assertEqual(fetcher.main(['--url', self.url, '--bytes', '10', '--md5', bad,
                                               '--dir', str(self.root)]), 2)
        self.assertEqual(fetcher.main(['--url', self.url, '--bytes', '0', '--md5', md5_of(self.payload),
                                       '--dir', str(self.root)]), 2)

    def test_curl_command_pins_https_for_the_request_and_every_redirect(self):
        argv = fetcher.curl_argv('curl', self.url, ['--output', 'x'])
        for flag in ('--proto', '--proto-redir', '--max-redirs', '--fail'):
            self.assertIn(flag, argv)
        self.assertIn('=https', argv)

    def test_https_url_is_accepted(self):
        self.assertEqual(fetcher.check_url('https://download.geofabrik.de/europe-261006.osm.pbf'),
                         'https://download.geofabrik.de/europe-261006.osm.pbf')
        self.assertEqual(fetcher.check_url('http://127.0.0.1:8000/x'), 'http://127.0.0.1:8000/x')


class ContentRangeTests(unittest.TestCase):
    def test_parses_complete_ranges_only(self):
        self.assertEqual(fetcher.content_range('bytes 100-200/1000', 1000), (100, 200, 1000))
        for bad in ('bytes 100-200/*', 'bytes 200-100/1000', 'bytes 100-200', 'chars 1-2/3', '', None,
                    'bytes 1-2/999'):
            with self.subTest(bad):
                with self.assertRaises(ValueError):
                    fetcher.content_range(bad, 1000)


class IfRangeTests(unittest.TestCase):
    def test_prefers_a_strong_etag_and_falls_back_to_last_modified(self):
        self.assertEqual(fetcher.if_range_value('"abc"', 'Wed, 07 Oct 2026 00:51:44 GMT'), '"abc"')
        self.assertEqual(fetcher.if_range_value('W/"weak"', 'Wed, 07 Oct 2026 00:51:44 GMT'),
                         'Wed, 07 Oct 2026 00:51:44 GMT')
        self.assertEqual(fetcher.if_range_value(None, 'Wed, 07 Oct 2026 00:51:44 GMT'),
                         'Wed, 07 Oct 2026 00:51:44 GMT')
        self.assertIsNone(fetcher.if_range_value(None, None))


if __name__ == '__main__':
    unittest.main()
