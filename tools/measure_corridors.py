"""Measure the real Android router on an already installed country (no data reset).

Input: corridor JSON with lat/lon a/b arrays, explicit truck options and modes.
Output records route failures too. A successful measurement run is not route approval.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import smoke_android as s


def read_pref(name):
    """Read a preference file, treating absence as an expected state.

    'shell' rather than 'exec-out': on a real API 26 device, exec-out reports a missing file as EXIT 0
    with the message on STDOUT, so nothing raises and the error text would be handed back as if it were
    the file. Through 'shell' the same read exits non-zero with empty stdout and the reason on stderr,
    which is what can be told apart from a genuine read failure. A fresh install has no settings.xml, so
    absence must not abort the run; run-as denied or an adb error must still raise. Both ends read the
    same way, so equality still means the file survived instrumentation.
    """
    try:
        return s.run("shell", "run-as", "org.eurorig.app", "cat", "shared_prefs/" + name)
    except subprocess.CalledProcessError as error:
        if b"No such file or directory" in (error.stderr or b""):
            return None
        raise


def preserved(before, after, name):
    """Refuse a run whose instrumentation changed persisted driver settings.

    Absent before and absent after counts as preserved, which is the normal state of a fresh install.
    """
    if before == after:
        return True
    raise RuntimeError(f"Instrumentation changed persisted driver settings: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--physical", action="store_true",
                        help="Explicitly measure an idle physical device's existing map without importing QA data")
    parser.add_argument("--camera", action="store_true",
                        help="Also check camera follow, heading and route trimming using a corpus route, without saved endpoint changes")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--qa-region", type=Path, help="Private manifest/routing.tar/display.sqlite folder; retains the selected map")
    args = parser.parse_args()
    physical = not args.device.startswith("emulator-")
    if physical and not args.physical:
        parser.error("Physical testing stops the app; explicitly use --physical on an idle device.")
    if physical and args.qa_region:
        parser.error("Physical testing uses the installed map; QA region staging is emulator-only.")
    # smoke_android.run builds every adb command from this module global, so it has to be set before the
    # first call - the storage preflight below is already one.
    s.device = args.device
    if physical:
        services = s.run("shell", "dumpsys", "activity", "services", "org.eurorig.app").decode()
        if "NavService" in services or "MapDownloadService" in services:
            parser.error("Stop guidance and map downloads before physical instrumentation.")
    payload = args.corpus.read_bytes()
    corpus = json.loads(payload)
    if not corpus.get("corridors") or not corpus.get("truck") or not corpus.get("modes"):
        parser.error("Input requires corridors, explicit truck options and modes.")
    manifest = None
    if args.qa_region:
        manifest = json.loads((args.qa_region / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("native_version") != "0.6.3" or manifest.get("format") != 2:
            parser.error("QA region must target the pinned native engine and SQLite format.")
        for name in ("routing.tar", "display.sqlite"):
            with (args.qa_region / name).open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != manifest["sha256"][name]:
                    parser.error("QA region checksum mismatch: " + name)
        # Storage preflight. The runner stages the package to /data/local/tmp, the app copies it into its
        # private files, and the engine then extracts routing.tar into graph tiles - so the device needs
        # roughly three times the package size free. Running out mid-copy leaves a truncated routing.tar
        # which the app later reports as a corrupt map, so refuse before staging rather than after.
        staged = sum((args.qa_region / n).stat().st_size
                     for n in ("manifest.json", "routing.tar", "display.sqlite"))
        free_kb = None
        try:
            free_kb = int(s.run("shell", "df", "-k", "/data").decode().strip().splitlines()[-1].split()[3])
        except (IndexError, ValueError):
            print(f"WARNING: could not read free space on {args.device}; staging "
                  f"{staged / 1e9:.2f} GB without a preflight", flush=True)
        if free_kb is not None and free_kb * 1024 < staged * 3:
            parser.error(f"{args.device} has {free_kb * 1024 / 1e9:.2f} GB free but this package needs about "
                         f"{staged * 3 / 1e9:.2f} GB (stage + private copy + extracted tiles); "
                         f"free space or use a larger emulator before staging")
    # Physical evidence starts before APK replacement, so installation cannot hide a preference change.
    settings_before = read_pref("settings.xml")
    truck_before = read_pref("truck.xml")
    endpoints_before = read_pref("endpoints.xml")
    # The QA region is opened alongside the driver's selected map, never in place of it. Record the installed
    # region's listing so a receipt can show the active map was left alone, not merely that preferences were.
    installed_region = None
    match = re.search(rb'<string name="region">([^<]*)</string>', settings_before or b"")
    if match:
        # Trim: 'shell' may translate line endings, so the captured value must not carry a stray \r into
        # the path built below - a mismatched path would read as an empty listing and pass the check.
        installed_region = match.group(1).decode("utf-8", "replace").strip()
    installed_hashes = None
    if physical:
        if not installed_region or not re.fullmatch(r"[0-9a-f-]{36}", installed_region):
            raise RuntimeError("Physical corridor testing requires an installed native country")
        installed_hashes = s.run("shell", "run-as", "org.eurorig.app", "sha256sum",
                                *("files/regions/" + installed_region + "/" + name
                                  for name in ("manifest.json", "routing.tar", "display.sqlite")))
    s.run("install", "-r", s.ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    s.run("install", "-r", s.ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    s.run("shell", "am", "force-stop", "org.eurorig.app")
    installed_before = None
    if args.qa_region and installed_region:
        installed_before = s.run("shell", "run-as", "org.eurorig.app", "ls", "-l",
                                 "files/regions/" + installed_region, check=False).decode()
    extra = []
    if args.qa_region:
        s.run("shell", "run-as", "org.eurorig.app", "mkdir", "-p", "files/coherent-qa")
        for name in ("manifest.json", "routing.tar", "display.sqlite"):
            temporary = "/data/local/tmp/eurorig-qa-" + name
            s.run("push", (args.qa_region / name).resolve(), temporary, timeout=600)
            try:
                s.run("shell", "run-as", "org.eurorig.app", "cp", temporary, "files/coherent-qa/" + name, timeout=600)
            finally:
                s.run("shell", "rm", "-f", temporary)
        extra = ["-e", "qaRegion", "coherent-qa"]
    s.run("push", args.corpus.resolve(), "/data/local/tmp/eurorig-corridors.json")
    s.run("shell", "run-as", "org.eurorig.app", "cp", "/data/local/tmp/eurorig-corridors.json", "files/native-corridors.json")
    checks = ["-e", "cameraOnly", "true", "-e", "cameraCorpus", "true"] if args.camera else ["-e", "corridorsOnly", "true"]
    output = s.run("shell", "am", "instrument", "-w", *checks,
                   *extra, "org.eurorig.app.test/org.eurorig.app.NativeSmokeInstrumentation", timeout=600).decode()
    print(output, flush=True)
    if "PASS:" not in output or "FAIL:" in output:
        raise RuntimeError("Corridor instrumentation failed")
    result = json.loads(s.run("exec-out", "run-as", "org.eurorig.app", "cat", "files/native-corridor-results.json"))
    if result["input"] != corpus:
        raise RuntimeError("Device results do not match this input")
    if manifest is not None and result["map_manifest"] != manifest:
        raise RuntimeError("Device measured a different QA region")
    # The same reader at both ends, so a fresh install without preference files is compared like for like
    # while a genuine read failure still raises.
    result["settings_preserved"] = settings_before == read_pref("settings.xml")
    result["truck_preserved"] = truck_before == read_pref("truck.xml")
    result["endpoints_preserved"] = endpoints_before == read_pref("endpoints.xml")
    result["device"] = args.device
    result["physical"] = physical
    result["camera_checks"] = args.camera
    result["corpus_sha256"] = hashlib.sha256(payload).hexdigest()
    result["installed_region"] = installed_region
    if installed_hashes is not None:
        after = s.run("shell", "run-as", "org.eurorig.app", "sha256sum",
                      *("files/regions/" + installed_region + "/" + name
                        for name in ("manifest.json", "routing.tar", "display.sqlite")))
        result["installed_map_hashes_unchanged"] = installed_hashes == after
        result["installed_map_sha256"] = installed_hashes.decode().splitlines()
    if installed_before is not None and installed_region:
        installed_after = s.run("shell", "run-as", "org.eurorig.app", "ls", "-l",
                                "files/regions/" + installed_region, check=False).decode()
        result["installed_map_untouched"] = installed_before == installed_after
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for item in result["results"]:
        print(item["id"], item["mode"], item["status"], item.get("km"), item.get("error", ""))
    # After the receipt is on disk, so a failure keeps its evidence instead of vanishing with the exception.
    for name in ("settings", "truck", "endpoints"):
        if not result[name + "_preserved"]:
            raise RuntimeError("Instrumentation or APK replacement changed " + name + "; receipt written to " + str(args.output))
    if result.get("installed_map_untouched") is False:
        raise RuntimeError(f"Instrumentation modified the driver's installed region "
                           f"{installed_region!r}; receipt written to {args.output}")
    if result.get("installed_map_hashes_unchanged") is False:
        raise RuntimeError("Physical testing changed installed map bytes; receipt written to " + str(args.output))


if __name__ == "__main__":
    main()
