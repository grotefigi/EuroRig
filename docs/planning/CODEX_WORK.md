# Codex execution plan

**27 October:** This is historical lane and model-allocation guidance. The owner is handing the whole project to Claude. Current state and remaining work are in [the handover](../HANDOVER-CLAUDE-2026-10-27.md) and [progress ledger](progress/codex.json); preserve dependency-based acceptance and the 40-minute no-progress rule.

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

Start with the earliest open package and focus on one at a time. If 40 minutes pass without significant progress, record the reason and move to the next package. Preserve partial work and do not count a package as accepted while its prerequisites remain open.

## Review and progress protocol

- Each accepted package needs evidence against its existing ROADMAP acceptance conditions. Partial code, worker drafts and a passing build are not package completion.
- For source extraction, compare exact records/anchors and preserve unknowns. For tests, review the independent oracle and run actual compile/test tools. For safety-relevant implementation, inspect the full changed path and its negative cases.
- Use deterministic scripts for hashes, parsers, bulk runs and record comparisons. Do not spend model tokens listing every road or repeating logs.
- Update `codex.json` through the progress CLI at task start, meaningful milestones, blockers and end. Review DeepSeek submissions through its manifest/report; record acceptance in Codex evidence and only then close the relevant project package.
- A live dashboard does not launch either provider or keep an agent working after its turn. No API credentials, purchases, new threads or scheduled jobs are configured by these plans.

## Model allocation assessment — 26 October 2026

Historical 26 October assessment: this model allocation did not itself advance package acceptance. Current ownership and status are described in the handover.

Use GPT-6 Luna High as the primary implementer for bounded work, including production code. Reserve GPT-6.1 Sol High for ambiguous architecture, safety invariants, source/applicability judgments and targeted independent acceptance. OpenAI's [model-selection guidance](https://developers.openai.com/api/docs/guides/model-selection) recommends Luna for scoped work and evaluating the lightest model that meets the quality bar. Its [model documentation](https://learn.chatgpt.com/docs/models) recommends starting with High for Luna. The stage assignments below are EuroRig engineering judgments, not published model benchmarks.

**Luna primary** means Luna can own implementation and execution under existing contracts, with focused review at consequential boundaries. **Split** means Sol first resolves a specific design or evidence decision; Luna then implements and exercises it. **Sol lead** means the central unresolved problem is reasoning-sensitive; Luna still performs bounded reproduction, fixture construction and test execution.

