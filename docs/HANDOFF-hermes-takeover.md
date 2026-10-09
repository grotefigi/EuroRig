# Handoff: temporary Hermes takeover of EuroRig implementation

Written 2026-10-07 by Hermes, which took temporary implementation ownership when Codex reached its usage
limit. Codex's audit resumes after its weekly reset on 11 October 17:45 Bucharest. Everything below is
stated so that a later reader can check it rather than trust it.

## Where this work lives

- **Product repository:** `C:\Users\Administrator\Desktop\EuroRig` (package `org.eurorig.app`).
- **Branch:** `codex/coherent-qa-audit`, created off `main` at **0c108d5** with the three uncommitted
  files Codex left in place. `main` is untouched, nothing was force-pushed and nothing was reset.
- **Commits on the branch:** **bea4d56** (stage QA regions only when the target can hold them).
- **Git identity:** this repository had none configured, so a **repo-local** identity
  (`Hermes <hermes@localhost>`) was set for the takeover. Codex's existing commits are authored
  `grotefigi <65136412+grotefigi@users.noreply.github.com>`. Restore or amend if that attribution matters.
- **Research and map-build tree:** `C:\AI\EuroRig`, which is *not* the product. Its partition/QA tooling is
  what produced the inputs described below.

## What changed in the product (bea4d56)

Codex's private `--qa-region` adapter is carried forward unchanged in behaviour, with two missing-state
guards added. The adapter lets a dedicated emulator open a separate `routing.tar` and `display.sqlite`
without activating it or replacing the driver's selected map, and it restores the previous native and
display instances in a `finally` block.

1. **Storage preflight** (`tools/measure_corridors.py`). The package is staged to `/data/local/tmp`, copied
   into app-private files, and then extracted into graph tiles, so the target needs about three times the
   package size. Running out mid-copy leaves a truncated `routing.tar` that the app later reports as a
   corrupt map, so the runner now refuses before staging, naming the numbers.
2. **Installed-map integrity** (`tools/measure_corridors.py`). The driver's selected region is read from
   `shared_prefs/settings.xml` and its directory listing is compared before and after the run. The receipt
   gains `installed_region` and `installed_map_untouched`, so "the active map was left alone" is evidence
   rather than an assumption. It raises **after** writing the receipt, so a failure keeps its evidence.

The production APK is unchanged by these edits, and the runner still refuses physical device serials.

## Inputs, and why they are trustworthy

The QA compiler Codex stopped (PID 8264) had been reading a live tile tree that a proof fixture later
rewrote in place through a hardlink. The replacement reads an immutable copy:

- **Immutable QA input:** `C:\AI\EuroRig\qa\rohurs-clean-v1` — 1024 tiles, 925 MB, **1024/1024 copies
  verified against the manifest, every copy `st_nlink == 1`**.
- **Manifests:** `qa/rohurs-clean-v1.snapshot.json` (per-tile SHA-256) and `qa/QA_INPUT.json`.
- **Generation:** `39aa4a993db6f085`.
- **Merged extract:** `packs/rohurs-merged-sorted.osm.pbf`, 852,517,337 bytes,
  SHA-256 `e8d42b0a20b47522bbec305bf043f048e3bd9c72aaab70a91a83fd47940d2cb3`.
- **Source extracts:** romania `5af1bc2e85cfd6df…` (329,689,991 B), hungary `a602f2d47a114faa…`,
  serbia `1c66caf0c13355c6…`, all with `osmosis_replication_timestamp` `2026-10-04T20:20:21Z`.
- **Engine:** `pyvalhalla 3.6.3`, `osmium 4.3.1`.

The builder refuses to start unless the inputs verify, re-verifies them **after** the build, and writes
`input_unchanged` into the provenance. Its first attempt was refused by design
(`[before] snapshot changed/tainted (1024): HARDLINKED`) because a proof run was holding hardlinks to the
snapshot; that refusal is kept as
`analysis/private/rohurs-qa-snapshot-attempt1-GUARD-REFUSED.log`.

## Running and pending

- **QA package build:** `analysis/private/prepare-coherent-qa-snapshot.py`, output
  `analysis/private/rohurs-qa-20261008-snapshot/`, log `analysis/private/rohurs-qa-snapshot.log`.
  Started on the verified snapshot; display compilation in progress at the time of writing.
- **Order diagnosis:** `merge_header_fix.py --check-only` on the two same-content merges, log
  `C:\AI\EuroRig\cache\order_v2.log`.
- **Not yet run:** the full partition proof against the clean data (it must follow the QA build, because it
  hardlinks from the same snapshot and would otherwise taint it mid-build), the emulator-5556 Android
  restriction audit, and multi-country download integration.

## Verification status, stated plainly

**Verified on the host, by myself and independently by Codex:**

- The clean coherent build serves every probe point, including the two that exposed the old corruption
  (`szeged_HU` 14.1 m `Széchenyi tér`, `debrecen_HU` 14.8 m), and matches the Hungary-only build to the
  decimetre on shared points.
- Codex ran 12 host queries (3 corridors x 4 modes, shipping request shapes, 32 MiB cache, pinned 3.6.3) on
  this set: all routed without warnings. The corrupt set failed both border corridors in all four modes.
- Cross-border routing on the clean set: `galati_szeged` 810.416 km, `galati_vrsac` 773.921 km.
- `seam_mutation_test.py` passes 4/4: unmutated exit 0; `expect=no_route`-but-routed exit 1;
  `expect=route`-but-refused exit 1; a one-country set routing a two-country corridor exit 1.
- `check_qa_package.py` exists for the gate-2 pre-check and has not yet had a package to run against.

