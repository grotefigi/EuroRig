# Native interface integration

2026-10-21: The user explicitly approved applying the interface to EuroRig and
connecting it to real navigation. Romania is the first usable-country milestone;
Europe follows. This supersedes the earlier design-copy-only boundary.

Approved design: Project EuroRig Design, interface review 04. Warm orange,
champagne/brown and dark red; light and OLED-black themes; a top-left dropdown;
portrait/landscape map-first driving; independent map perspective and vehicle
marker settings; editable truck profiles with private photos; route simulation with an adjustable 0â€“200 km/h slider. The user replaced the
earlier fixed 50 km/h replay on 2026-10-21. Replay follows the chosen route
between its two planned endpoints; zero holds position and never changes
the truck profile speed or live GPS guidance.

Integrate into the existing Android app and preserve the local Valhalla engine,
truck/ADR/access audits, country import/download checks, search and GPS service.
Do not turn the illustrative HTML into the navigation runtime. Do not label
unpublished country packages as downloadable or invent restrictions/speed limits.

Recovery: source branch codex/approved-interface starts at 31589e9. The clean
source archive is in Project EuroRig Design/backups/
eurorig-before-native-interface-2026-10-21.zip. Do not overwrite private app data.

Required verification: routing tests, Python map tooling tests, Android debug and
instrumentation builds, lint, emulator navigation/profile/theme checks where an
emulator can be run. Record actual evidence and remaining runtime limitations.

## Implementation

The interface is native Android Java. It retains the Valhalla truck router,
the SQLite road/search database, the foreground GPS service, audited route
sections and transactional country installation. There is no embedded HTML app.

The new interface has the top-left dropdown, warm light/OLED themes, native
truck profiles and private photo import, independent map perspective and marker
preferences, country flags/codes with honest availability, and a selected-route
simulator with a 0â€“200 km/h slider. The simulator uses elapsed active time and
the chosen route geometry; zero holds position, pause stops elapsed time,
rotation preserves position, and Stop restores the route overview. Simulator
speed never changes the truck profile's routing maximum speed or supplies GPS
fixes to the navigation service.

The renderer now reads the installed SQLite roads when optional MBTiles are
absent, paints a single vehicle marker, caches the full selected route geometry, retains
camera state across asynchronous initialization, and reserves map space beside
the landscape panels. OpenStreetMap attribution remains visible in the trip card.

Real-country testing found that published v2 packages lack the newer normalized
direction bits. The old audit therefore rejected `oneway=yes` in both directions.
Legacy records now resolve explicit truck-relevant one-way tags while retaining
height, weight, axle, ADR and access checks. Unknown direction remains blocked;
unsupported direction conditions remain unverified. An entrance declaration
cannot authorize wrong-way travel. New routing regression tests cover these cases.

On 2026-10-21 the user requested a trial of the newer local Desktop/EuroRig
Romania/Romania-Moldova.eurorig package. Its checksums/database, native import,
Romanian test route, simulator and GPS-provider guidance pass on the dedicated
emulator. The prior map is retained; no public catalog is changed. See the
verification report for exact provenance and coverage limits.

The road renderer coalesces background viewport queries and avoids rewriting
unchanged route sources or repeating stationary camera moves. This lets the
surrounding roads finish rendering during paused simulation and after rotation.

## Known limits

Romania is the only published country package. The 52-country/region list does
not imply downloadable European coverage. Cross-border routing remains unfinished.
The ordinary Romania package supplies roads and labels; detailed buildings and
land-cover polygons are not fabricated when no matching vector tile package exists.

This is a debug-signed development build. Physical-device and road validation,
full country-specific legal/ADR coverage, and continental performance remain open.
The API 26 emulator's software graphics driver crashed in MapLibre initialization;
the new renderer is verified on the API 37 emulator, not on every supported device.

The older native profile suite has an unresolved ADR detour case: it safely
refuses a route through a prohibited category C tunnel instead of finding the
expected alternative. Do not describe that native suite as fully passing.
Its contradictory expectation that delivery mode should override `hgv=no` was
corrected to require rejection; delivery permission does not create truck access.

See the accompanying native build report and APK guide for exact verification
results and installation steps. Historical verification records describe earlier
builds and must not be read as validation of this new renderer on Android 8.

## Map palette update â€” 2026-10-21

The user rejected OLED recoloring of the map. Keep the newer Romania-Moldova
map active and preserve the normal cartographic palette, route colors and labels
in both interface themes. OLED black applies to interface surfaces, not the map.
The earlier trial initially disabled its detail file. The subsequent request
for buildings and land cover supersedes that state: the licensed Romania
OpenMapTiles file is now enabled with the new natural cartographic style.

## Rich offline cartography and emulator graphics â€” 2026-10-21

