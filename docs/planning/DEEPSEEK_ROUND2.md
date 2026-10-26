# DeepSeek round two — complete one-go assignment

Work through all eight batches below in one continuous run. This is the second preparation assignment for EuroRig. Produce useful scripts, fixtures and reviewable patches in a sandbox; Codex owns production integration, architecture, legal interpretation and acceptance. Do not ask the owner which batch to start next. Document a genuine missing input and continue independent work.

## Workspace and fixed inputs

- Live repository: `C:/Users/Administrator/.codex/worktrees/eurorig-approved-interface/eurofig`.
- Desktop package: `C:/Users/Administrator/Desktop/EuroRig-DeepSeek-Work/ROUND2/`.
- Previous run, read-only: `C:/Users/Administrator/Desktop/EuroRig-DeepSeek-Work/runs/RUN-20261025-152124-deepseek/`.
- Codex review artifacts, read-only: `C:/Users/Administrator/Desktop/EuroRig-FastModel-Work/Codex-DS03-DS12-Review-2026-10-25/` and `Codex-DS02-Review-2026-10-25/` in the same parent.
- New output root: create a unique `C:/Users/Administrator/Desktop/EuroRig-DeepSeek-Work/runs/ROUND2-<timestamp>/`. All new scripts, downloads, fixtures, patches, logs and reports belong there. Copy `ROUND2/PROGRESS_TEMPLATE.json` to its `PROGRESS.json`.
- Read applicable AGENTS/skills, ROADMAP, CONTINUATION, `docs/TRUCK-CAPABILITIES.md`, `docs/CESTRIN-CANDIDATES.md`, `docs/RESTRICTION-SOURCE-INTAKE.md`, `docs/DS02-REVIEW-2026-10-25.md` and `docs/DS03-DS12-REVIEW-2026-10-25.md`.
- `ROUND2/inputs/` supplies hashed copies of the current review/contracts and three text-only EurowagNav research references. Read `INPUTS.json` and `ARCHIVE_PROVENANCE.md`. No remote repository connection is needed for the supplied archive-analysis task.

The code anchor is `2b0e456`; later documentation commits are allowed. Capture the actual live HEAD, dirty-status list and relevant input hashes once. Make a fresh source snapshot for experiments, including any explicitly identified dirty inputs. Never reset, stash, restore or check out the live repository. Codex may continue implementation while you work; compare your own writes, not whether the whole shared checkout changed.

The only allowed live-project write is your DeepSeek `current` progress text through the existing progress CLI. Preserve all twelve round-one item states and their evidence paths. Do not edit Codex state, the shared plan, production files, existing tests, maps or dependencies. Candidate patches may modify your source snapshot only. Do not stage/commit/push the live checkout.

No device/app/account operations, private GPS/logs, reference APKs, screenshots, `analysis/private/`, signing, publication, purchases or secret/configuration inspection. Do not execute archive probe/extraction scripts. Do not obtain or convert proprietary map values. Work with original code, synthetic fixtures and permitted public captures; redistribution rights remain a separate review.

## Already completed — do not repeat or undo

- Active-truck corruption now blocks planning/guidance until review; named profiles reject malformed present fields and integer truncation, retain readable siblings and preserve legacy hazmat meaning.
- Coincident consecutive stops share native locations while retaining all user stop boundaries. Nonconsecutive returns retain real legs. Whole-route auditing and exact connectedness remain strict.
- Native menu handling and cached marker paths are verified. CI provisions compile platform 36/build-tools 36.0.0; target 37 is separate and does not imply compile platform 37 must be installed.
- Current code evidence: 211 Java tests, 84 Python tests, zero lint errors/82 warnings. Historical 205/75/76/77/85 counts belong to their snapshots. Discover and report current counts rather than copying these expectations into a fake verifier.
- A real compiler/enrichment regression now retains ADR flags/raw tags with generic kind zero. `tools/test_adr_evidence.py` passes; zero generic kind alone is not proof of missing enforcement.
- The OG43 candidate adapter and seven tests already exist. Do not reimplement that format or describe it as approved legal data.

## Run order

Run R2-01, R2-02 and R2-03 first. Then complete R2-04 through R2-08. Missing legal or hardware oracles must not block JSON/HTML provenance, compilation, structural fixtures or text work. Each batch needs its own report and exact output paths. Avoid arbitrary minimum row/test counts: cover the specified cases and report the actual sets.

### R2-01 — repair source provenance and complete row reconciliation

Inputs: previous `DS03/`, Codex's DS03/DS12 review, current `cestrin_candidates.py`, and `og43-header-bound-v1.json` / `PARSER_RESULTS.json` in the Codex review directory.

