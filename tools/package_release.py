"""Package the development APK and original source, excluding private research."""
import hashlib
import shutil
import zipfile
import re
from pathlib import Path

root=Path(__file__).resolve().parents[1]
dist=root/'dist';dist.mkdir(exist_ok=True)
version=re.search(r'''versionName\s+['"]([^'"]+)['"]''',(root/'app/build.gradle').read_text(encoding='utf-8')).group(1)
apk=dist/f'EuroRig-{version}.apk'
shutil.copy2(root/'app/build/outputs/apk/debug/app-debug.apk',apk)

(dist/(apk.name+'.sha256')).write_text(hashlib.sha256(apk.read_bytes()).hexdigest()+'  '+apk.name+'\n')
folders=['app/src','routing/src','tools','docs','maps','gradle','.github','design-system']
files=['AGENTS.md','README.md','ROADMAP.md','VERIFICATION.md','LICENSE','CONTRIBUTING.md','THIRD_PARTY_NOTICES.md','.gitignore','.gitattributes',
       'build.gradle','settings.gradle','gradle.properties','app/build.gradle','routing/build.gradle',
       'gradlew','gradlew.bat','analysis/REFERENCE.md']
source=dist/f'EuroRig-{version}-source.zip'
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as archive:
    for name in files:
        archive.write(root/name,'EuroRig/'+name)
    for name in folders:
        for path in sorted((root/name).rglob('*')):
            if path.is_file() and '__pycache__' not in path.parts:
                archive.write(path,'EuroRig/'+path.relative_to(root).as_posix())
(dist/(source.name+'.sha256')).write_text(hashlib.sha256(source.read_bytes()).hexdigest()+'  '+source.name+'\n')
print(f'Packaged {apk.name} ({apk.stat().st_size:,} bytes), {source.name} ({source.stat().st_size:,} bytes)')
