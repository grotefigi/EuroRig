# EuroRig working instructions

## User workflow preferences

Use the installed Ponytail skill in full mode for coding tasks. Read the affected flow first, reuse existing helpers and platform features, and make the smallest change that fully handles the requested work. Keep validation, error handling, accessibility and required checks.

Use the installed Caveman skill for concise chat replies. Preserve technical facts, numbers, units, negations, code, commands and paths. Keep persisted code, documentation and commit messages in clear conventional language.

Use only original EuroRig code and licensed dependencies. Keep reference APKs, tablet screenshots, account data and real GPS evidence in ignored `analysis/private/`. Device smoke scripts that clear app data must run only on dedicated emulators. Truck dimensions, loaded weights, access restrictions and ADR checks apply to every routing mode.

Use the installed Compaction CLI when its integration is supported and its native hooks have been reviewed and trusted. Preserve current requirements, decisions, file references, unfinished work and test evidence when condensing context. Keep generated .compaction data private. Do not claim live input compression or token savings without measured receipts; the current Windows gateway connector does not detect Codex.

## Shared roadmap progress

Follow `docs/planning/CODEX_WORK.md`, `DEEPSEEK_WORK.md` and `LIVE_PROGRESS.md`. At task start, meaningful milestones, blockers and end, update your own lane using `tools/roadmap_progress.py`. Codex owns `docs/planning/progress/codex.json`; DeepSeek owns `deepseek.json`. Preserve the other writer's state. Record current work, actual blockers and evidence. Do not invent percentages or count worker drafts as accepted product features. A dashboard does not launch a model or prove it is still running. Keep private device/GPS/account evidence outside public progress records.

## EuroRig Agent Workflow

### Priorities
1. Correctness and reliability
2. Complete implementation
3. Autonomous recovery
4. Performance
5. Minimal dependencies
6. Token efficiency

### Agent responsibilities

Main agent:
- Own implementation and coordination.
- Delegate independent investigations.
- Keep changes focused on the task.
- Integrate and verify results.

Explorer:
- Investigate unfamiliar code.
- Identify dependencies and constraints.
- Do not modify files.

Reviewer:
- Independently review completed changes.
- Identify correctness and regression risks.
- Report findings to the main agent.

Tester:
- Execute relevant tests.
- Verify acceptance criteria.
- Report reproducible failures.

### Rules
- Avoid concurrent edits to the same files.
- Do not claim success without evidence.
- Fix confirmed failures and rerun affected tests.
- Preserve existing user modifications.
- Never weaken security controls to bypass errors.
- Do not introduce unnecessary dependencies.
- Keep intermediate reports concise.
- Stop and report genuine blockers.
