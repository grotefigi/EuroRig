import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from verify_android_sdk import verify


class AndroidSdkTests(unittest.TestCase):
    def test_pinned_platform_and_mislabelled_missing_corrupt_components(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            platform = sdk / 'platforms/android-36'
            tools = sdk / 'build-tools/36.0.0'
            platform.mkdir(parents=True)
            tools.mkdir(parents=True)
            metadata = 'AndroidVersion.ApiLevel=36\nPlatform.Version=16\nPkg.Revision=2\n'
            (platform / 'source.properties').write_text(metadata)
            (platform / 'android.jar').write_bytes(b'controlled platform')
            (tools / 'source.properties').write_text('Pkg.Revision=36.0.0\n')
            (tools / 'aapt2').touch()
            with patch('verify_android_sdk.PLATFORM_SHA256', hashlib.sha256(b'controlled platform').hexdigest()):
                verify(sdk)
                (platform / 'android.jar').write_bytes(b'renamed newer platform')
                with self.assertRaisesRegex(ValueError, 'JAR'):
                    verify(sdk)
                (platform / 'android.jar').write_bytes(b'controlled platform')
                (platform / 'source.properties').write_text(metadata.replace('Platform.Version=16', 'Platform.Version=17'))
                with self.assertRaisesRegex(ValueError, 'Android 16'):
                    verify(sdk)
                (platform / 'source.properties').write_text(metadata)
                (tools / 'aapt2').unlink()
                with self.assertRaisesRegex(ValueError, 'aapt2'):
                    verify(sdk)
