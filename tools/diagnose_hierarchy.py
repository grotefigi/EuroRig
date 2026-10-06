"""Compare host-only hierarchy pruning with exact Android requests.

Requires the existing map-build pyvalhalla dependency. These routes do not run
EuroRig's post-route evidence audit and must not be treated as approved routes.
"""
import argparse
import copy
import json
import time
from pathlib import Path
from valhalla import Actor

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("config", type=Path)
parser.add_argument("measurements", type=Path)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
config = json.loads(args.config.read_text(encoding="utf-8"))
for key in ("tile_url", "traffic_extract", "admin", "timezone", "landmarks", "transit_dir", "transit_feeds_dir", "tile_extract"):
    config["mjolnir"].pop(key, None)
config["mjolnir"]["max_cache_size"] = 32 * 1024 * 1024
config["service_limits"]["allow_hard_exclusions"] = True
config["service_limits"]["max_distance_disable_hierarchy_culling"] = 5_000_000
actor = Actor(config)
baseline = json.loads(args.measurements.read_text(encoding="utf-8"))
rows = []
for item in baseline["results"]:
    if item["mode"] != "SHORTEST" or item["id"] not in ("galati_bucharest", "galati_nadlac"):
        continue
    for disabled in (False, True):
        request = copy.deepcopy(item["request"])
        request["costing_options"]["truck"]["disable_hierarchy_pruning"] = disabled
        started = time.perf_counter()
        response = actor.route(request)
        row = {"id": item["id"], "request": request, "summary": response["trip"]["summary"],
               "elapsed_seconds": time.perf_counter() - started, "warnings": response.get("warnings")}
        print(json.dumps(row), flush=True)
        rows.append(row)
args.output.write_text(json.dumps({"config": config, "results": rows}, indent=2) + "\n", encoding="utf-8")
