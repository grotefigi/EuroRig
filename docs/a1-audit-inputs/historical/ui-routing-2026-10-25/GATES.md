# Gates: route planning, options and restriction display

OWNS: app/src/main/java/org/eurorig/app/**, app/src/main/res/**, app/src/androidTest/java/org/eurorig/app/**, routing/src/main/java/org/eurorig/routing/**, routing/src/test/java/org/eurorig/routing/**, tools/**, docs/**, ROADMAP.md, CONTINUATION.md, analysis/private/ui-routing-2026-10-25/**

Scope: diagnose and repair endpoint planning, remove the main-map GPS/Route buttons, implement the referenced native stop editor and uniform country options, and resolve restriction-display defects using verified evidence.

- [x] U1: selecting distinct origin/destination and intermediate stops produces the intended audited route; equal endpoints receive actionable validation
  EVIDENCE: uiRoutingOnly-native.txt and compact-native.txt PASS on the installed preview: distinct native routes, fresh-location origin handoff, actual destination-result selection, ten audited boundaries, physical drag gesture, in-place tenth-stop replacement, restored order, nonzero round trip and actionable equal-endpoint validation. mapControlOnly-native.txt PASS proves rerouting retains unvisited stops. These are replay/instrumentation checks, not live driving.

- [x] U2: the main map omits the GPS and Route buttons while required map/planner functions remain reachable
  EVIDENCE: Main native hierarchy asserts no GPS/Route buttons. Tablet/compact portrait and landscape screenshots inspected; Current location/Route overview remain in the navigation menu and origin search retains Current position. Native interface and map-control suites pass.

- [x] U3: the referenced stop editor and route options are implemented natively, with ten reorderable stops and identical supported option definitions for every country
  EVIDENCE: Native gesture and country-control checks pass in both tablet and 412x915 dp layouts. RO/MD expose all six identical definitions; switches persist independently; real RO admin metadata and avoided-country refusal verified. Legacy maps refuse active uncheckable options. Dark/light and rotated layouts inspected, including the compact footer fix with two fully visible stop handles. Congestion control is disabled with an explicit missing-data label.

- [x] U4: the restriction overlay draws every applicable verified record in view and city-coverage differences are resolved or precisely explained by dated source evidence
  EVIDENCE: crossedOnly-native.txt PASS for eight cities, two zoom levels and both palettes; matched truck profile recorded, but no run-bound input hash was supplied. Applicable drawable database records reach the native source; subpixel-tiling case explicitly diagnosed. docs/UI-ROUTING-2026-10-25.md records package/database SHA256, Galați inventory and dated source gap. This is rendering/diagnosis evidence only; municipal coverage remains U7.

- [x] U5: routing regressions, Android builds and lint pass
  CHECK: gradlew.bat :routing:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: automatic-evidence=v1; definition-sha256=5564dbd28ed865a2f0081769bb087728b9c809f46c578e86ea751f6d60102cc1; exit=0; EXPECT=matched; output-sha256=38fe409f413a564d8b7667ab2c94eeaa2de39dfb1906d87c7b633ec638a9ada4; output-bytes=5814; shell=<Windows command shell>; cwd=<private local workspace>; path=<redacted>

- [x] U6: the delivered APK and source preserve the approved interface and original code/assets, with verified backups and physical-device evidence kept private
  EVIDENCE: Original org.eurorig.app.dev remains version 9 / 0.5.4-dev with lastUpdateTime 2026-10-21 22:10:27. Saved recovery and country hashes verified; preview uses a separate package/certificate/storage. Original code/Canvas/vector assets reviewed, private screenshots/data ignored. Delivered and installed APK hashes independently compared in delivery-hashes.json. Native/compact evidence and remaining data/hardware limits recorded in the public verification note.

- [ ] U7: current authoritative municipal restrictions expand the city overlay and constrain routing on the corresponding roads
  EVIDENCE: unresolved; docs/UI-ROUTING-2026-10-25.md records the verified package inventory and missing current legal annexes, amendment chain and exact road matching.

- [x] U8: all map/compiler/package tooling tests pass without skips
  CHECK: ".toolchain/qa-python/Scripts/python.exe" -m unittest discover -s tools -p test_*.py
  EXPECT: /^OK\r?$/m
  CWD: .
  EVIDENCE: automatic-evidence=v1; definition-sha256=fc34b862b9754d3fac84854a66616d1381f4274af6852e3e42692ed8c2fc23e2; exit=0; EXPECT=matched; output-sha256=03646ce73ed211a13e470ba70cc5d6acebee91e7164e22960f398312a5047ac6; output-bytes=525; shell=<Windows command shell>; cwd=<private local workspace>; path=<redacted>

ABANDON: U7 current authoritative municipal annexes and their amendment chain could not be verified; official PDF access failed, and importing a proposal or inferring bans from street classes would fabricate restriction coverage. This remains required data work, not a completed feature.

