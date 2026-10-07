# Verification

## Routing evidence follow-up

- Android 8/API 26 native profile instrumentation passes explicit Shortest,
  Easiest and Economical checks against a routing graph whose display evidence
  is deliberately altered. Removing the primary way selects an evidenced detour;
  the returned route excludes way 20000003 and includes way 20000004. The audit
  records a retry and an exclusion. Removing all routed-way evidence refuses
  routing across all three preferences, with normal, hazmat and detailed ADR
  profiles, both with and without delivery permission. The intact control routes.
- Missing evidence uses the existing bounded exclusion loop. Truck restrictions
  remain enforced; a road with unknown evidence is never accepted as unrestricted.
  A native no-path refusal can replace the earlier evidence message, so the check
  verifies refusal plus the actual audit counters rather than one error phrase.
- Independent private map-generation verification checks 1024 actual tiles.
  The incompatible fixture is rejected for mixed generations and 636 conflicting
  shared-tile claims. The compatible control verifies 1024 tiles with no mismatches
  or unowned tiles. This is a host packaging check, not Android country composition.
- Country composition, fresh coherent-package Android corridor checks and ferry
  evidence remain unfinished. These changes do not certify real-road navigation.
- The standing animation, UI and AI reference catalog is recorded in
  [implementation references](docs/IMPLEMENTATION_REFERENCES.md).

## 0.6.0-dev candidate, 2026-10-07

- 37 Java tests and 28 Python tests pass; Android lint reports no issues.
- The UI uses paired light/dark semantic colours, original vector controls,
  native ripple and keyboard focus feedback, and a clear primary route action.
  Theme switching changes the rendered map, controls and dialogs; the saved
  selection survives restart and switching during guidance preserves following.
- API 26 and API 37 each pass the 22-check UI sweep. Additional checks exercise
  all seven numeric profile fields, six checkboxes, ADR E, precision after restart,
  invalid-profile preservation, map picking, saved places, endpoint swap, trip-only
  delivery permission, the live catalogue and licence content. These are emulator
  checks, not a real driving or spoken-output validation.
- Native profile, ADR, map-retention, camera, heading-arrow and route-trail checks
  pass on both versions. API 37 injected GPS checks pass acquisition, following,
  loss/unavailable speed, recovery, theme switching, edit protection and Stop.
- Layout and contrast checks pass at 375 x 812, 812 x 375, 1280 x 800 and
  800 x 1280 dp, including 200% text and disabled animations. Each of 14 main
  controls is reachable at 48 dp or larger. Seven profile fields, six checkboxes,
  the ADR selector and four routing preference controls meet the same minimum.
  Maps actions retain their complete labels at 200% text. Screenshot review caught
  split navigation labels and a clipped download-status action before fixing them.
- Native coverage errors now test both endpoints. Missing or malformed bounds
  remain undeclared, and a bounding rectangle never proves coverage inside it.
  Real native-fixture checks cover an outside destination, the reversed direction,
  the conservative interior advice and fresh audit counters after failures.
- Open Code Review 1.12.12 delegation mode selects files and resolves rules;
  Codex performs the review locally without a new external LLM endpoint. Peer
  review and actual device checks supplement it. QA-runner preflight and
  preference-read bugs found during review are fixed with regression checks.
- The public Romania map remains unchanged. Coherent cross-border maps and
  country-union installation remain separate work; no new Europe coverage or
  real-road certification is claimed by this UI update.

## 0.5.0-dev, 2026-10-06

- 37 Java tests and 17 Python tests pass; Android lint reports no issues.
- Follow-up peer-review checks require complete route-segment audit coverage;
  deliberately truncated, missing-ID, invalid-range and conflicting traces are
  rejected. Complete shared-junction/zero-length-edge traces pass. The trace copy
  preserves every costing option except the native crash trigger. Missing and
  empty routing archives are rejected before native initialization. API 26 and
  API 37 profile instrumentation and the 40-outcome corpus pass these changes.
- Joint Hermes/DeepSeek corpus testing now runs EuroRig's native Android router
  and restriction audit. API 26 and API 37 each produce 28 routes and 12 expected
  rejections over ten cases/four modes, with physical ETA and snap checks.
  22 same-endpoint Shortest comparisons pass; six comparisons are excluded for
  different endpoint snaps. They remain recorded, not silently accepted.
  Disabling route hierarchy pruning fixes the observed distance anomaly; passing
  that search option into edge-walk matching initially caused a native SIGSEGV.
  Only the audit's copy omits the option, preserving every truck/access check.
  Audited Shortest Galați–Bucharest improves 244.9284 to 226.3575 km, and
  Galați–Nădlac 741.0014 to 722.3086 km. See [collaboration](docs/COLLABORATION.md).
