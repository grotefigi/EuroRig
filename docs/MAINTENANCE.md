# Maintenance and issue reproduction

Run maintenance from the pinned source checkout. Keep source extracts, device captures, truck/account inputs and real locations in ignored `analysis/private/`; never add a location telemetry path.

## Regression checks

On Windows, use the verified JDK 21 and Android API 36 setup, then run:

```powershell
./tools/build.ps1 -Tasks ':routing:test',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:lintDebug'
./.toolchain/qa-python/Scripts/python.exe tools/run_tooling_tests.py
```

The required tooling runner rejects skipped or empty suites. CI runs the routing tests, Android build/test APK and lint on Windows and Ubuntu, then executes the same tooling runner. Record the exact checkout commit and any dirty-source hash alongside APK/test APK SHA-256 values; do not treat a build pass as a route-legality or device pass.

## Source, map and dependency review

- Before applying a restriction source, review publisher, reuse rights, effective dates, review due date, units, direction, vehicle/load meaning, exact road match and map snapshot. Keep unresolved fields in the rejection queue. `tools/authority_intake.py` binds source, review policy, exact-match file and display-map snapshot; it rejects draft/expired/future/unknown records. `tools/apply_authority_rules.py` repeats those checks at an explicit `--as-of` date and writes to a fresh map copy. Follow [restriction-source intake](RESTRICTION-SOURCE-INTAKE.md); current RO/MD rights and legal matches are still unresolved.
- On a map refresh, record PBF publisher/URL, source timestamp, licence/attribution, routing engine version, display/restriction source hashes and resulting package hashes. Rebuild a fresh coherent package and audit it before replacing a catalogue entry. Never append a newer restriction batch to an already-applied display database.
- Review Gradle/plugin/library versions in `build.gradle`, `app/build.gradle`, `routing/build.gradle`, `gradle/verification-metadata.xml` and dependency licence notices. Change one pinned dependency at a time, run both operating-system CI jobs and preserve the previous candidate until the new APK passes device checks.
- Feed freshness is decided from its publisher timestamp and reviewed expiry policy. A failed/expired feed remains unavailable; do not infer an empty restriction set or available parking.

## Route issue reproduction

For each reported route issue, capture a private receipt tied together by:

1. EuroRig source revision plus dirty-file hashes; release/Preview APK and instrumentation APK hashes.
2. Installed map name, source date, manifest/package SHA-256 and the relevant restriction source record/hash.
3. Truck profile revision and exact entered dimensions, loaded/permitted/axle weights, axle count, cargo/tunnel inputs and route preferences. Keep the actual profile private.
4. Ordered origin, stops and destination; whether endpoints came from a named map result or coordinates; online/offline and traffic assumptions.
5. Reproduction steps, expected outcome and actual route/refusal, native test output, command and exit code. For a legal/access disagreement, preserve an authoritative source and exact road IDs or mark the result unresolved.

Use public synthetic coordinates for shareable fixtures. Keep home/depot/customer locations, GPS traces, account data, screenshots and raw Eurowag/RoadLords captures under ignored `analysis/private/`. Share a sanitized case only after removing direct and indirect location identifiers. Never treat a reference navigation result by itself as proof that a truck road or entrance is legal.

The current local build receipt and C2–G4 device receipts live under `analysis/private/c2-g4-current/`. They bind the tests to their actual inputs; they do not supply live production restriction data, physical Pixel results or road validation.
