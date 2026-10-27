# Compile SDK and CI reconciliation — 2026-10-25

Historical report. [The 26 October A3 report](A3-BUILD-2026-10-26.md) supersedes current SDK identity, cold-bootstrap, test-count and CI-status claims. This older evidence did not bind the platform JAR hash.

The app compiles with SDK 36 and build-tools 36.0.0, while its existing target remains API 37. CI previously checked for `platforms/android-37.0/android.jar`; the Windows bootstrap downloaded Gradle 8.13 even though the checked-in wrapper and PowerShell fallback use 9.6.0.

The workflow now explicitly installs `platforms;android-36` and `build-tools;36.0.0`, then verifies those components. The [official SDK manager reference](https://developer.android.com/tools/sdkmanager) documents the package argument syntax used. No automatic license-acceptance command was added. The bootstrap now selects Gradle 9.6.0 with its published checksum. README build instructions match the existing compile SDK and distinguish it from the target API. No app, target/version, signing configuration or installed-device state was changed.

## Fresh source verification

A `git archive` of `dd5f2913abbf4e2391c43ab8fb7c90bcacaa6cac`, with only the identified workflow/bootstrap/README working-file overlays, was extracted into a new build directory. No ignored map fixtures, local Gradle build products or project toolchain folders were copied. All three overlay hashes and the archive hash are recorded in the receipt. Existing external dependency caches and the configured JDK/SDK/Python environment were reused; this is not a cold toolchain/download test.

Command: `gradlew.bat :routing:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain`.

- Exit 0, `BUILD SUCCESSFUL in 1m 13s`; 81 actionable tasks executed.
- 205 Java tests, zero failures, errors or skips, measured from JUnit XML.
- Development and companion test APKs built; their sizes and SHA-256 values are recorded.
- Lint XML: zero errors/fatal issues, 85 warnings.
- Separate fresh-snapshot tooling command: 76 tests, no failures or skips, exit 0.
- Bootstrap syntax and SDK/Gradle agreement were checked without running its download/install path.

Environment: JDK 21 at the documented local path, SDK platform `android-36`, build-tools `36.0.0`, Python 3.12 with the existing `osmium==4.3.1`, and the host's short JDK socket directory. No environment secrets were dumped. No APK was installed.

Detailed source hashes, commands/logs, test/lint XML and APK hashes are under `C:/Users/Administrator/Desktop/EuroRig-FastModel-Work/Codex-CI-SDK-2026-10-25/`, particularly `SOURCE.json`, `RESULTS.json`, `fresh-build.log` and `fresh-tooling-tests.log`.

## Remaining acceptance

No remote workflow was dispatched or observed, and no changes were pushed by this task. The Ubuntu runner's SDK installation step and a cold Windows bootstrap/download still need actual execution evidence before claiming those environments pass. Existing lint/platform issues remain subject to triage; this task does not suppress warnings or upgrade app dependencies. A3 therefore remains in progress. DeepSeek's concurrent lane changes and the pre-existing worker-plan deletion were preserved.
