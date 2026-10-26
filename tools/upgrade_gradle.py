import hashlib
import urllib.request
import zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
url='https://services.gradle.org/distributions/gradle-9.6.0-bin.zip'
checksum=urllib.request.urlopen(url+'.sha256').read().decode().strip()
path=root/'.toolchain/gradle-9.6.0.zip'
if not path.exists():
    urllib.request.urlretrieve(url,path)
if hashlib.sha256(path.read_bytes()).hexdigest()!=checksum:
    raise RuntimeError('Gradle checksum mismatch')
with zipfile.ZipFile(path) as z:
    z.extractall(root/'.toolchain/gradle')
print('Installed Gradle 9.6.0')