**Not verified, and not claimed:** anything on a device from this work, real-road safety, live restrictions,
complete national law, ADR behaviour, spoken output, or European coverage. `Europe-complete` remains false.
The user's tablet was not touched, and no map, preference or installed region was replaced.

## Next steps, in order

```bash
# 1. Wait for the QA build, then check the package on the host (gate 2 pre-check)
"C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe" \
  C:/AI/EuroRig/handoff/codex-merge/partition/check_qa_package.py \
  C:/Users/Administrator/Desktop/EuroRig/analysis/private/rohurs-qa-20261008-snapshot

# 2. Android post-audit on a dedicated emulator (never the tablet).
#    Use emulator-5558: the package needs roughly 5.7 GB peak (2.4 GB staged + 2.4 GB copied into
#    app-private + 0.9 GB extracted) and the pre-existing emulators have 3.0 GB (EuroRig37/5556) and
#    4.5 GB (EuroRig26Country/5554) free — both below the runner's own storage preflight, which refuses
#    rather than half-installing. EuroRigQA37 (5558) is API 37 with a 16 GB data partition, 14.7 GB free,
#    the app installed once so run-as can reach its private dirs.
python tools/measure_corridors.py analysis/private/romania-native-qa3.json \
  --device emulator-5558 --qa-region analysis/private/rohurs-qa-20261008-snapshot \
  --output analysis/private/coherent-qa-api37.json
python tools/check_corridors.py analysis/private/coherent-qa-api37.json

# 3. Tear down the proof install farm, then re-run the full partition proof on the same snapshot
rm -rf C:/AI/EuroRig/tools/valhalla/proof
"C:/AI/EuroRig/capture/eta_validation/vhvenv/Scripts/python.exe" \
  C:/AI/EuroRig/handoff/codex-merge/partition/run_proof.py
```

`analysis/private/romania-native-qa3.json` is the QA corpus: every coordinate from
`tools/benchmarks/romania-native.json` preserved, the two cross-border corridors changed to expect a route
because the package carries all three countries, the airport centroid still expected to be rejected, and the
full 40 t / 11.5 t axle / 4 m / 2.55 m / 16.5 m / five-axle / 80 km/h profile, avoidances and four modes
unchanged.

## Boundaries observed

No credentials were read, written or reproduced. No proprietary Eurowag bytes are included. The withdrawn
`eu_truck_restrictions` and `fcd_polygons` contributions remain withdrawn. Private receipts stay under
ignored `analysis/private/`.

## Findings added after the first handoff (Hermes, 2026-10-07)

**The corruption's cause is established: the file header — and it is 70 bytes of it.** Three instruments
returned negatives first: the two merges carry identical objects; both are genuinely type-then-id sorted
(identical counts 106,729,744 / 13,552,066 / 262,565 and zero order violations); and their OSMData blocks
are byte-identical. Measured section by section:

| file | size | header blob | data section | sha256 |
|---|---|---|---|---|
| `rohurs-merged-v2` (corrupt) | 852,517,407 | **203** | 852,517,204 | `9512301e6f4e6788…` |
| `rohurs-merged-sorted` (clean) | 852,517,337 | **133** | 852,517,204 | `e8d42b0a20b47522…` |
| spliced (v2 data + clean header) | 852,517,337 | 133 | 852,517,204 | `e8d42b0a20b47522…` |

Same data, 70 bytes of header, and one builds corrupt tiles while the other does not. The corrupt header
carries a `bbox` (16.108446, 42.229789, 30.278960, 48.589212 — decodes correctly and contains both failing
points), an `osmosis_replication_timestamp`, and a `timestamp=` optional feature. **Both declare
`Sort.Type_then_ID`**, so the missing-sorting-declaration theory was never the difference between these two
files. The spliced file is byte-identical to the clean merge, which is what makes it a control rather than a
third variant. `tiles_header_probe` (v2 data + clean header) serves Szeged at 14.1 m `Széchenyi tér` and
Debrecen at 14.8 m, where the v2 header gives `NodeInfo index out of bounds`.

**Which of the three fields is causal is still untested.** The isolation attempt manufactured a PBF whose
header osmium rejects outright (`unknown pbf field type exception`) — a fault in the hand encoder, not
evidence about the bbox — and the builder then aborted with exit 127 and zero tiles. The bad artifact was
deleted so nothing measures it; `C:\AI\EuroRig\cache\build_plusbbox.log` records the attempt. To finish: set
the box via pyosmium's `Header.add_box` and let the writer encode it, or fix the encoder and re-run
`header_field_isolation.py`.

**Still outstanding:** `run_proof.py` has not yet been re-run against the snapshot with all four harness
fixes (unlink-before-copy, source self-check, snapshot pinning, tar extract-then-route). That is the gate-1
deliverable.

**Device provisioning.** The gate-2 audit runs on a purpose-built AVD, `EuroRigQA37` (`emulator-5558`, API
37, 16 GB data partition, 14.7 GB free). Codex's emulators were neither modified nor cleared. The app is
installed once with its private dirs present so `run-as` works; `shared_prefs` is legitimately empty because
no preference has been written yet.

**Runner fix in this session.** `tools/measure_corridors.py` now reads `shared_prefs/settings.xml` with
`check=False` (a fresh install is a valid state; the preservation check then compares empty against empty),
on top of the earlier storage preflight and `installed_map_untouched` receipt field.

**Outside the product tree.** A multi-agent team plugin (Agora) and a UI/UX design skill set were installed
into Hermes, with that design team pointed at a scratch workdir (`C:\AI\EuroRig\agora-scratch`, brief in
`BRIEF.md`) explicitly barred from writing into the product tree. Nothing in this section touches the app,
its data, or any device.
