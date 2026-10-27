"""Retain OSM truck turn evidence, including ordered via-way history.

Unsupported applicability/topology remains evidence, never clearance. Coordinates
are junction identities for the independent route audit, not inferred sign locations.
"""
import json
import math

VERSION = '1'
SUPPORTED, UNKNOWN, EXEMPT = 1, 2, 0
VALUES = {'no_left_turn', 'no_right_turn', 'no_straight_on', 'no_u_turn',
          'no_entry', 'no_exit', 'only_left_turn', 'only_right_turn',
          'only_straight_on', 'only_u_turn'}
TRUCK = ('restriction:hgv', 'restriction:motor_vehicle', 'restriction:vehicle', 'restriction')
OTHER_EXCEPTIONS = {'bus', 'psv', 'taxi', 'motorcar', 'motorcycle', 'moped',
                    'bicycle', 'foot', 'emergency', 'agricultural', 'forestry'}


def applicability(tags):
    value = next((tags[k] for k in TRUCK if k in tags), '')
    scoped = any(k.startswith('restriction:') for k in tags)
    if not value:
        return ('', UNKNOWN, 'Unsupported vehicle or conditional scope') if scoped else ('', UNKNOWN, 'Missing restriction value')
    if any('conditional' in k or k in ('day_on', 'day_off', 'hour_on', 'hour_off') for k in tags):
        return value, UNKNOWN, 'Conditional turn applicability is not evaluated'
    # An explicit truck value combined with an exception is contradictory evidence.
    exceptions = {s.strip() for s in tags.get('except', '').split(';') if s.strip()}
    if exceptions & {'hgv', 'motor_vehicle', 'vehicle'}:
        if any(k in tags for k in TRUCK[:-1]):
            return value, UNKNOWN, 'Scoped turn conflicts with vehicle exception'
        return value, EXEMPT, 'Truck vehicle exception'
    if exceptions - OTHER_EXCEPTIONS:
        return value, UNKNOWN, 'Unsupported turn exception'
    if any(k.startswith(('restriction:', 'except:')) and k not in TRUCK for k in tags):
        return value, UNKNOWN, 'Unsupported turn qualifier'
    if tags.get('type') != 'restriction':
        return value, UNKNOWN, 'Unsupported restriction relation type'
    if value not in VALUES:
        return value, UNKNOWN, 'Unsupported turn value'
    return value, SUPPORTED, ''


def topology(members, ways):
    start = [ref for kind, ref, role in members if role == 'from' and kind == 'w']
    finish = [ref for kind, ref, role in members if role == 'to' and kind == 'w']
    via = [(kind, ref) for kind, ref, role in members if role == 'via']
    if len(start) != 1 or len(finish) != 1 or not via or len(via) > 62:
        raise ValueError('Unsupported turn member cardinality')
    if len(members) != len(via) + 2 or any(ref <= 0 for _, ref, _ in members):
        raise ValueError('Unsupported turn member role or identity')
    chain = start + [ref for kind, ref in via if kind == 'w'] + finish
    if any(ref not in ways for ref in chain):
        raise ValueError('Missing member way')
    shapes = [ways[ref] for ref in chain]
    if any(len(s) < 2 or len(s) > 10000 or len(set(s)) != len(s) for s in shapes):
        raise ValueError('Unsupported repeated-node or oversized member way')
    if len(via) == 1 and via[0][0] == 'n':
        node = via[0][1]
        if any(node not in (s[0], s[-1]) for s in shapes):
            raise ValueError('From/to must end at the via node')
        return chain, [node]
    if any(kind != 'w' for kind, _ in via) or len(set(chain)) != len(chain):
        raise ValueError('Unsupported mixed or repeated via members')
    joins = []
    for a, b in zip(shapes, shapes[1:]):
        shared = set(a) & set(b)
        if len(shared) != 1:
            raise ValueError('Ambiguous or disconnected turn chain')
        node = shared.pop()
        if node not in (a[0], a[-1]) or node not in (b[0], b[-1]):
            raise ValueError('Turn members must join at endpoints')
        joins.append(node)
    if len(set(joins)) != len(joins):
        raise ValueError('Repeated turn junction')
    return chain, joins


