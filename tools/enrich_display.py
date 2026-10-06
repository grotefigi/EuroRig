"""Add a spatial index and OSM restriction evidence to a EuroRig display database.

Routing tiles remain independent. This adds display evidence, not permission to
enter a road and not proof that an untagged road has sufficient clearance.
"""
import argparse
import json
import math
import sqlite3
from pathlib import Path
from compile_map import restrictions, ROADS, ALLOW, BLOCKED, UNCERTAIN


def summary(tags, kind=None):
    forward, a=restrictions(tags,'forward');backward,b=restrictions(tags,'backward')
    limits=[min((v for v in pair if v>0),default=0) for pair in zip(forward,backward)]
    flags=a|b
    if kind is not None and kind not in ROADS and tags.get('hgv') not in ALLOW:
        flags|=BLOCKED
    relevant={k:v for k,v in tags.items() if k.startswith(('max','access','hgv','vehicle','motor_vehicle','motorcar','hazmat','tunnel','barrier','oneway','bridge'))}
    return (*limits,flags,json.dumps(relevant,ensure_ascii=False,separators=(',',':')))


def enrich(sources,destination):
    import osmium
    destination=Path(destination)
    db=sqlite3.connect(destination)
    try:
        if db.execute("SELECT 1 FROM metadata WHERE key='restriction_version'").fetchone():
            raise ValueError('Database already enriched')
        db.executescript('''
            PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
            PRAGMA cache_size=-65536;
            CREATE TABLE large_cells(lat INTEGER,lon INTEGER,road INTEGER,PRIMARY KEY(lat,lon,road)) WITHOUT ROWID;
            CREATE TABLE road_rules(way INTEGER PRIMARY KEY,height REAL,width REAL,length REAL,weight REAL,axle REAL,flags INTEGER,tags TEXT);
            CREATE TABLE node_rules(node INTEGER PRIMARY KEY,lat REAL,lon REAL,height REAL,width REAL,length REAL,weight REAL,axle REAL,flags INTEGER,tags TEXT);
            CREATE INDEX node_rule_area ON node_rules(lat,lon);
        ''')
        for identifier,south,west,north,east in db.execute("SELECT id,south,west,north,east FROM roads WHERE large=1").fetchall():
            db.executemany("INSERT INTO large_cells VALUES(?,?,?)",((a,b,identifier) for a in range(math.floor(south/.25),math.floor(north/.25)+1) for b in range(math.floor(west/.25),math.floor(east/.25)+1)))
        class Handler(osmium.SimpleHandler):
            def way(self,way):
                tags=dict(way.tags);kind=tags.get('highway')
                if not kind:return
                values=summary(tags,kind)
                if any(values[:5]) or values[5]&(BLOCKED|UNCERTAIN|2):
                    db.execute('INSERT OR IGNORE INTO road_rules VALUES(?,?,?,?,?,?,?,?)',(way.id,*values))
            def node(self,node):
                tags=dict(node.tags)
                if not any(k.startswith(('max','access','hgv','barrier','hazmat')) for k in tags):return
                values=summary(tags)
                if node.location.valid() and (any(values[:5]) or values[5]&(BLOCKED|UNCERTAIN|2)):
                    db.execute('INSERT OR IGNORE INTO node_rules VALUES(?,?,?,?,?,?,?,?,?,?)',(node.id,node.location.lat,node.location.lon,*values))
        for source in sources:
            print('Reading restriction tags:',Path(source).name,flush=True)
            Handler().apply_file(str(source))
        # A rule for a way absent from the display must not masquerade as visible road evidence.
        db.execute('DELETE FROM road_rules WHERE way NOT IN (SELECT id FROM roads)')
        counts={name:db.execute('SELECT count(*) FROM '+name).fetchone()[0] for name in ('road_rules','node_rules')}
        db.execute('INSERT INTO metadata VALUES(?,?)',('restriction_version','1'))
        db.execute('INSERT INTO metadata VALUES(?,?)',('restriction_counts',json.dumps(counts)))
        db.commit()
        if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('Enriched database integrity check failed')
        print(json.dumps(counts),flush=True)
        return counts
    finally:db.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database');parser.add_argument('sources',nargs='+')
    args=parser.parse_args();enrich(args.sources,args.database)
