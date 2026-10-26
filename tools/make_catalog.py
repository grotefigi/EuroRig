"""Generate a checksum-pinned catalogue from published .eurorig packages.

Relative package URLs work with GitHub Releases and other static HTTPS hosts.
No country is listed until its package actually exists.
"""
import argparse
import json
import zipfile
from pathlib import Path
from package_region import sha256


def catalog(packages):
    entries=[];ids=set()
    for identifier,file in packages:
        if identifier=='russia' or identifier.startswith('russia-'):raise ValueError('EuroRig coverage excludes Russia')
        if identifier in ids:raise ValueError('Duplicate country')
        ids.add(identifier);file=Path(file)
        with zipfile.ZipFile(file) as archive:manifest=json.loads(archive.read('manifest.json'))
        if manifest['native_version']!='0.6.3':raise ValueError('Incompatible native map')
        entries.append({'id':identifier,'name':manifest['name'],'url':file.name,'bytes':file.stat().st_size,
                        'sha256':sha256(file),'osm_timestamp':manifest['routing_date']})
    return {'format':1,'native_version':'0.6.3','coverage':'europe-excluding-russia','europe_complete':False,'maps':entries}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output');parser.add_argument('maps',nargs='+',help='country-id=path.eurorig')
    args=parser.parse_args();result=catalog([item.split('=',1) for item in args.maps]);Path(args.output).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
