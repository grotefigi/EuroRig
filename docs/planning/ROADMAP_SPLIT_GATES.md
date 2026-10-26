# Gates: split execution plans and shared live progress

OWNS: docs/planning/CODEX_WORK.md, docs/planning/DEEPSEEK_WORK.md, docs/planning/LIVE_PROGRESS.md, docs/planning/progress/**, tools/roadmap_progress.py, tools/test_roadmap_progress.py, tools/roadmap_dashboard.html, ROADMAP.md, CONTINUATION.md

Scope: Preserve the complete project roadmap, divide work between DeepSeek and Codex, and provide a live shared progress view with honest acceptance percentages.

- [x] P1: The two plans cover all 37 existing packages, preserve dependencies and include a usable single-run DeepSeek handoff.
  EVIDENCE: check_split.py independently reconciles all 37 IDs, priorities and prerequisites against 5101f9d, twelve worker batches and both linked plans. Complete one-run assignment is DEEPSEEK_WORK.md.

- [x] P2: Progress distinguishes accepted product packages, worker submissions, partial work, current activity, stale updates and blockers without arbitrary percentages.
  EVIDENCE: Initial 1/37 (2.7%) is tied to A4's existing receipt. Partial work does not change the counter; DeepSeek has 0/12 submissions and is explicitly not started. Desktop/mobile labels separate writer timestamps from view refresh and list the Pixel blocker. Unit tests verify that submissions do not increase product progress.

- [x] P3: Both lanes can update their own state without overwriting the other; completion requires evidence and invalid states are rejected.
  EVIDENCE: Eight progress tests cover independent lanes, fresh evidence, worker submission versus acceptance, blockers, unknown IDs, stale writes, corrupt/duplicate plan states and actual HTTP refresh. Full Python suite: 59 tests, zero failures or skips. State replacement is atomic; each lane has one writer.

- [x] P4: The local live dashboard loads, refreshes after an actual update and remains usable at desktop and phone widths.
  EVIDENCE: Running at 127.0.0.1:8766. Browser showed the actual Codex CLI update after polling, desktop three-column layout, and a 360-pixel iframe with stacked cards and no horizontal clipping. DeepSeek view/search and project blocker filters were checked through the UI.

- [x] P5: The handoff, roadmap, continuation and final dashboard describe the same current work, limits and next steps.
  EVIDENCE: Plans and shared state preserve owner scope and latest scalable-ingestion/RE direction. AGENTS.md persists update obligations. No DeepSeek job, provider purchase, scheduled agent run or device mutation is claimed; product acceptance remains 1/37. Tests, source diff and manual plan review agree.
