# Native interface verification â€” 2026-10-21

Source: `codex/approved-interface`, based on `31589e9`.
Artifact: `EuroRig-Romania-Interface-debug.apk`, 86,117,822 bytes.
SHA-256: `d69152e5c67b427b3892ee706e4b82be808999ebb7a35288dd415bfcb105701e`.
Package: `org.eurorig.app.dev`; version `0.5.0-dev`; debug signature verified
using Android Build Tools `apksigner`.

## Passing checks

- Gradle `:routing:test`: **178 tests, zero failures/errors/skips**.
- Python map/compiler/package tests: **43 run, 3 skipped, no failures/errors**.
  The skipped cases depend on optional map-tooling libraries.
- Android debug APK and instrumentation APK assemble successfully.
- Android lint: **0 errors, 60 warnings**. Warnings remain, including hardcoded
  English UI text and existing platform/API warnings; this is not a localized
  production release.
- `git diff --check` passes.
- Romania package downloaded from the project's published release and verified
  against its catalog size and SHA-256 before emulator installation.
- Native routing on actual Romania A3 geometry produces an audited truck route.
- Attached MapLibre renders installed SQLite roads without requiring MBTiles.
- Actual native simulator controls: range 0â€“200 km/h; zero holds position;
  200 km/h advances according to elapsed time; opening menus pauses playback;
  rotation preserves paused position; Stop restores route preview.
- 2D map/3D truck and 3D map/2D arrow work independently using one marker view.
- Light/OLED themes, landscape map space and native truck profile page checked.
- Android API 37 dedicated emulator: 480Ã—800 portrait and 800Ã—480 landscape,
  density 160. Screenshot evidence is exported with the APK.
- Actual Android GPS-provider fixes injected through the emulator advance
  foreground guidance. The selected route remains painted after activity
  initialization; Stop clears guidance and ends the foreground service. These
  are platform location fixes, not the route simulator or direct service calls.

The A3 test endpoints are real recorded road geometry, not a suggested journey.
The older test origin lay on private roads and was correctly rejected. No access
check was disabled to make the Romania test pass.

## Outstanding checks and limits

- Native profile instrumentation refuses the prohibited category C tunnel but
  fails to find its expected alternative. The full native profile suite **does
  not pass**. This is an unresolved detour-search case, not permission to traverse
  the prohibited tunnel.
- Its former expectation that delivery should override `hgv=no` was corrected:
  the fixture states a prohibition, not destination/delivery access.
- The API 26 software-emulator graphics driver crashed in MapLibre initialization.
  Android 8 graphics compatibility remains unverified for this new interface.
- Physical 5â€“17 inch devices, real driving, successful offline speech synthesis,
  complete legal/ADR coverage and cross-border routing remain unverified.
- Country flags are native system-rendered flags; custom-photo selection and
  decoding have code-level review, not a full system-file-picker device test.
- The normal published package supplies road geometry/labels, not detailed
  buildings or land-cover polygons. Optional vector tiles are not fabricated.

The large-tablet test uses actual display rotation. Android ignores application
orientation requests on large screens targeting current SDK levels; see the
[Android adaptive-app guidance](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability).
The earlier orientation-request timeout was a test-harness issue. The corrected
physical-rotation run **passes at 2160Ã—3840 portrait and 3840Ã—2160 landscape**,
density 320, including the paused simulator, clear vehicle position, independent
map/marker choices, OLED mode and the native profile page. PNG dimensions were
checked before exporting the evidence. The map-only native snapshots must contain
both route and surrounding road pixels; the guidance panel must contain visible
turn text before the screenshots are accepted.

## Updated local map â€” Romania-Moldova

At the user's request, tested the package in Desktop/EuroRig Romania:
`Romania-Moldova.eurorig`, 601,292,283 bytes, SHA-256
`ec5e8826c63101042c3fdf79ce58685225b5d57fe5749781c0ca279fae82cc88`.
This is a local package; it does not change the published download catalog.

Both internal files match the manifest SHA-256 values. SQLite `quick_check`
returns `ok`. The database contains 1,362,249 roads and 2,877,013 places;
its source timestamp is 2026-10-17T20:20:51Z. The manifest and database name
both identify Romania-Moldova, with native engine format 0.6.3.

The real transactional importer accepts it on the dedicated API 37 emulator
and retains the previously installed Romania package. A Romanian A3 test route,
selected-route 0â€“200 km/h simulation, zero hold, pause, rotation, independent
map/marker preferences and native profiles pass. Actual Android GPS-provider
injection also advances foreground guidance on this map. These tests do not
validate every road, cross-border routing or Moldova-specific restrictions.
Allow at least 4 GB of free space for this package plus optional detailed cartography.

## Reproduce

Build with the configured Android SDK/JDK, then install the published Romania
package on a dedicated emulator. Run:

```text
python tools/smoke_interface.py --device emulator-5554
python tools/smoke_interface_gps.py --device emulator-5554
python tools/smoke_interface.py --device emulator-5554 --map "C:/Users/Administrator/Desktop/EuroRig Romania/Romania-Moldova.eurorig" --output work/updated-map/interface-evidence-4k
```

