"""Build an independent offline country package with the Android engine's core.

Install tools/map-requirements.txt in a dedicated Python 3.12 environment.
Default source is Geofabrik Romania. Output directories must be fresh.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path
from compile_display import compile_display
from enrich_display import enrich
from package_region import package,sha256


def fetch(url,destination):
    request=urllib.request.Request(url,headers={'User-Agent':'EuroRig map builder/0.3'})
    with urllib.request.urlopen(request,timeout=180) as response, destination.open('wb') as output:
        while block:=response.read(1024*1024):output.write(block)


def build_country(work,pbf,name,start,end,jobs):
    if importlib.metadata.version('pyvalhalla')!='3.6.3':raise ValueError('Use pyvalhalla 3.6.3, matching Valhalla Mobile 0.6.3')
    import valhalla
    work=Path(work).resolve();pbf=Path(pbf).resolve()
    if work.exists():raise ValueError('Choose a fresh work directory; existing map builds are preserved')
    work.mkdir(parents=True)
    root=Path(__file__).resolve().parents[1]
    config=json.loads((root/'app/src/main/assets/valhalla-default.json').read_text(encoding='utf-8'))
    mj=config['mjolnir'];mj.update(tile_dir=str(work/'tiles'),tile_extract='',admin=str(work/'admins.sqlite'),concurrency=jobs)
    for key in ('timezone','tile_url','traffic_extract'):mj.pop(key,None)
    config_file=work/'config.json';config_file.write_text(json.dumps(config),encoding='utf-8')
    binary_directory=Path(valhalla.__file__).parent/'bin';env=os.environ.copy()
    # Windows wheels bundle dependent DLLs; never replace the rest of PATH.
    env['PATH']=str(binary_directory.parent.parent/'pyvalhalla.libs')+os.pathsep+env.get('PATH','')
    for tool in ('valhalla_build_admins','valhalla_build_tiles'):
        binary=binary_directory/(tool+('.exe' if os.name=='nt' else ''))
        if not binary.is_file():raise FileNotFoundError('Native build tool missing: '+str(binary))
        command=[str(binary),'-c',str(config_file)]
        if tool=='valhalla_build_tiles':command.extend(['-j',str(jobs)])
        command.append(str(pbf));print('Running',tool,flush=True)
        with (work/(tool+'.log')).open('w',encoding='utf-8') as log:
            subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    display=work/'display.sqlite';meta=compile_display([pbf],display,name,{'start':start,'end':end})
    meta['restrictions']=enrich([pbf],display)
    tiles=work/'routing.tar';count=0
    with tarfile.open(tiles,'w',format=tarfile.USTAR_FORMAT) as archive:
        for file in sorted((work/'tiles').rglob('*.gph')):
            entry=tarfile.TarInfo(file.relative_to(work/'tiles').as_posix());entry.size=file.stat().st_size;entry.mtime=0;entry.mode=0o644
            with file.open('rb') as stream:archive.addfile(entry,stream)
            count+=1
    stamp=meta['sources'][0]['osm_timestamp'];output=work/(name+'.eurorig')
    package(tiles,display,output,name,'OpenStreetMap / Geofabrik',stamp)
    provenance={'source_sha256':sha256(pbf),'osm_timestamp':stamp,'valhalla_core':'3.6.3',
                'valhalla_commit':'e2f017b16080f49203de245a211b09efab09cf72','timezone_database':False,
                'build_admins':True,'roads':meta['roads'],'places':meta['places'],'routing_tiles':count,
                'package_sha256':sha256(output),'package_bytes':output.stat().st_size}
    (work/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
    print('Built',output,flush=True)
    return provenance


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('work');parser.add_argument('--pbf',required=True)
    parser.add_argument('--name',default='Romania');parser.add_argument('--jobs',type=int,default=4)
    parser.add_argument('--start',type=float,nargs=2,default=[44.5257,26.0734]);parser.add_argument('--end',type=float,nargs=2,default=[44.6008,26.0511])
    args=parser.parse_args()
    if args.jobs<1 or args.jobs>64:parser.error('jobs must be between 1 and 64')
    build_country(args.work,args.pbf,args.name,args.start,args.end,args.jobs)
