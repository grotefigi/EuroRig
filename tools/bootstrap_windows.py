"""Download an isolated build toolchain. No registry/PATH changes or licence acceptance."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.toolchain'

def read(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()

def install(name, url, checksum=None, algorithm='sha256', target=None):
    folder = DEST / name
    if (folder / '.complete').exists():
        return folder
    DEST.mkdir(exist_ok=True)
    archive = DEST / (name + '.zip')
    print('Downloading', name, flush=True)
    if not archive.exists():
        with urllib.request.urlopen(url, timeout=60) as response, archive.open('wb') as out:
            while block := response.read(1024 * 1024):
                out.write(block)
    if checksum and hashlib.new(algorithm, archive.read_bytes()).hexdigest() != checksum:
        raise RuntimeError(f'Checksum mismatch: {archive}')
    folder.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        z.extractall(folder)
    (folder / '.complete').write_text(url)
    print('Installed', name, flush=True)
    return folder

def main():
    packages = ET.fromstring(read('https://dl.google.com/android/repository/repository2-3.xml'))
    selected = []
    for path in ['platforms;android-36', 'build-tools;36.0.0', 'platform-tools']:
        package = next(p for p in packages.findall('remotePackage') if p.attrib['path'] == path)
        archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in (None, 'windows'))
        selected.append((path.replace(';', '-'), 'https://dl.google.com/android/repository/' + archive.findtext('complete/url'), archive.findtext('complete/checksum'), 'sha1'))
    versions = json.loads(read('https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&os=windows&arch=x86_64&archive_type=zip&java_package_type=jdk&latest=true&release_status=ga'))
    jdk = next(v for v in versions if '-ca-jdk' in v['name'])
    selected += [('jdk21', jdk['download_url'], None, 'sha256'), ('gradle', 'https://services.gradle.org/distributions/gradle-8.13-bin.zip', read('https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').decode().strip(), 'sha256')]
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(lambda args: install(*args), selected))
    sdk = DEST / 'sdk'
    sdk.mkdir(exist_ok=True)
    import shutil
    for source, destination in [
        ('platforms-android-36/android-16', 'platforms/android-36'),
        ('build-tools-36.0.0/android-16', 'build-tools/36.0.0'),
        ('platform-tools/platform-tools', 'platform-tools')]:
        src = DEST / source
        if not src.exists():
            src = next(p for p in (DEST / source.split('/')[0]).iterdir() if p.is_dir())
        shutil.copytree(src, sdk / destination, dirs_exist_ok=True)
    (ROOT / 'local.properties').write_text('sdk.dir=' + str(sdk).replace('\\', '/').replace(':', '\\:') + '\n')
    print('Ready. Review Android SDK terms before building: https://developer.android.com/studio/terms', flush=True)

if __name__ == '__main__':
    main()
