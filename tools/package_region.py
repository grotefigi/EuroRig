"""Package already-built Valhalla 0.6.3 tiles for manual, offline installation.

This packages maps; it does not build tiles or certify their truck restrictions.
Use one coherent tile extract to support cross-border routing.
"""
import argparse
import hashlib
import json
import tarfile
import zipfile
import sqlite3
from contextlib import closing
from pathlib import Path


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def package(tiles, display, destination, name, source, date):
    tiles, display, destination = map(Path, (tiles, display, destination))
    with tarfile.open(tiles, 'r:') as archive:
        count = 0
        for entry in archive:
            if entry.isfile() and entry.name.endswith('.gph'):
                if entry.size < 272:
                    raise ValueError('Truncated graph tile')
                count += 1
        if not count:
            raise ValueError('Routing extract contains no Valhalla graph tiles')
    with display.open('rb') as stream:magic=stream.read(16)
    indexed=magic==b'SQLite format 3\x00'
    if indexed:
        with closing(sqlite3.connect(f'file:{display.resolve().as_posix()}?mode=ro',uri=True)) as db:
            if db.execute('PRAGMA user_version').fetchone()[0]!=1:raise ValueError('Unknown display database version')
    else:
        if magic[:4] != b'ERG1':
            raise ValueError('Display map must be EuroPack v1')
    if destination.resolve() in (tiles.resolve(), display.resolve()):
        raise ValueError('Output must not overwrite an input')
    display_name='display.sqlite' if indexed else 'display.europack'
    manifest = dict(format=2 if indexed else 1, engine='valhalla', native_version='0.6.3', name=name,
                    routing_source=source, routing_date=date,
                    attribution='© OpenStreetMap contributors', license='ODbL-1.0',
                    sha256={'routing.tar': sha256(tiles), display_name: sha256(display)})
    # Import streams the decompressed payload to disk; routes memory-map that TAR.
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=3, allowZip64=True) as archive:
        archive.write(tiles, 'routing.tar')
        archive.write(display, display_name)
        archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tiles', help='Uncompressed .tar from compatible Valhalla build tools')
    parser.add_argument('display', help='Indexed SQLite display/search database or small EuroPack graph')
    parser.add_argument('destination', help='Output .eurorig file')
    parser.add_argument('--name', required=True)
    parser.add_argument('--source', required=True, help='Public OSM extract/source URL')
    parser.add_argument('--date', required=True, help='OSM snapshot date, or unknown')
    args = parser.parse_args()
    package(args.tiles, args.display, args.destination, args.name, args.source, args.date)
    print(f'Created {args.destination}; routes use local tiles, display/search uses the separate graph')