- A redesigned map-first UI, visible-viewport route fit, automatic guidance zoom
  and look-ahead following are exercised on Android 8/API 26 and Android 17/API 37.
  Camera checks cover every route geometry point, pan suspension, recenter and
  preserved following/manual view on rotation.
- Actual rendered-pixel checks pass on API 26 and API 37 for the original blue
  direction arrow, stale-position dot, partial-edge route trail removal, amber
  restricted segments, arrival clearing and new-route reset. GPS progress does
  not rebuild the cached road bitmap.
- Dark mode changes both actual map and control colors. Light/dark selection
  survives restart and switching during guidance preserves the foreground service
  and following. Physical Tab S9 appearance switching also passes.
- The 22-control UI sweeps exercise actual Galați routes in all three preferences,
  search feedback, endpoints/restart, GPX document export, truck form, map metadata,
  system import picker, zoom, About and portrait/landscape. Additional checks cover
  profile precision/invalid values, favourites/map picking, delivery permission
  reset, licences and GPS loss/recovery/stop. See [UI audit](docs/UI_AUDIT.md).
- Native profile/ADR checks and new Android SQLite filtering fixtures pass on both
  emulator versions. Pedestrian and track detail is hidden conservatively, while
  important/local/delivery roads and restriction evidence remain intact.
- A fresh API 26 install downloaded the actual public 407 MB Romania archive and
  routed with the network disabled, including after process termination. Romania
  data is unchanged from 0.4. Full native country routing/search/import tests passed
  during the 0.5 iteration; focused checks followed the last display changes.
- Final Tab S9 APK updates preserve user data. The warm four-swipe sample recorded
  310 frames, 5-ms median, 7-ms 95th percentile and 1.29% missed deadlines.
  This sample preceded the final dynamic route overlay; it is not a measurement
  of that final overlay or a low-end device guarantee.
  Search Cancel is visible above its keyboard. No real drive or physical GPS
  guidance is claimed; low-end hardware, spoken output and richer map behavior
  still need validation. Europe coverage and routing limitations below still apply.

## 0.4.0-dev, 2026-10-06

- 34 Java tests and 14 Python tests pass. Android lint reports no issues.
- Android 8/API 26 and Android 17/API 37 pass Romania route/search/import
  checks, GPS matching, cold restart, foreground guidance and pan/overview/recenter.
  Original synthetic native networks test all three preferences with separate
  height, width, length, gross weight and axle-load exclusions, permitted local
  delivery access, a prohibited low bridge and ADR C-to-B tunnel detours. A highway
  preference fixture selects a longer motorway alternative to the shortest route.
- Updated Romania data has 424,527 way-rule records and 25,886 node-rule records.
  These include access, nonmotor roads, physical limits and uncertain metadata;
  their presence does not establish complete restriction coverage. Routing tiles
  are unchanged. The 407,495,153-byte archive uses ordinary SQLite grid indexes.
  Android 8 SQLite lacks RTree support; the first RTree-based candidate failed
  and was replaced before release.
- On an authorized Galaxy Tab S9, Android 16/API 36, installation and replacement
  of Romania succeed without clearing user data. Native sample routing, map
  controls, profile/options UI and landscape are exercised. APK and UI evidence
  remain private. A physical GPS attempt rejected poor accuracy; no real drive,
  physical guidance or spoken output is claimed.
- Four equal 700-ms swipes on the Tab S9 sample route measured 281 frames with
  5-ms median/90th percentile, 6-ms 95th percentile and 2.49% missed deadlines.
  Earlier redraw-on-every-frame candidates measured 150-ms median and 100%
  missed deadlines in the same gesture sequence. Cache replacement still pauses
  (150-ms 99th percentile); these samples are not low-end device guarantees.
  Desktop indexed query time for one 1,541-road sample improved from 127 ms
  cold to 7.55 ms median across ten runs; this is not whole-app frame timing.
- The audit initially rejected private origin departure and unsigned-limit
  metadata. Dedicated checks now preserve numeric limits while allowing a bounded
  private egress. An ADR reroute initially excluded a shared junction; exclusion
  now targets the failed edge interior. Route audit failure has no car or server
  fallback. Default-height references are not treated as proof for trucks over 4 m.

Reproduce with dedicated emulators only:

