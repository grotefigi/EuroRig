"""Compile a SMALL OSM XML extract into a local EuroPack. Python stdlib only.

This prototype retains node geometry and interprets a deliberately bounded OSM
subset. Unrecognised present restrictions are excluded, not silently ignored.
Country/continental PBF preprocessing is a separate production milestone.
"""
import argparse
import datetime
import math
import re
import struct
import xml.etree.ElementTree as ET
from pathlib import Path

BLOCKED, HAZMAT, TOLL, FERRY, UNPAVED, UNCERTAIN = 1, 2, 4, 8, 16, 32
ROADS = {'motorway': 80, 'motorway_link': 50, 'trunk': 75, 'trunk_link': 45,
         'primary': 65, 'primary_link': 40, 'secondary': 55, 'secondary_link': 40,
         'tertiary': 45, 'tertiary_link': 35, 'unclassified': 35,
         'residential': 30, 'service': 20, 'living_street': 10}
ALLOW = {'yes', 'designated', 'permissive'}


def limit(value, weight=False):
    """Zero means absent. Unknown PRESENT values return None (exclude road)."""
    if value is None:
        return 0.0
    value = value.strip().lower()
    feet = re.fullmatch(r"(\d+)'\s*(\d+(?:\.\d+)?)\"?", value)
    if feet and not weight:
        return int(feet[1]) * .3048 + float(feet[2]) * .0254
    match = re.fullmatch(r'(\d+(?:\.\d+)?)\s*(t|tonnes|kg|lbs|m|cm|ft)?', value)
    if not match:
        return None
    number, unit = float(match[1]), match[2]
    factors = {None: 1, 't': 1, 'tonnes': 1, 'kg': .001, 'lbs': .00045359237} if weight else {None: 1, 'm': 1, 'cm': .01, 'ft': .3048}
    if unit not in factors or number <= 0:
        return None
    return number * factors[unit]


def restrictions(tags, direction):
    flags = 0
    supported_limits = {key + suffix for key in ('maxheight', 'maxwidth', 'maxlength', 'maxweight', 'maxaxleload')
                        for suffix in ('', ':physical', ':forward', ':backward', ':hgv', ':hgv:forward', ':hgv:backward')}
    for key in tags:
        if key.startswith(('maxheight', 'maxwidth', 'maxlength', 'maxweight', 'maxaxleload')) and key not in supported_limits:
            flags |= UNCERTAIN
        if (key.startswith(('maxheight', 'maxwidth', 'maxlength', 'maxweight', 'maxaxleload',
                            'maxweightrating', 'maxaxles', 'maxbogieweight', 'minspeed', 'access',
                            'vehicle', 'motor_vehicle', 'motorcar', 'hgv', 'oneway', 'hazmat', 'toll', 'maxspeed'))
                and ':conditional' in key):
            flags |= UNCERTAIN
    # Specific access overrides general access; destination/private access needs
    # delivery permissions and is excluded by this first implementation.
    access = 'yes'
    for key in ('access', 'vehicle', 'motor_vehicle', 'motorcar', 'hgv'):
        if key in tags:
            access = tags[key]
        if f'{key}:{direction}' in tags:
            access = tags[f'{key}:{direction}']
    if access not in ALLOW:
        flags |= BLOCKED
    if 'barrier' in tags and tags['barrier'] not in ('no', 'toll_booth', 'border_control', 'cattle_grid'):
        if not any(tags.get(k) in ALLOW for k in ('motor_vehicle', 'hgv')):
            flags |= BLOCKED
    dimensions = []
    for key in ('maxheight', 'maxwidth', 'maxlength', 'maxweight', 'maxaxleload'):
        candidates = [limit(tags[k], key in ('maxweight', 'maxaxleload')) for k in
                      (key, f'{key}:physical', f'{key}:{direction}', f'{key}:hgv', f'{key}:hgv:{direction}') if k in tags]
        if any(v is None for v in candidates):
            flags |= UNCERTAIN
        dimensions.append(min((v for v in candidates if v is not None), default=0))
    if any(k.startswith(('maxweightrating', 'maxaxles', 'maxbogieweight', 'hgv:trailer', 'trailer', 'minspeed')) for k in tags):
        flags |= UNCERTAIN
    if any(k.startswith(('hazmat', 'tunnel:category')) for k in tags) or tags.get('tunnel') not in (None, 'no'):
        flags |= HAZMAT  # Conservative: no ADR tunnel-category evaluator yet.
    if tags.get('toll') == 'yes' or tags.get('toll:hgv') == 'yes':
        flags |= TOLL
    if tags.get('surface') not in (None, 'asphalt', 'paved', 'concrete', 'concrete:plates', 'concrete:lanes', 'paving_stones'):
        flags |= UNPAVED
    return dimensions, flags


