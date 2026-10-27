# B1 capability matrix and profile eligibility — 26 October 2026

The [truck/ADR matrix](TRUCK-CAPABILITIES.md) classifies the requested truck configurations and fields by representation, native request, mapped-rule audit or explicit unsupported status. This package establishes that contract; it does not claim complete standard-combination, ADR or national legal support.

The former 200 t model / 100 t bridge mismatch is now an explicit, shared eligibility contract. Profiles retain their exact saved values up to the existing representation bounds. Above 100 t loaded gross mass, the list/editor and main screen explain the unsupported input before planning. Primary routes, alternatives, guidance start, interrupted-trip resume and the final native request use the same support reason. Loading or selecting such a profile stops active guidance without replacing it with a smaller truck. Permitted mass remains a separate fact.

The real foreground-service stop callback now reports the profile-support reason rather than incorrectly announcing authorization withdrawal. The mass cell is wide enough to show `200.0 t` without ellipsis. General profile notices identify missing component geometry, trailer-count enforcement, axle groups and full ADR class/quantity/exemption semantics; existing informational metadata is not presented as engine support.

## Executed evidence

Exact archival source: `03bc36ca77f167f37286fcd46f7679ee94f03402`. The independent current-source restoration contains 345 exact files and preserves the live HEAD/index.

- 215 routing tests: zero failures, errors or skips. Three added tests cover scalar maximum/equality/next-floating-point boundaries, nonfinite/nonpositive values, axle/gross contradictions and all accepted axle-count/speed/load-mask/tunnel-code domains.
- Preview app and companion APKs build; lint has zero errors and 85 warnings.
- All five native selectors pass: `correctnessOnly`, `profilesOnly`, `uiRoutingOnly`, `gpsOnly`, `interfaceOnly`.
- Native correctness checks preserve exactly `nextUp(100)` and 200 t active/named inputs, reject planning/alternatives before graph access, refuse guidance, stop an active guidance callback and recover eligibility after editing back to exactly 100 t. Permitted mass of 200 t remains unchanged.
- The GPS regression starts the real foreground service, selects the unsupported profile through the actual activity, checks the real stop callback and support reason, verifies warning visibility through its parent views and checks the complete mass label after layout. The rendered screenshot was inspected.
- Existing native profile/ADR, ordered ten-stop/editor and interface/simulator regressions remain passing.

Ignored local `analysis/private/b1-current/` contains the restored source/bundle, current SHA-named APKs, five-suite receipt/logs, byte-identical preference restoration and screenshot. Each accepted current receipt carries a fresh B1 fixture manifest for the changed test code, preserved synthetic map assets and exact source; the private driver-input binding references the unchanged A1 preservation. Earlier preliminary runs remain superseded evidence, not additional accepted features.

## Limits

Only the dedicated emulator was modified. Physical tablet and original development-package data were untouched. GPS is synthetic; no physical driving, actual 100 t route certification or complete legal/ADR oracle is claimed. Model bounds and exact JSON serialization are not proof of all native parser ranges. Informational permitted mass and trailer/fuel/emission/year metadata do not constitute additional enforcement. Required unsupported capabilities still prevent claiming full truck navigation or a release-ready build.