The newer Romania-Moldova routing package remains active. A separate licensed
Romania OpenMapTiles file supplies real vector roads, building footprints,
approximate heights, land use, mapped fields/boundaries, forest and water.
Neither routing restrictions nor access/ADR audits are replaced by display data.
The 2D map shows footprints; 3D perspective adds opaque building extrusions.
The map palette remains independent of light/OLED interface preferences.

The tile reader uses a bounded 16 MB compressed-tile cache and a bounded worker
pool, serves only loopback, and keeps SQLite road fallback outside the detail
coverage. Inside detail coverage, lightweight label queries avoid constructing
duplicate road GeoJSON. Startup no longer replaces the Romania camera with an
uninitialized zero coordinate. Label and restriction projections are batched,
with reusable placement buffers; native checks compare the batch locations with
individual projections to within 0.1 display pixels.

Offline maps â†’ Detail imports the optional `.mbtiles` file transactionally;
country/routing imports and private truck-photo imports retain separate request
codes. Schema/integrity and free-space checks run before replacing existing detail.
OpenMapTiles and OSM attribution remain visible; licenses ship in the APK.

The dedicated API 37 emulator runs on the RTX 3080 host GPU. Its original GLES
translation path was the remaining dense-city bottleneck. Selecting the Android
ANGLE driver for **only `org.eurorig.app.dev`** uses Vulkan on that same GPU and
improves the matched six-second city pan from 127â€“145 to 317â€“322 frame callbacks.
This is an emulator configuration, not an assumed improvement on every phone.
See the verification report for measurements and reproduction commands.

## Separate light and OLED dark themes â€” 2026-10-21

The user's latest appearance request supersedes the earlier instruction to keep
one daylight map palette in both themes. Keep the approved map unchanged in
**Light Â· Cream**, with creamy-white menus and controls (#FFF9F0 / #FFFCF5).
Provide **Dark Â· OLED black** as a separate saved choice: pure-black interface
surfaces and a matching night cartographic palette. Retain all geometry, roads,
building heights, fields, forests, water, filters, widths and 2D/3D behavior.
Warm orange, champagne/brown and dark-red accents remain the visual direction.

Both modes are selected through menu â†’ Settings â†’ Appearance. Light is the
default for a fresh install; existing saved choices persist. Changing appearance
retains camera, selected route, marker choice and paused simulator progress.
The night palette is applied when loading the same offline style, with no
per-frame recoloring or additional map downloads. Restriction classifications
and all routing audits remain unchanged.

## Browsing, route editing and crossed restriction roads — 2026-10-21

This revision supersedes older claims about floating restriction chips, travelled
route trimming, pure-black cards and unchanged minor-road zoom thresholds.
OLED root surfaces remain black; charcoal/brown cards and the warmer menu button
provide the requested contrast. Cream remains the default light interface.

Native search opens address/coordinate results, actual local categories and
recent selections. Decimal input preserves double precision, including
45.65905358103864, 25.592833956175188. A selected point has a map pin and
Get directions. Complete street addresses work where the installed map contains
the address record; missing OSM addresses are not invented.

Free browsing has the search bar and profile name/loaded weight/height underneath,
with no empty journey card. Route overview has origin/destination, Edit and real
ETA/distance; genuine audit notices remain visible. The native editor supports
origin, destination and up to five reorderable waypoints, profile and route options.
Waypoints use Valhalla stop legs: each unchanged leg is exactly matched, their
geometry/maneuvers are joined without a gap, and one complete trip access audit
preserves the original origin and final destination semantics. No stop grants a
new delivery/private permission. Disconnected or unauditable legs are refused.

Pan/drag starts a 30-second Re-center fill with an original truck/trailer icon.
Another gesture restarts it. Menus/search/background pause it. Automatic return
uses a fresh GPS fix or actual route simulation, never a fabricated seed location;
without a fresh fix it waits for GPS. Only the small control animates.

Restrictions use a light red line and repeated GPU-rendered crosses; no floating
restriction tags remain. The overview checks both directions with AccessPolicy,
including dimensions/weights on one-way roads. Allowed recorded limits keep base
road paint. Direction-only prohibitions do not become a false two-way blanket ban.
Local streets appear at zoom 13 and tracks at 14; regional views use major roads
without assuming they permit trucks. The viewport record cap is removed.

The full selected route stays resident on the GPU while the marker, ETA and
remaining distance advance. GPS/simulator ticks no longer reconstruct and
reserialize all route geometry. Tracking uses linear native camera interpolation.
The app/window/surface requests up to 120 Hz where supported, but the measured
4K renderer throughput is about 22–23 callbacks/s on the 60 Hz emulator. Reliable
4K/120 FPS is NOT established. No geometric detail was removed to claim that target.

See EUROWAG-REFERENCE-REVIEW.md for the live tablet review, earlier PC analysis,
measured city-data gaps and the pending municipal-zone/corridor work. Full city
restriction parity and live congestion remain unfinished.


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
