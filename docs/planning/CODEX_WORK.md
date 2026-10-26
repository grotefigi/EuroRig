# Codex execution plan

Owner: Codex. This is the decision, implementation and acceptance half of [the project roadmap](../../ROADMAP.md). [DeepSeek's plan](DEEPSEEK_WORK.md) supplies bulk preparation; [shared live progress](LIVE_PROGRESS.md) records both lanes. The existing 37 package IDs and dependencies remain authoritative. No model pricing or performance benchmark is claimed.

## Responsibilities

Codex owns architecture, the normalized restriction contract, source/currentness and reuse decisions, exact road/zone matching policy, mass/ADR/access interpretation, routing/matching/recovery algorithms, production implementation, independent expected outcomes, integration and release acceptance. Read worker summaries and machine-readable diffs first; investigate exceptions and high-risk evidence instead of rereading entire raw dumps.

DeepSeek may collect, index, normalize and run specified experiments at scale. Worker scripts and tests are candidates. A worker's green verifier or self-reported completion is never the independent acceptance oracle. The Mercury review demonstrated why: five corrupted bundles passed its verifier, while actual Android resource compilation failed.

## Work by existing package

| Packages | Codex work and acceptance | DeepSeek preparation |
| --- | --- | --- |
| A1–A4 | Preserve the approved baseline; reconcile CI/toolchain; close actual failures and scope device receipts. Keep completed QA isolation intact. | DS01 |
| B1–B4 | Decide the supported truck/ADR matrix; resolve model/native bounds; implement and verify hard limits, path-history turns and destination-bound access. No permission or preference waives a hard restriction. | DS02 |
| B5–B6 | Design shared authority-rule ingestion, rights/currentness gates, source adapters and exact road/zone joins. Implement conditional/timezone evaluation and automatic exceptions. Apply accepted datasets in bulk. | DS03 |
| C1–C4 | Build coherent RO–MD routing/display packages; implement verified import/update/rollback, source/coverage UI and free distribution. | DS04 |
| D1–D4 | Implement/review matching, bounded rerouting, GPS uncertainty and confirmed restart while preserving unvisited stops and authorization. Define independent replay outcomes. | DS05 |
| D5–D6 | Implement maneuver timing, truthful lane advice, RO/EN resources and actual offline speech. Review safety wording and observable audio behavior. | DS06 |
| E1–E3 | Finish ten-stop/lifecycle acceptance and bounded lawful alternatives, including multi-stop joins. Preserve chosen order. | DS07 |
| E4–E7 | Integrate validated truck parking and available free optional feeds; implement freshness and unavailable-coverage behavior. Decide tariff/category semantics and uncertainty. | DS08 |
| F1–F2 | Define accepted comparison inputs/oracles; operate the authorized reference UI; investigate disagreements using current source evidence. | DS09 |
| F3–F5 | Own physical device operations, final tablet/Pixel qualification and measured performance/soak interpretation. No hardware or road-test claims without receipts. | DS10 |
| G1–G5 | Own signing, release, update/support policy, security, country expansion and later permit-load design. Integrate only compatible licensed material. | DS11 |
| Cross-cutting reverse engineering | Continue bounded behavior/interface analysis of EurowagNav at a pinned revision; reproduce historical findings and implement useful behavior with original code. No commercial map values without confirmed reuse rights. | DS12 |

## Current priority order

1. Finish the shared plans and live progress surface. Preserve current owner requirements and record actual acceptance.
2. Use DS02/DS03/DS12 outputs to design and implement the next shared restriction-source adapter and trace tests. The evidence audit is implemented; it does not populate missing restrictions.
3. Close the truck/ADR model/native capability gaps and CI provisioning discrepancies. Reuse current source and passing fixtures.
4. Establish the RO–MD source/border corpus; implement and verify guidance/recovery and remaining stop/alternative/language acceptance.
5. Complete matched-profile reference comparison, device qualification and release prerequisites. Expand countries with reusable source adapters and exception review.

## Review and progress protocol

- Each accepted package needs evidence against its existing ROADMAP acceptance conditions. Partial code, worker drafts and a passing build are not package completion.
- For source extraction, compare exact records/anchors and preserve unknowns. For tests, review the independent oracle and run actual compile/test tools. For safety-relevant implementation, inspect the full changed path and its negative cases.
- Use deterministic scripts for hashes, parsers, bulk runs and record comparisons. Do not spend model tokens listing every road or repeating logs.
- Update `codex.json` through the progress CLI at task start, meaningful milestones, blockers and end. Review DeepSeek submissions through its manifest/report; record acceptance in Codex evidence and only then close the relevant project package.
- A live dashboard does not launch either provider or keep an agent working after its turn. No API credentials, purchases, new threads or scheduled jobs are configured by these plans.
