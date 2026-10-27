"""Build the original unconditional turn fixture with pinned pyvalhalla/osmium.

Usage: python tools/build_turn_fixture.py FRESH_OUTPUT_DIRECTORY
The source is tools/fixtures/turns.osm; no external map data is fetched.
"""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import osmium
import valhalla


def build(destination):
    if importlib.metadata.version('pyvalhalla') != '3.6.3' or importlib.metadata.version('osmium') != '4.3.1':
        raise ValueError('Use tools/map-requirements.txt pinned versions')
    root = Path(__file__).resolve().parents[1]
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=False)
    source = root/'tools/fixtures/turns.osm'
    pbf = destination/'turns.osm.pbf'
    class Convert(osmium.SimpleHandler):
        def node(self, obj): writer.add_node(obj)
        def way(self, obj): writer.add_way(obj)
        def relation(self, obj): writer.add_relation(obj)
    with osmium.SimpleWriter(str(pbf)) as writer:
        Convert().apply_file(str(source))
    config = json.loads((root/'app/src/main/assets/valhalla-default.json').read_text(encoding='utf-8'))
    config['mjolnir'].update(tile_dir=str(destination/'tiles'), tile_extract='', admin='', timezone='', concurrency=1)
    config['mjolnir'].pop('tile_url', None)
    config['mjolnir'].pop('traffic_extract', None)
    config['mjolnir']['data_processing']['use_admin_db'] = False
    config_path = destination/'config.json'
    config_path.write_text(json.dumps(config, indent=2), encoding='utf-8')
    binary = Path(valhalla.__file__).parent/'bin'
    tool = binary/('valhalla_build_tiles.exe' if os.name == 'nt' else 'valhalla_build_tiles')
    env = os.environ.copy()
    env['PATH'] = str(binary.parent.parent/'pyvalhalla.libs') + os.pathsep + env.get('PATH', '')
    with (destination/'build.log').open('wb') as log:
        subprocess.run([str(tool), '-c', str(config_path), '-j', '1', str(pbf)], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    tiles = sorted((destination/'tiles').rglob('*.gph'))
    if not tiles:
        raise ValueError('No native tiles produced')
    with tarfile.open(destination/'routing.tar', 'w', format=tarfile.USTAR_FORMAT) as archive:
        for tile in tiles:
            entry = tarfile.TarInfo(tile.relative_to(destination/'tiles').as_posix())
            entry.size, entry.mtime, entry.mode = tile.stat().st_size, 0, 0o644
            with tile.open('rb') as data: archive.addfile(entry, data)
    from compile_display import compile_display
    from enrich_display import enrich
    compile_display([pbf], destination/'display.sqlite', 'Original synthetic turn restrictions')
    enrich([pbf], destination/'display.sqlite')
    files = [source, pbf, config_path, destination/'build.log', destination/'routing.tar', destination/'display.sqlite']
    manifest = {'schema': 1, 'fixture_revision': 2, 'pyvalhalla': valhalla.__version__,
                'osmium': importlib.metadata.version('osmium'), 'admin_timezone': False,
                'synthetic_only': True, 'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
    (destination/'provenance.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('TURN_FIXTURE_BUILT')


if __name__ == '__main__':
    build(sys.argv[1])