```text
python tools/smoke_native.py --country dist/Romania-2026-10-04-v2.eurorig --device emulator-5554 --prefix android8-final
python tools/smoke_native.py --country dist/Romania-2026-10-04-v2.eurorig --device emulator-5556 --prefix android17-final
```

The script rejects physical serials before clearing data. GPS fixes in these
checks are synthetic. Restricted access is limited to 2 km from the destination
and requires driver permission; it is not a legal exemption. Global minimum
restricted distance, literal fewest turns, absolute highway continuity, complete
ADR/country/time rules, low-end hardware and road validation remain unfinished.

## 0.3.0-dev, 2026-10-06

- 22 Java tests pass: 15 routing/geometry/decoder tests and 7 real HTTP download
  tests (SHA-256, range resume, interrupted/complete partials, pause, unsafe URL
  rejection and bad-range preservation). 13 Python tests pass with osmium
  available. Android lint reports no issues. APK 16-KiB zip alignment passes.
- No `.tar`, `.europack` or `.sqlite` map is present in the application APK.
  Fresh installation displays No maps installed; Truck and Maps remain usable.
- Full Romania package: 636 routing tiles, 1,057,239 display roads and 1,747,703
  searchable places, zero missing geometry, OSM timestamp 2026-10-04T20:20:21Z.
  Original Geofabrik PBF was checked against its published MD5. Map source,
  derived databases, notice, provenance and SHA-256 files are publicly offered.
- Android 8/API 26 and Android 17/API 37 native instrumentation passes real
  Romania truck routing, ETA/maneuvers, sequential polyline GPS matching,
  out-of-coverage rejection, invalid path/checksum rollback, bounded SQLite
  viewport roads and accent-insensitive/prefix search.
- Both device UI flows pass 14.0-km truck route display, maneuvers, process
  termination/cold restart, persisted country loading, synthetic GPS fixes
  following actual route geometry, muted foreground guidance and stop.
- A fresh Android 17 install downloads the actual 415,239,820-byte Romania
  package from the public GitHub HTTPS catalogue. The foreground download and
  transactional installation succeed. After Wi-Fi/data are disabled, route
  planning works, including after force-stop/cold restart. Country selection
  lists Romania; all-Europe reports that full coverage is not yet published.
- Final screenshots show smooth road strokes, cached map drawing and the
  empty/downloaded/offline-route states. Tests use dedicated emulators only.

The first sample destination did not connect within the configured road-search
cutoff; test endpoints were changed to a connected route north of Bucharest,
without changing restrictions or expanding the search radius. Native GPS tests
feed successive geometry fixes instead of jumping beyond the forward matching
window. An older Android 8 emulator instance shut down during a run; a fresh
isolated API 26 image completed instrumentation and UI/GPS tests.

Reproduce with dedicated running emulators:

```text
python tools/smoke_native.py --country dist/Romania-2026-10-04.eurorig --device emulator-5554 --prefix android8-romania
python tools/smoke_native.py --country dist/Romania-2026-10-04.eurorig --device emulator-5556 --prefix android17-romania
python tools/smoke_download.py --device emulator-5556 --prefix android17-download
```

These scripts clear EuroRig data on the named test device. Public downloads use
network only for maps; offline planning is tested after that connection is disabled.
GPS tests inject synthetic coordinates and mute TTS; they are not physical road
or spoken-output validation. Intermediate Android versions, 32-bit devices and
real 16-KiB-page hardware remain untested. Country truck laws, missing restrictions,
time-dependent restrictions/timezone enrichment and cross-border routing require
further work. The download flow is tested end-to-end on API 37; transport resume
and pause are tested against a local HTTP server in Java.


## 0.2.0-dev, 2026-10-06

- JDK 21, Gradle 9.6.0, AGP 9.4.0; minimum API 26, compile/target API 37.
- 15 Java tests pass, including native maneuver handling and buffered map
  decoding under short reads/across buffer boundaries. Nine Python tests pass
  (seven compiler, two native-region packaging). Android lint reports no issues.
- Valhalla Mobile 0.6.3 executes real local truck routes on Android 8/API 26
  and Android 17/API 37 emulators. Tests check native geometry, ETA, maneuvers,
  dense-polyline GPS matching, outside-coverage rejection, successful native
  package import, invalid-path/checksum rejection and preservation of the active
  region. The test checks that the app has no INTERNET permission.
