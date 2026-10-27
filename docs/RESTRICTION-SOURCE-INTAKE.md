# Reviewed restriction-source intake, version 1

This is the shared authority-data intake for ROADMAP B5. `tools/authority_intake.py` processes a country-independent CSV interchange format and emits normalized candidates plus a complete rejection queue. It reuses the existing numeric parser and restriction classifier. Intake itself does not change maps. The application step below revalidates reviewed batches and adds supported rules to a separate display-map copy for the existing route audit.

The split is deliberate: source-format adapters prepare evidence in bulk; a separately reviewed source policy and exact road-match file determine whether a record may enter the candidate bundle. The adapter does not infer current law or road correspondence from a name, nearby coordinate or model confidence. Source approval is an explicit reviewer input, not a claim copied from the feed. A hash proves snapshot identity, not that an authority rule or match is correct.

## Inputs

1. A UTF-8 CSV source batch using the exact header below. Quoted commas, escaped quotes and multiline fields use standard CSV parsing. Each record ID must be unique within the source batch.
2. A reviewer-owned JSON policy, bound to the CSV, match file and finalized display database by SHA-256. An unapproved or stale policy quarantines its records rather than importing them.
3. A reviewer-owned match JSON object mapping source record IDs to explicit OSM way IDs and correspondence-review references. All listed ways must exist in the exact display snapshot. Membership is checked; the reviewer still establishes that these are the correct roads, subsegments and travel directions.
4. A finalized, immutable input display SQLite snapshot, format version 1, with a `roads` table. WAL or journal sidecars are refused. No nearest-road guess or automatic street-name match is performed.
5. An explicit `--as-of YYYY-MM-DD` date and a fresh output directory.

```csv
record_id,country,restriction,value,unit,direction,mass_basis,valid_from,valid_until,condition,exception
synthetic-height,RO,height,350,cm,both,,2026-01-01,,,
```

The example is synthetic. It is not an authority record or a restriction to import into a real map.

| Field | Contract |
| --- | --- |
| `record_id` | Stable nonempty source record ID; duplicates are all quarantined. |
| `country` | Two-letter country code equal to the separately reviewed source scope. No country-specific code branch is required. |
| `restriction` | `height`, `width`, `length`, `loaded_mass`, `axle_load`, or `hgv_access`. Other semantics remain unsupported. |
| `value`, `unit` | Positive finite plain numeric input, with `m`, `cm`, `ft` for dimensions or `t`, `kg`, `lbs` for mass. Output preserves raw text and normalizes to metres/tonnes. Access values are `no`, `private`, `delivery`, `destination`, with no unit. |
| `direction` | Explicit `both`, `forward` or `backward`, relative to the matched OSM way node order. Missing or unknown direction is quarantined. |
| `mass_basis` | `loaded` for loaded mass, `axle` for axle load, empty otherwise. Documented permitted mass, axle-group ratings and ambiguous mass meanings are not converted into loaded-mass rules. |
| `valid_from`, `valid_until` | Explicit reviewed calendar dates, inclusive. Start is mandatory; end may be empty for an open-ended record whose source review still has a finite recheck date. Future, expired, invalid or unknown dates are quarantined. These are intake snapshot dates, not time-of-day routing support. |
| `condition`, `exception` | Must be empty in v1. Time windows, holidays, permits and other exceptions require supported semantics before acceptance. An HGV `delivery`/`private` access value remains a restriction evaluated by the existing permission policy; it never grants authorization. |

## Reviewer policy

```json
{
  "schema_version": 1,
  "status": "approved",
  "source_id": "synthetic-example",
  "publisher": "EuroRig original synthetic fixture",
  "source_url": "https://example.org/synthetic",
  "country": "RO",
  "license": "Original EuroRig fixture",
  "reuse_allowed": true,
  "license_review": "Reference to the actual reuse-rights review",
  "currentness_review": "Reference to the enacted/current-source review",
  "reviewed_on": "2026-10-25",
  "review_due": "2026-11-01",
  "source_sha256": "actual SHA-256 of the exact CSV bytes",
  "matches_sha256": "actual SHA-256 of the exact match JSON bytes",
  "display_sha256": "actual SHA-256 of the finalized display snapshot"
}
```

The illustrative hash strings above intentionally do not pass validation. Use actual hashes. The producer/worker must not set approval or invent review references. A draft with unknown rights, currentness or correspondence stays unapproved. A policy is reviewer evidence; this local tool is not an authentication system and cannot independently prove the review's factual conclusions.

Example match-file structure:

```json
{
  "synthetic-height": {
    "osm_way_ids": [20000003],
    "review_ref": "Synthetic fixture correspondence; not a real surveyed road"
  }
}
```

Duplicate/nonstandard JSON, duplicate CSV headers, unknown header fields, unparseable CSV syntax, empty datasets and unsupported display versions fail the batch before output. Missing/extra row fields are quarantined; unexpected trailing columns are preserved under `_extra_columns` in the raw rejection data. Missing, duplicate, invalid or absent-from-map way IDs are not partially applied. Different values for overlapping same-type/direction rules on the same way quarantine every conflicting record. Opposite-direction records remain distinct; the staging step does not flatten them into one scalar or edit existing OSM evidence.

