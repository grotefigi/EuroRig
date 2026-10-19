"""Deterministic fixture regression: engine behaviour and pack evidence, checked separately.

Expectations come from the authored OSM in tools/fixtures/warehouse/warehouse.osm, not from the
matcher or the authorization code under test. The two sections stay apart because neither proves the
other: the engine choosing a lawful route says nothing about our audit, and a pack record says
nothing about what the engine did.

Fixture geometry (lat, lon):
  2001 Main Street West   (45.0, 25.0000) -> (45.0, 25.0010)   public
  2002 Low Bridge Road    (45.0, 25.0010) -> (45.0, 25.0020)   maxheight 3.5
  2005 Bypass West        (45.0, 25.0010) -> (45.0020, 25.0010)
  2006 Bypass North       (45.0020, 25.0010) -> (45.0020, 25.0020)  oneway=yes
  2008/2010 delivery gates;  2011 and 2013-2015 private

Run with the project venv:
  tools/.venv/Scripts/python.exe tools/fixture_checks.py <config.json> <display.sqlite>
"""
import json
import sqlite3
import sys

import valhalla

TRUCK = {"height": 3.89, "width": 2.55, "length": 16.5, "weight": 41.0, "axle_load": 8.0}


def trace(actor, origin, destination):
    """Route, then read the directed edges the engine actually used.

    trace_attributes is how the application audits a route, so the harness reads the same thing the
    app would rather than trusting a shape string.
    """
    body = {"locations": [{"lat": origin[0], "lon": origin[1]}, {"lat": destination[0], "lon": destination[1]}],
            "costing": "truck", "costing_options": {"truck": dict(TRUCK)}}
    reply = actor.route(json.dumps(body))
    result = json.loads(reply) if isinstance(reply, str) else reply
    if not isinstance(result, dict) or "error" in result or "trip" not in result:
        return None, (result or {}).get("error", "no trip")
    shape = result["trip"]["legs"][0]["shape"]
    request = {"encoded_polyline": shape, "costing": "truck",
               "costing_options": {"truck": dict(TRUCK)}}
    for matcher in ("edge_walk", "map_snap"):
        request["shape_match"] = matcher
        try:
            attributes = json.loads(actor.trace_attributes(json.dumps(request)))
        except Exception as failure:  # edge_walk raises on a partial-edge route; try the next matcher
            last_error = "%s: %s" % (matcher, str(failure)[:120])
            continue
        if "error" in attributes:
            last_error = "%s: %s" % (matcher, attributes["error"][:120])
            continue
        return [(edge.get("way_id"), edge.get("begin_heading")) for edge in attributes.get("edges", [])], matcher
    return None, last_error if "last_error" in locals() else "no matcher produced edges"


def ways_of(edges):
    return [way for way, _ in edges]


def consecutive(ways):
    return list(zip(ways, ways[1:]))


def main(config, database):
    actor = valhalla.Actor(config)
    db = sqlite3.connect(database)
    rules = {row[0]: row[1:] for row in db.execute("select way,flags,kind,evidence from road_rules")}
    failures = []

    def check(name, condition, detail=""):
        print(("PASS  " if condition else "FAIL  ") + name + (("  - " + str(detail)) if detail else ""))
        if not condition:
            failures.append(name)

    print("== engine (what Valhalla does, not what our audit decides) ==")

    edges, why = trace(actor, (45.0, 25.0002), (45.0, 25.0008))
    check("public route along 2001 is matched", edges is not None, why)
    if edges:
        check("public route uses way 2001", ways_of(edges) == [2001], ways_of(edges))

    edges, why = trace(actor, (45.0, 25.0014), (45.0, 25.0017))
    check("bridge route is matched", edges is not None, why)
    if edges:
        check("engine routes over the 3.5 m bridge although the truck is 3.89 m",
              2002 in ways_of(edges), ways_of(edges))

    # Relation 3001 forbids 2002 -> node 1003 -> 2010; assert the transition, not a detour distance.
    edges, why = trace(actor, (45.0, 25.0015), (45.0008, 25.0018))
    check("route towards warehouse A gate is matched", edges is not None, why)
    if edges:
        check("prohibited turn 2002 -> 2010 is absent",
              (2002, 2010) not in consecutive(ways_of(edges)), ways_of(edges))

    # Both endpoints sit on the one-way way, so the engine may use it for endpoint access. This is
    # recorded as an observation, not an enforcement claim: a route that touches a one-way edge near
    # a snapped endpoint proves nothing about through travel, which is why the audit checks the way's
    # own node order rather than trusting the engine.
    edges, why = trace(actor, (45.0020, 25.0018), (45.0020, 25.0012))
    observed = ways_of(edges) if edges else []
    print("NOTE  reverse trip with both endpoints on 2006 used edges:", observed or why)

    print("== pack evidence (what the audit can enforce) ==")
    check("way 2002 carries maxheight 3.5", "maxheight=3.5" in rules.get(2002, (0, 0, ""))[2], rules.get(2002))
    check("way 2006 is one-way with the prohibition facing backward",
          rules.get(2006, (0, 0, ""))[:2] == (0, 33), rules.get(2006))
    check("way 2008 is delivery-only with the access flag",
          rules.get(2008, (0, 0, ""))[:2] == (1, 2), rules.get(2008))
    check("way 2011 is private with the access flag",
          rules.get(2011, (0, 0, ""))[:2] == (1, 2), rules.get(2011))
    check("no rule invents a restriction on plain public road 2001", 2001 not in rules)

    print()
    if failures:
        print("FAILED:", len(failures), "check(s):", ", ".join(failures))
        return 1
    print("all fixture checks passed")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
