# Gates: map controls, marker rendering and truck payload checks

OWNS: app/src/main/java/org/eurorig/app/MapControlMenu.java, app/src/main/java/org/eurorig/app/VehicleMarkerView.java, app/src/main/java/org/eurorig/app/Store.java, app/src/main/java/org/eurorig/app/MainActivity.java, app/src/main/java/org/eurorig/app/NavigationService.java, app/src/main/java/org/eurorig/app/NativeRouter.java, app/src/androidTest/java/org/eurorig/app/NativeCorrectnessChecks.java, app/src/androidTest/java/org/eurorig/app/NativeSmokeInstrumentation.java, app/src/androidTest/java/org/eurorig/app/ProfileRoutingChecks.java, routing/src/test/java/org/eurorig/routing/TruckMassTest.java, docs/CORRECTNESS-2026-10-25.md, docs/TRUCK-CAPABILITIES.md, docs/DS02-REVIEW-2026-10-25.md, ROADMAP.md, CONTINUATION.md, docs/planning/progress/codex.json

Scope: Remove unsafe click-fix pressure and drawing allocations while preserving native behavior, add independent truck payload/boundary evidence, and review the submitted DS02 claims against actual source.

- [x] N1: Touch, cancellation, drag, keyboard and accessibility activation trigger each menu action exactly once and preserve idle-collapse behavior.
  EVIDENCE: Shared native Button factory reviewed; correctnessOnly tablet instrumentation passes tap/cancel/outside-drag/keyboard/accessibility and held/cancel timer assertions. Native behavior is retained without synthetic clicks. See docs/CORRECTNESS-2026-10-25.md and private correctness-instrumentation.log.

- [x] N2: Vehicle-marker drawing avoids per-frame helper allocations and matches pre-change pixels on the available tablet.
  EVIDENCE: Source review confirms paths/varargs are constructed once and wheel iteration has no temporary array. Independent RGBA comparison is pixel-identical for all twelve original/final renders. Private marker-comparison.json records each case; DrawAllocation warning is removed.

- [x] N3: Native request tests preserve exact dimensions/loaded mass/axles/preferences and refuse above-limit payloads in every mode; model/permitted-mass boundary tests pass.
  EVIDENCE: Independent Android JSON expectations pass every mode with/without delivery, both preference combinations and 100 t/nextUp(100)/200 t boundaries. Six JVM tests pass in the 211-test suite. A 100 t request is not a certified real journey.

- [x] N4: DS02 factual claims are checked independently, relevant regressions pass, and original app/maps and concurrent worker outputs are preserved.
  EVIDENCE: docs/DS02-REVIEW-2026-10-25.md checks all ten claims; 1061 source anchors resolve. Final preview/normal builds pass; 211 Java and 76 tooling tests pass, lint 0 errors/82 warnings; native correctness/profile/UI suites pass. Seven original preview preferences byte-match their backup and rotation is restored. No operation targeted original app/maps. Worker originals, its lane and the pre-existing FAST_MODEL_WORK deletion are not staged or changed by Codex.

- [x] N5: Malformed saved active-truck preferences cannot silently authorize routing or guidance under a replacement standard profile; saving a reviewed valid profile restores planning.
  EVIDENCE: Companion-preference checks pass malformed numeric/type cases, NaN, both planning guards, guidance refusal, immediate active-session stop, valid save and legacy float precision. Driver truck preferences are not edited by these checks. The enclosing Store.load fallback was the reproduced defect; named-profile migrations remain separate work.

- [x] N6: An intermediate stop equal to the destination preserves the stop boundary without attempting to match fictitious travel; the whole moving route remains audited.
  EVIDENCE: Native input-location/boundary mapping checks retain repeated stops, preserve nonconsecutive returns and reject all-stationary inputs. Real duplicate-origin/destination fixtures pass every mode; the formerly failing saved-trip UI suite now passes. Existing disconnected-leg and complete-audit negative checks still pass; connectedness tolerance and matcher policy are unchanged.
