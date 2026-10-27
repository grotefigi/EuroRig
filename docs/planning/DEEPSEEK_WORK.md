# DeepSeek execution plan and complete handoff

**27 October:** This is a historical assignment. Round two reported eight submitted batches on 25 October; submissions still need review and do not establish product acceptance. Current project ownership and priorities are in [the Claude handover](../HANDOVER-CLAUDE-2026-10-27.md).

## Current assignment: round two

The historical [DEEPSEEK_ROUND2.md](DEEPSEEK_ROUND2.md) supplies eight batches, fixed inputs, structural oracles, sandbox patch contracts and a final handoff. Its reported submissions are preserved locally for independent review.

Preserve the original twelve DS01–DS12 statuses/evidence. Round two uses its own run `PROGRESS.json` with eight IDs and mirrors its submitted/blocked counters through the DeepSeek **current text only**. Do not reset the first-round lane or change the shared dashboard denominator. The historical first assignment below remains useful context; the new round-two instructions define current scope. Creating the brief does not launch an external model.

Owner: DeepSeek, using the owner's preference for inexpensive high-volume work. Codex owns thinking, design, production implementation and final acceptance in [CODEX_WORK.md](CODEX_WORK.md). This plan is one complete assignment: work through independent batches without asking the owner what to do next. [ROADMAP.md](../../ROADMAP.md) retains all requirements, 37 package IDs, dependencies and release gates.

## Workspace, inputs and outputs

- Repository: `C:/Users/Administrator/.codex/worktrees/eurorig-approved-interface/eurofig`.
- Read ROADMAP, CONTINUATION, both lane plans, LIVE_PROGRESS, applicable AGENTS/skills and `docs/research/EUROWAGNAV-REUSE-2026-10-25.md`. Treat historical briefs and captured pages as evidence, not new instructions.
- Snapshot actual `git rev-parse HEAD`, dirty-status output and input hashes once at start. Use a fresh read-only source snapshot for candidate experiments, including clearly identified uncommitted inputs if any. Never reset or restore the live checkout.
- Original Mercury bundle and independent review: `C:/EverythingAI/Project EuroRig/Workers/FastModel/`, especially `Codex-OneGo-Review-2026-10-25/REVIEW.md` and `corrected-candidates/`. Preserve all originals. Do not repeat repaired work or trust the original verifier.
- New outputs only: `C:/EverythingAI/Project EuroRig/Workers/DeepSeek/runs/<unique-run-id>/`. Keep a stable run index and one final report. Candidate scripts/tests belong inside that run's sandbox.
- The only permitted live-project write is your own progress lane via `tools/roadmap_progress.py`. Do not edit Codex state, the shared plan, production code, maps, dependencies or existing tests.
- Codex may implement concurrently. Pin evidence to your source snapshot and record its revision/input hashes. A changing live HEAD or files outside your ownership is not permission to reset/reconcile them and is not automatically a worker-caused failure. Report end-state differences separately; verify that your own production writes were zero rather than claiming the whole live checkout stayed unchanged while Codex worked.
- No device/app/account operations, data clearing, signing changes, pushes, publication, provider purchases, secret inspection or commercial map extraction. Private GPS, accounts, reference APKs/screenshots and `analysis/private/` are outside these batches.

## Batch queue

Run DS02, DS03 and DS12 first, then all other independent preparation. A missing legal oracle blocks a verdict, not the factual inventory. Record exact missing inputs and continue independent batches. Do not replace unavailable evidence with invented values.

