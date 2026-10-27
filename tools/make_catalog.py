"""Generate a checksum-pinned catalogue from published .eurorig packages.

Relative package URLs work with GitHub Releases and other static HTTPS hosts.
No country is listed until its package actually exists.
"""
import argparse
import hashlib
import json
from urllib.parse import urljoin, urlsplit
import zipfile
from pathlib import Path
from package_region import sha256

MAX_RELEASE_ASSET_BYTES=2*1024**3


def checked_asset_size(size):
    if size<=0 or size>=MAX_RELEASE_ASSET_BYTES:
        raise ValueError('Map package must be nonempty and smaller than GitHub’s 2 GiB release-asset limit')
    return size


def catalog(packages,base_url=''):
    if base_url and (urlsplit(base_url).scheme!='https' or not urlsplit(base_url).netloc or not base_url.endswith('/')):
        raise ValueError('Catalogue base URL must be an HTTPS directory URL ending in /')
    entries=[];ids=set()
    for identifier,file in packages:
        if identifier=='russia' or identifier.startswith('russia-'):raise ValueError('EuroRig coverage excludes Russia')
        if identifier in ids:raise ValueError('Duplicate country')
        ids.add(identifier);file=Path(file)
        size=checked_asset_size(file.stat().st_size)
        with zipfile.ZipFile(file) as archive:
            if archive.testzip() is not None:raise ValueError('Map package failed ZIP CRC verification')
            manifest=json.loads(archive.read('manifest.json'))
            if manifest.get('format')!=2 or manifest.get('engine')!='valhalla' or manifest.get('native_version')!='0.6.3':
                raise ValueError('Incompatible native map')
            for field in ('name','routing_source','routing_date','license','attribution'):
                if not isinstance(manifest.get(field),str) or not manifest[field].strip():
                    raise ValueError('Map manifest is missing '+field)
            hashes=manifest.get('sha256')
            if not isinstance(hashes,dict):raise ValueError('Map manifest is missing inner payload hashes')
            for name in ('routing.tar','display.sqlite'):
                expected=hashes.get(name)
                if not isinstance(expected,str) or len(expected)!=64 or any(ch not in '0123456789abcdefABCDEF' for ch in expected):
                    raise ValueError('Map manifest has an invalid '+name+' hash')
                try:
                    with archive.open(name) as payload:
                        digest=hashlib.sha256()
                        for block in iter(lambda:payload.read(1024*1024),b''):digest.update(block)
                except KeyError as error:raise ValueError('Map package is missing '+name) from error
                if digest.hexdigest().lower()!=expected.lower():raise ValueError(name+' does not match its manifest hash')
        entries.append({'id':identifier,'name':manifest['name'],'url':urljoin(base_url,file.name),'bytes':size,
                        'sha256':sha256(file),'osm_timestamp':manifest['routing_date'],'license':manifest['license'],
                        'attribution':manifest['attribution'],'source':manifest['routing_source']})
    return {'format':1,'native_version':'0.6.3','coverage':'europe-excluding-russia','europe_complete':False,'maps':entries}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output');parser.add_argument('maps',nargs='+',help='country-id=path.eurorig');parser.add_argument('--base-url',default='',help='HTTPS directory containing the map assets; omit when the catalogue and maps share a directory')
    args=parser.parse_args();result=catalog([item.split('=',1) for item in args.maps],args.base_url);Path(args.output).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