Tasks:
1. Resolve every previous source-record hash against actual captured bytes. The reviewed baseline finds zero of 7,275 `artifact_sha256` references resolving to the fourteen raw files. Produce an explicit old-reference-to-actual-artifact table; unresolved mappings stay unknown. Never replace a digest with a convenient file's hash without documenting the original URL, filename, content location and any decoding transformation.
2. Reconcile the complete one-based raw row multiset against the candidate index for each HTML source, including repeated publisher IDs, blank limits, closures and appendix-dependent statements. Preserve original row indices and actual raw cells. No silently dropped row or deduplication by dictionary key.
3. Produce corrected candidate records plus a field-level diff; keep all old inputs untouched. Gross mass must not be renamed loaded or plated mass without reviewed evidence. Retain unknown rights, effective periods, applicability and exact road matches.

Supplied structural controls: the observed OG43 UTF-8 capture has SHA-256 `f43decbb4c886f18ca94a9bda56c45b4c0981378465681f30db5ffb346c29138`. Current extraction retains 202 rows / 192 physical measurements / 68 additionally quarantined rows. Source row 10 has blank measurement cells despite numeric detour positions. Row 21 contains a 16 t gross-mass value in column 14, with mass basis unknown. Rows 133/134, publisher IDs 124/125, were absent from the old inventory and must remain visible. These are checks for this frozen capture, not completeness targets for changed downloads.

Outputs: `provenance_map.csv`, `row_reconciliation.json`, `corrected_candidates.jsonl`, `field_diff.jsonl`, `unresolved.json`, an executable validator with its negative-control results, and `REPORT.md`.

### R2-02 — candidate adapters for the three remaining captured table layouts

Inputs: original captured temporary, permanent and mass-maximum HTML files; current OG43 parser API/tests and the source-intake contract. The previous adapter-group descriptions are clues, not authoritative column maps.

Build candidate adapters in your snapshot/sandbox for these three layouts. Bind real headers, units and numbered columns before extracting values. Preserve permanent-table vehicle-class and side/lane columns verbatim; do not decide their legal meaning. Separate bypass chainage from measurements. Preserve nonnumeric conditions, missing values, source row IDs, duplicate IDs and encoding uncertainty. Fail visibly on unsupported headers; never infer a new layout from a city or road name. Keep the already working OG43 behavior unchanged.

Required cases: empty limits with numeric bypasses; changed/reordered unit headers; merged/row-spanned cells; comma decimals; invalid numeric text; missing and reversed chainage; multiple values/classes per source row; blank closure values; exact input hashes. Unknown gross-mass basis remains unknown. Output always candidate-only with import approval false. Refuse existing output filenames.

Run against all three real captures and original synthetic fixtures. Use standard parsers; do not add a dependency without need. Produce a focused candidate patch, tests, output summaries, raw-to-field column maps and `REPORT.md`. Do not copy unlicensed raw tables into a production test patch; use small original synthetic fixtures.

### R2-03 — national source/endpoints and reuse evidence, Moldova first

Inputs: previous source/feed registers and the factual gaps in Codex review.

Find central/national bulk source and road-linear-reference leads for Moldova first, then fill Romanian provenance gaps. This is not a city-by-city law survey. Limit to twenty useful source/endpoints and fifty public HTTP requests; log inaccessible results and continue other batches. Reuse existing captures before downloading. Do not download map-country blobs or archives larger than 10 MiB in this batch.

For each useful endpoint capture actual request URL/parameters, original/final URL, UTC fetch time/status, response headers, original bytes and SHA-256. Record declared charset and any derived decoded artifact with both hashes and the exact transform. For JSON/ArcGIS/Koop leads, capture the real service URL, CRS, pagination/count metadata and a bounded sample; a detached JSON body is not a verified endpoint. Do not claim completeness if a page cap is unexercised.

Locate explicit publisher reuse/licence statements and dated applicability/update statements. Quote only a short decisive excerpt with exact source location and capture hash. Public accessibility is not a reuse licence; a document month is not automatically its effective period. Missing facts remain unknown; do not produce legal conclusions, contact publishers or invent licence terms. For road-reference leads record identifiers, chainage fields, carriageway/direction semantics and available geometry without approving joins.

Outputs: `sources.jsonl`, `requests.jsonl`, `artifact_index.json`, `rights_evidence.jsonl`, `linear_reference_leads.csv`, preserved permitted captures and `REPORT.md`. An unknown-rights record is a useful discovery result, not an approved source.