The helpers reject physical-device serials. Their logs and screenshots go into
the ignored project `work/` folder. The initial source archive remains in
`Project EuroRig Design/backups/eurorig-before-native-interface-2026-10-21.zip`.

## Rich map and GPU driver verification â€” 2026-10-21 (prior appearance revision)

Detailed display source: `C:/EverythingAI/Project EurowagNav/data/romania.mbtiles`,
527,015,936 bytes, SHA-256
`a9938dc3e3401c21f6bc78bebc7dd50b7699efb6802420298b4a20d1a61a7484`.
This is licensed OSM/OpenMapTiles Romania data, not extracted proprietary map
artwork. Routing still uses the newer Romania-Moldova native package above.

The native rich-map check passes with the RTX 3080 Vulkan/ANGLE driver at
1280Ã—800: actual rendered building features with recorded `render_height`, 2D
footprints replacing extrusions, fields/boundaries, water, forest, and regional
roads. The city scene contains 170 visible 3D building features. An invalid
detail import preserves the installed map's length and modification time.
Switching UI themes preserves the natural map background. Batched projections
match individual native projections to within 0.1 display pixels in perspective.

Performance evidence uses MapLibre's frame-finished callbacks during the same
6,000 ms animation over central Bucharest (44.4355,26.0998 â†’ 44.4385,26.115;
zoom 16.2, tilt 55Â°, 1280Ã—776 map viewport). It is renderer throughput, not a
physical-device display-frame benchmark or a comparison against Eurowag.

| Driver / revision | Callbacks in 6 s | Median interval | 95th percentile |
| --- | ---: | ---: | ---: |
| Host GLES, first rich map | 127 (~21/s) | 39.88 ms | 85.88 ms |
| Host GLES, opaque buildings and batched overlays | 144 (~24/s) | 35.03 ms | 76.90 ms |
| RTX 3080 Vulkan through ANGLE | 322 (~54/s) | 18.30 ms | 33.02 ms |
| ANGLE repeat | 317 (~53/s) | 17.61 ms | 32.31 ms |

Initial label-query readiness was 1.6â€“2.3 s across these launches; this is not a
measurement of the first visible map pixel. Dense-city performance is improved,
but is not claimed to be a locked 60 FPS. Earlier 60/s road-only results describe
a lighter scene. Full-detail 4K performance is not equivalent to this 1280 test.

The emulator remains `-gpu host`. ANGLE is selected per app using Android's
supported developer setting; MapLibre identifies the renderer as ANGLE/Vulkan
on NVIDIA Goldfish GFXStream (NVIDIA GeForce RTX 3080 Laptop GPU). No software
SwiftShader renderer is used. Reproduce on the dedicated emulator:

```text
adb -s emulator-5554 shell settings put global angle_gl_driver_selection_pkgs org.eurorig.app.dev
adb -s emulator-5554 shell settings put global angle_gl_driver_selection_values angle
adb -s emulator-5554 shell am instrument -w -e performanceOnly true -e performanceScene city org.eurorig.app.dev.test/org.eurorig.app.NativeSmokeInstrumentation
adb -s emulator-5554 shell am instrument -w -e richMapOnly true org.eurorig.app.dev.test/org.eurorig.app.NativeSmokeInstrumentation
```