| Stage | Recommended ownership | Concrete division |
| --- | --- | --- |
| B4 | Sol lead | Sol owns access lifetime/final-approach invariants across stops and GPS loss. Luna builds lifecycle fixtures and runs the approved matrix. |
| B5 | Sol lead | Sol owns shared rule semantics, rights/currentness gates and exact road/zone matching. Luna implements format adapters, inventories sources and processes approved inputs in bulk. |
| B6 | Sol lead | Sol owns timezone/holiday/conditional/load applicability and unknown behavior. Luna implements bounded parsers and dated fixture cases after those decisions. |
| C1 | Split | Sol decides coherent graph identity, source compatibility and border expectations. Luna builds packages and runs reproducible border/profile matrices. |
| C2 | Luna primary | Extend existing install/update/rollback paths and corruption/storage/interruption tests. Sol reviews transaction preservation and unexpected data loss. |
| C3 | Luna primary | Implement coverage/date/search UI and known-source entrance tests. Escalate ambiguous entrance/access evidence rather than inventing it. |
| C4 | Luna primary | Package hashes, manual-import instructions, storage/retention checks and approved free-hosting integration. Review rights and any external publication before release. |
| D1 | Sol lead | Sol owns sequence matching, confidence and ambiguous parallel-road/ramp behavior. Luna generates labeled replay variations and runs the corpus. |
| D2 | Sol lead | Sol owns cancellation/generation state, bounded reroutes, stop preservation and safety. Luna writes specified race/replay tests and collects failure traces. |
| D3 | Split | Sol defines confidence/provider/service transitions. Luna implements Android callbacks, visible states and the permission/background matrix. |
| D4 | Split | Sol defines minimal persisted intent, invalidation and confirmed resume. Luna implements serialization, resume UI and kill/restart tests. |
| D5 | Split | Sol defines maneuver timing and lane evidence rules. Luna wires UI/metadata and executes recorded close-turn/roundabout fixtures. |
| D6 | Luna primary | Extract/localize RO/EN resources, wire language/voice controls and verify actual offline audio/error behavior. Sol reviews safety wording and evidence adequacy. |
| E1 | Luna primary | Finish existing ten-stop editor, accessibility, save/restart and duplicate/crossed-stop cases under the accepted lifecycle design. |
| E2 | Sol lead | Sol owns lawful candidate generation, diversity and bounded-search tradeoffs. Luna implements specified helpers and executes the route corpus. |
| E3 | Sol lead | Sol owns joined-trip history/permission invariants and combination bounds. Luna implements the chosen algorithm and multi-stop matrices. |
| E4 | Split | Sol decides licensed source matching, truck entrances and drivable-detour semantics. Luna imports approved parking records, renders UI and runs wrong-side/access cases. |
| E5 | Luna primary | Implement freshness/count/cache/outage validation for approved covered sites. No RO–MD live occupancy claim from Dutch or test feeds. |
| E6 | Split | Sol decides applicable vehicle/category/tariff/date semantics. Luna implements approved tariff formats, arithmetic, displays and example tests. |
| E7 | Split | Sol approves feed rights, road matching and reroute policy. Luna implements adapters/cache/freshness/outage behavior using the existing full truck audit. |
| F1 | Luna primary | Populate agreed public-coordinate scenarios and identical input sheets. Sol reviews the coverage gaps and whether comparisons are equivalent. |
| F2 | Luna primary | Operate the authorized reference UI and record matched-input outcomes in bulk. Sol investigates safety disagreements; Eurowag output is not a legal oracle. |
| F3 | Luna primary | Execute the fixed Tab S9 matrix and collect private bound receipts. Escalate safety/state-machine defects rather than changing expected outcomes. |
| F4 | Luna primary | Repeat the established matrix on the actual Pixel when connected. Unavailable hardware remains a blocker for either model. |
| F5 | Luna primary | Run replay/soak, collect performance/memory/thermal metrics and localize failures. Sol defines consequential thresholds and reviews systemic routing/concurrency defects. |
| G1 | Luna primary | Prepare the private candidate, installation/upgrade/rollback checks and release evidence using approved signing procedures. Sol performs final release/security/coverage acceptance. |
| G2 | Luna primary | Implement repeatable source/dependency review, expiry checks, regression runs and receipt-based issue reproduction. Escalate changed semantics or rights. |
| G3 | Split | Sol approves each country's rights/rule/border contract. Luna repeats the accepted adapters, builds, corpus and coverage audits. |
| G4 | Luna primary | Implement approved public-distribution/update/support policy and compatibility matrices. Sol reviews signing/security/privacy and new platform failures before publication. |
| G5 | Sol lead | Sol owns permit, trailer/axle/swept-path modeling and the separate support contract. Luna implements specified geometry helpers and independently defined cases. |

The remaining 30 packages comprise 14 Luna-primary, eight split and eight Sol-led recommendations. These counts describe ownership, not effort, intelligence scores or a measured token/cost saving. F4 needs the physical Pixel; current authoritative/licensed data and permit evidence constrain other packages independently of model choice.

### Keep delegation cheaper than doing the work

Use one coherent assignment per bounded component, with exact files, the required observable result and existing checks. Give Luna only the current relevant context; do not copy the entire chat or create a new reporting framework. Let Luna write production code, run builds, collect receipts and fix ordinary failures. Shell scripts perform copying, hashing, counting, formatting and repeated runs without model narration.

Sol reads the changed safety path, compact results and actual failures. Do not rerun a passing check without a changed input or an unresolved concern. A batch report should return the diff, commands/exits, evidence paths and blockers, rather than raw logs or another plan. Never let the same author redefine the expected safety result to match output. Stop a failing cheap-model loop when it cannot resolve the invariant; escalate that specific problem with its reproduction.

When work resumes, B4 is next. Sol should first define the access-lifetime invariants and independent expected cases, then hand fixture construction and matrix execution to Luna High. Do not launch C–G stages early to keep workers busy. Actual model settings, usage and repair/review overhead must be recorded before claiming savings.
