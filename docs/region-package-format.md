# Region package format

The `.eurorig` package is the unit a driver downloads. This documents what is inside each format, the
per-tile evidence index, and the invariants the packager and app enforce. The app consumes formats 1,
2 and format 3 with version-1 canonical TARs or verified version-2 tile directories.
Country-set activation is not enabled.

Written from `tools/package_region.py` (packager) and `tools/check_qa_package.py` (host gate).

## Entries per format

| format | when | entries |
|---|---|---|
1 | EuroPack v1 display graph | `manifest.json`, `routing.tar`, `display.europack` |
2 | SQLite display/search database | `manifest.json`, `routing.tar`, `display.sqlite` |
3 | as format 2, plus per-tile evidence | `manifest.json`, `routing.tar`, `display.sqlite`, `tiles.sqlite` |

Format is chosen by inputs, never by a flag: an indexed SQLite display gives format 2, a EuroPack
display gives format 1, and format 3 additionally requires **both** `--generation` (the coherent
build's `generation.json`) and `--country` (an ISO2 code in upper case). Giving one without the other
is refused, because neither alone means anything.

The manifest is capped at **65536 bytes**, which the app enforces. Per-tile evidence cannot live in the
manifest for a whole country, so it lives in its own hashed file, `tiles.sqlite`.

## Tile paths

A tile path is the canonical Valhalla graph path: a level digit **0, 1 or 2**, one or more three-digit
groups, then `.gph`, and at most 64 characters. Real coherent builds use both shapes:

- level 0 and 1 with two groups — `0/003/019.gph`, `1/048/082.gph`
- level 2 with three groups — `2/000/761/203.gph` (941 of the 1024 tiles in the QA snapshot)

Anything else is refused rather than normalised: another level digit, a group that is not exactly three
digits, an absolute path, a backslash, a `..` component, a missing `.gph`, or a path over 64 characters.
A pattern that only allowed two groups would refuse most of a real country, which is why the shapes
above are covered by tests directly.

## `tiles.sqlite`

`PRAGMA user_version = 1`, exactly these two tables, exactly these columns:

```sql
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER) WITHOUT ROWID;
```

`metadata` carries `generation_id`, `country`, and `tiles` (the row count, as text). `tiles` carries one
row per tile the package's tar actually contains: `path` is the canonical path, `sha256` is the tile's
hash as 64 lowercase hex, `size` its byte count.

`generation_id` is **16 lowercase hex** characters, the form `generation.py` produces. Every digest —
in the index and in the generation manifest — is **64 lowercase hex**; a 64-character string that is not
hexadecimal is refused, not accepted.

## Manifest additions

### Experimental compressed index version 2

`--experimental-compressed` keeps package format 3 and writes deterministic gzip-6
tiles named `<canonical path>.gz`. It requires `--generation` and `--country`.
The Android app verifies this version, extracts a fresh private tile directory and
removes the duplicate TAR before activation. Keep development packages out of driver
catalogues until whole-country installation, reopening and routing are verified.

Version 2 keeps the metadata table and canonical identity, extending only `tiles`:

```sql
CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER,
                   compressed_sha256 TEXT, compressed_size INTEGER) WITHOUT ROWID;
```

`sha256`/`size` describe decoded canonical bytes. The additional pair describes
stored gzip bytes; both NULL means raw-only. The host gate accepts versions 1 and 2,
requires each pair to be both present or both NULL, verifies stored hash/size,
and caps decoding at the declared canonical size plus one byte before comparing
canonical identity. A declared gzip payload cannot fall back to raw bytes.
Truncated gzip, symlinks, duplicate canonical paths and country/generation mismatches
are refused. Generation binding to the coherent build still requires `--generation`.

## Manifest identity

Format 3 adds `country` and `generation_id` to the manifest, and `sha256` covers all three payloads
including `tiles.sqlite`. The **`generation_id` is the coherent build's, unchanged**: a country package
is a subset of one coherent build, not a new generation. Every package a device composes together must
carry the same generation id, and comparing shared tile lists by key is **not** a substitute — it cannot
certify cross-build edge and hierarchy adjacency.

## What the packager enforces (before writing anything)

1. `--generation` and `--country` come together; the country is ISO2 in upper case.
2. The generation manifest is a JSON object with a 16-hex `generation_id` and a 64-hex `sha256` for
   every tile, keyed by canonical paths. Two spellings of the same canonical path — `2/000/761/203.gph`
   and `./2/000/761/203.gph` — are refused rather than merged, because merging would let one claim
   silently overwrite the other.
3. Every tile in the tar is **streamed and hashed** against its claim. A tile the manifest does not
   claim, a hash mismatch, a tile shorter than its tar header, a duplicate path, a `.gph` member that is
   not a regular file (symlink, hardlink, device), and an unsafe or non-canonical name each refuse the
   package.
4. Only rows for tiles the tar actually contains are indexed; tiles the coherent build has for other
   countries are not copied in.
5. The coverage box, if given, validates before the archive is written, so a refused box leaves no file.
6. The manifest is serialised and measured against the 65536-byte cap; over it, the package is refused
   rather than shipped unreadable.
7. The staging index is created with an unpredictable name inside the destination's own directory and
   removed on every path, so it can never overwrite or delete a file the caller happens to own.
8. The output may not be the tar, the display, or the generation manifest.

## Host gate

```
python tools/check_qa_package.py RO-HU-RS-QA.eurorig
python tools/check_qa_package.py RO-HU-RS-QA.eurorig --generation <coherent generation.json> --out gate.json
```

It refuses on: an entry listed twice, any directory entry, an entry set that does not match the declared
format; a missing, oversized, unparseable or non-object manifest; a wrong `engine` or `native_version`;
a payload that does not hash to its recorded value, or a declared/actual payload mismatch; an invalid
coverage box; for format 3 a wrong country or `generation_id` syntax, a wrong `user_version`, a schema
that is not the two tables above verbatim, missing `metadata` keys, a malformed digest in the index,
index rows that disagree with the tar (missing, extra, duplicate, wrong hash or size), or a row count
differing from `metadata.tiles`.

The index metadata is compared with the manifest **with or without** `--generation`: an index that
disagrees with the manifest it ships beside is refused either way. With `--generation` the gate
additionally re-checks, independently of the packager, that the package's generation id matches the
coherent manifest and that every tar tile hashes to the coherent claim.

Malformed JSON, an unexpected JSON type, a sqlite error and a bad tar are reported as a **FAIL receipt
with exit 1**, never as an uncaught traceback. Exit 0 is PASS, 1 is a refusal naming the check; `--out`
writes the verdict as JSON, and a receipt that cannot be written does not change the verdict.

## Not true yet

- Country-set installation and full-country compressed distribution are not verified.
  The host tools and app consume version-2 indices. Canonical version-1 indices are checked against
  the exact schema, manifest country/generation, declared count and every TAR tile's bytes/hash before
  activation. Failed imports preserve the old map. The index is verified again when reopening it;
  a missing or changed installed index is refused.
- `NativeRouter` has a tested directory adapter used by verified version-2 imports.
  Legacy and canonical version-1 imports keep the TAR adapter. Country-set installation is unfinished.
- Composition must not assume per-country `ATTACH`: SQLite's attached-database limit is finite and
  cannot span all of Europe. Bounded connections or merged queries are the alternative to test.
- Cleanup and rollback must never leave an installed country unreachable: removal preserves the
  previous active set until the new one verifies.

## Structural equivalence, not native costing parity

The host proof runs the same tile sets through `actorcfg.py`, whose configuration differs from the app's
in at least two ways that bear on routing: hard exclusions are **false** on the host and **true** in the
app, and the cache is **1 GB** on the host against **32 MB** in the app. A host corridor result therefore
establishes that the tile set routes the corridor **structurally** — the same endpoints, the same
crossings, a sane distance — and does not establish that native costing matches the device exactly.
On-device corridor receipts are the only evidence for device behaviour, and they are kept separately.
