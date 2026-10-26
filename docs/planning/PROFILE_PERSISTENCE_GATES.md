# Gates: named truck profile persistence

OWNS: app/src/main/java/org/eurorig/app/VehicleProfiles.java, app/src/main/java/org/eurorig/app/InterfacePages.java, app/src/androidTest/java/org/eurorig/app/ProfilePersistenceChecks.java, app/src/androidTest/java/org/eurorig/app/NativeCorrectnessChecks.java, docs/TRUCK-CAPABILITIES.md, docs/PROFILE-PERSISTENCE-2026-10-25.md, ROADMAP.md, CONTINUATION.md, docs/planning/progress/codex.json

Scope: Keep named-profile decoding from replacing malformed present values with permissive defaults, preserve the general hazardous-material flag on migration, and retain readable sibling profiles plus exact recovery input. Missing legacy fields keep documented defaults; no new vehicle-law support is implied.

- [x] P1: Named-profile round trips preserve routing-relevant dimensions, loaded/permitted mass, axle/speed values, avoidance settings and hazardous/tunnel meaning.
  EVIDENCE: ProfilePersistenceChecks passes 320 exact field combinations and the legacy broad-hazmat migration/explicit-clearing controls. Native correctnessOnly result is PASS; see docs/PROFILE-PERSISTENCE-2026-10-25.md.

- [x] P2: Malformed present routing fields and fractional integer fields are rejected rather than defaulted or truncated; missing legacy fields still migrate.
  EVIDENCE: 51 malformed numeric/boolean/null/overflow/fractional controls pass; empty JSON and older-record controls retain existing defaults. Constructor materializes absent numeric values before editor use, without replacing malformed present values.

- [x] P3: A bad named record cannot discard valid siblings; original input is retained for recovery and a missing active record is represented by the unchanged current truck with an explicit review notice.
  EVIDENCE: Isolated constructor tests preserve valid siblings/current truck and exact failed input, reject duplicate IDs, recover broken JSON and prepare legacy editor fields. InterfacePages uses existing native scrolling rows for recovery/current-profile notices; no separate visual screenshot claim is made.

- [x] P4: Normal/preview builds, lint and relevant native regressions pass; real driver preferences and original app/maps remain preserved.
  EVIDENCE: Normal/preview APK builds pass, 211 Java tests pass, lint is 0 errors/82 warnings. Tablet correctness/profile-ADR/UI-routing suites pass. Seven driver preference files byte-match backup; rotation restored; original development-package identity unchanged. Installed preview APKs match archived hashes. See private profile-persistence-2026-10-25 receipts and public verification document.