On this dedicated emulator the two selection settings were previously absent.
To restore its default driver, delete those two settings. Do not overwrite
unrelated package selections on a shared device. This emulator-only choice is
not forced through the application manifest for users' phones. Android documents
the ANGLE test settings at
[Use Vulkan for graphics](https://developer.android.com/games/develop/vulkan/overview).

Evidence is retained under `work/rich-map-angle-evidence`,
`work/rich-map-angle.txt`, `work/angle-performance.txt` and
`work/angle-performance-repeat.txt`. The physical-device/road, API 26 graphics,
ADR-detour and cross-border limitations above remain open.

Final ANGLE regression checks also pass at 480Ã—800/800Ã—480 and at actual
2160Ã—3840/3840Ã—2160: the selected route and surrounding roads remain visible,
simulator zero/200 speed and pause work, marker choices remain independent,
rotation and light/OLED controls preserve the map, and profile settings open.
Android GPS-provider injection still advances and stops foreground guidance.
At 2160Ã—3840, the rich-map scene contains 281 visible 3D building features;
fields, water, forest, 2D/3D switching and invalid-import recovery also pass.
PNG dimensions are recorded in the exported build receipt. These functional
4K checks do not establish 4K animation throughput or physical-device results.

## Separate Light / OLED Dark verification â€” 2026-10-21

This revision supersedes the earlier same-map-palette theme checks. The approved
daylight asset is unchanged. Light uses creamy-white interface surfaces; OLED
Dark uses pure-black menu/control surfaces and a separate night map palette.
The same offline source, layer sequence, filters, zoom thresholds, widths,
opacities and 3D heights remain in both modes. No map data was replaced.

Native checks pass for actual rendered 3D buildings, minor/regional roads,
fields/boundaries, forests and water in both palettes; 2D/3D switching and
failed-detail-import recovery still pass. Screenshots show the actual native
cream and pure-black settings pages. The selected-route 0â€“200 km/h simulator,
zero hold, pause, camera retention across theme changes, single arrow/truck
marker, rotation and native profile settings pass at 480Ã—800/800Ã—480 and
2160Ã—3840/3840Ã—2160. The map/route are inspected in rendered snapshots, not
only through source properties. Native routing code is unchanged in this update.

One dense-city OLED pan on the existing RTX 3080 ANGLE/Vulkan emulator produced
276 frame callbacks over six seconds (~46/s), median 20.35 ms and p95 39.14 ms.
This is an emulator observation, not a locked frame-rate or physical-device
guarantee. Prior ~53â€“54/s results above refer to the earlier daylight scene.

Evidence: `work/oled-map-native.txt`, `work/oled-theme-evidence/interface-checks`,
`work/oled-interface-small`, `work/oled-interface-4k` and
`work/oled-performance.txt`. Existing physical-device/road, API 26 graphics,
ADR detour and cross-border limitations remain unchanged.

## Current browsing/restriction revision — 2026-10-21

Debug app/test builds and lint pass. Routing JVM checks: 178 tests, zero failures
or errors. Native small-screen (480×800/800×480) and 4K
(2160×3840/3840×2160) checks pass for selected-route simulator 0–200 km/h,
zero hold, pause, single marker, map/marker independence, themes and rotation.
Actual Android GPS-provider guidance/progress/stop passes.

Native browsing checks pass for the exact requested coordinate pair, a real
offline full street address, point preview, audited waypoint route, route-geometry
cache reuse, and a real 30,000 ms Re-center countdown reset by another injected
map gesture. The countdown never returns early. 4K captures include the editor,
overview, search/pin and partially filled truck/trailer Re-center control.

The cream/OLED crossed-road checks compare native source geometry with independently
queried visible policy records, not an assumed city-wide ban. A pitched camera's
bounding box is larger than its visible trapezoid, so visible line segments are
checked in screen space after asynchronous source processing. In the 4K test area,
216 visible ways are blocked for the small fixture and 330 for the larger fixture;
both themes agree. No floating TextView tags remain. Major roads are not exempt
from the restriction decision. Height, width, length, gross/axle weights and a
height limit combined with one-way evidence have explicit fixtures.

The full native profile-routing suite still FAILS the previously known ADR
category-C alternative-corridor case. It rejects the prohibited tunnel rather
than accepting it; discovering the expected legal detour remains unfinished.
This is not a fully passed native routing suite or road-validation certificate.
Waypoint join/connectedness/shape-index checks run before that known failure.

Matched dense 3D city pans, six seconds each, current 4K buffer:

| Palette | Renderer callbacks | Approx. callbacks/s | Median interval | p95 interval |
|---|---:|---:|---:|---:|
| Cream | 135 | 22.50 | 42.57 ms | 80.76 ms |
| OLED | 137 | 22.83 | 39.19 ms | 82.88 ms |

Map surface: 3840×2112, inside a 3840×2160
screen including the status bar. Emulator display: 60 Hz;
RTX 3080 host GPU with the existing per-app ANGLE/Vulkan configuration. These are
native renderer frame callbacks, not a physical 120 Hz display measurement. The
8.33 ms / 120 FPS target is NOT met. The connected Tab S9 reports a 1600×2560,
120 Hz-capable display; EuroRig's current APK was not benchmarked there in this
pass, which inspected Eurowag only. Physical 4K/120 and thermal-duration validation
remain open.

Evidence is in work/browsing-final-small, work/browsing-final-4k,
work/crossed-final-4k.txt, work/browsing-handoff-4k.txt,
work/browsing-handoff-evidence, work/browsing-final-crossed-validated,
work/interface-evidence/native-gps.txt, work/browsing-profiles.txt and the two
work/browsing-performance-4k-*.txt files. Known API-26 rendering, complete ADR,
country-law, municipal-zone and cross-border limitations remain. All emulator
tests preserve installed map packages; physical-device reference media remain
private and are not exported.


## Profile summary icons checkpoint — 2026-10-21

The native summary under Search now has original transparent truck/trailer,
weight and height vector icons with three readable cells. Long profile names
ellipsize; loaded mass and height retain units. The entire strip opens profiles
and exposes a full accessibility description. An exceeded permitted-mass warning
remains visibly below the cells. Cream and OLED screenshots were inspected at
360 dp width. Build, lint (0 errors, 72 warnings), routing JVM checks (178 tests)
and the native narrow-screen browsing workflow pass. New icons have not yet had
their own 4K/orientation visual check. Earlier 4K functional checks and 22–23/s
renderer callback measurements refer to the immediately preceding build.
User requested a checkpoint and wrap-up due to remaining usage. No commit/push.


## Update: 0.5.1 GPS, labels and distance units

See LOCATION-AND-LABELS-UPDATE.md for the new integrated top search/menu, native label anchoring, GPS/follow lifecycle, accuracy rules, km/m display, current validation and performance limits. The Desktop APK has been updated in place; maps and profiles are preserved.
