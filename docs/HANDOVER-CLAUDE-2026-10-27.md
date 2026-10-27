# EuroRig whole-project handover — 27 October 2026

## Start here

EuroRig is an offline truck-navigation project with an approved native MapLibre interface. The immediate geographic scope is Romania and Moldova. The app is a development build, not a road-validated or release-ready navigator. [ROADMAP.md](../ROADMAP.md) defines all 37 packages, their dependencies and acceptance conditions; [the progress ledger](planning/progress/codex.json) is the current package-state record. Historical reports describe the specific snapshots on which they ran.

Start with B4, the earliest open safety package. Focus on one package at a time. The owner permits moving on after 40 minutes without significant progress; record the obstacle and return when it can be resolved. Dependencies remain acceptance requirements even when implementation proceeds out of order.

## Status of the entire roadmap

| Status | Packages |
| --- | --- |
| Accepted | A1–A4, B1–B3: **7/37 (18.9%)**. |
| Partial | B4–B6, C1, C2, C4, D1–D6, E1–E7, F1, F3, F5, G1, G2, G4. |
| Blocked | C3, F2, F4. |
| Pending | G3, G5. |

The previous ledger counted 13 packages as complete. C1, C2, C4, D5, D6 and E1 have useful passing technical evidence, but their upstream prerequisites remain open. Their evidence is retained and their status is partial; this is not a reversal of the test results. Equal-weight package counts measure acceptance, not work completed or time remaining. DeepSeek's eight submitted round-two batches are a separate worker count, not accepted product packages.

## The former 14-stage assignment

| Stage | Handover state and next acceptance work |
| --- | --- |
| C2 | Import, update, rollback, malformed-package and retention checks have passed. C1/B5 prerequisites remain open. Rebind final evidence to the accepted map and source. |
| C3 | Search and disclosure checks pass, but the actual terminal truck entrance and current permit conditions lack current authoritative confirmation. Refresh the operator/source evidence; the last check was 26 October. Keep the entrance unverified until then. |
| C4 | Package, hash, storage and manual-import work exists. C1/B5 and publication/licensing prerequisites remain open; no RO–MD candidate was published. |
| D6 | Romanian/English resources, offline speech and notification checks have technical evidence. D3/D5 prerequisites remain open. Do not infer audible speech from a TTS callback alone. |
| E1 | Five local gates passed for editor behavior, 0/1/5/10 stops, duplicate boundaries and process-death reroute. B4/D2/C3 remain open. Reorder/lifecycle fixtures used co-located stops; prove broader distinct-stop reorder cases. A measured 14.49 m discontinuity remains a refusal, with no invented connector. |
| E5 | Timestamp, validation, cache and unavailable-state infrastructure exists. The Dutch test feed does not prove live RO–MD occupancy; obtain licensed covered sites or retain unavailable status. |
| F1 | Four public-coordinate scenarios have EuroRig results. Complete the roadmap's 60 matched scenarios with identical profiles, maps and assumptions. |
| F2 | The authorized reference app contains a private route and its test profile is not matched. Establish an isolated QA context or a verified way to restore its state before comparing. Reference output is not a legal oracle. |
| F3 | Partial Tab S9 matrix exists, including permissions, offline routing, language and journey checks. Complete the full matrix against a bound candidate and map. |
| F4 | Physical Pixel 8 Pro qualification remains blocked until that device is available. A tablet or emulator cannot substitute. |
| F5 | Synthetic replay, reroute and short render samples exist. Complete the required soak, investigate failures and repeat on the Pixel when available. No public-road validation has been performed. |
| G1 | Upgrade-retention and private-candidate preparation exist. Complete release prerequisites, installation, recovery and signing evidence before acceptance. |
| G2 | Maintenance documentation and tooling exist. Finish repeatable source/dependency expiry review and bound regression execution. |
| G4 | Distribution, licensing and support documentation exists. Complete compatibility, security, publication and upstream acceptance gates before any release claim. |

## Latest E2 work

