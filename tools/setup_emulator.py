"""Download an isolated Android 8 or Android 17 emulator for smoke testing."""
import argparse
import concurrent.futures
import hashlib
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
dest=root/'.toolchain'

def package(url, path, host=None):
    xml=ET.fromstring(urllib.request.urlopen(url).read())
    p=next(p for p in xml.findall('remotePackage') if p.attrib['path']==path)
    a=next(a for a in p.findall('./archives/archive') if a.findtext('host-os') in (None,host))
    return a.find('complete')

def download(name, base, complete):
    archive=dest/(name+'.zip')
    if not archive.exists():
        urllib.request.urlretrieve(base+complete.findtext('url'),archive)
    checksum=complete.findtext('checksum')
    if hashlib.sha1(archive.read_bytes()).hexdigest()!=checksum:
        raise RuntimeError('Checksum mismatch: '+name)
    folder=dest/name
    if not (folder/'.complete').exists():
        with zipfile.ZipFile(archive) as z:
            for item in z.infolist():
                if not (folder/item.filename).resolve().is_relative_to(folder.resolve()):
                    raise RuntimeError('Unsafe archive path')
            z.extractall(folder)
        (folder/'.complete').write_text(checksum)
    print('Installed '+name,flush=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api',type=int,choices=(26,37),default=26)
    api=parser.parse_args().api
    image_source='android' if api==26 else 'google_apis'
    image_api='26' if api==26 else '37.0'
    image_tag='default' if api==26 else 'google_apis'
    image_base='https://dl.google.com/android/repository/sys-img/'+image_source+'/'
    jobs=[('emulator-download','https://dl.google.com/android/repository/',package('https://dl.google.com/android/repository/repository2-3.xml','emulator','windows')),
          ('image'+str(api),image_base,package(image_base+'sys-img2-3.xml',f'system-images;android-{image_api};{image_tag};x86_64'))]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda j:download(*j),jobs))
    avd=dest/'avd';avd.mkdir(exist_ok=True)
    device=avd/f'EuroRig{api}.avd';device.mkdir(exist_ok=True)
    image=next(p.parent for p in (dest/('image'+str(api))).rglob('system.img'))
    (avd/f'EuroRig{api}.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(device)+'\ntarget=android-'+image_api+'\n')
    (device/'config.ini').write_text('\n'.join([f'AvdId=EuroRig{api}','avd.ini.encoding=UTF-8','abi.type=x86_64','hw.cpu.arch=x86_64','hw.cpu.ncore=2','hw.ramSize=2048','hw.lcd.width=480','hw.lcd.height=800','hw.lcd.density=160','hw.keyboard=yes','hw.gpu.enabled=yes','hw.gpu.mode=swiftshader','disk.dataPartition.size=2G','image.sysdir.1='+str(image),'tag.id='+image_tag,'tag.display='+image_tag,'showDeviceFrame=no','skin.name=480x800','vm.heapSize=256','hw.gps=yes','hw.audioInput=no','PlayStore.enabled=false'])+'\n')
    print(f'Created isolated EuroRig{api} AVD')

if __name__=='__main__':main()
