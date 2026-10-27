# B2 ADR detour verification — 26 October 2026

The historical category-C fixture now passes with an independently specified corridor, endpoint changes and positive/negative controls. No production routing restriction was relaxed in this package. The earlier preference-costing fix remains in place; this work strengthens its regression evidence.

## Independent oracle

The original authored `tools/fixtures/profiles.osm` joins connectors `20000011` and `20000012` through two parallel ways: direct tunnel `20000013` has category C; longer bypass `20000014` has category B. The fixture's category-C load must use `[11,14,12]`, while ordinary cargo uses `[11,13,12]` (IDs abbreviated here). Reverse travel reverses those sequences. Shifting endpoints along the connectors preserves the corridor. A separate read-only source review derived these expectations before routing output was inspected.

At `(45.12, 27.008)`, the direct tunnel's midpoint, the nearest permitted connectors are about 314 m away and the B bypass is about 334 m north. No permitted origin relocation exists within the app's 250 m limit. An ordinary truck can start there; the category-C profile must be refused. A category-B load has neither tunnel available and must also be refused. Delivery selection cannot waive either restriction.

The checked-in native/display fixtures are content-hashed. Their way identities and B/C tags agree with the source oracle and actual audited route output. A byte-for-byte native-tile regeneration recipe is absent; the display metadata names `profiles.osm.pbf`. This package verifies the frozen fixture's behavior, not a claimed regeneration from the textual OSM file.

## Executed cases

Exact archival source: `deb197f9fe28c1f8d72e094da56b49fe9b4c2324`. The Preview and companion APKs build, and the expanded native `profilesOnly` suite passes.

All four modes—Shortest, Easiest, Economical and Fastest—run with delivery both off and on. Each combination checks nine cases:

1. Ordinary cargo takes the direct corridor.
2. ADR-C takes the specified B bypass.
3. Reverse ADR-C travel takes the reversed bypass sequence.
4. Shifted connector endpoints retain the bypass.
5. A 10 km/h ADR-C profile still finds the only permitted corridor.
6. The category-B profile is refused because both tunnels are prohibited.
7. Ordinary cargo can start in the C tunnel, establishing coverage for the negative origin control.
8. ADR-C is refused at the prohibited midpoint outside relocation range.
9. Returning to ordinary cargo restores the direct corridor; exclusions do not leak between requests.

Measured total: **72 cases, 56 accepted audited routes, 16 refusals**. The slower profile's native traversal estimate is 640.366 s versus 185.969 s for the 80 km/h profile, in every mode/delivery combination. This is a vehicle-wide traversal-time stress case, not a selectively repriced bypass fixture or a real journey ETA. Every accepted result has nonzero native geometry/time and the exact expected compressed way sequence. Three altered-report controls—omitted case, prohibited corridor and reversed corridor—are rejected by the parent verifier.

## Evidence and limits

Ignored local `analysis/private/b2-current/` retains the exact source bundle, immutable APKs, current fixture/input manifest, native suite log and restored preferences. After the passing suite, `adr-detour-report.json` was collected from Preview's `files/profile-qa/` on the dedicated emulator. `adr-cases-binding.json` binds that report's SHA-256 to the execution receipt, source and oracle file. This is explicit post-run collection, not a claim that the controller collected it before completion.

This closes the reproduced synthetic ADR detour failure and its tested software corridor. Full consignment/ADR semantics remain explicitly unsupported where the B1 matrix says so. No current public-road legality, country-wide tunnel coverage, physical driving or road-readiness claim follows from these checks. B3 continues with imported/native/audited hard rules.
