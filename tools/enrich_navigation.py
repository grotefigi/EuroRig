"""Add original, licensed OSM lane/alert evidence to a COPY of the matching display map.

The routing graph is retained. Point limits are associated by OSM node membership,
not by nearest-road guesses that could confuse bridges and roads beneath them.
"""
import argparse,json,sqlite3,shutil
from pathlib import Path
import osmium

def category(tags):
    if tags.get('highway')=='speed_camera':return 'speed_camera'
    if tags.get('railway')=='level_crossing':return 'railway_crossing'
    if tags.get('amenity') in ('fuel','parking'):return tags['amenity']
    if tags.get('shop')=='car_repair' or tags.get('craft')=='car_repair':return 'repairs'
    if tags.get('amenity')=='car_wash':return 'truck_wash' if tags.get('hgv')=='yes' else 'wash'
    if tags.get('amenity')=='tank_cleaning' or tags.get('industrial')=='tank_cleaning':return 'tank_cleaning'
    if tags.get('shop'):return 'shopping'
    if tags.get('office'):return 'business'
    return None

def enrich(source_db,destination,sources):
    destination=Path(destination)
    if destination.exists():raise ValueError('Choose a fresh output; never overwrite an existing map')
    destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source_db,destination)
    db=sqlite3.connect(destination)
    try:
        db.executescript("""PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA cache_size=-65536;
        CREATE TABLE navigation_roads(way INTEGER PRIMARY KEY,name TEXT,kind TEXT,tags TEXT);
        CREATE TABLE navigation_places(id INTEGER PRIMARY KEY,lat REAL,lon REAL,kind TEXT,label TEXT);
        CREATE TABLE navigation_node_ways(node INTEGER,way INTEGER,PRIMARY KEY(node,way)) WITHOUT ROWID;
        """)
        restricted_nodes={r[0] for r in db.execute('select node from node_rules')}
        road_ids={r[0] for r in db.execute('select id from roads')}
        counts={'roads':0,'places':0,'node_way_links':0}
        class Handler(osmium.SimpleHandler):
            def node(self,n):
                tags=dict(n.tags);kind=category(tags)
                if kind and n.location.valid():
                    db.execute('insert or ignore into navigation_places values(?,?,?,?,?)',(n.id*2,n.location.lat,n.location.lon,kind,tags.get('name',kind.replace('_',' ').title())))
                    counts['places']+=1
            def way(self,w):
                tags=dict(w.tags)
                if w.id in road_ids:
                    kept={k:v for k,v in tags.items() if k.startswith(('lanes','turn:lanes','change:lanes','maxspeed','oneway','toll','zone:traffic','charge'))}
                    db.execute('insert or ignore into navigation_roads values(?,?,?,?)',(w.id,tags.get('name',tags.get('ref','')),tags.get('highway','road'),json.dumps(kept,ensure_ascii=False,separators=(',',':'))))
                    counts['roads']+=1
                    linked=[(n.ref,w.id) for n in w.nodes if n.ref in restricted_nodes]
                    if linked:db.executemany('insert or ignore into navigation_node_ways values(?,?)',linked);counts['node_way_links']+=len(linked)
                kind=category(tags)
                if kind and len(w.nodes)>0 and all(n.location.valid() for n in w.nodes):
                    db.execute('insert or ignore into navigation_places values(?,?,?,?,?)',(w.id*2+1,sum(n.location.lat for n in w.nodes)/len(w.nodes),sum(n.location.lon for n in w.nodes)/len(w.nodes),kind,tags.get('name',kind.replace('_',' ').title())))
                    counts['places']+=1
        evidence=[]
        for source in sources:
            source=Path(source)
            with osmium.io.Reader(str(source)) as reader:stamp=reader.header().get('osmosis_replication_timestamp')
            evidence.append({'file':source.name,'osm_timestamp':stamp})
            print('Reading navigation evidence: '+source.name,flush=True)
            Handler().apply_file(str(source),locations=True,idx='flex_mem');db.commit()
        original=json.loads(db.execute("select value from metadata where key='sources'").fetchone()[0])
        if evidence!=original:raise ValueError('Navigation evidence and installed road snapshot do not match')
        db.executescript('CREATE INDEX navigation_place_area ON navigation_places(kind,lat,lon);CREATE INDEX navigation_node_way ON navigation_node_ways(way,node);ANALYZE;')
        db.execute('insert into metadata values(?,?)',('navigation_version','1'))
        db.execute('insert into metadata values(?,?)',('navigation_sources',json.dumps(evidence)))
        counts={key:db.execute('select count(*) from '+table).fetchone()[0] for key,table in [('roads','navigation_roads'),('places','navigation_places'),('node_way_links','navigation_node_ways')]}
        counts['by_place_kind']=dict(db.execute('select kind,count(*) from navigation_places group by kind'))
        db.execute('insert into metadata values(?,?)',('navigation_counts',json.dumps(counts)));db.commit()
        if db.execute('pragma integrity_check').fetchone()[0]!='ok':raise ValueError('Navigation map integrity check failed')
        print(json.dumps(counts),flush=True)
        return counts
    finally:db.close()

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source_db');p.add_argument('destination');p.add_argument('sources',nargs='+');args=p.parse_args();enrich(args.source_db,args.destination,args.sources)
