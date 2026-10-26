# Gates: independent DS03 and DS12 review

OWNS: docs/DS03-DS12-REVIEW-2026-10-25.md, tools/test_adr_evidence.py, docs/RESTRICTION-SOURCE-INTAKE.md, ROADMAP.md, CONTINUATION.md, docs/planning/progress/codex.json

Scope: Verify the submitted bundle without editing worker originals, test national-source extraction semantics against captured raw rows, and establish whether the current compiler actually loses ADR evidence. Candidate validation is not import approval or legal/currentness certification.

- [x] R1: Delivered file counts and hashes are independently reconciled; worker submissions, blockers and product acceptance remain distinct.
  EVIDENCE: Independent path-bounded review hashes all 362 entries; zero mismatches, missing or unlisted files. Live lane has eight submitted/four blocked batches. Accepted product progress remains separate.

- [x] R2: Source extraction is checked against real table columns and representative rows; road chainage, measurements, dates, rights and unresolved matches cannot silently become legal rules.
  EVIDENCE: Original HTML cell parser and independently opened publisher index/table confirm detour chainage contaminates candidate values. docs/DS03-DS12-REVIEW-2026-10-25.md rejects that field as import-ready; all rights remain unknown and no map or legal-rule import occurred.

- [x] R3: A real compiler/enrichment fixture proves whether mapped hazardous-load/tunnel evidence survives even when the generic kind classifier returns zero.
  EVIDENCE: tools/test_adr_evidence.py executes the actual compiler, enrichment and coverage audit: four retained ways/one node, correct flags/tags, three explicit ADR-tag records and unknown legal completeness. Existing JVM hazard checks separately cover categories/load types. Full tooling suite passes 77 tests without skips.

- [x] R4: Findings, reuse limits, actual test totals and next implementation work are recorded in the roadmap without overwriting DeepSeek's lane or importing unreviewed data.
  EVIDENCE: Public review, source-intake warning, ROADMAP and CONTINUATION updated. Raw worker bundle and its progress lane remain unchanged by Codex. No production map, classifier or enforcement change was made for this review; the next adapter must bind headers and reject uncertain inputs.
