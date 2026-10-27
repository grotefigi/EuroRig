# B3 — imported turn evidence and complete-path audit

Verified 26 October 2026 against archival source `3ae570f1ec64bbc407b22c2e849deaaff20e51a8`. The live branch and main Git index are preserved. Private receipts are under `analysis/private/b3-current/run-20261026-130311`; the source bundle, immutable APKs and fixture bindings are adjacent. This is software qualification for the supported capability matrix, not proof of public-road legality or complete truck/trailer/ADR support.

## Changes

`tools/turn_restrictions.py` adds relation evidence to display enrichment without changing the existing scalar-rule version or legacy pack format. It retains relation IDs, source tags/members, ordered from/via/to ways, junction coordinates, vehicle exceptions and unsupported reasons. A separate version-1 turn schema and membership index identify coverage. Streaming passes collect only referenced way topology and required junction coordinates. Conflicting source identities fail the build. Ambiguous, missing, mixed, repeated-node and oversized topology remains explicitly unknown, with its original evidence retained.

The independent `TurnRestriction` audit checks node and ordered single/multiple-via no/only rules against directed native trace boundaries. It retains history through native edge splits and stop-leg joins. Only rules reject an incorrect entry, early departure or incorrect final exit. No rules require the complete recorded chain. Reverse and other-entry controls remain distinct. Duplicate boundary vertices cannot hide a same-way U-turn. Known prohibitions have no delivery, private-access or preference waiver.

`DisplayDatabase` queries related turns by touched ways. Unsupported relations retain all touched membership across query batches, independently of the bounded diagnostic chain. `NativeRouter` audits the joined trip before accepting it; alternatives use the same planner. Unknown applicability produces warnings and unverified sections under the existing RR-04 policy. Legacy maps explicitly report native-only turn checking. Unknown turn schema versions are refused.

Native trace records can span several geometry segments. Audit paint now expands each verdict over its complete shape interval. Arrival certainty derives from the final traveled interval, so an empty trailing record cannot turn an unverified approach into a verified one.

## Evidence

- 230 routing JVM tests: no failures, errors or skips, including 15 independent turn-audit tests.
- 140 Python tooling tests: no failures, errors or skips, including importer hierarchy, exceptions, topology, raw retention and conservative legacy-pack checks.
- Preview app and instrumentation APKs assemble with the genuine API-36 toolchain. Lint: zero errors, 85 warnings.
- Four bound emulator suites pass: `turnsOnly`, `profilesOnly`, `correctnessOnly`, `uiRoutingOnly`. Five preserved input preference files are restored. Physical-device commands were not issued.
- The authored fixture has five relations and 22 ways. XML/PBF parity, pinned pyvalhalla 3.6.3/osmium 4.3.1 build inputs, native TAR and imported database are independently checked. `tools/build_turn_fixture.py` rebuilds it into a fresh output directory.
- Sixteen source-derived routes across four modes and both delivery states produce 128 exact expected way sequences. Eight additional joined-stop routes all find the lawful detour. Alternative candidates receive the same complete-path audit; the small network does not promise three choices.
- Forced prohibited native-shaped traces prove that importer evidence rejects via no/only violations across a stop split and ignores travel-free boundary records. Additional controls cover unsupported applicability/paint, unknown schema refusal, legacy warnings and unknown membership in a later 400-ID query batch.
- The existing 72-case ADR corridor report is recollected from the same native run. Existing dimensions, mass, axle, access, profile eligibility and editor/stop regressions pass.
- Three corrupt turn reports are rejected: missing result, prohibited way sequence and duplicate case. Acceptance gates bind actual source, APK, fixture, report and execution hashes.

## Failed attempts retained

The original worker fixture was reproducible but its exclusive forward expectation overlooked a legal dead-end turnaround on an alternate-entry branch. Native output `[301,305,302,303]` did not traverse the forbidden contiguous chain. That failed assertion and original artifacts are retained. Revision 2 makes alternate-entry branches 305/405/506 one-way toward their junctions, removing that source-oracle ambiguity. Production restrictions were not relaxed to obtain a pass.

An initial tooling test left a SQLite connection open on Windows; it now closes it explicitly. An instrumentation compilation attempt used unavailable Android `Files.readString`/`writeString`; it now uses supported byte APIs. Secondary route reconstruction originally treated repeated zero-length shape segments as travel; its independent check now skips those segments. Logs remain private. Independent review also caught and resolved hairpin history loss, duplicate-boundary U-turns, repeated-node topology and uncertainty propagation defects before acceptance.

## Limits

The independent matcher uses junction geometry with a 3 m tolerance. Coincident/overlapping geometry is not a complete directed OSM-node-position model. Ambiguous topology is quarantined; geometric identity is not evidence of a physical road sign.

After a known turn violation, bounded replanning can exclude its outgoing native edge. That exclusion can over-refuse a lawful approach using the same road from another direction. A refused search is not proof that no lawful route exists. The tested joined-stop cases find lawful detours, but the search is not exhaustive.

Conditional schedules, full vehicle/trailer qualifiers, national combination legality, axle groups and complete ADR consignments remain outside the supported matrix. Unknown evidence is visible; it is not claimed as clearance. Legacy custom graphs still exclude unsupported via-way member roads conservatively rather than receiving a new pack format. Existing country packs need rebuilding before they gain independent relation coverage; C1 owns that work.

Semantics follow the primary [OSM restriction relation documentation](https://wiki.openstreetmap.org/wiki/Relation:restriction). Native location/leg behavior is documented in the [Valhalla route API](https://valhalla.github.io/valhalla/api/route/api-reference/). These sources define data/API behavior, not applicable road law.
