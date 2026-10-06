#!/usr/bin/env python3
"""Compare corridor results from several engines. Standard library only.

DESIGN CONSTRAINTS (deliberate, see README):
  * No engine is "truth". Distance agreement between engines is reported as DIVERGENCE, never as a
    pass/fail gate, because the engines differ in data snapshot, cost model, restriction semantics
    and snapping policy. A number close to another engine's number is not evidence of correctness.
  * Nothing here asserts anything about legality, restrictions, turns, ferries or endpoints.
    Those need the checks in ../regressions and the native audit on the device.
  * Adding an engine = dropping a JSON file in corridors/results/ with the shape below, or pointing
    --adapter at a script (see adapter.py).

Result file shape (all fields except id optional but recorded in the table when present):
  {"<engine label>": ..., "corridors": {"<id>": {"km": float, "min": float, "engine": str,
                                                 "version": str, "osm_snapshot": str, ...}}}

Usage:
  python compare.py                          # every results/*.json
  python compare.py --engines a.json b.json  # chosen files
  python compare.py --divergence 10          # flag pairs differing by more than N% (default 10)
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
RESULTS = HERE.parent / "corridors" / "results"


def load(path: pathlib.Path) -> dict:
    d = json.loads(path.read_text(encoding="utf-8"))
    return d


def corridor_table(data: dict) -> dict:
    c = data.get("corridors", data)
    out = {}
    for k, v in c.items():
        if isinstance(v, dict):
            if "ch" in v and isinstance(v["ch"], dict):       # graphhopper shape
                out[k] = v["ch"]
            elif "km" in v or "error" in v:
                out[k] = v
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engines", nargs="*", default=None)
    ap.add_argument("--divergence", type=float, default=10.0)
    args = ap.parse_args()

    files = ([pathlib.Path(f) for f in args.engines] if args.engines
             else sorted(RESULTS.glob("*.json")))
    engines = {p.stem: corridor_table(load(p)) for p in files}
    ids = sorted({k for e in engines.values() for k in e})
    if not ids:
        print("no corridors found", file=sys.stderr)
        return 2

    print(f"{'corridor':22}" + "".join(f"{n[:14]:>16}" for n in engines))
    print("-" * (22 + 16 * len(engines)))
    for cid in ids:
        row = f"{cid:22}"
        for name, e in engines.items():
            r = e.get(cid)
            km = r.get("km") if isinstance(r, dict) else None
            if isinstance(km, (int, float)):
                cell = f"{km:.1f} km"          # null km is a legitimate result: no route
            elif r is None:
                cell = "-"
            else:
                cell = "err/no-route"
            row += f"{cell:>16}"
        print(row)

    print(f"\nPairwise divergence (> {args.divergence:g}% flagged as INVESTIGATE, never FAIL):")
    names = list(engines)
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            a, b = engines[names[i]], engines[names[j]]
            for cid in ids:
                ra, rb = a.get(cid), b.get(cid)
                if not (ra and rb and ra.get("km") and rb.get("km")):
                    continue
                pct = (rb["km"] - ra["km"]) / ra["km"] * 100.0
                mark = "  INVESTIGATE" if abs(pct) > args.divergence else ""
                print(f"  {cid:22} {names[i][:12]:>13} -> {names[j][:12]:<13} {pct:+7.2f}%{mark}")

    print("\nRecorded metadata per engine:")
    for p in files:
        d = load(p)
        meta = {k: v for k, v in d.items() if k != "corridors"}
        if "truck" in meta:
            meta["truck"] = d["truck"]
        print(f"  {p.stem:20} {json.dumps(meta, ensure_ascii=False)[:150]}")
    print("\nReminder: divergence is not error. See README, 'What this comparison can and cannot say'.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
