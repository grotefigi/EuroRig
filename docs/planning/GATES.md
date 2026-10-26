# Gates: full truck-navigation roadmap

OWNS: ROADMAP.md, docs/planning/GATES.md, docs/planning/FAST_MODEL_WORK.md, CONTINUATION.md

Scope: produce an evidence-based implementation and release roadmap for the approved interface, incorporating the owner's decisions and exposing unanswered scope questions.

- [x] R1: the current-state inventory distinguishes source implementation, automated tests, historical native evidence and unverified road behavior
  EVIDENCE: Reconciled the latest implementation checkpoint 65f3104 with docs/UI-ROUTING-2026-10-25.md and source. ROADMAP section 2 retains the latest 205/43 test and 0-error/85-warning receipts, records the current passing ADR-C fixture and separates partial implementation, current tablet/compact evidence, missing municipal coverage and unperformed Pixel/road validation. No old-main native result is counted as an approved-branch pass. This planning-only update does not claim new runtime tests.

- [x] R2: every roadmap work package has a priority, dependency, concrete deliverable and measurable acceptance condition
  EVIDENCE: Reviewed all 37 work-package rows across phases A-G and six milestone exits. A structural check verified unique/complete package inventory, existing dependency IDs, an acyclic graph, local Markdown targets and balanced code fences. Tablet soak work can precede Pixel availability; two-device completion still requires Pixel receipts. Acceptance targets are identified as proposed engineering targets, not measurements.

- [x] R3: confirmed owner decisions, pending questions and free-data constraints are reflected throughout release scope and acceptance gates
  EVIDENCE: Reconciled every answer: owner-only RO/MD first, standard legal combinations/ADR with permit loads later, ten manually ordered/dragged stops without optimization, RO/EN, offline core/free app/optional feeds, no fixed date/free data/zero recurring hosting, confirmed fresh-check resume, agent-only Eurowag comparison, and unavailable Pixel. No initial-scope question remains unanswered; external data/device/road-validation constraints and future country decisions remain explicit. Primary-source review supports the OSM offline-tile, hosting and alternatives feasibility notes.

- [x] R4: the roadmap covers truck routing, navigation reliability, maps, data, required trip features, both target devices and staged release validation
  EVIDENCE: Coverage review confirms phases for baseline/native ADR reproduction, capability/source rules, coherent RO/MD borders and updates, guidance/GPS/rerouting/lifecycle/speech, ten-stop drag/drop, whole-trip alternatives, parking/conditional tolls/traffic, Eurowag/device/soak comparison and private release/Europe expansion. Immediate queue and critical path preserve the approved UI. Current driverless testing never becomes a road-validation claim.

- [x] R5: current package status and execution priorities reflect the latest implementation checkpoint without closing unproved release gates
  EVIDENCE: Reviewed source checkpoint 65f3104, current verification note and source/CI configuration. ROADMAP identifies A4 verified for the current tablet build and all other package groups partial/open or later scope. It preserves the municipal-data handoff, Pixel/road limits and current 205/43/85 receipts, and replaces the stale immediate queue with remaining work. Local structural verification passed: 37 unique packages, valid acyclic dependencies, eight worker tasks, 13 valid local links and balanced fences. No runtime code changed and no new runtime test pass is claimed.

- [x] R6: the fast-model queue gives bounded tasks, real source inputs, reviewable outputs and acceptance checks without delegating safety decisions
  EVIDENCE: Reviewed docs/planning/FAST_MODEL_WORK.md and its eight task rows. W01-W03 have existing source/report inputs and copyable read-only briefs; translation, authority indexing, scenario/test generation and log triage state their additional prerequisites. Each task defines output and acceptance limits. Legal/routing decisions, independent test oracles and final acceptance stay with the lead. Private evidence is excluded from external prompts. Batch/correction limits and pilot review/cost measurements are explicit. Provider existence was checked against Inception's current public page; no Mercury benchmark, integration, dispatch or saving is claimed.