### R2-04 — missing importer refusal fixtures

Inputs: current `RegionPackages.java`, `RegionImportTest`/package tests, `tools/package_region.py`, and previous DS04. Read the actual importer before choosing cases.

The previous run tested the packager. Prepare original bounded malformed ZIP/TAR fixtures and test candidates that reach importer-side refusal: duplicate ZIP entry; unexpected extra entry; traversal/absolute path; missing manifest/payload; byte/hash mismatch; truncation; unsupported schema/version; stale partial installation versus intact inactive country retention. Map each case to the exact existing importer guard. Do not demand an exact diagnostic string that the guard does not promise.

Independent invariants: rejection cannot change the active region selection or destroy an existing valid region; an inactive complete region is retained; partial input never becomes selected. Generate duplicates with a ZIP writer that actually allows duplicate entries, not the packager's stricter API. Use existing small synthetic assets; no live country maps or device data. Readability/stream failures must remain distinguishable from intended rejection.

Outputs: fixture generator, a case-to-guard/oracle table, test-only patch and build results. Execute runnable JVM/tooling cases. Compile Android instrumentation candidates using the existing toolchain; mark device execution unrun. Do not use ADB or claim an Android importer ran because a host validator passed. Record a real reproduced defect as a failing candidate for Codex rather than changing production behavior.

### R2-05 — mechanical RO/EN resource wiring candidate

Inputs: previous DS06 key/source map, English/Romanian drafts and placeholder contracts; current app source/resources. Work from the new snapshot, not the old line numbers.

Prepare a focused candidate patch wiring exact unchanged UI literals to resources in `JourneyUi` and other directly supported Android UI call sites from that map. Preserve English text, punctuation, line breaks, formatting arguments and accessibility wording. Reuse existing keys; avoid duplicate resources. Romanian remains a draft requiring lead review, especially safety wording.

Resolve each old source reference against current exact text. Changed/absent literals go into an unresolved list; do not fuzzily replace a similar sentence. Context-free routing/model classes require a separate integration decision: record their mappings, but do not introduce Android dependencies or new context plumbing just to translate them. This batch is not a UI redesign or new feature.

Validate XML and the complete placeholder contract: argument indices, types, precision, escaped percent signs and intentional newline escaping. Run real resource compilation/linking or normal app/test builds in the sandbox using the existing toolchain; compile-only output is not an installed Romanian UI or speech pass. Negative controls must catch changed precision, missing/reordered argument indices and escaped-newline corruption. Outputs: candidate patch, final key/source map, unresolved/safety list, build logs and `REPORT.md`.

### R2-06 — structural trip/profile/recovery test gaps

Inputs: current route/profile tests, `NativeRouter`, `JourneyPlan`, `Progress`, `LocationFixes`, recovery/service code and the owner requirements below. First list which cases already have direct tests; add only gaps, not mirrored implementations or duplicate tests.

Supplied independent owner invariants:
- Ten intermediate stops maximum; eleven are rejected. Chosen order is preserved; drag reorders only when the driver acts. Cancelled editing preserves the trip.
- Exact consecutive duplicate locations retain all stop identities/boundaries; nonconsecutive returns retain actual travel. A wholly stationary trip is refused. Nearby different coordinates must not be silently merged as duplicates.
- No disconnected leg or missing edge-audit coverage can become an accepted straight segment. A preference, reroute or alternative cannot waive a hard restriction.
- Loaded and permitted mass remain independent; the native bridge refuses above 100 t without clamping. Do not interpret model maxima as legal limits.
- After process death, the trip may be restored but guidance requires explicit resume after fresh GPS/profile/authorization checks. Missing or malformed profile data cannot silently authorize travel using easier limits.
- GPS freshness/accuracy thresholds must be taken from the applicable call-site contract; capture the exact threshold and evaluate equality and immediately outside it. Do not conflate different 10 s/15 s consumers.

Prepare compact JVM or Android test candidates and synthetic replay inputs with explicit profiles/switches. For new synthetic scenarios you may choose fully specified switches; label these as test inputs, not the owner's preferences or a real comparison oracle. Run JVM tests, compile Android candidates and mark hardware execution unrun. If current code violates an invariant, retain the failing regression and minimal reproduction separately; do not weaken the oracle. No legal route/detour expectation is supplied, so do not invent one. Outputs: gap matrix, test-only patch, replay JSON and `REPORT.md`.

### R2-07 — supplied archive requirements versus current implementation

Inputs: the three text references under `ROUND2/inputs/archive/`, their provenance index, current source and the Codex reuse/ADR reviews. This task is ready even if GitHub is inaccessible.

