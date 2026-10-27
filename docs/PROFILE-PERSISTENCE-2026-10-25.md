# Named-profile persistence checkpoint — 2026-10-25

Base: `17eda0e`. This extends the active-preference refusal fix to the named-profile JSON decoder and recovery path. It does not establish complete truck/ADR support.

## Result

Present malformed numeric/boolean values now produce an error instead of silently taking defaults. Integer fields reject fractions and overflow instead of truncating. Missing legacy fields keep the existing defaults, which are materialized before an older profile reaches the editor; missing numeric keys cannot become `NaN` in its display formatter.

A legacy truck with only the broad hazardous-material flag now migrates to the general-load checkbox. Its protection survives the round trip and the driver can explicitly clear it. Existing detailed load bits and tunnel codes retain their meaning; no new ADR consignment classes are claimed.

An unreadable named record no longer discards readable siblings. Duplicate IDs are excluded from the usable list. The exact failed input remains in the existing `vehicle_profiles_recovery` slot, which stores the latest recovery input rather than a historical archive. If the saved active ID is missing, the list includes a copy of the unchanged current truck instead of silently presenting an unrelated sibling as active. The profile list displays a review notice; the current truck's separate review refusal is also visible there. Named recovery does not automatically replace the active routing truck.

## Evidence

- Preview and normal debug/companion builds pass; final preview build took 49 s, normal build 51 s with existing caches.
- Routing suite: 211 tests, zero failures/errors/skips. Current lint: zero errors, 82 warnings. The unchanged tooling suite's latest run remains 76 passing tests.
- Android `correctnessOnly` now executes `ProfilePersistenceChecks`: 320 combinations of load mask, tunnel code and avoidance switches round-trip all routing-relevant fields; 51 malformed-field controls are rejected. Additional cases cover legacy general hazmat, explicit clearing, missing fields, valid siblings, missing active ID, duplicate IDs and broken JSON.
- Android native profile/ADR and real-map ten-stop/editor suites also pass. The previous duplicate-stop and active-preference corruption checks remain green.
- Recovery tests use a dedicated preferences namespace in the companion test APK. All seven real preview preference files byte-match the new pre-test backup after the broader suites; rotation is restored and the original development package's version/update identity is unchanged. Installed preview/companion hashes match the archived APKs.

Private logs, exact source/APK hashes and preference receipts are ignored under `analysis/private/profile-persistence-2026-10-25/`. The checked-in Android test is invoked by the existing preview-only correctness instrumentation. UI review confirms the notice uses the existing scrolling native profile list; no separate visual screenshot acceptance is claimed for that notice.

Four task gates are met. Full vehicle-combination modeling, plated-mass regulations, complete ADR semantics, current municipal sources, hosted CI and Pixel/road qualification remain open roadmap work.
