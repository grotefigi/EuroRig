# Map storage: measured costs, the duplicate search text, and what Europe needs

## Settlement-label index, 2026-10-08

New display builds index only `city`, `town` and `village` rows in `place_area`.
The app uses that index for this exact settlement-label query. All places,
addresses, search text, roads and restrictions remain in their existing tables.
The implementation uses SQLite's existing [partial index support](https://www.sqlite.org/partialindex.html).

An owned copy of the old private RO/HU/RS display database measured
1,300,606,976 bytes after replacing this index and running host `VACUUM`, versus
1,495,130,112 bytes before: a saving of 194,523,136 bytes. The index itself fell
from 154,755,072 to 552,960 bytes; the remaining saving comes from compaction,
and must not be attributed entirely to the compiler's index change.
All 5,889,699 places, 2,725,871 roads and restriction/search row counts remain.
Four settlement queries and six search queries returned identical results;
the existing app query uses the smaller index. Six compiler checks pass,
including accent/prefix/address search and the settlement query plan. Actual
API26 service installation/rendering of two original artificial country fixtures
with the partial index passes and restores the previous map/preferences.

These are a real host measurement and a small Android compatibility control,
not a new public country package, physical tablet result or continent estimate.
Existing public/tablet maps are unchanged. The compiler affects future builds;
the complete 20,000,000,000-byte Europe/storage gate remains open.

## Compatible search-index follow-up, 2026-10-07

New country builds use `fts4(text,tokenize=unicode61,matchinfo=fts3)` in
`compile_display.py`. This removes unused per-document token-length storage,
while keeping normalized `search.text`, row IDs, prefix matching and the
composed-country evidence query intact. EuroRig does not call `matchinfo()`.
This differs from the contentless/external-content experiments below.

A private copy of the Romania display database measured 459,649,024 bytes,
saving 22,384,640 bytes (4.64%) versus 482,033,664 bytes. Twenty host searches
returned the same IDs and the SQLite integrity check passed. Native checks mix
compact and legacy indexes across 12 fictional countries and reject conflicting
shared search text. The published and installed Romania package is unchanged;
the compiler option affects future builds. This alone does not meet the 20 GB
continental installed-storage requirement.

Audit of the private QA maps, **read-only**, 2026-10-07. Nothing here changed a map, a package, a tablet
or a published artifact. Tool: `tools/audit_map_storage.py` (test: `tools/test_audit_map_storage.py`,
18 tests). Receipts: `analysis/private/map-storage-{union,romania}.json`,
`analysis/private/map-storage-fts-{romania,union}.json`. Integration review:
`analysis/private/map-storage-review.md`.

**Status of the search savings: real, measured, and NOT yet app-compatible.** The size reduction and the
search parity below are measured on host SQLite; the app's composed-country evidence check cannot run
against either proposed index format (§4). No production FTS change is proposed or applied here.

Two earlier receipts were written before the first correction and are **preserved, not deleted**, as
`map-storage-{union,romania}-superseded-mislabelled-column-bytes.json`: they labelled
`SUM(LENGTH(column))` as column bytes (for a REAL or INTEGER that is the character count of the value's
text rendering, not storage) and reported `min(FTS text, label text)` as proof of duplication
(the two differ — the FTS holds *normalized* text, the column holds display text).

## Every number is labelled

| label | meaning |
| --- | --- |
| **MEASURED** | file sizes, page counts, dbstat page bytes: real physical storage |
| **MEASURED (UTF-8 bytes)** | `SUM(LENGTH(CAST(col AS BLOB)))` on a TEXT column |
| **ESTIMATE** | arithmetic from the declared type and row count (REAL = 8 B, INTEGER = 1–9 B varint, INTEGER PRIMARY KEY = the rowid, 0 B in the row). Confirming one needs a rebuilt copy compared against the original |
| **CANDIDATE** | a saving that has not been applied and, for the search text, has only been checked on a sample unless `--identity-full` was used |

## 1. What a map costs (MEASURED)

