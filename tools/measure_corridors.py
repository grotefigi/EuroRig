"""Measure the real Android router on an already installed country (no data reset).

Input: corridor JSON with lat/lon a/b arrays, explicit truck options and modes.
Output records route failures too. A successful measurement run is not route approval.
"""
import argparse
import hashlib
import json
from pathlib import Path
import smoke_android as s


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.device.startswith("emulator-"):
        parser.error("Use a dedicated emulator; instrumentation stops the running app.")
    payload = args.corpus.read_bytes()
    corpus = json.loads(payload)
    if not corpus.get("corridors") or not corpus.get("truck") or not corpus.get("modes"):
        parser.error("Input requires corridors, explicit truck options and modes.")
    s.device = args.device
    s.run("install", "-r", s.ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    s.run("install", "-r", s.ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    s.run("shell", "am", "force-stop", "org.eurorig.app")
    s.run("push", args.corpus.resolve(), "/data/local/tmp/eurorig-corridors.json")
    s.run("shell", "run-as", "org.eurorig.app", "cp", "/data/local/tmp/eurorig-corridors.json", "files/native-corridors.json")
    output = s.run("shell", "am", "instrument", "-w", "-e", "corridorsOnly", "true",
                   "org.eurorig.app.test/org.eurorig.app.NativeSmokeInstrumentation", timeout=600).decode()
    print(output, flush=True)
    if "PASS:" not in output or "FAIL:" in output:
        raise RuntimeError("Corridor instrumentation failed")
    result = json.loads(s.run("exec-out", "run-as", "org.eurorig.app", "cat", "files/native-corridor-results.json"))
    if result["input"] != corpus:
        raise RuntimeError("Device results do not match this input")
    result["corpus_sha256"] = hashlib.sha256(payload).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for item in result["results"]:
        print(item["id"], item["mode"], item["status"], item.get("km"), item.get("error", ""))


if __name__ == "__main__":
    main()
