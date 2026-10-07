"""Package already-built Valhalla 0.6.3 tiles for manual, offline installation.

This packages maps; it does not build tiles or certify their truck restrictions.
Use one coherent tile extract to support cross-border routing.
"""
import argparse
import hashlib
import json
import math
import tarfile
import zipfile
import sqlite3
from contextlib import closing
from pathlib import Path


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def coverage_box(value):
    """Validate a published coverage box, or refuse the package.

    The app treats the box as authoritative when deciding that a location is outside the installed
    map, so a reversed, out-of-range or non-finite box is worse than none: it would send a driver to
    download a map they already have. A box is not proof of coverage inside it.
    """
    if value is None:
        return None
    try:
        numbers = [float(component) for component in value]
    except (TypeError, ValueError):
        raise ValueError('Coverage bbox must be four numbers: minLon,minLat,maxLon,maxLat')
    if len(numbers) != 4:
        raise ValueError('Coverage bbox needs exactly four numbers: minLon,minLat,maxLon,maxLat')
    if not all(math.isfinite(component) for component in numbers):
        raise ValueError('Coverage bbox values must be finite (no NaN or Infinity)')
    min_lon, min_lat, max_lon, max_lat = numbers
    if not -180 <= min_lon < max_lon <= 180:
        raise ValueError('Coverage bbox longitudes must satisfy -180 <= minLon < maxLon <= 180')
    if not -90 <= min_lat < max_lat <= 90:
        raise ValueError('Coverage bbox latitudes must satisfy -90 <= minLat < maxLat <= 90')
    return numbers


def package(tiles, display, destination, name, source, date, bbox=None):
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
    manifest: dict = dict(format=2 if indexed else 1, engine='valhalla', native_version='0.6.3', name=name,
                    routing_source=source, routing_date=date,
                    attribution='© OpenStreetMap contributors', license='ODbL-1.0',
                    sha256={'routing.tar': sha256(tiles), display_name: sha256(display)})
    # Coverage is published so the app can tell "this location is outside your installed map" apart from
    # "your point is not on a mapped truck road". Without it both surface as engine code 171 and the
    # driver is told to check country coverage for what is really a missing download. Validated before
    # the archive is written, so a rejected box leaves no package behind.
    coverage = coverage_box(bbox)
    if coverage is not None:
        manifest['coverage'] = dict(tiles=count, bbox=coverage)
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
    parser.add_argument('--bbox', default=None,
                        help='minLon,minLat,maxLon,maxLat of the extract; published as coverage in the manifest')
    args = parser.parse_args()
    try:
        package(args.tiles, args.display, args.destination, args.name, args.source, args.date,
                args.bbox.split(',') if args.bbox else None)
    except ValueError as error:
        parser.error(str(error))
    print(f'Created {args.destination}; routes use local tiles, display/search uses the separate graph')