def compile_osm(path, name=None):
    nodes, ways, relations = {}, [], []
    # Clear each top-level element so the XML tree itself does not accumulate.
    root = None
    for event, element in ET.iterparse(path, events=('start', 'end')):
        if root is None:
            root = element
        if event != 'end' or element.tag not in ('node', 'way', 'relation'):
            continue
        tags = {t.attrib['k']: t.attrib['v'] for t in element.findall('tag')}
        if element.tag == 'node':
            lat, lon = float(element.attrib['lat']), float(element.attrib['lon'])
            if not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 85 or abs(lon) > 180:
                raise ValueError('Invalid source coordinate')
            label = tags.get('name', '')
            if not label and 'addr:street' in tags:
                label = (tags['addr:street'] + ' ' + tags.get('addr:housenumber', '')).strip()
            nodes[int(element.attrib['id'])] = (lat, lon, label, tags)
        elif element.tag == 'way':
            if tags.get('highway') in ROADS or tags.get('route') == 'ferry':
                ways.append((int(element.attrib['id']), [int(n.attrib['ref']) for n in element.findall('nd')], tags))
        elif tags.get('type', '').startswith('restriction'):
            relations.append((tags, [dict(m.attrib) for m in element.findall('member')]))
        element.clear()
        root.clear()
    used = {n for _, refs, _ in ways for n in refs if n in nodes}
    if len(used) > 250000:
        raise ValueError('Extract too large: maximum 250,000 road nodes for this prototype')
    ordered = sorted(used)
    index = {n: i for i, n in enumerate(ordered)}
    turns, excluded = [], set()
    way_ids = {w[0] for w in ways}
    for tags, members in relations:
        exceptions = set(tags.get('except', '').split(';'))
        if exceptions & {'hgv', 'motor_vehicle', 'vehicle'}:
            continue
        restriction = tags.get('restriction:hgv', tags.get('restriction:motor_vehicle', tags.get('restriction', '')))
        froms = [m for m in members if m.get('role') == 'from']
        tos = [m for m in members if m.get('role') == 'to']
        vias = [m for m in members if m.get('role') == 'via']
        relevant = restriction or any(k.startswith(('restriction:hgv', 'restriction:motor_vehicle', 'restriction:conditional')) for k in tags)
        if not relevant:
            continue
        valid = (len(froms) == len(tos) == len(vias) == 1 and vias[0].get('type') == 'node'
                 and froms[0].get('type') == tos[0].get('type') == 'way'
                 and int(vias[0]['ref']) in index and int(froms[0]['ref']) in way_ids and int(tos[0]['ref']) in way_ids
                 and restriction in ('no_left_turn', 'no_right_turn', 'no_straight_on', 'only_left_turn', 'only_right_turn', 'only_straight_on')
                 and not any('conditional' in k for k in tags))
        if valid:
            turns.append((index[int(vias[0]['ref'])], int(froms[0]['ref']), int(tos[0]['ref']), restriction.startswith('only_')))
        else:
            excluded.update(int(m['ref']) for m in members if m.get('type') == 'way')
    edges = []
    for way, refs, tags in ways:
        kind = tags.get('highway', 'ferry')
        oneway = tags.get('oneway:hgv', tags.get('oneway:motor_vehicle', tags.get('oneway', 'yes' if tags.get('junction') == 'roundabout' or kind == 'motorway' else 'no')))
        if oneway not in ('yes', 'true', '1', '-1', 'no', 'false', '0'):
            continue
        directions = [('forward', False)] if oneway in ('yes', 'true', '1') else [('backward', True)] if oneway == '-1' else [('forward', False), ('backward', True)]
        for a, b in zip(refs, refs[1:]):
            if a not in index or b not in index:
                continue
            for direction, reverse in directions:
                dims, flags = restrictions(tags, direction)
                for nid in (a, b):
                    nd, nf = restrictions(nodes[nid][3], direction)
                    dims = [min(x, y) if x and y else max(x, y) for x, y in zip(dims, nd)]
                    flags |= nf
                if way in excluded:
                    flags |= UNCERTAIN
                if kind == 'ferry':
                    flags |= FERRY
                speed = ROADS.get(kind, 20)
                for key in ('maxspeed', 'maxspeed:hgv', f'maxspeed:{direction}', f'maxspeed:hgv:{direction}'):
                    raw = tags.get(key, '')
                    match = re.fullmatch(r'(\d+(?:\.\d+)?)\s*(mph|km/h)?', raw)
                    if match:
                        value = float(match[1]) * (1.609344 if match[2] == 'mph' else 1)
                        if value > 0:
                            speed = min(speed, value)
                edges.append((index[b] if reverse else index[a], index[a] if reverse else index[b], way,
                              tags.get('name', tags.get('ref', kind.replace('_', ' '))), kind, *dims, flags, speed))
    if len(edges) > 1000000 or len(index) < 2 or not edges:
        raise ValueError('Empty or oversized graph')
    return {'name': name or Path(path).stem, 'attribution': '© OpenStreetMap contributors · ODbL 1.0',
            'date': 'Source freshness unverified; compiled ' + datetime.date.today().isoformat(), 'demo': False,
            'nodes': [nodes[n][:3] for n in ordered], 'edges': edges, 'turns': turns,
            'excluded_ways': len(excluded)}


