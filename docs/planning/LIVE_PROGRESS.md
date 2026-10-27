# Shared live roadmap

The live view is served locally at `http://127.0.0.1:8766/` while the helper is running. It refreshes every three seconds. No cloud account, provider API or paid hosting is required. It does not start DeepSeek/Codex jobs or keep an agent working after a turn ends.

## Two writer lanes

- [Codex plan](CODEX_WORK.md): architecture, interpretation, production implementation, independent tests and final acceptance.
- [DeepSeek plan and full assignment](DEEPSEEK_WORK.md): twelve bulk-preparation batches, fixed output contracts and one final bundle.
- `progress/plan.json`: the 37 project packages and twelve DeepSeek batches.
- `progress/codex.json`: accepted package states, current Codex work, blockers and recent updates. Codex owns this file.
- `progress/deepseek.json`: worker batch states, current DeepSeek work, blockers and recent updates. DeepSeek owns this file.

Each writer updates only its lane through the CLI. Atomic replacement avoids partial reads; separate files avoid one lane overwriting the other. A stale writer can also use `--expect-updated-at`. A shared machine/filesystem is required for automatic live updates. An external chat without filesystem access cannot update this view until its records are imported; it must report that limit honestly.

## Percentage method

Overall progress is **accepted packages / 37**, equally weighted. The denominator includes later/optional roadmap packages. Existing partial implementation is visible but receives no arbitrary fractional credit. This percentage measures acceptance, not hours spent, software lines implemented, safety coverage or time remaining.

Initial evidence accepts A4 for the current tablet QA build: **1/37 = 2.7%**. That does not mean only 2.7% of the app exists. Other packages contain substantial working implementation but still have open acceptance conditions. DeepSeek's separate percentage counts submitted batches / 12; worker submissions do not increase accepted product progress. A missing physical Pixel remains a blocker for its device package.

Round two has eight additional preparation batches in [DEEPSEEK_ROUND2.md](DEEPSEEK_ROUND2.md). Its run-local `PROGRESS.json` records `R2-01`–`R2-08`; current text shows explicitly named round-two submitted/blocked counts and percentage. The original 8/12 submission card remains the round-one counter. Preserve its historical item states/evidence, and do not mix either worker percentage with accepted product progress. The round-two worker mirrors status with `update --lane deepseek --current ...` only; no new model job is launched by these files.

Record complete/submitted items only with current evidence. Blocked items need a concrete missing prerequisite. Wrong IDs, invalid lane states and corrupt state fail visibly; they are never converted to zero or a fabricated green result. The dashboard shows last-writer timestamps separately from view refresh time. An old update is not proof that an agent is still working.

## Commands

Run from the repository root, using the existing Python environment:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/roadmap_progress.py serve --port 8766
```

Update current work without closing a package:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/roadmap_progress.py update --lane codex --current "Implementing the shared restriction-source adapter"
```

Record a worker submission:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/roadmap_progress.py update --lane deepseek --item DS03 --status submitted --current "Bulk source packet ready for review" --evidence "C:/EverythingAI/Project EuroRig/Workers/DeepSeek/runs/RUN-ID/DS03/report.md"
```

Record an actual blocker:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/roadmap_progress.py update --lane deepseek --item DS03 --status blocked --current "Collecting independent sources in the other countries" --blocker "The identified publisher requires credentials that are not available"
```

Use `show` for a machine-readable snapshot. Update at task start, meaningful milestones, blockers and end. The helper serves only the dashboard and `/api/progress` on loopback; it does not expose arbitrary workspace files or accept remote writes. Restart it after a PC reboot using the command above. No scheduled job or automatic startup was installed.
