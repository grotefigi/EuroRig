# A1 preservation and recovery — 26 October 2026

Status: **complete for the current checkpoint**. Three acceptance gates pass, with zero unmet gates. Independent review verified the restored source and current APK/map hashes. Historical reports remain preserved and unaccepted; fresh, fully bound current receipts supersede them for A1 acceptance.

## Accepted current checkpoint

- Exact tested source snapshot: `7aa7c24cbef9edd6ad7e96e19f0147838a5e2e1c`. All 336 captured files restore with matching SHA-256 values; an injected corruption is detected. The working branch, HEAD and index remain unchanged.
- The roadmap's approved checkpoint `5101f9dd9c7e11791df55362282419b278888f05` is independently preserved and restored. Both source trees use complete root snapshots in a self-contained Git bundle. The older checkout has shallow history with a missing parent; its original bundle and patch are retained without claiming complete history traversal.
- Four direct Preview suites pass on Tab S9 (`SM-X716B`, Android 16): profile/ADR, correctness, UI routing and importer refusal/retention. Every fresh receipt identifies the preserved source commit, exact app/test APKs, routing/display map hashes, device/OS, private driver-profile input hash and synthetic truck-fixture hash. Raw instrumentation logs are saved and hashed.
- Original `.dev`, Preview and test APKs are copied into the preservation set. Their signing certificates are verified; Preview and its tests share a certificate, distinct from the original app. The build uses genuine Android API 36, whose `android.jar` SHA-256 is `d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`.
- The three baseline map backups match independently checked sizes and hashes. Fresh copies of the active region are bound to device hashes before and after testing. The preserved detail map is retained too.
- Original-app and Preview preferences are backed up privately. The Preview snapshot restores byte-for-byte in separate device test storage. Live driver preferences, installed map data and original-app APK/update time remain unchanged.
- Six negative receipt controls detect altered hashes, traversal, boolean sizes, missing roles, duplicate JSON keys and nonfinite JSON. The accepted current receipt, recovery archives and private inputs remain in ignored local evidence.

This accepts preservation, evidence binding and recovery. It does not establish country-wide restriction completeness, road validation or completion of later navigation packages.

## Source and recovery

- Project branch at capture: `codex/eurorig-ui-routing-fixes`; HEAD `09a348df2098337a04970a8350b16365577be0f0`. The roadmap's pinned source checkpoint is retained separately.
- A verified Git bundle, binary patch of all tracked worktree changes, and archive of every non-ignored untracked file are retained in ignored private preservation evidence. The bundle verification and reverse patch check both passed. Their SHA-256 values are retained privately.
- The patch preserves Codex and DeepSeek changes as found. The deleted `FAST_MODEL_WORK.md` and untracked MiniCPM planning files are included in the snapshot; no other lane was edited for this capture.

## Maps

Read-only copies were captured from the isolated preview on the Tab S9. The display database copy was byte-compared with the installed preview copy. The existing regional data is preserved in the same ignored directory.

- Detail MBTiles: 527,015,936 bytes; SHA-256 `a9938dc3e3401c21f6bc78bebc7dd50b7699efb6802420298b4a20d1a61a7484`.
- Regional routing archive: 516,413,440 bytes; SHA-256 `7f419236ecf3c9c052a6b70a1953d9ed6640733b747e44296906238596fd167a`.
- Regional display SQLite: 798,195,712 bytes; SHA-256 `8ffe21db72b6e4cf64a1cce4e53a971edab8e4d3a1489f503a5d195971a77b5f`; device-to-backup comparison passed.
- Region manifest and identifiers are retained in private preservation evidence.

## App identity, signing and settings

- Original `org.eurorig.app.dev` remains version `0.5.4-dev`/code 9 with its prior update timestamp. Its installed APK was copied read-only and its signing certificate fingerprint recorded privately.
- Isolated `org.eurorig.app.preview` remains version `0.5.4-dev`/code 9. Its signing certificate differs from `.dev`; the local debug key matches Preview. This confirms the normal build cannot silently replace the original app.
- Seven Preview preference files were captured in a private archive with individual hashes. Existing recovery receipt records no missing or changed files, endpoint match and unchanged `.dev` identity.
- Device model, Android build, APK hashes, certificate fingerprints and preference hashes are retained privately.

## Historical run receipt audit

The run receipts were checked independently against the supplied files and Git objects. `unknown` means no run-bound evidence was supplied; current preservation evidence does not fill historical gaps.