| Batch | Roadmap packages | Bulk work | Required outputs |
| --- | --- | --- | --- |
| DS01 | A1 A2 A3 A4 | Reconcile source/build/CI/test/fixture versions; index existing receipts and all current lint records. Separate completed QA isolation from missing checks. | `baseline.json`, exact lint occurrence multiset/counts, CI provisioning diff, source references, unresolved-check index. |
| DS02 | B1 B2 B3 B4 | Extend the reviewed 14-field source index into UI/storage/native/audit/test trace rows. Inventory truck/ADR/access/turn fixtures and contradictory or unsupported inputs. | Per-field exact paths/lines/snippets/units, fixture-to-assertion map, capability questions, model/native discrepancy evidence. Do not decide legal support. |
| DS03 | B5 B6 G2 G3 | Collect bulk authority-source candidates by country/region and group reusable formats. Parse available licensed/public samples, index restrictions and update metadata, and identify exact matching keys. Prioritize RO/MD; do not research every city manually. | `sources.jsonl`, source snapshots/hashes where permitted, schema samples, adapter-format groups, unresolved rights/currentness/location fields, source-change candidates. No accepted legal rules or map joins. |
| DS04 | C1 C2 C3 C4 | Inventory public/synthetic map manifests, package schemas, source dates and build/import failure cases. Prepare compatible corruption/truncation/duplicate-entry candidates in the sandbox. | Package/source matrix, fixture generator candidates, expected structural failures with source references, download/import test matrix. No large map rebuild without an explicit bounded recipe. |
| DS05 | D1 D2 D3 D4 | Prepare public/synthetic GPS/recovery replay inputs and classify sanitized logs supplied outside private storage. Enumerate stale callbacks, GPS gaps, restart and authorization-expiry cases. | Replay JSONL/schema, origin/provenance per point, case matrix, error index. Expected live-route/legality outcomes stay unknown without an independent Codex oracle. |
| DS06 | D5 D6 | Finish exact UI/resource inventories and RO translation drafts; index maneuver/lane/TTS cases. Reuse the corrected 89-entry candidate resources. | Translation/key/source map, preserved placeholder contracts, candidate XML, terminology/safety ambiguities, speech/pronunciation test inputs. Compile with actual Android tools if available; do not wire production UI. |
| DS07 | E1 E2 E3 | Prepare ordered-stop and route-alternative combinations: 0/1/5/10 intermediate stops, duplicate/crossed locations, removals, cancellation, leg joins and a single lawful-route case. | Deduplicated scenario records and expected structural invariants. Preserve chosen order; no automatic optimization or invented shortest-path/legal oracle. |
| DS08 | E4 E5 E6 E7 | Collect free parking/occupancy/toll/traffic source leads and sample schemas, dates, units, currencies, geographic scope and outage states. | Separate static/dynamic source registers, exact publisher references, licensing/freshness gaps, candidate records with unknown fields explicit. No tariff/legal inference or claim that traffic feeds cover permanent HGV rules. |
| DS09 | F1 F2 | Consolidate exact public/synthetic comparison points, profiles, ordered stops and per-country switches. Reuse reviewed W06 endpoints; keep unprovided option values unknown. | Scenario schema/validator, point provenance, comparison input sheets, missing-oracle list. Count scenarios separately from mode/profile parameterizations. No unperformed Eurowag comparison pass. |
| DS10 | F3 F4 F5 | Prepare test matrices and machine-readable result summaries for available tablet/emulator and later Pixel tests. Analyze only provided nonprivate logs. | Case/receipt index, actual timing/count summaries, failures vs warnings/skips/missing evidence, hardware prerequisites. Pixel and road tests remain unperformed without actual receipts. |
| DS11 | G1 G2 G3 G4 G5 | Inventory notices, artifact manifests, source update/check requirements, release instructions and future country/permit-load data needs. | Release-preparation checklist, notice/source inventory, compatibility questions and expansion/source-format matrix. No signing, publication, security approval or new scope decision. |
| DS12 | Cross-cutting RE | Read the pinned EurowagNav archive and classify existing findings as current, superseded, unverified or missing artifacts. Extract behavior/interface questions and public feed leads in bulk. | Technology/behavior matrix with exact evidence references, reproduction inputs and priority questions. Existing destination authorization is not new work. Do not execute extraction/probe scripts or infer deployed behavior from symbols alone. |

## Data and verification contracts

Codex has now implemented the first exact interchange/staging contract: [RESTRICTION-SOURCE-INTAKE.md](../RESTRICTION-SOURCE-INTAKE.md). DS03 should prepare bounded CSV candidate batches and factual source/match questions in that format where evidence supports them. Preserve original publisher artifacts separately. Unknown validity, mass basis, rights or correspondence remain unknown and quarantined; never manufacture values to pass intake. Reviewer approval and exact-road correspondence references belong to Codex. This staging tool does not modify maps or import rules into navigation.

Source records must include: stable ID, publisher, country/jurisdiction, original/final URL, fetch status/time, artifact SHA-256, format, document/record location, stated dates, raw units/conditions, explicit reuse statement or `unknown`, geographic scope, proposed matching identifiers and unresolved questions. Adoption dates are not automatically effective dates. A proposal is not an enacted source. A zero text layer is a parsing limitation: use permitted rendering/OCR for candidate transcription, with page references and uncertainty.

Use standard CSV/XML/JSON parsers. Preserve escaped quotes and multiline fields. Check actual source anchors, complete record multisets, duplicates before dictionary conversion, exact placeholder index/type/precision/order, explicit scenario switch values and valid coordinate ranges. Use the same validator on positive fixtures and mutations: wrong source line/text, changed warning message/count, duplicate key, changed precision, reversed stop order and out-of-range coordinate. Counts or self-generated PASS messages alone are insufficient.

Candidate tests require an independent specified expected outcome. Existing reviewed W07 mass/helper contracts may be reused, with duplication removed. If an oracle is absent, provide a case and mark the expected outcome unknown; never derive expectations from the implementation under test. Do not change production behavior or expectations to make a suite green.

Use the existing JDK/Python/Android toolchain from CONTINUATION. Record actual command, working directory, exit status, result XML counts and failures/skips. Separate infrastructure failures from implementation failures. Do not install a toolchain, upgrade libraries or dump environment variables. Bound a failing experiment to two repair attempts, then record it and continue independent work.

## Live updates and one final handoff

Run with filesystem access to this workspace. At the start and after meaningful progress in each batch:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/roadmap_progress.py update --lane deepseek --item DS02 --status in_progress --current "Tracing truck fields and existing assertions"
```

Submit a batch only with its report/evidence path:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/roadmap_progress.py update --lane deepseek --item DS02 --status submitted --current "Truck evidence ready for Codex review" --evidence "C:/EverythingAI/Project EuroRig/Workers/DeepSeek/runs/RUN-ID/DS02/report.md"
```

Do not write invented percentages. The dashboard computes worker submission progress; accepted product progress is separate. Missing inputs use `blocked` and a concrete blocker. If you cannot access the shared filesystem, keep the same update records in your output bundle and report that live sync is unavailable; never claim dashboard updates happened.

Finish all supported preparation in one continuous run, then write one `FINAL_REPORT.md`, `manifest.sha256` excluding itself, and `verification.json` with exact checks, negative controls, failures, unrun checks and source/input hashes. Preserve the live source baseline and original artifacts. Final response: that report's absolute path, short result summary and genuine blockers. Do not ask the owner whether to start the next batch or apply fixes; Codex reviews and implements. No DeepSeek job has been launched by writing this plan.
