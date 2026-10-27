# Header-bound CESTRIN OG43 candidate adapter

`tools/cestrin_candidates.py` reads the observed OG43 Word-HTML export and emits **unreviewed physical measurements**, not accepted routing rules. It never reads or edits a map. This is one national source-format adapter; there are no city-name branches.

## Contract

- Require the grouped column labels, measurement labels/units and complete 34-cell numbered layout. Other formats fail visibly. The permanent, temporary and mass-maximum layouts are not supported by this adapter.
- Only physical cells 14–17 supply measurements: gross mass in tonnes, single-axle mass in tonnes, width in metres and height in metres. Bypass positions remain in `detour_raw`; they cannot become measurements.
- Each record retains all raw cells, its original one-based row index, full input SHA-256, publisher row ID, road identity and chainage context. HTML entities are decoded, preserving the captured text's diacritics where available. A global flag exposes replacement characters already present in the supplied capture; the parser cannot reconstruct original HTTP bytes from a decoded artifact.
- Unknown row shapes, nonnumeric/zero/negative/ambiguous values, missing road identities, ambiguous/reversed chainage and duplicate publisher IDs remain visible. Records are preserved rather than silently deduplicated or repaired. Source rows and unique legal restrictions are different counts.
- Output is always `candidate_only: true`, `import_approved: false`, with unknown reuse rights, effective period and exact road matching. Every record requires rule-applicability review. Gross-mass measurements explicitly retain `mass_basis: unknown`; the parser does not infer loaded versus plated mass, direction or exceptions.
- Missing numeric values are not invented. A road closure or appendix-dependent statement stays raw and quarantined when no supported scalar is available. A correct numeric extraction still needs all external reviews before any routing use.
- The CLI requires a fresh output filename and refuses to overwrite existing files. Its supplied publisher URL is a provenance lead, not evidence that the input bytes were independently downloaded from that URL.

## Use

From the repository root:

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/cestrin_candidates.py source.utf8.htm --source-url 'https://www.cestrin.ro/restrictii/restrictii/2026/OG43/10%20Octombrie/Restrictii%20octombrie%202026_OG43.htm' --output new-og43-candidates.json
```

The [publisher's index](https://www.cestrin.ro/restrictii/) supplies source leads. Preserve the actual downloaded bytes/headers or document decoding separately. Keep private/candidate captures outside production fixtures. The output is intentionally not the authority-intake CSV schema: a reviewer must resolve legal quantity/basis, conditions, validity, reuse and exact road matching before creating the separate [intake inputs](RESTRICTION-SOURCE-INTAKE.md).

## Measured captured-table result

The preserved worker capture `OG43_oct2026.utf8.htm` has SHA-256 `f43decbb4c886f18ca94a9bda56c45b4c0981378465681f30db5ffb346c29138`. The real CLI emits 202 source rows, 192 physical measurement candidates and 68 quarantined rows. Counts include overlapping issue categories: 46 ambiguous chainages, 21 nonnumeric/missing limits, two records sharing a publisher ID and one reversed range. Measurements comprise 121 gross masses, 49 widths and 22 single-axle masses. No height value is invented for this capture.

Two source rows absent from the worker inventory are retained: row 133 / publisher ID 124 and row 134 / publisher ID 125. One is a closure without a scalar; the other has ambiguous chainage and an appendix-dependent statement. Both remain quarantined. This is not a claim of two newly approved restrictions.

The first source row has empty measurement cells and now produces no measurements despite numeric bypass positions. A positive control independently identifies 16 t in the gross-mass column for a DN1C row, keeping mass basis unknown. Actual CLI re-execution refuses to overwrite the result; input/output hashes remain unchanged. The corrected artifact and receipts are in `C:/Users/Administrator/Desktop/EuroRig-FastModel-Work/Codex-DS03-DS12-Review-2026-10-25/og43-header-bound-v1.json` and `PARSER_RESULTS.json`. An earlier draft there is explicitly named superseded.

Seven adapter tests cover detour contamination, typed columns, changed headers/units/numbering, malformed values, chainage, duplicate IDs, unknown shapes, entities, truncation, provenance-host checks and the actual CLI. The full tooling suite passes **84 tests**, zero failures/skips. Existing Android evidence remains at the profile-persistence checkpoint; no app or country rebuild is claimed for this tooling-only change. Rights, current applicability, exact matches, other layouts and Moldova source coverage remain open.