| Run | Source snapshot | App / test APK SHA-256 | Map hashes | Device / OS | Truck/load/ADR input | Result |
|---|---|---|---|---|---|---|
| Correctness | Base `600b6dd29ecf35d5adb38ab790bf3af051b00231`; not the tested tree. 0/10 supplied source hashes match the exact base-commit blobs; two files are absent there. No complete tree/patch snapshot. | `2ea94110817e593e8cfd1a6e021f47b844168fa1e11889f7adce33821de7b61a` / `0bd7a0039a518967022bf4d94c2b6e0f7224414c20e3fc719fadc3d4422e557f` | unknown | unknown | unknown | Java 211, Python 76, native correctness/profile/UI suites pass; 7 preference files restored. |
| Profile persistence | Base `17eda0e25aaf57e34885742317fdd75e9de71ee6`; not the tested tree. 0/4 supplied source hashes match exact base-commit blobs; one file is absent there. No complete tree/patch snapshot. | `93c235e784d5b30ebac23f7348842c1251a3c5f57fb00ea1b8fc5cbcecbe35cb` / `061ada2f6e13b6c37afdecb06a50250eb86b06af80810a0630ed45caa76fda48` | unknown | unknown | unknown | Java 211 and native correctness/profile/UI suites pass; 320 routing-field round trips, 51 malformed-field controls; 7 preference files restored. |
| Trip recovery diagnostic | unknown | `80c2c5e6baa3cc562030df247c02d8280d33289c35106d6b550cc85381e331f3` / `cdf3bb554df97e82ebeee8f4dc996640b78cffdfeaeac4ff004c7a7e631ed9cd` | unknown | unknown | unknown | Restore check reports no missing/different files, endpoints match, original app identity unchanged. Profiles and correctness suites pass; UI routing suite fails with `Reordering changes only the selected order` (`INSTRUMENTATION_CODE:1`). Keep this failed diagnostic distinct from the earlier passing runs. |
| UI routing | unknown; no run-bound source tree | `3b7730d1ba353612b37d77afad612e76303cf621b1fbfdbfe994285a67436ce8` / `2e00fca1b18e3d12c07c09ece2400c3b2a0e443b871a419b2dfe4e69e0b49c15` | Detail MBTiles `a9938dc3e3401c21f6bc78bebc7dd50b7699efb6802420298b4a20d1a61a7484`; routing/display hashes unknown | unknown | A matching profile is recorded, but no run-bound input hash is supplied | Native receipt reports country options, stop editor/reorder, equal-endpoint validation and map controls pass. Municipal restriction-source gate remains unresolved. |

The correctness and profile-persistence base commits cannot identify the tested source trees: the supplied per-file hashes are not a substitute for a complete tree hash or run-bound patch. Their map hashes, device/OS, and truck/load/ADR inputs remain unknown. The trip-recovery and UI-routing receipts also lack a source snapshot and complete run-bound context. The current map preservation hashes above are preservation evidence only; they were not copied into historical receipts as if they were run-bound.

## Historical gate reconciliation

The four older rows remain an audit of historical reports, with their unknown fields preserved. They are excluded from current acceptance. The new checkpoint above replaces the required current suite evidence with fully bound passing receipts, including the resolved reorder regression; it does not invent metadata for the old runs.

## Follow-up: reorder fix and local verification — 26 October 2026

- Updated the stop drag handle to launch one drag per gesture and suppress click handling after drag starts. `JourneyUi.java` SHA-256: `dcc2bf8542374071cad7587296503351117bc244a5fdd3dada0a32e6fff5d940`; `UiRoutingChecks.java` SHA-256: `6fc8eff3af6260226d88097529d200f1b26e72d92b8cbbb6734d4640448496bc`. Independent code review found no issue.
- Build input checkpoint: HEAD `09a348df2098337a04970a8350b16365577be0f0` plus the current tracked app, routing, maps and build configuration files. Their sorted path/content digest is `8ab7b3bb97b75b7182bcc0cfb8923e01393b497d0d95f165cccb802b774ddd16` (164 files). The build used `tools/preview.gradle` to keep package/storage isolated.
- `:routing:test --rerun-tasks` passed: 211 tests, 0 failures/errors/skips. `:app:assembleDebug` and `:app:assembleDebugAndroidTest` both built successfully.
- Isolated app APK (`org.eurorig.app.preview`) SHA-256: `3e46f181491aae174f932da5313f0429e73a4ce45e97f1a1d0dbffe123e6bf78`. Instrumentation APK (`org.eurorig.app.preview.test`) SHA-256: `415c8a563e72aa24b1ffeb49b43d62737fd150a25027036bef078daf65255afc`.
- Direct ADB instrumentation on the Tab S9 (`SM-X716B`, Android 16) passed `uiRoutingOnly`, `correctnessOnly` and `profilesOnly`. The UI suite covers independent country options, ten ordered stops, native editor/reorder, equal-endpoint validation and main-map controls; its preference-restore assertion passed. The original `.dev` package update time remained unchanged. The current map manifest, routing archive and display database hashes match the preserved map artifacts: `3bd4d28499f9506ebe6d0ff7f623df87eee3aff1b86ee94eb406d4ad992486d3`, `7f419236ecf3c9c052a6b70a1953d9ed6640733b747e44296906238596fd167a` and `8ffe21db72b6e4cf64a1cce4e53a971edab8e4d3a1489f503a5d195971a77b5f`.
- That earlier private receipt binds its source tree, both APKs, map artifacts, device/OS, truck-input snapshot, and three saved raw instrumentation output logs with SHA-256 hashes. At that point A1 remained in progress and accepted progress was 1/37. The accepted checkpoint at the top now provides the complete preservation and recovery evidence; missing historical fields remain unknown.
