# EuroRig Romania native development build

Historical build guide. Its unresolved ADR-C statement below is superseded by [the 26 October B2 verification](B2-ADR-DETOURS-2026-10-26.md). This does not close the guide's other device, map or renderer limitations.

This build applies the approved interface to the Android project. It is a native
Android app with local routing, map display/search and foreground GPS guidance.
It is debug-signed, uses package `org.eurorig.app.dev`, and remains a development
build requiring physical-device and road validation.

## Install and start

1. Copy `EuroRig-Romania-Interface-debug.apk` to the Android device and open it.
   Allow installation from that file source if Android requests it.
2. Open the top-left menu â†’ Offline maps â†’ Romania. Download the map, or use
   Import to select the project's published `.eurorig` country package.
   Allow at least 2 GB of free space for the published map. To try the newer
   local map, choose Import and select `Romania-Moldova.eurorig` from the
   Desktop/EuroRig Romania folder after copying it to the device. Allow at
   least 4 GB free for that larger package and optional map detail. It has passed emulator import,
   Romanian route, simulator and GPS-provider checks; the previous installed
   map is retained.
3. Open Vehicle profiles and enter the actual truck dimensions, loaded gross
   and axle weights, permitted maximum mass, and any applicable load/tunnel codes.
4. Open Edit route, choose From and To, and select Plan route. Offline search
   covers names and full street addresses where address records exist in the
   installed package. You can also enter latitude, longitude, for example
   45.65905358103864, 25.592833956175188. Tap a result and Get directions.
   Add/reorder up to five waypoints in the editor. A map tap can also choose an endpoint.

## Detailed roads, buildings and countryside

The emulator already has the detailed Romania map installed. On a fresh device,
the APK and routing package alone provide the simpler road map. For detailed
cartography, also copy the licensed OpenMapTiles vector file `romania.mbtiles`
to the device, then choose menu â†’ Offline maps â†’ **Detail** (or Settings â†’ Map
view & units â†’ Map detail). This import is separate from the `.eurorig` routing
package. An invalid detail import preserves the existing file.

The local source is `C:/EverythingAI/Project EurowagNav/data/romania.mbtiles`,
527,015,936 bytes, SHA-256
`a9938dc3e3401c21f6bc78bebc7dd50b7699efb6802420298b4a20d1a61a7484`.
It contains OpenStreetMap data dated 17 October 2026, using the OpenMapTiles
schema. It is not bundled in the APK. Detailed coverage is verified in Romania;
this does not establish equivalent Moldova or Europe-wide coverage.

The map displays mapped minor roads, building footprints and approximate 3D
heights, fields and their recorded boundaries, grass, forests, parks, water and
railways. Detail depends on the underlying OSM mapping; absent farm boundaries
or building heights are not surveyed measurements. Light mode preserves the approved daylight cartography. OLED Dark uses a black
base with visible night colors for roads, buildings, terrain and water; it does
not remove detail layers.

## Simulate the route you chose

After planning, select **Simulate selected route**. The vehicle follows that
route's road geometry. The speed slider ranges from **0 to 200 km/h** and can
be changed during playback. Zero holds the current position. Pause/Resume,
Stop and Replay are available. Opening the menu or leaving the app pauses
simulation; rotating the screen preserves its position.

Simulation speed is separate from the truck profile's maximum routing speed.
Simulation does not send location fixes to Android or the real GPS service.

## Driving display and settings

Use the 2D/3D map buttons, or Settings â†’ Map view & units. Map perspective and
vehicle marker are independent: a perspective map can keep the arrow, or display
only the truck. **Menu â†’ Settings â†’ Appearance** offers two saved choices:

- **Light Â· Cream**: creamy-white menus/controls with the approved natural map.
- **Dark Â· OLED black**: pure-black menu/control surfaces and a separate night
  map with champagne roads, warm-brown buildings, dark-green fields/forests and
  dark-blue water. Roads and labels stay visible.

Light is the default for a new installation. Theme changes preserve the route,
camera, simulation position and independent 2D/3D map/marker choices. At street
zoom, 3D mode shows extruded buildings where the detail data supplies them.

Vehicle profiles accept private truck photos through Android's file picker.
Tap numeric measurements to type them directly. Fuel, emission and tunnel
choices have dedicated selection pages; hazardous load types are independent.
Fuel, year, trailer count and emission category are saved metadata and do not
yet add routing restrictions beyond the engine's supported truck profile.

For actual GPS guidance, grant precise location, enable device location, and
start near the planned origin with a fresh accurate fix. Offline voice needs
an installed voice. Country data can be incomplete; this build is not road-validated.

## Coverage and outstanding work

Romania is currently the only published country package. The other country
flags do not imply available downloads or cross-border coverage. The separately
provided local Romania-Moldova package has newer 17 October 2026 OSM data;
the download catalog still points to the published Romania package. Moldova
and cross-border journeys have not received equivalent navigation validation.

An older native ADR detour test remains unresolved: the app refuses a route
through a prohibited category C tunnel but does not find the expected alternative.
The native profile suite must not be described as fully passing. Android 8's
software-emulator graphics crash also remains unresolved for the new renderer.
See [INTERFACE-INTEGRATION.md](INTERFACE-INTEGRATION.md) and the accompanying
verification report for precise test results.

The working source is on `codex/approved-interface` in the existing EuroRig
checkout. A source backup of the starting revision is retained in Project
EuroRig Design/backups. Nothing has been pushed or published to an app store.

## Current map browsing revision

Restricted roads use light red with small repeated crosses, without floating
tags. Markings update for the selected truck profile. Close zoom reveals local
streets; regional views retain main-road restrictions. Dark red is reserved for
heavy traffic, which is not yet supplied by a live feed.

Pan the map to show Re-center. Its truck/trailer control fills for 30 seconds,
then returns to a fresh vehicle position. A new gesture restarts it; menus and
search suspend it. Without fresh GPS it waits rather than moving to a seed point.

The map does not yet contain complete municipal truck-zone/permit/corridor rules
for Galați, Brăila or Bucharest. Unmarked roads are not confirmed suitable or legal.
Tight-turn/swept-path feasibility and complete ADR/country-law validation remain
unfinished. The known ADR alternative-corridor case still safely refuses routing.
Current 4K emulator measurements are about 22–23 renderer callbacks/s; reliable
120 FPS has not been achieved or demonstrated.


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
