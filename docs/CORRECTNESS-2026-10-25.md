# Correctness checkpoint — 2026-10-25

Base: `600b6dd` on the approved-interface branch. Private before/after device receipts, APK hashes, build logs and marker images are ignored under `analysis/private/correctness-2026-10-25/`. This checkpoint does not certify truck-navigation completion or legal map coverage.

## Changes

- Map controls observe touch inactivity at the container. Native buttons retain gesture cancellation, keyboard and accessibility click handling; no synthetic second click or lint suppression was added.
- Vehicle-marker paths are created once per view. Drawing reuses those paths and avoids the temporary wheel array. This removes the identified helper allocations, not every allocation inside Android rendering.
- Malformed saved truck values keep the profile editor usable but require review before planning or guidance. A replacement standard profile cannot silently authorize travel. Invalid data loaded during guidance stop the session; saving a reviewed valid profile clears the refusal. The existing missing-field defaults remain unchanged.
- Identical consecutive input locations share one native location, while a separate map preserves every user stop and its shape boundary. Nonconsecutive returns still create real legs. This prevents duplicate origin/destination stops from creating fictitious travel. Every moving leg still passes exact matching, strict connectedness and the whole-route hard-rule audit; no gap tolerance or fuzzy matcher was introduced. An entirely stationary trip remains invalid.
- Added six JVM mass tests and independent Android payload/profile-recovery checks. Reviewed DeepSeek DS02 without changing its originals; see [claim dispositions](DS02-REVIEW-2026-10-25.md) and [actual truck capabilities](TRUCK-CAPABILITIES.md).

## Verification record

| Check | Measured result |
| --- | --- |
| Routing JVM suite | 211 tests; zero failures, errors or skips, including six new mass tests. |
| Python tooling suite | 76 tests; zero failures or skips. |
| Preview and normal app/test builds | Both build configurations pass. Final preview run: 56 s; normal run: 49 s, using existing dependency caches. |
| Android lint | Zero errors, 82 warnings. Two `ClickableViewAccessibility` occurrences and one `DrawAllocation` occurrence are removed without suppressions. Other warnings remain. |
| Tablet correctness suite | PASS: exact native JSON, 100 t request boundary, corrupt active-profile refusal/recovery, native touch/cancel/drag, keyboard/accessibility activation, held-touch timer suspension and cancellation rearming. |
| Native profile/ADR suite | PASS in every routing mode, including duplicate origin/destination boundaries and existing hard-limit/ADR-C detour fixtures. |
| Journey/editor regression | PASS, including the formerly failing saved duplicate-destination stop, ten stops, real drag/drop and independent country options. |
| Marker comparison | All 12 images are pixel-identical: arrow/truck, headings 0/37/181 degrees, sizes 129/257 px, captured with the original and final production drawing code. No FPS improvement is claimed. |
| Preservation | All seven original preview preference files byte-match their backup after restoration. Rotation settings are restored. Original development-app version/update identity is unchanged; no install, clear or map operation targeted that package. |
| Artifact identity | Installed preview and companion SHA-256 match the archived tested APKs. Source hashes and measurements are in the private `RESULTS.json`; private APKs are under `apks/`. |

Run the checked-in suites using `gradlew.bat :routing:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; supply `-I tools/preview.gradle` for the isolated preview build. Tooling uses `.toolchain/qa-python/Scripts/python.exe -m unittest discover -s tools -p test_*.py` on this host. On the authorized tablet, the preview companion's `NativeSmokeInstrumentation` accepts `-e correctnessOnly true`, `-e profilesOnly true` and `-e uiRoutingOnly true`. The journey suite changes preview trip/preferences: use the private backup/restore wrapper and preserve device rotation. Never run it against the original development package.

Earlier failed receipts remain private and are not relabeled successful. The six task gates are met; that does not complete any larger roadmap package automatically.

The first journey regression exposed a persisted intermediate stop equal to its destination. Its stationary native final leg failed exact matching with Valhalla code 443. A standard nonhazardous-profile control failed identically, ruling out the initial hazardous-load hypothesis. Diagnostic evidence also found a 15.85 m gap between the moving leg's snapped endpoint and the dummy leg. Input normalization prevents the dummy leg while preserving the driver's stops and the strict audit. The failed intermediate experiments remain recorded; they are not acceptance evidence.

No physical Pixel, actual driving, complete ADR/country-law support or newly imported municipal coverage is claimed. The live overall percentage still measures formally accepted roadmap packages, not this task's checks.
