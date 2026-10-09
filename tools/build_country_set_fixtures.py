"""Original native three-tile ownership fixtures; artificial RO/HU partitions, not country coverage."""
import argparse,gzip,hashlib,io,json,sqlite3,sys,tarfile,tempfile,zipfile
from contextlib import closing
from pathlib import Path
root=Path(__file__).resolve().parents[1]
from package_region import package
assets=root/'app/src/androidTest/assets'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('output',type=Path,help='New owned directory for the five test packages; existing paths are refused')
base=parser.parse_args().output.resolve();base.mkdir(parents=True,exist_ok=False)
scratch=tempfile.TemporaryDirectory(prefix='country-set-build-',dir=base)
work=Path(scratch.name)
with tarfile.open(assets/'profile-routing.tar') as archive:
    tiles={entry.name:archive.extractfile(entry).read() for entry in archive if entry.isfile() and entry.name.endswith('.gph')}
assert len(tiles)==3
order=sorted(tiles)
for name,country,paths,generation in [('ro','RO',order[:2],'0000000000000001'),('hu','HU',order[1:],'0000000000000001'),('stale-hu','HU',order[1:],'0000000000000002')]:
    tar=work/(name+'.tar')
    with tarfile.open(tar,'w') as archive:
        for path in paths:
            header=tarfile.TarInfo(path);header.size=len(tiles[path]);archive.addfile(header,io.BytesIO(tiles[path]))
    manifest=work/(name+'-generation.json')
    manifest.write_text(json.dumps({'generation_id':generation,'tiles':{path:hashlib.sha256(payload).hexdigest() for path,payload in tiles.items()}}))
    package(tar,assets/'profile-display.sqlite',base/(name+'.eurorig'),'Original '+country+' fixture','Original EuroRig test network','2026-10-06',generation=manifest,country=country,experimental_compressed=True)
package(assets/'profile-routing.tar',assets/'profile-display.sqlite',base/'legacy.eurorig','Original legacy fixture','Original EuroRig test network','2026-10-06',generation=work/'ro-generation.json',country='RO')
with zipfile.ZipFile(base/'hu.eurorig') as archive:entries={name:archive.read(name) for name in archive.namelist()}
index=work/'conflict-index.sqlite';index.write_bytes(entries['tiles.sqlite']);output=io.BytesIO()
with closing(sqlite3.connect(index)) as db,tarfile.open(fileobj=io.BytesIO(entries['routing.tar'])) as source,tarfile.open(fileobj=output,mode='w') as target:
    for member in source:
        stored=source.extractfile(member).read()
        if member.name==order[1]+'.gz':
            raw=bytearray(gzip.decompress(stored));raw[-1]^=1;stored=gzip.compress(raw,compresslevel=6,mtime=0);member.size=len(stored)
            db.execute('UPDATE tiles SET sha256=?,compressed_sha256=?,compressed_size=? WHERE path=?',(hashlib.sha256(raw).hexdigest(),hashlib.sha256(stored).hexdigest(),len(stored),order[1]))
        target.addfile(member,io.BytesIO(stored))
    db.commit()
entries['routing.tar']=output.getvalue();entries['tiles.sqlite']=index.read_bytes();manifest=json.loads(entries['manifest.json'])
for name in ('routing.tar','tiles.sqlite'):manifest['sha256'][name]=hashlib.sha256(entries[name]).hexdigest()
entries['manifest.json']=json.dumps(manifest).encode()
with zipfile.ZipFile(base/'conflict-hu.eurorig','w',compression=zipfile.ZIP_DEFLATED) as archive:
    for name,payload in entries.items():archive.writestr(name,payload)
scratch.cleanup()
print('Three original tiles, two artificial overlapping partitions, one shared identity; no source mutation.')