def write_pack(data, path):
    output = bytearray(b'ERG1')
    def text(value):
        encoded = value.encode('utf-8')
        if len(encoded) > 65536:
            raise ValueError('Text too long')
        output.extend(struct.pack('>i', len(encoded)))
        output.extend(encoded)
    for key in ('name', 'attribution', 'date'):
        text(data[key])
    output.extend(struct.pack('>?i', data['demo'], len(data['nodes'])))
    for lat, lon, label in data['nodes']:
        output.extend(struct.pack('>dd', lat, lon))
        text(label)
    output.extend(struct.pack('>i', len(data['edges'])))
    for a, b, way, name, kind, height, width, length, weight, axle, flags, speed in data['edges']:
        output.extend(struct.pack('>iiq', a, b, way))
        text(name)
        text(kind)
        output.extend(struct.pack('>dddddid', height, width, length, weight, axle, flags, speed))
    output.extend(struct.pack('>i', len(data['turns'])))
    for turn in data['turns']:
        output.extend(struct.pack('>iqq?', *turn))
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', help='Small .osm XML extract (PBF is not supported yet)')
    parser.add_argument('output', help='Output .europack')
    parser.add_argument('--name')
    args = parser.parse_args()
    graph = compile_osm(args.source, args.name)
    write_pack(graph, args.output)
    print(f"Wrote {len(graph['nodes'])} nodes, {len(graph['edges'])} directed roads, {len(graph['turns'])} turn restrictions; {graph['excluded_ways']} ways excluded for unsupported turn restrictions")