| | Romania | RO/HU/RS union |
| --- | --- | --- |
| transport (the `.eurorig` a driver downloads) | 407,495,153 B (388.6 MiB) | 1,119,366,138 B (1.02 GiB) |
| installed (payloads as the app stores them) | 873,488,384 B (833.0 MiB) | 2,462,666,752 B (2.29 GiB) |
| expansion transport → installed | 2.14× | 2.20× |
| new-map staging peak | 1,280,983,537 B (1.19 GiB) | 3,582,032,890 B (3.34 GiB) |
| in-place upgrade peak | 2,154,471,921 B (2.01 GiB) | not measured |
| routing tiles | 636 tiles, 391,454,720 B | 1,024 tiles, 967,536,640 B |
| display database | 482,033,664 B | 1,495,130,112 B |
| places / roads / road_rules | 1,747,703 / 1,057,239 / 424,527 | 5,889,699 / 2,725,871 / 1,288,934 |

Per payload, stored and compressed:

| payload | Romania stored / compressed | union stored / compressed |
| --- | --- | --- |
| `display.sqlite` | 459.7 MiB / 228.5 MiB (2.01×) | 1.4 GiB / 690.0 MiB (2.07×) |
| `routing.tar` | 373.3 MiB / 160.1 MiB (2.33×) | 922.7 MiB / 377.5 MiB (2.44×) |
| `manifest.json` | 452 B / 298 B | 582 B / 373 B |

**Staging peak is a scope, not a guarantee.** `new_map_staging_bytes` counts only the transport still
present while the payloads are extracted into free space with no other map installed. It deliberately
excludes, and the receipt lists: the active map already installed (that is what `--upgrade-from` reports
separately — an in-place Romania upgrade peaks at **2.01 GiB**, not 1.19 GiB), a retained download, a
second copy of tiles if an install extracts into the tile directory, filesystem reserve and allocator
slack, SQLite scratch from any in-place VACUUM/reindex, and OS page cache.

The Eurowag screenshot's **149.5 MB** is a *download* figure and is not comparable to our installed
sizes. In the same decimal units, Romania's download is **407.5 MB**, about **2.73×** that label.
Its installed payload is 873.5 MB (833.0 MiB). We do not know Eurowag's installed size or data scope;
nothing here extrapolates either. At measurement time the physical Tab S9 held this imported
payload with no retained download archive. Current queued and deferred installations remove
their verified transport archive after successful activation and source closure. Failed installs
or failed archive deletion retain it for recovery, adding the 407.5 MB transport size to this
measured example until resolved. The archive still contributes to installation peak space.
This cleanup does not prove the full European installed-storage or staging budget.

## 2. Where the bytes go (MEASURED, dbstat)

Romania (459.7 MiB, 117,684 pages): roads 129.3 MiB (28.1%) · places 103.9 MiB (22.6%) ·
search_content 59.9 MiB (13.0%) · place_area 44.2 MiB (9.6%) · search_segments 28.1 MiB (6.1%) ·
road_large 21.3 MiB (4.6%) · road_level 20.2 MiB (4.4%) · search_docsize 20.1 MiB (4.4%).
FTS total 108.0 MiB (23.5%).

Union (1.4 GiB, 365,022 pages): places 372.9 MiB (26.2%) · roads 320.2 MiB (22.5%) ·
search_content 221.2 MiB (15.5%) · place_area 147.6 MiB (10.4%) · search_segments 87.3 MiB (6.1%) ·
search_docsize 71.3 MiB (5.0%) · cells 57.1 MiB (4.0%) · road_large 54.9 MiB (3.9%).
FTS total 379.8 MiB (26.6%).

dbstat is the slow step and its wall time varies with cache and concurrent I/O: 12.7 s and 16.7 s for
these databases earlier, 51.8 s for the union while an unrelated experiment was reading the same disk.

The display database grows faster than the source: the union's pbf is 2.59× Romania's but its display is
3.10×, and it carries 3.37× the places. Growth tracks places, not bytes.

## 3. The search text, as a candidate with its identity checked

| | FTS private copy | places.label | verdict |
| --- | --- | --- | --- |
| Romania | 41.0 MiB over 1,747,703 rows | 42.6 MiB | **candidate** duplicate |
| union | 147.3 MiB over 5,889,699 rows | 151.3 MiB | **candidate** duplicate |

The two totals are **not equal, and should not be**: `search_content` holds the *normalized* text
(NFD → lower case → combining marks removed) while `places.label` holds the display text. Equal totals
would have been an artifact of character counting, not evidence.

Identity is therefore checked by joining on `search_content.rowid = places.id` and comparing the indexed
text to `normalize(label)`:

* Romania: `partially_checked_same_text` — 227,369 matched, 0 mismatched, step 8 of 1,747,703 rows
* union: `partially_checked_same_text` — 203,302 matched, 0 mismatched, step 29 of 5,889,699 rows
* the negative control (a copy holding raw labels) reports `texts_differ`, so the check can fail
* `--identity-full` makes it `proven_same_text`; the default is a deterministic stepped sample because a
  full pass over 5.9M rows costs minutes

**Read this as: the copy mirrors the label text wherever it was checked, so removing it is a real
candidate, and the byte figure stays a candidate until a full check is run for the region being shipped.**

## 4. Savings candidates (nothing applied)

| candidate | Romania | union | basis |
| --- | --- | --- | --- |
| FTS4 without a private text copy (contentless, or external content) | 59.9 MiB | 221.2 MiB | MEASURED physical (dbstat `search_content`) |
| FTS4 without the docsize table (`matchinfo=fts3`) | 20.1 MiB | 71.3 MiB | MEASURED physical (dbstat `search_docsize`) |
| roads bounding box derived, not stored (drop `west`/`north`/`east`; `south` stays for the indexes) | 24.2 MiB | 62.4 MiB | ESTIMATE, rows × 3 × 8 B |
| places lat/lon as fixed-point integers | 13.3 MiB | 44.9 MiB | ESTIMATE, rows × 2 × 4 B saved |
| reclaim free pages (VACUUM) | — | 88.0 KiB | MEASURED physical (freelist) |

### The app contract: both proposed formats break composed-country validation

`DisplayDatabase.checkDuplicateEvidence` (DisplayDatabase.java:71–95) attaches the peer country during
composition and, for search, runs:

```sql
SELECT 1 FROM main.search a JOIN peer.search b ON a.rowid=b.rowid WHERE NOT(a.text IS b.text) LIMIT 1
```

A returned row raises `IOException("Conflicting country search evidence")`; a query that cannot execute
raises `RuntimeException` → `IOException("Cannot verify shared country evidence")`. Either way composition
fails. This is **not** the `MATCH` query the experiment exercises — it reads the FTS `text` column
directly, which is exactly what the two candidate formats remove or redirect.

Measured on copies, an identical peer and a peer with one genuinely conflicting rowid (host SQLite
3.53.1, `tools/test_audit_map_storage.py::AppContractTests`):

| index format | identical peer | conflicting peer |
| --- | --- | --- |
| `fts4(text,tokenize=unicode61)` — **today** | no conflict | **conflict reported** (the check works) |
| `fts4(...,content='',matchinfo=fts3)` — contentless | query failed: `SQL logic error` | query failed |
| `fts4(...,content=places,matchinfo=fts3)` — external | query failed: `SQL logic error` | query failed |

**Contentless or external content makes the app unable to verify shared country search evidence at all,
and a composed install fails.** The check must be preserved, not weakened to accept the new index. Note
also the semantic difference: with `content=places` the `text` column resolves to `places.label`, which
is display text rather than the normalized indexed text, so even a working query would compare different
things.

Requirements before any production FTS change:

* the app side must provide the contract — one avenue is a view mapping `id AS rowid, label AS text` so
  external content still exposes a `text` column; it is untested and is not a substitute for keeping the
  validation meaningful
* it must be verified on **Android API 26** with the composed-country evidence checks, not on host
  SQLite: the device carries an older SQLite and the failure above could present differently there,
  including as a silently empty result
* composed-country evidence validation must stay able to fail on a genuinely conflicting peer
* `INSERT INTO search(search) VALUES('rebuild')` must never be used with external content: it re-indexes
  the raw labels and silently drops diacritic folding

### Measured on a rebuilt copy (`--experiment`, copies only)

The experiment copies the display database into a workspace, rebuilds the index with each declaration,
VACUUMs, and compares the app's *MATCH* query result **ids** (not counts) against the original for 20
queries covering diacritics, prefixes and misses. This table is about size and search parity only — it
says nothing about the composed-country contract above.

| map | variant | rebuilt size | saving | parity | build |
| --- | --- | --- | --- | --- | --- |
| Romania (482,033,664 B) | `content='',matchinfo=fts3` | 396,853,248 B | 85,180,416 B (17.7%) | 20/20 | 18.3 s |
| Romania | `content=places,matchinfo=fts3` | 396,853,248 B | 85,180,416 B (17.7%) | 20/20 | 20.2 s |
| union (1,495,130,112 B) | `content='',matchinfo=fts3` | 1,156,046,848 B | 339,083,264 B (22.7%) | 20/20, 0 mismatches | 71.8 s |
| union | `content=places,matchinfo=fts3` | 1,156,046,848 B | 339,083,264 B (22.7%) | 20/20, 0 mismatches | 710.1 s |