- On **both emulators**, the final native smoke flow passed route display,
  maneuver screen, process termination/cold restart, persisted region loading,
  real-coordinate GPS injection, muted foreground guidance, stop and training-map
  restoration. Genuine route and GPS screenshots were inspected.
- Native ARM64/x86_64 ELF load segments use 16 KiB alignment. APK `zipalign`
  passes the 16 KiB page check. ARMv7/x86 binaries are packaged but were not
  executed. Real devices with 16 KiB pages have not been tested.
- The PBF-to-XML-to-display-graph pipeline was exercised on the exact Andorra
  source: 37,848 road nodes, 67,384 directed segments and 39 supported turn
  restrictions. Display data is separate from native routing decisions.

Startup testing exposed slow primitive stream decoding and recurring road
redraws. Buffered decoding, viewport culling, overview road filtering and a
static map/route cache were added. The saved native map then loaded in roughly
four seconds on the tested Android 17 emulator. This is one sample/device
measurement, not a continental performance guarantee.

Reproduce on dedicated emulators:

```powershell
./tools/build.ps1 -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':routing:test',':app:lintDebug'
python -m unittest discover -s tools -p test_*.py
python tools/smoke_native.py --device emulator-5554 --prefix android8
python tools/smoke_native.py --device emulator-5556 --prefix android17
```

Start each isolated emulator before its script. The scripts install only EuroRig
and its test APK, select the Andorra region and end on the training map. Source
includes the decoder tests and native instrumentation implementation.

Not verified: physical devices, intermediate Android versions, spoken output,
real driving, complete native truck-restriction/country-law correctness,
continental files/performance, independent country merging, automatic updates,
complete geocoding, multi-stop navigation, lane guidance and public CI hosting.
The routing fixture's snapshot date is unknown. Android 8 image Pico TTS crashed
in earlier testing; this release's GPS tests were muted. This APK is debug-signed.
Europe-wide coverage, public release and the full product request remain unfinished.

## Earlier 0.1.0-dev evidence

## Build and automated checks

- Gradle 9.6.0, Android Gradle plugin 9.4.0, JDK 17.
- Android APK builds with minimum API 26 and compile/target API 37.
- 13 Java routing tests pass: truck/van route differences, individual dimension
  limits, axle/gross weight, hazmat, tolls, only/no turns, impossible routes,
  coordinate snapping, search, navigation progress/arrival and malformed data.
- Seven Python compiler tests pass: units, unknown/conditional/lanes limits,
  directional restrictions, access specificity, barriers, tunnels/tolls,
  one-way geometry and supported/unsupported turn relations.
- Android lint passes with no issues in the final build.
- APK manifest has no INTERNET permission and no billing/analytics dependency.

## Device evidence

Android 8/API 26 emulator: startup, truck detour calculation, route simulation,
stopping, local search, truck-profile save and training-map restoration passed.
Portrait and landscape screenshots were inspected. A private fictional QA
package was imported and the foreground GPS service accepted injected location
fixes and displayed guidance. The final muted-GPS test passed import, foreground
service, progress display, stop and training-map restoration. This is an emulator
test, not a truck road test.

Android 17/API 37 emulator: startup, truck detour calculation, route simulation,
stopping, local search, truck-profile save and training-map restoration passed
on the final APK. The route screenshot was inspected.

The isolated emulator images experienced System UI cold-boot ANRs; recovery is
explicitly logged by the test helper. The Android 8 image's external Pico TTS
provider crashed inside its native synthesis library. Spoken output is therefore
**not verified**. Voice can be muted without requiring a network service.

## Reproduce

```text
python -m unittest discover -s tools -p test_*.py
./tools/build.ps1
python tools/setup_emulator.py --api 26
python tools/smoke_android.py --device emulator-5554 --prefix android8
python tools/smoke_navigation.py --device emulator-5554 --prefix android8
```

Start the dedicated headless emulator before running the device scripts. The
private GPS QA fixture changes a flag only in a test copy of the fictional map
to exercise the service. It is excluded from the source archive and must never
be used on roads. `tools/smoke_navigation.py` leaves the training map selected
after a successful test. Android 17 setup uses `--api 37` and a separate device
port, e.g. `emulator-5556`.

## Not verified / not shipped

All intermediate Android versions and physical devices, real driving, successful
voice synthesis, continental-scale routing/performance, country-specific law/ADR
accuracy, full European maps, cross-package trips, multi-stop navigation, complete
geocoding, lane guidance and live information. The CI workflow has been prepared
but has not run on a public repository. This is a debug-signed development build;
release signing, public hosting and the full product request remain unfinished.