Build a requirement-to-current-source/test matrix for RR identifiers and actionable behavior questions. Distinguish historical claims, static symbols, measured historical results, current source and currently executed tests. Do not count symbol names as deployed behavior, or old measurements as fresh passes. Correct the historical whole-audit-blindness claim using the current retention regression. Reuse existing destination authorization and the repaired stop/profile behavior; do not propose them as absent features.

Prepare candidate reproduction inputs for genuinely unresolved conflict-combination, direction, uncertainty, maneuver timing and conditional-time questions. State which expected outcomes follow supplied software invariants and which require a later legal/reference oracle. Source references need exact file, line and snippet in your frozen snapshot. Preserve `Truck.length` accesses rather than filtering every dotted `length` token as array noise. Do not extract commercial map data or execute archive scripts.

Outputs: `requirements_trace.csv`, `behavior_questions.jsonl`, `superseded_claims.csv`, bounded reproduction input files and `REPORT.md`.

### R2-08 — semantic adversarial validation and one final handoff

Run after the other batches; keep every unfinished/blocked case visible. Validate semantics, not only counts or syntax. Inspect every supplied command/script before executing it. All checks operate on your sandbox/output bundle.

Required negative controls, with known positive controls first: syntactically valid hash pointing to the wrong source artifact; correctly sized hash with wrong raw-row reference; numeric detour chainage substituted for a measurement; a dropped source row despite unchanged self-reported count; changed unit or loaded/plated basis; duplicate source ID hidden by dictionary conversion; absent placeholder precision/index; changed stop order; falsely labelled executed Android test. A changed legitimate artifact is a new capture, not automatically malicious: bind validation to the stated frozen input and record new provenance rather than pretending an old hash covers it.

Do not write validators that merely compare a generated count to the same generator's report. Independently re-read actual files/cells/rows and executed result XML/logs. Report exact input/output multisets and hashes; distinguish a quarantined unresolved record from an approved rule. For semantic controls, specify what must be rejected, what must be preserved and which real parser/tool made that decision.

Write `FINAL_REPORT.md`, `verification.json`, `PROGRESS.json`, `PATCHES.md` and `manifest.sha256`. Manifest excludes itself and hashes actual bytes; reconcile its full relative-path set against delivered files, without duplicate or escaping paths. Include per-patch base commit, owned paths, compile/test outcome, known failing regressions and whether ready for Codex review. No patch is auto-approved. Hash final reports/progress only after their final write; reverify after any subsequent edit.

## Execution rules and progress

Use the existing tools, not a toolchain installer. Windows build details are in CONTINUATION; JDK 21 and SDK 36/build-tools 36.0.0 are already available. Tooling Python: `<repo>/.toolchain/qa-python/Scripts/python.exe`. Build app/test candidates with the existing Gradle wrapper from the sandbox snapshot and available caches. Never run data-clearing smoke scripts, ADB, signing or publication. Keep dependency/runtime caches separate from delivered source snapshots; do not copy private files into them.

For each experiment record command, working directory, input hashes, start/end UTC, exit code and bounded output/result XML. Classify environment failures separately. Allow two repair attempts for a failing experiment, then retain the failure and continue another batch. Do not alter an independent expected outcome to obtain green. No redacted/unavailable evidence may be called independently verified.

Update your run's `PROGRESS.json` at task start and each meaningful milestone. States: `pending`, `in_progress`, `submitted`, `blocked`. Each submitted/blocked item needs evidence; a blocker needs its exact missing prerequisite and what still ran. The round-two percentage is submitted batches / 8, not engineering effort or product completion.

Mirror the actual run counters into the existing live card using only its current-text update:

```powershell
& '<repo>/.toolchain/qa-python/Scripts/python.exe' '<repo>/tools/roadmap_progress.py' update --lane deepseek --current 'Round 2: submitted X/8 (Y%); blocked Z; R2-01 — current activity; evidence: absolute/run/path/PROGRESS.json'
```

Substitute real paths/counters; do not submit `X/Y/Z` literally. Do not pass `--item` or change the twelve historical DS01–DS12 statuses. Their displayed 8/12 round-one submission count stays historical; round two is explicitly named in current text and its separate progress file. The accepted product percentage stays Codex-owned. If shared filesystem sync fails, retain updates locally and report the failed sync; never claim live progress updated.

Finish with one short message giving the absolute final report path, actual submitted/blocked counts, executed tool results, candidate patch locations and concrete remaining blockers. No intermediate owner approvals or requests to choose the next batch are needed. Production integration and legal decisions remain with Codex.