Two-point routing keeps the primary and makes at most two additional exclusion attempts. Candidates use the same truck, mode and delivery inputs and full route audit; duplicate ordered road sequences are removed. Each route tab presents duration, distance and recorded restriction status in English and Romanian. NativeRouter now refuses any edge classified as prohibited for the selected truck, including zero-length edges. An independent review found and confirmed the correction of that fail-closed case.

The local E2 ledger has four passing gates: a focused candidate-policy test and Preview builds, Tab S9 two-point and multi-stop checks, Tab S9 selection/accessibility checks, and independent safety review. The last device test checks each offered edge's recorded audit verdict and effective classification. A native fixture proving the single-lawful-route case still needs an independent oracle; the policy-only empty-candidate control is not that proof. B4 and C1 also remain open. E3, ordered multi-stop alternatives, is separate and has not been accepted.

The latest E1 local recheck passed all five gates after the E2 change. D5 and D6 had previously passed their recorded local checks; the final NativeRouter guard changed afterward. No new full emulator D5/D6 rerun is claimed. Exact local command outputs, hashes, device details and the E1/E2 ledgers stay under ignored `.unlazy/` and `analysis/private/` on the original machine; those local receipts are not included in the public repository.

## Safety and data limits

- Native routing refuses truck weights above its 100 t bridge limit; the profile model can represent more. Trailer/axle-group and complete ADR consignment semantics remain incomplete.
- Restriction ingestion and quarantine tooling exist, but rights, currentness, exact joins and municipal coverage remain unresolved. Conditional rules, timezones and unknown applicability need B6 work. No missing restriction data implies proven clearance.
- Coherent RO–MD border candidates were exercised technically; that does not establish legal completeness or distribution rights.
- Static RO–MD parking coverage, occupancy, tolls and traffic remain incomplete or unavailable. Preserve unknown and stale states.
- The available tablet and synthetic replay evidence are not physical Pixel, long-soak, driving, or Europe-wide qualification.

## Repositories, evidence and integration

The canonical application source is [EuroRig](https://github.com/grotefigi/EuroRig). The private EurowagNav reference repository contains screenshots, device extracts and research material and must remain private. Do not import its proprietary or personal contents into the public source repository. Original private maps, APKs, device/settings snapshots, research deliverables and large build archives remain on the original machine; [project locations](planning/PROJECT_LOCATIONS.md) records their historical local organization.

Open [PR #1](https://github.com/grotefigi/EuroRig/pull/1) and its branch contain distinct country-set, map-cache, queue and guidance work not present in the approved-interface line. Preserve the PR and review its changes feature by feature before integration; it is not superseded. Unverified map-matching and turn experiments from the older worktree are preserved separately in the `archive/early-routing-20261027` branch, not accepted production behavior. The active local history and pre-redaction progress were backed up privately for recovery.

Public `main` is a clean source-tree snapshot based on the previous public main. It does not contain every historical local commit or ignored receipt referenced in older documents. The verified local history bundle is `analysis/private/claude-handover-20261027/final-local-history.bundle` in the original approved-interface worktree. Treat that bundle and its surrounding receipts as private.

The CESTRIN parser test headers in this checkout are synthetic and contain no publisher records. Source-format matching, rights, applicability and real imports still require independent evidence. Historical HyperResearch reports remain local research leads; their licence and feature claims need current primary-source verification before reuse.

## Verification and handover discipline

Use the project Preview build recipe and inspect device identity before installation. Never clear application data on a physical device. Preserve the owner's apps, settings, maps and private evidence. `tools/run_e1_lifecycle.ps1` and `tools/drive_oneway.py` require an explicitly selected `ANDROID_SERIAL`.

The E2 build and focused routing tests passed with the isolated Preview configuration; E1 and E2 device gates passed on the available Tab S9. The full tooling suite passed 150 tests with no skips, including 24 CESTRIN synthetic-header tests. These results do not certify unrelated historical branches, the whole roadmap, or a future build. Re-run relevant checks after changes and bind acceptance to the actual source, APK, map, device and profile.
