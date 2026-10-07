"""Regression tests for the corridor measurement runner.

Both failures these cover are invisible off-device: the storage preflight reads smoke_android's module
global before it is set, and a fresh install has no preference files, which the read must treat as
expected without accepting a real read failure.
"""
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import measure_corridors


class Stop(Exception):
    """Abort the runner once the behaviour under test has been observed."""


def corpus(root):
    path = Path(root) / "corpus.json"
    path.write_text(json.dumps({"corridors": [{"id": "a", "a": [45.0, 27.0], "b": [45.1, 27.1]}],
                                "truck": {"height": 4.0}, "modes": ["DEFAULT"]}), encoding="utf-8")
    return path


def qa_region(root):
    """A minimal region that satisfies the preflight's manifest and checksum gates."""
    folder = Path(root) / "region"
    folder.mkdir()
    digests = {}
    for name in ("routing.tar", "display.sqlite"):
        payload = name.encode() * 8
        (folder / name).write_bytes(payload)
        digests[name] = hashlib.sha256(payload).hexdigest()
    (folder / "manifest.json").write_text(json.dumps(
        {"native_version": "0.6.3", "format": 2, "sha256": digests}), encoding="utf-8")
    return folder


def newline_absent(name):
    """The stderr adb returns when a preference file simply does not exist yet."""
    return f"cat: shared_prefs/{name}: No such file or directory\n".encode()


class DevicePreflight(unittest.TestCase):
    def setUp(self):
        # The runner has to set this itself; a test that inherited a value could not detect the regression.
        if hasattr(measure_corridors.s, "device"):
            del measure_corridors.s.device

    def observe(self, root, *extra):
        calls = []

        def fake(*args, **kwargs):
            calls.append((measure_corridors.s.device, args))
            raise Stop

        arguments = ["measure_corridors.py", str(corpus(root)), "--output", str(Path(root) / "out.json"),
                     "--device", "emulator-5558", *extra]
        with mock.patch.object(measure_corridors.s, "run", fake), mock.patch("sys.argv", arguments):
            with self.assertRaises(Stop):
                measure_corridors.main()
        return calls

    def test_device_is_set_before_the_storage_preflight(self):
        with tempfile.TemporaryDirectory() as root:
            calls = self.observe(root, "--qa-region", str(qa_region(root)))
        self.assertEqual(calls[0][0], "emulator-5558", "the device must be set before the first command")
        self.assertEqual(calls[0][1][:3], ("shell", "df", "-k"), "the storage preflight is the first command")

    def test_device_is_set_before_the_first_command_without_a_region(self):
        with tempfile.TemporaryDirectory() as root:
            calls = self.observe(root)
        self.assertEqual(calls[0][0], "emulator-5558", "the device must be set before the first command")
        self.assertEqual(calls[0][1][:4], ("shell", "run-as", "org.eurorig.app", "cat"))

    def test_physical_testing_refuses_active_guidance_before_mutating_device(self):
        with tempfile.TemporaryDirectory() as root:
            calls = []
            def run(*args, **kwargs):
                calls.append(args)
                return b'ServiceRecord{123 org.eurorig.app/.NavService}'
            arguments = ['measure_corridors.py', str(corpus(root)), '--output', str(Path(root)/'out.json'),
                         '--device', 'tablet', '--physical']
            with mock.patch.object(measure_corridors.s, 'run', run), mock.patch('sys.argv', arguments):
                with self.assertRaises(SystemExit) as error:
                    measure_corridors.main()
            self.assertEqual(error.exception.code, 2)
            self.assertEqual(calls, [('shell', 'dumpsys', 'activity', 'services', 'org.eurorig.app')])

    def test_physical_testing_refuses_qa_staging_without_any_device_command(self):
        with tempfile.TemporaryDirectory() as root:
            arguments = ['measure_corridors.py', str(corpus(root)), '--output', str(Path(root)/'out.json'),
                         '--device', 'tablet', '--physical', '--qa-region', str(qa_region(root))]
            with mock.patch.object(measure_corridors.s, 'run') as run, mock.patch('sys.argv', arguments):
                with self.assertRaises(SystemExit):
                    measure_corridors.main()
            run.assert_not_called()


