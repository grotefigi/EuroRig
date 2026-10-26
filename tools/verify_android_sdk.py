"""Refuse missing or mislabeled pinned Android build components."""
import hashlib
from pathlib import Path
import sys

# Official Android 16/API 36 platform revision 2, shared by all host archives.
PLATFORM_SHA256 = 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a'


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.startswith('#'))


def verify(sdk):
    platform = Path(sdk) / 'platforms/android-36'
    props = properties(platform / 'source.properties')
    if any(props.get(k) != v for k, v in {
        'AndroidVersion.ApiLevel': '36', 'Platform.Version': '16', 'Pkg.Revision': '2',
    }.items()):
        raise ValueError('Expected Android 16/API 36 platform revision 2')
    with (platform / 'android.jar').open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != PLATFORM_SHA256:
            raise ValueError('Android platform JAR does not match official API 36 revision 2')
    tools = Path(sdk) / 'build-tools/36.0.0'
    if properties(tools / 'source.properties').get('Pkg.Revision') != '36.0.0':
        raise ValueError('Expected Android build-tools 36.0.0')
    if not any((tools / name).is_file() for name in ('aapt2', 'aapt2.exe')):
        raise ValueError('Android build-tools aapt2 is missing')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
    print('GENUINE_API36_VERIFIED')
