# Gates: reviewed authority-source intake

OWNS: tools/authority_intake.py, tools/test_authority_intake.py, docs/RESTRICTION-SOURCE-INTAKE.md, docs/planning/DEEPSEEK_WORK.md, ROADMAP.md, CONTINUATION.md, docs/planning/progress/codex.json

Scope: Implement a reusable candidate-intake adapter that preserves source provenance, validates a separately reviewed source/map match and quarantines unsupported or unreviewed records without changing installed maps.

- [x] S1: The contract separates raw source claims from reviewer approval, identifies the exact map/source snapshots and represents unsupported legal semantics honestly.
  EVIDENCE: RESTRICTION-SOURCE-INTAKE.md specifies a reviewer-owned policy and match file bound to exact source/display/match hashes. Numeric normalization reuses compile_map.limit and restriction_kind.classify; permitted mass, conditions, generic exceptions, ADR/zone/turn semantics are explicitly unsupported. Membership checks do not purport to prove factual road correspondence.

- [x] S2: The adapter accepts the supported, reviewed source cases in multiple country configurations and rejects changed, stale, unreviewed, ambiguous or conflicting evidence.
  EVIDENCE: Seventeen focused tests pass, including RO/MD synthetic configuration reuse, unit/mass-basis checks, changed source/map/match hashes, stale/draft/unknown-rights reviews, missing/invalid IDs, conflicting versus opposite directions, malformed fields/JSON/headers and currentness. Expert review found an extra-column serialization defect; its regression failed before the fix and now passes.

- [x] S3: Intake is reproducible and preserves source/map inputs and existing outputs, with complete accepted/rejected record accounting.
  EVIDENCE: Byte-identical repeated bundles and unchanged input hashes are asserted. The actual CLI runs against compiler/enrichment fixture output; it stages a 4.25 m candidate without replacing the existing 3.5 m map rule. Persistent synthetic-demo receipts verify both country configurations each accept 1/reject 2, all output hashes, and unchanged database bytes. The manifest is written last and never hashes itself.

- [x] S4: Relevant regression checks pass, DeepSeek has an exact preparation contract, and roadmap/live progress state the completed step and remaining integration accurately.
  EVIDENCE: Full Python suite passes 76 tests with zero failures/skips. DeepSeek DS03's plan links the exact interchange contract; its live lane remains untouched. Codex progress and the roadmap identify staging v1 as implemented while B5 and the overall 1/37 acceptance figure remain unchanged. No real authority dataset, installed map or app/routing production code changed; native/device checks are not claimed.