def enrich_turns(sources, db):
    import osmium
    records, ways, points = {}, {}, {}

    class Relations(osmium.SimpleHandler):
        def relation(self, relation):
            tags = dict(relation.tags)
            if not tags.get('type', '').startswith('restriction'):
                return
            members = [(m.type, m.ref, m.role) for m in relation.members]
            record = (tags, members)
            if relation.id in records and records[relation.id] != record:
                raise ValueError('Conflicting turn relation across map sources: ' + str(relation.id))
            records[relation.id] = record

    for source in sources:
        Relations().apply_file(str(source))
    wanted = {ref for _, members in records.values() for kind, ref, _ in members if kind == 'w'}

    class Ways(osmium.SimpleHandler):
        def way(self, way):
            if way.id in wanted:
                shape = [n.ref for n in way.nodes]
                if way.id in ways and ways[way.id] != shape:
                    raise ValueError('Conflicting turn way across map sources: ' + str(way.id))
                ways[way.id] = shape

    if wanted:
        for source in sources:
            Ways().apply_file(str(source))
    pending = []
    for identifier, (tags, members) in records.items():
        value, status, reason = applicability(tags)
        chain, joins = [], []
        try:
            chain, joins = topology(members, ways)
            if len(chain) == 2 and chain[0] == chain[1] and value not in ('no_u_turn', 'only_u_turn'):
                raise ValueError('Same-way turn requires a U-turn value')
        except ValueError as invalid:
            if status != EXEMPT:
                status, reason = UNKNOWN, str(invalid)
            chain = list(dict.fromkeys(ref for kind, ref, _ in members if kind == 'w' and ref > 0))[:64]
        pending.append([identifier, value, status, reason, chain, joins, tags, members])
    wanted_nodes = {node for record in pending for node in record[5]}

    class Nodes(osmium.SimpleHandler):
        def node(self, node):
            if node.id in wanted_nodes and node.location.valid():
                point = [node.location.lat, node.location.lon]
                if all(math.isfinite(v) for v in point) and -85 <= point[0] <= 85 and -180 <= point[1] <= 180:
                    if node.id in points and points[node.id] != point:
                        raise ValueError('Conflicting turn node across map sources: ' + str(node.id))
                    points[node.id] = point

    if wanted_nodes:
        for source in sources:
            Nodes().apply_file(str(source))
    db.executescript('''
        CREATE TABLE turn_rules(relation INTEGER PRIMARY KEY,value TEXT NOT NULL,status INTEGER NOT NULL,
            reason TEXT NOT NULL,chain TEXT NOT NULL,junctions TEXT NOT NULL,raw TEXT NOT NULL);
        CREATE TABLE turn_members(way INTEGER,relation INTEGER,PRIMARY KEY(way,relation)) WITHOUT ROWID;
    ''')
    counts = {'supported': 0, 'unknown': 0, 'exempt': 0}
    for identifier, value, status, reason, chain, joins, tags, members in pending:
        if status == SUPPORTED and any(node not in points for node in joins):
            status, reason = UNKNOWN, 'Missing turn junction coordinates'
        junctions = [points[node] for node in joins if node in points]
        raw = {'tags': tags, 'members': members, 'junction_nodes': joins}
        db.execute('INSERT INTO turn_rules VALUES(?,?,?,?,?,?,?)',
                   (identifier, value, status, reason, json.dumps(chain), json.dumps(junctions), json.dumps(raw, ensure_ascii=False)))
        db.executemany('INSERT OR IGNORE INTO turn_members VALUES(?,?)',
                       ((ref, identifier) for kind, ref, _ in members if kind == 'w' and ref > 0))
        counts[{SUPPORTED: 'supported', UNKNOWN: 'unknown', EXEMPT: 'exempt'}[status]] += 1
    db.execute('INSERT INTO metadata VALUES(?,?)', ('turn_restriction_version', VERSION))
    db.execute('INSERT INTO metadata VALUES(?,?)', ('turn_restriction_counts', json.dumps(counts)))
    return counts
