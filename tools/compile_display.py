"""Compile OSM into a disk-backed display/search database. Requires osmium 4.3.1.

This database draws roads and finds places; Valhalla alone decides truck routes.
"""
import argparse
import json
import math
import sqlite3
import unicodedata
from pathlib import Path

CELL=0.02
LEVELS={'motorway':1,'motorway_link':1,'trunk':1,'trunk_link':1,
        'primary':2,'primary_link':2,'secondary':2,'secondary_link':2,
        'tertiary':3,'tertiary_link':3,'unclassified':4,'residential':4,
        'living_street':4,'service':5,'track':6,'footway':6,'path':6,
        'pedestrian':6,'cycleway':6}


def normalize(text):
    return ''.join(c for c in unicodedata.normalize('NFD',text.lower()) if not unicodedata.combining(c))


def encode(points):
    output=[];previous=[0,0]
    for point in points:
        for axis,value in enumerate(point):
            number=round(value*1e6);delta=number-previous[axis];previous[axis]=number
            value=(delta<<1) if delta>=0 else ~(delta<<1)
            while value>=32:output.append(chr(((value&31)|32)+63));value>>=5
            output.append(chr(value+63))
    return ''.join(output)


def cells(bounds):
    south,west,north,east=bounds
    a,b,c,d=math.floor(south/CELL),math.floor(west/CELL),math.floor(north/CELL),math.floor(east/CELL)
    if (c-a+1)*(d-b+1)>64:return []
    return [(lat,lon) for lat in range(a,c+1) for lon in range(b,d+1)]


def compile_display(sources,destination,name,seeds=None):
    import osmium
    destination=Path(destination)
    if destination.exists():raise ValueError('Display output already exists; choose a fresh build directory')
    database=sqlite3.connect(destination)
    database.executescript('''
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA user_version=1;
        CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE roads(id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
            level INTEGER NOT NULL, large INTEGER NOT NULL, south REAL,west REAL,north REAL,east REAL,shape TEXT NOT NULL);
        CREATE TABLE cells(lat INTEGER,lon INTEGER,road INTEGER,PRIMARY KEY(lat,lon,road)) WITHOUT ROWID;
        CREATE TABLE places(id INTEGER PRIMARY KEY,label TEXT NOT NULL,lat REAL,lon REAL,kind TEXT);
        CREATE VIRTUAL TABLE search USING fts4(text,tokenize=unicode61,matchinfo=fts3);
    ''')
    bounds=[90.0,180.0,-90.0,-180.0];missing=0;source_info=[]
    def place(identifier,label,lat,lon,kind):
        if not label:return
        inserted=database.execute('INSERT OR IGNORE INTO places VALUES(?,?,?,?,?)',(identifier,label,lat,lon,kind)).rowcount
        if inserted:database.execute('INSERT INTO search(rowid,text) VALUES(?,?)',(identifier,normalize(label)))
    def label(tags):
        street=tags.get('addr:street','');number=tags.get('addr:housenumber','')
        address=(' '.join(x for x in [street,number,tags.get('addr:city',''),tags.get('addr:postcode','')] if x)) if street else ''
        return ' · '.join(x for x in [tags.get('name',''),address] if x)
    class Handler(osmium.SimpleHandler):
        def node(self,node):
            if node.location.valid():place(node.id*2,label(node.tags),node.location.lat,node.location.lon,node.tags.get('place',node.tags.get('amenity','place')))
        def way(self,way):
            nonlocal missing
            kind=way.tags.get('highway','')
            text=label(way.tags)
            if kind not in LEVELS and not text:return
            if any(not node.location.valid() for node in way.nodes):missing+=1;return
            points=[(node.location.lat,node.location.lon) for node in way.nodes]
            if len(points)<2:return
            if kind in LEVELS:
                bbox=(min(p[0] for p in points),min(p[1] for p in points),max(p[0] for p in points),max(p[1] for p in points))
                bins=cells(bbox)
                inserted=database.execute('INSERT OR IGNORE INTO roads VALUES(?,?,?,?,?,?,?,?,?,?)',
                    (way.id,way.tags.get('name',way.tags.get('ref','')),kind,LEVELS[kind],int(not bins),*bbox,encode(points))).rowcount
                if inserted:
                    database.executemany('INSERT INTO cells VALUES(?,?,?)',[(a,b,way.id) for a,b in bins])
                    for i in (0,1):bounds[i]=min(bounds[i],bbox[i]);bounds[i+2]=max(bounds[i+2],bbox[i+2])
                if not text:text=way.tags.get('ref','')
            middle=points[len(points)//2]
            place(way.id*2+1,text,*middle,kind or way.tags.get('amenity','place'))
    try:
        for source in sources:
            with osmium.io.Reader(str(source)) as reader:stamp=reader.header().get('osmosis_replication_timestamp')
            source_info.append({'file':Path(source).name,'osm_timestamp':stamp or 'unknown'})
            print('Indexing',Path(source).name,flush=True)
            Handler().apply_file(str(source),locations=True,idx='flex_mem');database.commit()
        roads=database.execute('SELECT count(*) FROM roads').fetchone()[0]
        if not roads:raise ValueError('Source contains no roads')
        if seeds is None:
            # Caller should normally supply meaningful route origins/destinations.
            seeds={'start':[sum((bounds[0],bounds[2]))/2,sum((bounds[1],bounds[3]))/2],
                   'end':[sum((bounds[0],bounds[2]))/2,sum((bounds[1],bounds[3]))/2]}
        meta={'name':name,'bounds':bounds,'seeds':seeds,'sources':source_info,'roads':roads,
              'places':database.execute('SELECT count(*) FROM places').fetchone()[0],'missing_geometry':missing,
              'attribution':'© OpenStreetMap contributors · ODbL 1.0','cell_size':CELL}
        database.executemany('INSERT INTO metadata VALUES(?,?)',[(k,json.dumps(v,ensure_ascii=False)) for k,v in meta.items()])
        database.executescript('CREATE INDEX road_level ON roads(level,south);CREATE INDEX road_large ON roads(large,level,south);CREATE INDEX place_area ON places(kind,lat);ANALYZE;')
        database.commit()
        if database.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('Display database failed integrity check')
        print('Indexed',roads,'roads and',meta['places'],'places',flush=True)
        return meta
    finally:database.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination');parser.add_argument('sources',nargs='+');parser.add_argument('--name',required=True)
    parser.add_argument('--start',nargs=2,type=float);parser.add_argument('--end',nargs=2,type=float)
    args=parser.parse_args();seeds={'start':args.start,'end':args.end} if args.start and args.end else None
    compile_display(args.sources,args.destination,args.name,seeds)
