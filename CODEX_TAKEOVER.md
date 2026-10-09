# EuroRig handoff for a new Codex account and PC

Use the copy-paste prompt at the end of this document after cloning the current development branch. This file is public project guidance. It intentionally excludes private device receipts, driver preferences, screenshots, GPS traces, proprietary APK material, credentials, and local SDK paths.

## Get the project running on Windows

The public repository is [grotefigi/EuroRig](https://github.com/grotefigi/EuroRig). The current development work is on `codex/coherent-qa-audit`; PR [#1](https://github.com/grotefigi/EuroRig/pull/1) is open against `main`. Do not assume `main` contains this development work. Clone the development branch:

```powershell
git clone --branch codex/coherent-qa-audit https://github.com/grotefigi/EuroRig.git
Set-Location EuroRig
git status --short --branch
```

Install JDK 21, Android SDK platform 37.0, Android build-tools 36.0.0, Android platform-tools/ADB, Git, Python 3.12, and (optionally) GitHub CLI. Android Studio can install the SDK components. Create the ignored `local.properties` file for this PC with its own SDK path; never commit it. The Gradle wrapper pins Gradle 9.6.0 and its checksum. Android Gradle Plugin is pinned in the project.

Run the existing checks and debug build:

```powershell
python --version
java -version
.\gradlew.bat :routing:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python -m unittest discover -s tools -p 'test_*.py'
```

The CI job uses Temurin JDK 21, Python 3.12 and `osmium==4.3.1` for display compiler tests. The repository's Windows helper is `tools/build.ps1`. For map compiler work only, use a dedicated Python 3.12 virtual environment and `tools/map-requirements.txt`.

## State at handoff

At the baseline for this guide, the public development branch was `c0eefc85cf5affbc8782fce20eef13e9b7148366`; both exact-commit CI jobs passed (runs `37905053237` and `37905047617`). The handoff guide adds a later documentation commit, so check GitHub Actions for that newer commit before reporting its CI status.

The app is an Android 8+/API 26+ offline truck-navigation development build. The APK ships without maps; a user downloads or imports a country package, then routing and search work locally. The public catalogue currently offers Romania, while the all-Europe button reports unavailable coverage. No subscription, analytics, navigation server or network route fallback is required. The open PR is not a signed production release and the app has not passed road validation.

Verified development evidence recorded in `VERIFICATION.md` includes 58 Java checks, 158 Python checks, app/test APK assembly and lint; an Android 8 emulator check of light/dark theme rendering and persistence, camera following, direction-arrow rendering and travelled-route removal; and prior Android 8/Android 14 device checks on earlier builds. The newest candidate was not installed on the driver's physical Tab S9. Emulator animation checks do not establish real GPS, driving, voice, low-end performance or road readiness. Always bind test claims to the exact source revision and APK/test APK hashes.

The main remaining work is a coherent Europe excluding Russia, independently validated country boundaries and cross-border truck routes, mapped height/weight/width/ADR enforcement, safe country-set update/removal/rollback, and a complete storage/peak-space measurement. The installed APK, Europe maps, retained archives and cache must fit within 20,000,000,000 bytes; no full-Europe build is approved until a measured peak-space plan meets that limit. The 35,145,100,444-byte source PBF is not in GitHub. If it is transferred from the previous PC, verify SHA-256 `adf0f6fc31719a1184291ace7c57908aede5ea253ff18d029abd12071591d64a` and exact size before use. It includes Russia, which the final map pipeline must exclude while preserving border connectors. Do not upload this huge source or private pilot packages to GitHub.

Use `README.md`, `ROADMAP.md`, `VERIFICATION.md`, `AGENTS.md`, `docs/DEVICE_REQUIREMENTS.md`, `docs/map-storage.md`, and `docs/region-package-format.md` as the project sources of truth. `analysis/private/` is intentionally ignored; do not copy private screenshots, tablet preferences, real GPS evidence, or raw logs into public issues or commits. Map data is OpenStreetMap-derived and carries ODbL attribution requirements. Do not copy Eurowag/RoadLords code, map data, icons or other proprietary assets.

The API 26 candidate's code came from tree `de3acc26923524fcb3eafbba4be6f065873f16df`. Its debug APK was 37,997,939 bytes, SHA-256 `7254946ea77ce66c10be35e12cc2e33adf1ed9f3582bd275ccebe70b656ab6f0`; its Android test APK was 2,443,597 bytes, SHA-256 `a7d5a55f851a3991353e4ccaab922c7abbd2bc1bb8a72df8480322a53d8ffea6`. These local artifacts are not GitHub release assets. The public source later received a verification-documentation-only edit before this handoff; rebuild locally and record the hashes before using a new APK.

## Codex skills to enable in the new Codex account

The following 21 skills were available in the source Codex session. Install or enable all of them in the new Codex account; if a skill is already bundled, verify it is available instead of duplicating it. This list is Codex-only. Hermes has its own separate installed-skill inventory: the owner should transfer the private `HERMES_SKILLS_TRANSFER.md` file separately and restore those skills in Hermes. That private inventory is intentionally not stored in this public repository. If it is not available on the new PC, ask the owner to transfer it; do not claim Hermes skills were restored without checking Hermes itself.

1. `imagegen` — bundled image generation.
2. `openai-docs` — official OpenAI product and API documentation.
3. `skill-creator` — create or update Codex skills.
4. `skill-installer` — install skills from supported sources.
5. `agent-reach` — web and platform research; [Panniantong/Agent-Reach](https://github.com/Panniantong/Agent-Reach).
6. `caveman` — concise chat replies; [JuliusBrussee/caveman](https://github.com/JuliusBrussee/caveman).
7. `computer-use` — first-party computer-use integration; enable its Codex plugin if available.
8. `cua-driver` — native GUI automation; [trycua/cua](https://github.com/trycua/cua).
9. `documents` — Word/document creation and review.
10. `open-code-review` — AI-assisted code review; [alibaba/open-code-review](https://github.com/alibaba/open-code-review).
11. `open-code-review-delegate` — host-led review using Open Code Review's deterministic tooling.
12. `pdf` — PDF reading, creation and visual verification.
13. `plugin-management` — discover and manage Codex plugins.
14. `ponytail` — minimal, YAGNI-focused coding; [DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail).
15. `presentations` — presentation creation and review.
16. `spreadsheets` — spreadsheet creation and analysis.
17. `excel-live-control` — control a connected live Excel workbook.
18. `template-creator` — create reusable artifact templates.
19. `ui-ux-pro-max` — UI design and accessibility guidance; [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill).
20. `visualize` — create explanatory charts and interactive visualizations.
21. `graphify` — build/query a knowledge graph for codebase structure; [Graphify-Labs/graphify](https://github.com/Graphify-Labs/graphify).

Also install/enable the project-requested `compaction` skill/tool from [philipppohlmann/compaction](https://github.com/philipppohlmann/compaction) when its Codex integration is supported. Keep its generated state private and do not claim token savings without measured receipts. The REA repository ([morluto/rea](https://github.com/morluto/rea)) is a reverse-engineering reference tool, not a Codex skill; use it only for lawful analysis of material the user owns or is authorized to inspect.

The six listed repositories are upstream installation sources, not proof that the skill is installed. Inspect each `SKILL.md` and setup instructions before installing dependencies. Keep credentials in each app's secure sign-in flow; never put tokens or signing keys in prompts, repo files, terminals that may be shared, or Git commits.

## Copy-paste prompt for the new Codex account

```text
Continue the EuroRig project from this public repository. I am moving to a new Codex account and PC. First read CODEX_TAKEOVER.md, AGENTS.md, README.md, ROADMAP.md, VERIFICATION.md, CONTRIBUTING.md, and the device/storage/package-format documentation it references. This guide's initial commit and CI state may have advanced; inspect the current branch, open PR, exact HEAD, GitHub Actions, current code and tests before making claims.

Set up this PC for Windows Android development using JDK 21, Android SDK platform 37.0, build-tools 36.0.0, platform-tools/ADB, Python 3.12 and the included Gradle 9.6.0 wrapper. Configure only this PC's ignored local.properties. Run the project's existing host checks, Android app/test APK build and lint, then report exact results and artifact hashes.

Install or enable every Codex skill listed in CODEX_TAKEOVER.md, plus compaction when its Codex integration is supported, in this Codex account. Use bundled skills when already available. Inspect upstream SKILL.md/setup instructions; report every installed item and any platform limitation. Do not claim a skill or integration is installed without verifying it.

Restore Hermes separately using the owner's private `HERMES_SKILLS_TRANSFER.md` inventory. Install/restore those skill folders in Hermes (use Hermes Skills Hub's “Add to this Agent” for available catalog skills, and a trusted transfer/backup for custom skills), then verify the installed skills in Hermes' UI/profile. Keep the Codex and Hermes inventories separate. Never copy Hermes credentials, provider configuration, session history or secrets, and do not publish the private Hermes inventory or skill contents. If the inventory is missing from this PC, ask the owner to transfer it before claiming Hermes setup is complete.

Continue toward a free, fully local Android 8+ truck-navigation app for Europe excluding Russia. Keep routing/search on device after country maps are installed. Do not claim the app is road-ready: coherent Europe data, geographic cross-border routing, source-backed truck/ADR restrictions, storage under 20,000,000,000 bytes, update/removal rollback, real GPS/driving/voice, old-device performance, lifecycle checks and a signed public release remain gates. The pinned 35,145,100,444-byte Europe PBF is not in GitHub; use a transferred copy only after verifying the exact size and SHA-256 stated above. Do not launch a continent build before measuring temporary peak space and the complete installed footprint against the 20 GB cap.

Preserve the driver's physical Tab S9 maps, raw preferences, endpoints and app state. Use a dedicated emulator for tests that clear app data. Do not replace the tablet's maps or clear its data. Never publish screenshots, private tablet settings, GPS/trip history, home/depot/delivery locations, private map packages, credentials or signing keys. Keep all `analysis/private/` data private. Use only original EuroRig code and properly licensed dependencies; do not copy proprietary Eurowag/RoadLords assets or implementation.

For coding, follow Ponytail in full mode and the repository's AGENTS.md. Research unfamiliar map/routing behavior against official documentation and licensed open-source projects. Reuse the project's native Valhalla, SQLite and Android architecture unless measured evidence supports a change. Make the smallest safe change, preserve truck restriction checks in every routing mode, and run the relevant existing checks. Keep development in a branch/PR; do not merge or sign/publish a production release without the owner's explicit request. Never present synthetic fixtures, emulator smoke checks or a source download as real cross-border/road validation.

First identify one bounded, high-value release blocker and finish the implementation plus its verification. Provide concrete files changed, exact source and APK/test APK hashes, test outcomes including failures, remaining blockers, and any required device action. Do not stop at a plan or at repeated status checks.
```