class PreferenceReads(unittest.TestCase):
    @staticmethod
    def failing(stderr):
        def run(*args, **kwargs):
            raise subprocess.CalledProcessError(1, "adb", stderr=stderr)
        return run

    def test_reads_dispatch_through_shell(self):
        """exec-out reports a missing file as exit 0 with the message on stdout, so it cannot be caught."""
        seen = []

        def run(*args, **kwargs):
            seen.append(args)
            return b"<map a=\"1\"/>"

        with mock.patch.object(measure_corridors.s, "run", run):
            measure_corridors.read_pref("settings.xml")
        self.assertEqual(seen[0][:4], ("shell", "run-as", "org.eurorig.app", "cat"))

    def test_absent_preference_file_is_expected(self):
        # adb shell translates stderr line endings; the catch must not depend on the exact terminator.
        for terminator in (b"\n", b"\r\n"):
            with self.subTest(terminator):
                stderr = b"cat: shared_prefs/settings.xml: No such file or directory" + terminator
                with mock.patch.object(measure_corridors.s, "run", self.failing(stderr)):
                    self.assertIsNone(measure_corridors.read_pref("settings.xml"))

    def test_present_preference_file_is_returned(self):
        with mock.patch.object(measure_corridors.s, "run", lambda *args, **kwargs: b"<map a=\"1\"/>"):
            self.assertEqual(measure_corridors.read_pref("settings.xml"), b"<map a=\"1\"/>" )

    def test_a_real_read_failure_still_raises(self):
        denied = self.failing(b"run-as: package not debuggable\n")
        with mock.patch.object(measure_corridors.s, "run", denied):
            with self.assertRaises(subprocess.CalledProcessError):
                measure_corridors.read_pref("settings.xml")

    def test_missing_twice_is_preserved_but_changed_preferences_are_not(self):
        self.assertTrue(measure_corridors.preserved(None, None, "settings.xml"))
        with self.assertRaises(RuntimeError) as caught:
            measure_corridors.preserved(b"<map a=\"1\"/>", b"<map a=\"2\"/>", "settings.xml")
        self.assertIn("settings.xml", str(caught.exception))
        with self.assertRaises(RuntimeError):
            measure_corridors.preserved(None, b"<map a=\"1\"/>", "settings.xml")


class FreshInstallRun(unittest.TestCase):
    def test_camera_checks_do_not_use_saved_endpoints_and_keep_failure_receipt(self):
        with tempfile.TemporaryDirectory() as root:
            source=corpus(root);input_payload=json.loads(source.read_bytes());output=Path(root)/'out.json'
            installed=False;commands=[]
            def run(*args,**kwargs):
                nonlocal installed
                commands.append(args)
                if args[0]=='install':installed=True
                if 'shared_prefs/endpoints.xml' in args:
                    return b'<map changed="true"/>' if installed else b'<map changed="false"/>'
                if 'shared_prefs/settings.xml' in args or 'shared_prefs/truck.xml' in args:return b'<map/>'
                if 'instrument' in args:return b'PASS: camera checks\n'
                if str(args[-1]).endswith('native-corridor-results.json'):
                    return json.dumps({'input':input_payload,'results':[]}).encode()
                return b''
            arguments=['measure_corridors.py',str(source),'--output',str(output),'--camera']
            with mock.patch.object(measure_corridors.s,'run',run),mock.patch('sys.argv',arguments):
                with self.assertRaisesRegex(RuntimeError,'changed endpoints'):
                    measure_corridors.main()
            receipt=json.loads(output.read_text(encoding='utf-8'))
            self.assertTrue(receipt['camera_checks']);self.assertFalse(receipt['endpoints_preserved'])
            instrument=next(command for command in commands if 'instrument' in command)
            self.assertIn('cameraCorpus',instrument);self.assertIn('cameraOnly',instrument)
            self.assertNotIn('corridorsOnly',instrument)

    def test_a_fresh_install_without_preference_files_completes(self):
        """The reported failure: the tail read used check=True, so an absent settings.xml aborted the run."""
        with tempfile.TemporaryDirectory() as root:
            input_payload = json.loads(corpus(root).read_bytes())
            results = json.dumps({"input": input_payload,
                                  "results": [{"id": "a", "mode": "DEFAULT", "status": "route", "km": 1.0}]}).encode()

            def fake(*args, **kwargs):
                if args[0] == "shell" and "shared_prefs" in str(args[-1]):
                    raise subprocess.CalledProcessError(1, "adb", stderr=newline_absent(str(args[-1]).split("/")[-1]))
                if str(args[-1]).endswith("native-corridor-results.json"):
                    return results
                if "instrument" in args:
                    return b"PASS: 1\n"
                return b""

            output = Path(root) / "out.json"
            arguments = ["measure_corridors.py", str(corpus(root)), "--output", str(output),
                         "--device", "emulator-5558"]
            with mock.patch.object(measure_corridors.s, "run", fake), mock.patch("sys.argv", arguments):
                measure_corridors.main()
            receipt = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(receipt["settings_preserved"])
            self.assertTrue(receipt["truck_preserved"])


if __name__ == "__main__":
    unittest.main()
