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
    if destination.exists() or destination.with_name(destination.name+'.normalising').exists():
        raise FileExistsError('Choose a fresh package output path and normalization path')
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
    restriction_sources=[]
    if indexed:
        with closing(sqlite3.connect(f'file:{display.resolve().as_posix()}?mode=ro',uri=True)) as db:
            if db.execute('PRAGMA user_version').fetchone()[0]!=1:raise ValueError('Unknown display database version')
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='metadata'").fetchone():
                row=db.execute("SELECT value FROM metadata WHERE key='authority_sources'").fetchone()
                if row:
                    restriction_sources=json.loads(row[0])
                    if not isinstance(restriction_sources,list) or any(not isinstance(s,dict) for s in restriction_sources):
                        raise ValueError('Invalid restriction source provenance')
                    if any(not isinstance(s.get('map_attribution'),str) or not s['map_attribution'].strip() for s in restriction_sources):
                        raise ValueError('Missing reviewed restriction source attribution')
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
    if restriction_sources:
        manifest['restriction_sources']=restriction_sources
        manifest['attribution']+='; '+'; '.join(dict.fromkeys(s['map_attribution'] for s in restriction_sources))
    # Import streams the decompressed payload to disk; routes memory-map that TAR.
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=3, allowZip64=True) as archive:
        archive.write(tiles, 'routing.tar')
        archive.write(display, display_name)
        archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2))
    normalise(destination)
    verify_local_headers(destination)
    return manifest


def normalise(destination):
    """Rewrite the archive so every local header carries exact sizes.

    A large entry written by a streaming writer can leave compressed size 0 in its local
    header. Python's zipfile tolerates that via the central directory; Android's
    java.util.zip.ZipInputStream does not and aborts the import with
    "invalid entry compressed size (expected 0 but got N bytes)".
    """
    destination = Path(destination)
    temporary = destination.with_name(destination.name + '.normalising')
    with zipfile.ZipFile(destination) as source, \
         zipfile.ZipFile(temporary, 'x', compression=zipfile.ZIP_DEFLATED,
                         compresslevel=3, allowZip64=True) as target:
        for entry in source.infolist():
            info = zipfile.ZipInfo(entry.filename, date_time=entry.date_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with source.open(entry) as reader, target.open(info, 'w') as writer:
                while True:
                    block = reader.read(4 * 1024 * 1024)
                    if not block:
                        break
                    writer.write(block)
    temporary.replace(destination)


def verify_local_headers(destination):
    """Fail the build rather than ship an archive Android cannot import."""
    with zipfile.ZipFile(destination) as archive:
        entries = archive.infolist()
    with Path(destination).open('rb') as stream:
        for entry in entries:
            stream.seek(entry.header_offset)
            header = stream.read(30)
            if len(header) != 30 or header[:4] != b'PK\x03\x04':
                raise ValueError('Corrupt ZIP local header for ' + entry.filename)
            flags = int.from_bytes(header[6:8], 'little')
            compressed = int.from_bytes(header[18:22], 'little')
            # java.util.zip.ZipInputStream rejects an entry whose local header leaves the
            # size to a trailing data descriptor.
            if flags & 0x08:
                raise ValueError('ZIP entry relies on a data descriptor: ' + entry.filename)
            if compressed != entry.compress_size:
                raise ValueError('ZIP local header size mismatch for ' + entry.filename)


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
