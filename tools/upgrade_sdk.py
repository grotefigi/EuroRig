"""Install the latest requested Android SDK platform in this workspace only."""
import hashlib
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
import shutil
import copy

root = Path(__file__).resolve().parents[1]
repo = ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml').read())
package = next(p for p in repo.findall('remotePackage') if p.attrib['path'] == 'platforms;android-37.0')
complete = package.find('./archives/archive/complete')
archive = root / '.toolchain/android-37.zip'
if not archive.exists():
    urllib.request.urlretrieve('https://dl.google.com/android/repository/' + complete.findtext('url'), archive)
if hashlib.sha1(archive.read_bytes()).hexdigest() != complete.findtext('checksum'):
    raise RuntimeError('Android platform checksum mismatch')
dest = root / '.toolchain/platform37'
with zipfile.ZipFile(archive) as z:
    # Official SDK archive, paths are verified to remain in the isolated target.
    for info in z.infolist():
        if not (dest / info.filename).resolve().is_relative_to(dest.resolve()):
            raise RuntimeError('Unsafe archive path')
    z.extractall(dest)
source = next(p for p in dest.iterdir() if p.is_dir())
shutil.copytree(source, root / '.toolchain/sdk/platforms/android-37.0', dirs_exist_ok=True)
# Archives do not contain the SDK Manager's local package inventory. Write the
# downloaded package's installed metadata; this does not record licence consent.
inventory = ET.Element('{http://schemas.android.com/repository/android/common/02}repository', {
    'xmlns:sdk': 'http://schemas.android.com/sdk/android/repo/repository2/03'})
licence = repo.find("license[@id='android-sdk-license']")
if licence is not None:
    inventory.append(copy.deepcopy(licence))
local = copy.deepcopy(package)
local.tag = 'localPackage'
for tag in ('archives', 'channelRef'):
    for element in local.findall(tag):
        local.remove(element)
api = local.find('./type-details/api-level')
# Local SDK platform details use integer major API levels; 37.0 has no minor APIs.
api.text = '37'
inventory.append(local)
ET.ElementTree(inventory).write(root / '.toolchain/sdk/platforms/android-37.0/package.xml', encoding='utf-8', xml_declaration=True)
print('Installed Android API 37 platform')
