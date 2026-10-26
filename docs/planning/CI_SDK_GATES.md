# Gates: compile SDK and CI provisioning reconciliation

OWNS: .github/workflows/build.yml, tools/bootstrap_windows.py, README.md, ROADMAP.md, CONTINUATION.md, docs/CI-SDK-VERIFICATION-2026-10-25.md, docs/planning/progress/codex.json

Scope: Align CI and Windows bootstrap with the existing compileSdk 36, build-tools 36.0.0 and Gradle 9.6.0, preserving application target/version behavior and validating a fresh source snapshot.

- [x] C1: CI explicitly provisions and verifies the SDK components required by the current build; Windows bootstrap and build instructions agree with the wrapper.
  EVIDENCE: Reviewed workflow installs/checks platforms;android-36 and build-tools;36.0.0; bootstrap downloads wrapper-matching Gradle 9.6.0. Source-derived assertions reconcile compileSdk/buildTools/wrapper and bootstrap syntax parses successfully. README separates compile 36 from unchanged target 37.

- [x] C2: A fresh source snapshot passes routing tests, debug/test APK builds, lint and tooling tests using the documented environment.
  EVIDENCE: Git archive of dd5f291 plus only three reviewed source overlays; ignored fixtures/build outputs were not copied. Gradle exits 0 with 81 tasks executed. JUnit XML measures 205/0/0/0; tooling log measures 76 tests, no failures/skips; lint XML has 0 errors and 85 warnings. Both APKs and hashes are recorded in the Desktop RESULTS.json receipt.

- [x] C3: Documentation and live progress distinguish local build evidence from a hosted CI run, preserve concurrent DeepSeek changes and leave remaining A3 work explicit.
  EVIDENCE: CI-SDK-VERIFICATION-2026-10-25.md and continuation state that caches were reused and hosted CI/cold bootstrap/device installation were not run. A3 remains in progress; only Codex's lane is updated. DeepSeek's file and pre-existing FAST_MODEL_WORK deletion are not staged by this task.