The two declarations produce the **same size and the same search parity**, but not the same build cost:
at 5.89M rows the contentless rebuild took 71.8 s and external content 710.1 s (10×), while at Romania's
1.75M rows the two were indistinguishable. The difference appears with scale. (The union experiment
receipt was produced just before the label correction: it carries `tables_present` where the tool now
reports `duplicate_tables_removed`. `tables_present: []` states the same thing — neither duplicate table
survives — and its sizes, savings and parity are the measured values.)

Both variants drop `search_content` **and** `search_docsize`; `search_segments`/`search_segdir` remain,
because that is the index itself. `bucuresti*` still finds `București`, so normalization and prefix
search survive. The two numeric estimates still need their own rebuild comparison before anyone relies
on them.

## 5. Europe: source plan

The pinned source reproduces exactly. HEAD-only metadata check (no payload fetched), 2026-10-07:

| file | bytes | md5 sidecar | ranges |
| --- | --- | --- | --- |
| `europe-261006.osm.pbf` (the pin) | 35,145,100,444 | `cdc42a828731c185fed7b352908bd087` | `Accept-Ranges: bytes` |
| `europe-latest.osm.pbf` | 35,145,100,444 (same size, same Last-Modified) | — | `Accept-Ranges: bytes` |
| `russia-latest.osm.pbf` | 4,173,671,645 | — | `Accept-Ranges: bytes` |

**Both candidates can resume; neither has to start over.** `Accept-Ranges: bytes` is served, so a
partially fetched file continues from the byte already held and a network failure costs the missing
range, not the whole source. That applies to the pinned Europe file as much as to a country extract.

Two routes for excluding Russia:

* **A — fetch the pinned Europe file and clip Russia out.** Needs a Russia polygon, and the clipped
  output exists alongside the full download at peak.
* **B — fetch the per-country/region extracts and merge them into one coherent pbf**, so Russia simply
  never enters the merge. A failed part costs one country; the merged pbf's hash then defines the
  generation and every country subset inherits it.

Sizes here are **estimates, not a coherent merged source size.** Subtracting the separately compressed
`russia-latest` (4,173,671,645 B) from `europe-261006` (35,145,100,444 B) gives ~30,971,428,799 B, but
the Europe file contains only Russia's European part while `russia-latest` covers all of it, so the
subtraction over-removes and the remainder is **under**-estimated; the parts are independent downloads
with their own compression, and the sum of the extracted parts is not the size of their coherent merge.
Country extracts also **overlap** one another and contain features that cross political borders, so they
must be **pinned to one shared timestamp** before merging, or the merge silently blends two snapshot
dates.

**Omitting the Russia download does not prove every final feature is outside Russia.** It proves only
that no Russia extract was merged. Border-crossing ways and relations, and Geofabrik's own clipping of
each country extract at political borders, still have to be checked — the same seam question the
RO/HU/RS union already had to answer — before any claim about coverage at the Russian border is made.

Record `(url, bytes, md5, Last-Modified)` for every fetched part in a source manifest, re-verify before
the build, and note that `-latest` moves daily: a build is only reproducible against the pinned dated
name (`europe-261006`), which today still serves byte-identically.

## 6. Europe: projection and working-space limits

Scaling from measured transforms (union and Romania, the only multi-country and single-country builds
we hold):

| measured ratio | Romania | union |
| --- | --- | --- |
| routing tiles ÷ pbf | 1.19 | 1.14 |
| display ÷ pbf | 1.46 | 1.75 |
| installed ÷ pbf | 2.65 | 2.89 |
| transport ÷ installed | 0.467 | 0.455 |
| places per MB of pbf | 5,301 | 6,909 |
| display bytes per place | 275.8 | 253.9 |

Projected from the estimated ~31 GB of source, using the union ratios as the closer analogue — every
figure below inherits that estimate's uncertainty and is not a measurement:

| layer | projection | cross-check |
| --- | --- | --- |
| routing tiles | **35–37 GB** | 1.14–1.19 × source |
| display database | **45–54 GB** | 6,909 places/MB × 29,537 MiB ≈ 204M places × 254 B ≈ 52 GB |
| installed total | **82–90 GB** | 2.65–2.89 × source |
| download | **37–42 GB** | 45–47% of installed |

**The display layer is the binding constraint, and it is not shippable.** A single ~50 GB display
database cannot be built here (see below) and would never fit a phone, so the display layer has to be
built and shipped per region from the same coherent generation — which is also what makes the search
savings worth pursuing, once they are app-compatible (17.7% measured on Romania, 22.7% on the union).

Peak working space for a coherent build of the whole continent (route B):

| piece | size |
| --- | --- |
| per-country downloads, held until the merge completes | ~31 GB (estimated) |
| merged coherent pbf (the generation identity) | ~31 GB (estimate; not the sum of the parts) |
| sort/orientation scratch, if the pipeline sorts the merge output | up to ~31 GB (unmeasured) |
| tile output | 35–37 GB |
| **disk peak** | **~98 GB, up to ~129 GB with sort scratch**, plus any display DB being written |

Free space: **C: 67 GB, D: 87 GB** at the time of writing. Neither drive holds that peak alone, so the
build fits only distributed across both or on a dedicated volume. This is the limit the plan has to
respect, and the reason no Europe build is started from here.

Memory: 31.8 GiB installed. Valhalla's build cache is bounded (`max_cache_size: 1000000000`,
`max_concurrent_reader_users: 1`) and the RO/HU/RS build of an 852 MB pbf peaked around 4 GB, so that
side is cache- and writer-bound rather than linear in the input — but a 36× larger source is
**untested**, so a multi-GB multi-country pilot must measure it first.

The display compiler is **not** bounded that way: `compile_display.py` calls
`Handler().apply_file(str(source), locations=True, idx='flex_mem')`, so it builds a node-location index
in memory for the whole source. Streaming parsing does not make that index small — its footprint scales
with the node count, which for a continent is a real memory requirement and a real unknown. Treat the
continental display compile as bounded by that index until a pilot measures it, not by the write batch.

## 7. Country and region subsets

* The partition proof already establishes the semantics to reuse: per-country ownonly tiles plus a
  shared set, with removal of unowned tiles (163 removed, 861/861 retained on the RO/HU/RS probe).
* Every subset must carry the **same generation id** as the coherent build it came from. Matching
  overlap hashes alone do not certify cross-border edge and hierarchy adjacency; a subset from a
  different build does not compose.
* Sources must be pinned to **one shared timestamp** — extracts overlap, so mixing snapshot dates
  produces a merge no generation id can describe.
* An all-Europe download is a **manifest of links to per-country packages**, never a second copy of the
  tiles, and removal must keep the installed countries working.
* Routing must never activate without matching display, search and restriction evidence compiled from
  the same generation, and that evidence check must stay able to fail.
* 32-bit devices may prefer a native `tile_dir` over a monolithic union TAR — a design point to test,
  not a way around the TAR integrity checks.

### Country sizes for the download menu

Measured ratios give an honest estimate before a country is built: transport ≈ 1.2–1.35 × pbf bytes.
Checked against the one country where both numbers exist: Romania's pbf is 329,689,991 B, predicted
transport 396–445 MB, actual **407,495,153 B**. Tiles ≈ 1.14–1.19 × pbf, display ≈ 1.46–1.75 × pbf.
These are estimates and should be shown as such until a country has been built and audited.

## 8. What this does not do

* No geography pruned, no row dropped, no restriction relaxed to reach a size target.
* Nothing applied. The search savings need a **display compiler** change *and* an **app-side**
  compatibility change, because the composed-country evidence check reads the FTS `text` column that
  these formats remove (§4). Android API 26 composed-country evidence tests must pass before either
  change ships. Neither change is mine.
* "Installed" is the payload byte count as the package stores it; the app's real on-device footprint
  depends on the extractor and is confirmed by the app side, not here.
* The search-text identity result is a stepped sample by default; run `--identity-full` on a region
  before shipping a change that relies on it.
* Europe sizes are extrapolations, and the source size itself is an estimate rather than a measured
  coherent merge (§5). Density differs by region — Hungary's 326 MB pbf produces 256 tiles at 354 MiB
  while Romania's 330 MB produces 636 tiles at 371 MiB — so per-country numbers must be measured, not
  assumed.
