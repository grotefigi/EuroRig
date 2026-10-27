"""Fail-loud region integrity gate for device tests.

A missing offline map previously looked like a GPS or routing failure, and a test run was reported as
inconclusive because the region had been deleted underneath it. Every device test that needs a map
must prove the map is there before it starts and still there when it ends.

Never repairs, reinstalls or deletes anything: on failure it stops and preserves the observed state.
"""
import hashlib
import json
import subprocess
import sys

REQUIRED = ("manifest.json", "routing.tar")


class RegionError(RuntimeError):
    pass


def adb(serial, *args, check=True):
    r = subprocess.run(["adb", "-s", serial, "shell", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RegionError("adb failed: %s" % (r.stderr.strip() or r.stdout.strip()))
    return r.stdout.strip()


def _prefs(serial, package):
    xml = adb(serial, "run-as %s cat shared_prefs/settings.xml" % package)
    out = {}
    for line in xml.splitlines():
        line = line.strip()
        if line.startswith('<string name="region">'):
            out["region"] = line.split(">", 1)[1].split("<", 1)[0]
        if line.startswith('<boolean name="native"'):
            out["native"] = 'value="true"' in line
    return out


def state(serial, package):
    """Observed region state, without changing anything."""
    prefs = _prefs(serial, package)
    region = prefs.get("region", "")
    listing = adb(serial, "run-as %s ls files/regions" % package, check=False).split()
    files = {}
    if region:
        raw = adb(serial, "run-as %s ls -l files/regions/%s" % (package, region), check=False)
        for line in raw.splitlines():
            parts = line.split()
            # -rw-rw-rw- 1 u0_a370 u0_a370 799916032 2026-10-19 18:09 display.sqlite
            if len(parts) >= 8:
                files[parts[-1]] = int(parts[4]) if parts[4].isdigit() else 0
    return {"region": region, "native": prefs.get("native", False), "regions": listing, "files": files}


def require(serial, package, expect_region=None, expect_name=None, label="test"):
    """Stop the test unless the selected region is complete and usable."""
    s = state(serial, package)
    problems = []
    if not s["region"]:
        problems.append("no region selected in settings")
    if not s["native"]:
        problems.append("native map mode is off (settings native=false)")
    if expect_region and s["region"] != expect_region:
        problems.append("selected region %r is not the expected %r" % (s["region"], expect_region))
    for name in REQUIRED:
        if s["files"].get(name, 0) <= 0:
            problems.append("missing or empty %s" % name)
    if s["files"].get("display.sqlite", 0) <= 0 and s["files"].get("display.europack", 0) <= 0:
        problems.append("missing display map")
    if expect_name:
        try:
            manifest = json.loads(adb(serial, "run-as %s cat files/regions/%s/manifest.json" % (package, s["region"])))
            if manifest.get("name") != expect_name:
                problems.append("region name %r is not %r" % (manifest.get("name"), expect_name))
        except (RegionError, ValueError):
            problems.append("manifest.json is unreadable")
    if problems:
        raise RegionError("REGION PREFLIGHT FAILED for %s: %s | observed: %s"
                          % (label, "; ".join(problems), json.dumps(s)))
    return s


def verify_unchanged(serial, package, before, label="test"):
    """After the test: the same region must still be installed, with its files intact."""
    after = state(serial, package)
    if after["region"] != before["region"]:
        raise RegionError("REGION CHANGED during %s: %s -> %s" % (label, before["region"], after["region"]))
    for name, size in before["files"].items():
        if after["files"].get(name, 0) != size:
            raise RegionError("REGION FILE %s changed during %s: %d -> %d"
                              % (name, label, size, after["files"].get(name, 0)))
    return after


if __name__ == "__main__":
    serial, package = sys.argv[1], sys.argv[2]
    expect = sys.argv[3] if len(sys.argv) > 3 else None
    print(json.dumps(require(serial, package, expect_region=expect), indent=2))
