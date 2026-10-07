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
    parser.add_argument('--abi',choices=('x86_64','x86'),default='x86_64')
    parser.add_argument('--reuse-emulator',action='store_true',help='Reuse the previously checksum-verified installed emulator')
    args=parser.parse_args();api=args.api;abi=args.abi
    if abi=='x86' and api!=26:parser.error('The 32-bit test image is supported at API 26 only')
    image_name='image'+str(api)+('' if abi=='x86_64' else '-x86')
    device_name=f'EuroRig{api}'+('' if abi=='x86_64' else 'x86')
    image_source='android' if api==26 else 'google_apis'
    image_api='26' if api==26 else '37.0'
    image_tag='default' if api==26 else 'google_apis'
    image_base='https://dl.google.com/android/repository/sys-img/'+image_source+'/'
    jobs=[(image_name,image_base,package(image_base+'sys-img2-3.xml',f'system-images;android-{image_api};{image_tag};{abi}'))]
    if args.reuse_emulator:
        receipt=dest/'emulator-download/.complete';archive=dest/'emulator-download.zip'
        if not receipt.is_file() or not archive.is_file() or not (dest/'emulator-download/emulator/emulator.exe').is_file():
            parser.error('No previously verified emulator is installed')
        with archive.open('rb') as stream:actual=hashlib.file_digest(stream,'sha1').hexdigest()
        if actual!=receipt.read_text().strip():parser.error('Installed emulator archive does not match its original receipt')
    else:
        jobs.insert(0,('emulator-download','https://dl.google.com/android/repository/',package('https://dl.google.com/android/repository/repository2-3.xml','emulator','windows')))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda j:download(*j),jobs))
    avd=dest/'avd';avd.mkdir(exist_ok=True)
    device=avd/f'{device_name}.avd';device.mkdir(exist_ok=True)
    image=next(p.parent for p in (dest/image_name).rglob('system.img'))
    (avd/f'{device_name}.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(device)+'\ntarget=android-'+image_api+'\n')
    (device/'config.ini').write_text('\n'.join([f'AvdId={device_name}','avd.ini.encoding=UTF-8',f'abi.type={abi}',f'hw.cpu.arch={abi}','hw.cpu.ncore=2','hw.ramSize=2048','hw.lcd.width=480','hw.lcd.height=800','hw.lcd.density=160','hw.keyboard=yes','hw.gpu.enabled=yes','hw.gpu.mode=swiftshader','disk.dataPartition.size=2G','image.sysdir.1='+str(image),'tag.id='+image_tag,'tag.display='+image_tag,'showDeviceFrame=no','skin.name=480x800','vm.heapSize=256','hw.gps=yes','hw.audioInput=no','PlayStore.enabled=false'])+'\n')
    print(f'Created isolated {device_name} AVD')

if __name__=='__main__':main()
