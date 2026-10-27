# Gates: header-bound CESTRIN OG43 candidates

OWNS: tools/cestrin_candidates.py, tools/test_cestrin_candidates.py, docs/CESTRIN-CANDIDATES.md, docs/RESTRICTION-SOURCE-INTAKE.md, ROADMAP.md, CONTINUATION.md, docs/planning/progress/codex.json

Scope: Parse the observed OG43 Word-HTML layout into unreviewed candidates without treating bypass chainage as a limit. This is a source-format adapter, not a map import or legal rule approval. Other layouts must fail visibly.

- [x] S1: Header labels, units and the full numbered column layout bind measurements to exactly the four restriction columns; detour numbers never become measurements.
  EVIDENCE: Seven adapter tests pass including contamination/changed layout/units controls. The real captured table produces no limit for its blank-valued first row despite numeric detour cells; an independent positive control identifies 16 t in the actual DN1C gross-mass column. See docs/CESTRIN-CANDIDATES.md.

- [x] S2: Every candidate retains raw cells, exact input hash, original row index, road/chainage context and explicit unknown rights/currentness/matching; ambiguous values and duplicate source IDs remain visible.
  EVIDENCE: Actual file yields 202 rows, including two absent from the worker inventory, and 68 rows with additional issues. All gross-mass basis remains unknown and applicability is a required review. PARSER_RESULTS.json independently measures counts, issues, positive/negative source controls and actual input hash.

- [x] S3: CLI output is deterministic, cannot overwrite an existing file, never edits maps, and remains outside accepted authority staging without review.
  EVIDENCE: Real CLI and direct extraction outputs agree; rerunning against its existing output fails with FileExistsError and input/output hashes remain unchanged. Output always declares candidate_only/import_approved=false and is intentionally distinct from intake CSV. Worker originals remain unchanged; no map API or map path is used.

- [x] S4: Full tooling tests pass and actual source counts, limitations and next work are recorded in live progress.
  EVIDENCE: Full suite passes 84 tests with zero failures/skips. Public adapter documentation, source-intake warning, worker review, ROADMAP and CONTINUATION record actual results and remaining review/join/integration work; Codex progress is updated without modifying the worker lane.