## Run and output

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/authority_intake.py --source source.csv --review review.json --matches matches.json --display display.sqlite --out fresh-staging-folder --as-of 2026-10-25
```

The command prints actual accepted/rejected totals. Exit zero means the intake completed, including any quarantined rows; it does not mean all records passed. Inspect `accepted`, `rejected` and `rejection_counts`.

The fresh output contains `accepted.jsonl`, `rejected.jsonl`, exact copies of `source.csv`, `matches.json`, `review.json`, and a final `manifest.json` with input/output hashes and complete record counts. Raw fields and CSV end-line references remain available for inspection. `accepted + rejected == records`. The manifest is written last and excludes itself from its hash list; consumers must require and verify that manifest rather than consume a partial output directory. Existing output directories are preserved. Identical inputs/as-of date produce identical bundle bytes.

Both acceptance and rejection are **staging results**. `status=staged_only`, `map_modified=false`, and `legal_completeness=unknown` remain explicit. No network calls, credentials, model API calls or device operations are needed. One reviewed source batch is held in memory; source adapters should split large feeds into bounded batches until measured needs justify disk-backed staging.

## Apply reviewed batches and package the result

`tools/apply_authority_rules.py` connects reviewed batches to the display rules used by the route audit. Give it the original enriched map, one or more intake bundle directories, a fresh output directory, and the explicit application date. Every bundle must name that same original map snapshot.

Application checks every bundle file hash, then reruns the original CSV intake against its source, policy, exact-way matches and current application date. A changed acceptance file still fails if a worker updates its manifest hash. Expired reviews, changed maps, missing source approvals and missing exact matches fail before an output map is created.

The reviewer policy needs two additional application fields:

```json
{
  "map_derivative_approved": true,
  "map_attribution": "EuroRig original synthetic fixture"
}
```

These examples describe original synthetic test material. For real data, the approval must cover the combined map's redistribution, license compatibility and required notices. The reviewed attribution is retained in the SQLite source metadata and exported into the package manifest with its license and publisher.

The supported application subset is numeric height, width, length, loaded-mass and axle-load limits, plus an explicit `hgv_access=no` ban. Existing stricter limits, prohibition flags, raw evidence and uncertainty are retained. A ban in both directions closes existing scoped HGV grants. New directional rules retain their recorded direction; mixed records use the existing conservative scalar-map representation. Delivery/private/destination exceptions and uninterpretable existing numeric values enter `application-rejected.jsonl`; they never create permission. An HGV ban on a way with existing uncertainty is also quarantined, because the current runtime can report uncertainty before reaching the access ban. Each source record is prepared for all matched ways before any of its changes are written.

```powershell
& ./.toolchain/qa-python/Scripts/python.exe tools/apply_authority_rules.py --display display.sqlite --bundle reviewed-ro-batch --bundle reviewed-md-batch --out fresh-applied-map --as-of 2026-10-26
& ./.toolchain/qa-python/Scripts/python.exe tools/package_region.py routing.tar fresh-applied-map/display.sqlite reviewed.eurorig --name Romania-Moldova --source "Original OSM extract attribution" --date 2026-10-26
```

Application writes a separate `display.sqlite`, per-way source records in its `authority_rules` table, `applied.jsonl`, `application-rejected.jsonl`, a fresh restriction-coverage audit and a final `manifest.json` with hashes. Original maps and native tiles remain intact. Packaging preserves the display bytes, their checksum, source license records and reviewed attribution. Existing package and normalization output files are refused.

Inspect `applied_records`, `application_rejected` and `intake_rejected`. A `quarantined_only` result applies zero source rules. The final manifest is required before an application output is ready. To refresh an already-applied source, rebuild from the original OSM display snapshot and apply the current reviewed set. The tool refuses duplicate source IDs instead of accumulating obsolete versions.

The date is a build snapshot check. Runtime expiry, schedules, zones, source-format extraction and exact road matching still need their roadmap work. Real RO/MD source rights and reviewed matches remain unresolved; legal coverage remains unknown.

## Verification and remaining work

Seventeen focused tests cover units and source preservation, two synthetic country configurations, independently supplied review/match bindings, changed snapshots, stale/draft/unknown-rights reviews, unsupported semantics and conditions, duplicate IDs/keys/headers, conflict directions, malformed values and field counts, expired/future dates, multiline CSV, existing-output preservation and an actual CLI run against a compiled/enriched fixture map. The CLI test stages a 4.25 m candidate while confirming the existing 3.5 m map rule and database hash are unchanged.

Actual authority-format adapters, review of real licensed source samples, zone/point/segment correspondence, accepted-rule merge into routing/display, provenance shown in the app, expiry/update/rollback and end-to-end native trace tests remain open. ADR classes, tunnel categories, rated mass, conditional rules, geometry zones and turn restrictions are not silently reduced to this adapter's supported subset. This step does not establish new municipal or country coverage.
# Worker source-data disposition

The independent [DS03/DS12 review](DS03-DS12-REVIEW-2026-10-25.md) rejects the submitted CESTRIN `conditions_raw.valori` field as import-ready measurements: it mixes restriction values with bypass kilometre positions. Use original artifacts and correctly bound table headers for the next candidate adapter. All 7,275 candidate source rows still have unknown reuse rights; none are accepted legal rules or exact map joins. Preserve worker originals and quarantine their numeric interpretation instead of routing from it.

The [header-bound OG43 parser](CESTRIN-CANDIDATES.md) now supplies a separate candidate stage with actual input hashes, raw columns, unresolved gross-mass basis and unknown review status. It does not emit accepted intake records or bypass any reviewer/map-match checks. The worker's record-level hashes do not resolve to its supplied raw files; use the corrected byte-bound provenance before reviewing candidates.
