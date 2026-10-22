"""Batch-build EuroRig country packages from Geofabrik OSM extracts.

Each country: download PBF (if absent) -> build_country.py -> .eurorig.
Countries run N at a time; a failure is logged and the run continues.

Usage:
    python batch_countries.py --out C:/EverythingAI/countries --parallel 3
    python batch_countries.py --out ... --only andorra malta
    python batch_countries.py --out ... --skip-big        # small countries first
    python batch_countries.py --out ... --stage small|big|rest
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

TOOLS = Path(r"C:\EverythingAI\EuroRig\tools")
PYTHON = Path(r"C:\EverythingAI\Project EurowagNav\tools\.venv\Scripts\python.exe")
PBF_DIR = Path(r"C:\EverythingAI\Project EurowagNav\data")
DATA = Path(r"C:\EverythingAI\batch_state.json")

# (display name, geofabrik url path, bbox center lat, lon)
# bbox is only the map's opening view; a +/-0.05 deg box around the centre.
COUNTRIES = [
    ("Albania",       "europe/albania",        41.15, 20.17),
    ("Andorra",       "europe/andorra",        42.55,  1.58),
    ("Armenia",       "asia/armenia",          40.18, 44.51),
    ("Austria",       "europe/austria",        47.60, 14.12),
    ("Belarus",       "europe/belarus",        53.71, 27.95),
    ("Belgium",       "europe/belgium",        50.50,  4.47),
    ("Bosnia",        "europe/bosnia-herzegovina", 43.92, 17.68),
    ("Bulgaria",      "europe/bulgaria",       42.73, 25.49),
    ("Croatia",       "europe/croatia",        45.10, 15.20),
    ("Cyprus",        "europe/cyprus",         35.13, 33.43),
    ("Czechia",       "europe/czech-republic", 49.82, 15.47),
    ("Denmark",       "europe/denmark",        56.26,  9.50),
    ("Estonia",       "europe/estonia",        58.60, 25.01),
    ("Faroe Islands", "europe/faroe-islands",  61.89, -6.91),
    ("Finland",       "europe/finland",        61.92, 25.75),
    ("France",        "europe/france",         46.23,  2.21),
    ("Georgia",       "europe/georgia",        42.32, 43.36),
    ("Germany",       "europe/germany",        51.17, 10.45),
    ("Greece",        "europe/greece",         39.07, 21.82),
    ("Hungary",       "europe/hungary",        47.16, 19.50),
    ("Iceland",       "europe/iceland",        64.96,-19.02),
    ("Ireland",       "europe/ireland-and-northern-ireland", 53.41,-8.24),
    ("Italy",         "europe/italy",          41.87, 12.57),
    ("Kosovo",        "europe/kosovo",         42.60, 20.90),
    ("Latvia",        "europe/latvia",         56.88, 24.60),
    ("Liechtenstein", "europe/liechtenstein",  47.17,  9.55),
    ("Lithuania",     "europe/lithuania",      55.17, 23.88),
    ("Luxembourg",    "europe/luxembourg",     49.82,  6.13),
    ("Malta",         "europe/malta",          35.94, 14.38),
    ("Moldova",       "europe/moldova",        47.41, 28.37),
    ("Monaco",        "europe/monaco",         43.74,  7.42),
    ("Montenegro",    "europe/montenegro",     42.71, 19.37),
    ("Morocco",       "africa/morocco",        31.79, -7.09),
    ("Netherlands",   "europe/netherlands",    52.13,  5.29),
    ("North Macedonia","europe/macedonia",     41.61, 21.75),
    ("Norway",        "europe/norway",         60.47,  8.47),
    ("Poland",        "europe/poland",         51.92, 19.15),
    ("Portugal",      "europe/portugal",       39.40, -8.22),
    ("Romania",       "europe/romania",        45.94, 24.97),
    ("Russia",        "russia",                55.75, 37.62),
    ("Serbia",        "europe/serbia",         44.02, 21.01),
    ("Slovakia",      "europe/slovakia",       48.67, 19.70),
    ("Slovenia",      "europe/slovenia",       46.15, 14.99),
    ("Spain",         "europe/spain",          40.46, -3.75),
    ("Sweden",        "europe/sweden",         60.13, 18.64),
    ("Switzerland",   "europe/switzerland",    46.82,  8.23),
    ("Turkey",        "europe/turkey",         38.96, 35.24),
    ("Ukraine",       "europe/ukraine",        48.38, 31.17),
    ("United Kingdom","europe/united-kingdom", 54.24, -2.94),
]

SMALL = 100      # MB — below this is "small"
BIG = 1000       # MB — above this is "big"


def state() -> dict:
    if DATA.exists():
        try:
            return json.loads(DATA.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def save(d: dict) -> None:
    DATA.write_text(json.dumps(d, indent=1), encoding="utf-8")


def pbf_size(url: str) -> float:
    try:
        r = urllib.request.Request(url, method="HEAD",
                                   headers={"User-Agent": "EuroRig batch/1.0"})
        with urllib.request.urlopen(r, timeout=30) as resp:
            n = resp.headers.get("Content-Length")
            return int(n) / 2**20 if n else 0.0
    except Exception:
        return 0.0


def ensure_pbf(url: str, dest: Path) -> None:
    if dest.exists() and dest.stat().st_size > 1024:
        return
    print(f"    downloading {dest.name} ...", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "EuroRig batch/1.0"})
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(req, timeout=600) as r, open(tmp, "wb") as out:
        while block := r.read(1 << 20):
            out.write(block)
    tmp.replace(dest)


def build_one(name: str, slug: str, lat: float, lon: float, out_root: Path) -> tuple[str, str, float]:
    t0 = time.time()
    pbf = PBF_DIR / (slug.split("/")[-1] + "-latest.osm.pbf")
    url = f"https://download.geofabrik.de/{slug}-latest.osm.pbf"
    work = out_root / name
    try:
        ensure_pbf(url, pbf)
        if work.exists():
            shutil.rmtree(work)
        cmd = [str(PYTHON), str(TOOLS / "build_country.py"), str(work),
               "--pbf", str(pbf), "--name", name, "--jobs", "4",
               "--start", f"{lat-0.05:.4f}", f"{lon-0.05:.4f}",
               "--end", f"{lat+0.05:.4f}", f"{lon+0.05:.4f}"]
        proc = subprocess.run(cmd, cwd=str(TOOLS), capture_output=True, text=True, timeout=21600)
        dt = time.time() - t0
        if proc.returncode != 0:
            return (name, "FAIL " + (proc.stderr or proc.stdout or "")[-300:].replace("\n", " "), dt)
        pkg = work / f"{name}.eurorig"
        if not pkg.exists():
            return (name, "FAIL no package produced", dt)
        return (name, f"ok {pkg.stat().st_size/2**20:.1f} MB", dt)
    except Exception as e:
        return (name, f"FAIL {e}", time.time() - t0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--parallel", type=int, default=3)
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--stage", choices=["small", "big", "rest", "all"], default="all")
    args = ap.parse_args()

    out_root = Path(args.out)
    out_root.mkdir(parents=True, exist_ok=True)

    todo = []
    for name, slug, lat, lon in COUNTRIES:
        if args.only and name not in args.only:
            continue
        if args.only is None and args.stage != "all":
            mb = pbf_size(f"https://download.geofabrik.de/{slug}-latest.osm.pbf")
            if args.stage == "small" and not (0 < mb < SMALL):
                continue
            if args.stage == "big" and not (mb >= BIG):
                continue
            if args.stage == "rest" and not (SMALL <= mb < BIG):
                continue
        todo.append((name, slug, lat, lon))

    print(f"building {len(todo)} countries, {args.parallel} at a time\n", flush=True)
    st = state()
    done = 0
    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures = {pool.submit(build_one, n, s, la, lo, out_root): n for n, s, la, lo in todo}
        for fut in as_completed(futures):
            name, result, dt = fut.result()
            done += 1
            st[name] = {"result": result, "seconds": round(dt, 1),
                        "when": time.strftime("%Y-%m-%d %H:%M")}
            save(st)
            print(f"  [{done}/{len(todo)}] {name:<16} {result:<22} {dt/60:>5.1f} min", flush=True)

    ok = sum(1 for v in st.values() if v["result"].startswith("ok"))
    print(f"\ncomplete: {ok} ok, {len(todo) - ok} failed this run")
    return 0


if __name__ == "__main__":
    sys.exit(main())
