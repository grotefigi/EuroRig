"""Validate native corpus outcomes and physical bounds; engine agreement is not a gate."""
import argparse
import json
import math
from pathlib import Path
from benchmarks.hermes.regressions.eta_is_physical import assert_eta_at_least_bound


def check(data):
    corpus = data["input"]
    cases = {c["id"]: c for c in corpus["corridors"]}
    expected = {(name, mode) for name in cases for mode in corpus["modes"]}
    rows = {(r["id"], r["mode"]): r for r in data["results"]}
    if len(rows) != len(data["results"]) or rows.keys() != expected:
        raise AssertionError("Missing, duplicate or unexpected measurements")
    routes = 0
    for (name, mode), row in rows.items():
        outcome = cases[name]["expected"]
        if row["status"] != outcome:
            raise AssertionError(f"{name}/{mode}: expected {outcome}, got {row['status']}: {row.get('error', '')}")
        if outcome == "no_route":
            if not row.get("error"):
                raise AssertionError(f"{name}/{mode}: rejection has no explanation")
            continue
        for field in ("km", "min", "origin_snap_metres", "destination_snap_metres"):
            if not math.isfinite(row[field]) or row[field] < 0:
                raise AssertionError(f"{name}/{mode}: invalid {field}")
        if row["km"] <= 0 or row["min"] <= 0 or row["shape_points"] < 2:
            raise AssertionError(f"{name}/{mode}: empty route")
        for key in ("snapped_origin", "snapped_destination"):
            if len(row[key]) != 2 or not all(math.isfinite(x) for x in row[key]):
                raise AssertionError(f"{name}/{mode}: invalid snapped coordinates")
        if max(row["origin_snap_metres"], row["destination_snap_metres"]) > 251:
            raise AssertionError(f"{name}/{mode}: snap exceeds the configured 250 m cutoff plus rounding")
        # A ferry may travel faster than the truck's road speed; this bound applies to road-only requests.
        if corpus["truck"]["avoid_ferries"]:
            assert_eta_at_least_bound(row["min"], [(row["km"] * 1000, corpus["truck"]["top_speed"])], 1 / 60)
        routes += 1
    comparisons = 0
    different_snaps = 0
    for name in cases:
        shortest = rows.get((name, "SHORTEST"))
        if not shortest or shortest["status"] != "route":
            continue
        for mode in corpus["modes"]:
            other = rows[name, mode]
            if other["status"] != "route":
                continue
            if any(abs(a - b) > 0.00001 for key in ("snapped_origin", "snapped_destination")
                   for a, b in zip(shortest[key], other[key])):
                different_snaps += 1
                continue
            if shortest["km"] > other["km"] + 0.001:
                raise AssertionError(f"{name}: SHORTEST exceeds {mode}; investigate, do not tune a percentage tolerance")
            comparisons += 1
    return (f"PASS: {routes} native routes and {len(rows) - routes} expected rejections; ETA and snaps; "
            f"{comparisons} same-endpoint shortest comparisons, {different_snaps} comparisons excluded for different snaps")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    print(check(json.loads(args.results.read_text(encoding="utf-8"))))
