"""Audit recorded restriction evidence across any enriched EuroRig display map.

Counts describe stored records, never legal completeness or truck clearance.
Spatial buckets use road bounding-box centres; they are not municipal boundaries.
Existing maps are opened read-only. No external data or road rules are imported.
"""
import argparse
import json
import math
import sqlite3
from pathlib import Path

from restriction_kind import UNCERTAIN


def _counts():
    return dict.fromkeys(('roads', 'way_rule_records', 'roads_without_way_rule_record',
                         'numeric_limit_records', 'hgv_tag_records', 'adr_tag_records',
                         'conditional_tag_records', 'uncertain_records', 'adr_records_without_classification'), 0)


def audit_database(path, cell_size=0.25):
    if not math.isfinite(cell_size) or not 0 < cell_size <= 10:
        raise ValueError('Cell size must be finite and between 0 and 10 degrees')
    path = Path(path).resolve()
    db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)
    try:
        required = {'roads', 'road_rules', 'node_rules', 'metadata'}
        tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not required <= tables:
            raise ValueError('Map lacks enriched restriction tables: ' + ', '.join(sorted(required - tables)))
        totals, classes, cells = _counts(), {}, {}
        query = '''SELECT r.id,r.kind,r.south,r.west,r.north,r.east,
                          q.way,q.height,q.width,q.length,q.weight,q.axle,q.kind,q.tags
                   FROM roads r LEFT JOIN road_rules q ON q.way=r.id ORDER BY r.id'''
        for row in db.execute(query):
            identifier, road_class, south, west, north, east, way, *rule = row
            if not all(v is not None and math.isfinite(v) for v in (south, west, north, east)):
                raise ValueError('Invalid road bounds for OSM way ' + str(identifier))
            if not (-90 <= south <= north <= 90 and -180 <= west <= east <= 180):
                raise ValueError('Invalid road bounds for OSM way ' + str(identifier))
            key = (math.floor((south + north) / 2 / cell_size), math.floor((west + east) / 2 / cell_size))
            groups = (totals, classes.setdefault(road_class, _counts()), cells.setdefault(key, _counts()))
            values = _counts()
            values['roads'] = 1
            values['way_rule_records'] = int(way is not None)
            values['roads_without_way_rule_record'] = int(way is None)
            if way is not None:
                height, width, length, weight, axle, kind, raw_tags = rule
                tags = json.loads(raw_tags)
                if not isinstance(tags, dict):
                    raise ValueError('Invalid restriction tags for OSM way ' + str(identifier))
                values['numeric_limit_records'] = int(any(v is not None and v > 0 for v in (height, width, length, weight, axle)))
                values['hgv_tag_records'] = int(any(k == 'hgv' or k.startswith('hgv:') for k in tags))
                values['adr_tag_records'] = int(any(k.startswith('hazmat') or k == 'tunnel:category' for k in tags))
                values['conditional_tag_records'] = int(any(':conditional' in k for k in tags))
                values['uncertain_records'] = int(bool((kind or 0) & UNCERTAIN))
                # A zero kind can use the app's legacy fallback; it is not proof of failed enforcement.
                values['adr_records_without_classification'] = int(values['adr_tag_records'] and not kind)
            for group in groups:
                for name, value in values.items():
                    group[name] += value
        metadata = dict(db.execute('SELECT key,value FROM metadata'))
        return {
            'schema_version': 1,
            'database': path.name,
            'legal_completeness': 'unknown',
            'scope': 'Stored way records only; node rules are counted separately. Missing way records do not establish clearance or a missing legal ban.',
            'sources': json.loads(metadata.get('sources', '[]')),
            'restriction_version': metadata.get('restriction_version', 'unknown'),
            'totals': totals,
            'node_rule_records': db.execute('SELECT count(*) FROM node_rules').fetchone()[0],
            'orphan_way_rule_records': db.execute('SELECT count(*) FROM road_rules q LEFT JOIN roads r ON r.id=q.way WHERE r.id IS NULL').fetchone()[0],
            'by_road_class': dict(sorted(classes.items())),
            'spatial': {
                'method': 'road bounding-box centre; not jurisdiction coverage',
                'cell_size_degrees': cell_size,
                'cells': [{'south': a * cell_size, 'west': b * cell_size, **counts}
                          for (a, b), counts in sorted(cells.items())],
            },
        }
    finally:
        db.close()


def write_report(databases, destination, cell_size=0.25):
    reports = [audit_database(p, cell_size) for p in databases]
    destination = Path(destination)
    # Preserve previous receipts and maps; each report needs a fresh output path.
    with destination.open('x', encoding='utf-8') as output:
        json.dump({'schema_version': 1, 'maps': reports}, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write('\n')
    return reports


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('databases', nargs='+', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--cell-size', type=float, default=0.25)
    args = parser.parse_args()
    write_report(args.databases, args.out, args.cell_size)
