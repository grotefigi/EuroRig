# EuroRig

Free, open-source truck navigation, made by drivers for drivers.

**0.5.0-dev: Romania is the first country for device testing.** The app installs
with no maps. Download a country once; roads, search, truck routing and GPS then
run locally. Coverage target: Europe excluding Russia.

[Repository](https://github.com/grotefigi/EuroRig) ·
[Development APK](https://github.com/grotefigi/EuroRig/releases/tag/v0.5.0-dev) ·
[Country maps and OSM source](https://github.com/grotefigi/EuroRig/releases/tag/maps-current)

## Install and try Romania

1. Install `EuroRig-0.5.0-dev.apk`. This is a debug APK for testing; EuroRig is
   not published on the Play Store yet.
2. Open **Maps → Download Romania**. The public GitHub catalogue needs no
   account. Download size is about 407 MB; reserve at least 2 GB of free space.
   Notifications show progress. Pause retains partial bytes; download resumes.
3. Set actual loaded dimensions, gross weight and axle load under **Truck**.
   Choose endpoints with offline search, coordinates or a map tap. Plan a route.
4. For GPS testing enable precise location, choose **More → Start at GPS**,
   select a destination and replan. Guidance needs a fresh fix within 250 metres
   of the planned origin. Choose both endpoints explicitly; the app does not
   start with a hidden sample trip. Selected endpoints survive restarting.
5. Repeat planning/search/guidance in airplane mode after installing the map.
   Spoken guidance needs an installed offline TTS voice. **More → Mute voice**
   provides visual guidance when a voice is unavailable.

Manual import works through **Maps → Import country (.eurorig)**. A single
Romania package includes routing, display and search; no other country is needed.
OSM snapshot: 2026-10-04T20:20:21Z. It has 636 routing tiles, 1,057,239 roads and
1,747,703 places. Installed data uses about 871 MB plus the saved 407 MB archive.
Updates temporarily need more space.

**More → Appearance → Dark mode** switches both the map and controls immediately.
The choice survives restarting. Changing appearance during guidance preserves
the route, foreground service and camera. Portrait uses map overlays; landscape
uses a scrollable sidebar. Search stays above the keyboard.

## Truck profiles, routing and restricted deliveries

Set height, width, length, actual loaded gross weight, maximum loaded axle weight,
total axles and maximum speed. Hazardous loads distinguish general material,
water pollution and explosives. Select the applicable ADR tunnel code B, C, D
or E from the transport documents; mixed and quantity-dependent codes require
human resolution. Mapped unknown tunnel categories are excluded for those loads.

**Shortest**, **Easiest** and **Economical** are local routing preferences.
Shortest prioritizes distance, Easiest penalizes maneuvers, and Economical strongly
penalizes minor roads and favours highways. These use the existing native engine;
they do not yet prove globally minimum distance/turn count or highway-only travel.
All modes retain dimensions, weight, axle load, access and turn checks.

**Delivery access** is a per-trip option for a driver with permission. It permits
supported access exceptions only within 2 km of the destination, uses shortest
routing, audits native route ways against local OSM evidence and marks restricted
segments amber with their length. Physical limits, ADR, unknown restrictions,
barriers and bridges are not waived. It can refuse a route. The least possible
restricted distance across every alternative is not yet guaranteed. Departure
from a private origin is allowed only along its initial restricted segment within
250 m; a blanket truck prohibition is not waived for departure.

Updated country maps paint mapped restrictions red for the profile, show numeric
limit badges, and mark uncertain evidence amber. The data is incomplete: a sample
3.5-tonne restriction visible in the reference app was absent from OSM. No limit
is invented from a screenshot. Verified municipal rules and driver reports need
an independent, source-backed update pipeline.

GPS controls include **Use GPS**, **Overview**, zoom and guidance **Recenter**.
Starting guidance zooms to the driver arrow in the lower part of the usable map
and follows fresh positions. Dragging pauses following. Recenter resumes it; stale positions and speeds are
marked unavailable. The map remains north-up; an original blue direction arrow
uses moving GPS fixes. Route color disappears behind matched progress, including
partial edges; arrival clears it and a new route begins with a full trail.

Truck map detail hides ordinary pedestrian/cycle paths and minor tracks without
explicit vehicle access evidence. Tagged truck or delivery approaches remain
visible. Main roads, residential streets and industrial service roads remain,
including their restriction markings. Small road classification or missing width
alone cannot establish whether a truck fits. This is a display filter: source
geometry and restriction evidence remain on disk and routes are still audited.

## Country downloads

**Choose another country** lists published packages. **Downloaded countries**
selects a saved package. One country is active at a time in this development
build; saved archives remain on the device.

**Download all Europe** queues countries when a complete catalogue is published.
It currently reports unavailable coverage because only Romania is published.
It does not present Romania alone as Europe. Seamless cross-border routing needs
a coherent European build and country partitions: independent country graphs
cannot simply be concatenated. See [ROADMAP.md](ROADMAP.md).

Downloads use HTTPS and verify SHA-256 before installation. Only map downloads
use the network; routing has no tile URL or server fallback. No account, billing
SDK, subscription, analytics or navigation server is required. Community mirrors
can be configured under **Map download source**. Debug builds allow loopback HTTP
for emulator tests only. Checksums detect corruption, not publisher identity.

The version-controlled default catalogue is
[`maps/catalog.json`](maps/catalog.json); binaries remain free GitHub release
assets. Existing 0.3 clients retain their original catalogue and package.

## Implementation and build

Minimum Android 8/API 26, compile/target API 37. Valhalla Mobile 0.6.3/core 3.6.3
memory-maps local routing tiles. Truck costing receives dimensions, loaded
weights, hazmat and toll/ferry/unpaved exclusions. It has no car-route fallback.
SQLite roads and FTS4 search stay on disk; bounded queries render a viewport.
GPS uses a foreground service and offline device voice. Favourites, profiles and
GPX export are local. Failed transactional imports retain the active map.

See [UI audit](docs/UI_AUDIT.md) for exercised controls and remaining gaps, and
[driver feature priorities](docs/DRIVER_FEATURES.md) for the expanded roadmap.

Use JDK 21, SDK platform 37.0/build-tools 36.0.0, Gradle 9.6.0 and AGP 9.4.0.
Configure private `local.properties` with `sdk.dir`.

```powershell
./tools/build.ps1 -Tasks ':routing:test',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:lintDebug'
python -m unittest discover -s tools -p 'test_*.py'
```

Use `bash gradlew` on other hosts. `-PmapCatalogUrl=https://your-host/catalog.json`
overrides the mirror. For country builds install `tools/map-requirements.txt` in
a dedicated Python 3.12 environment, then run:

```text
python tools/build_country.py work/romania --pbf romania.osm.pbf
python tools/make_catalog.py catalog.json romania=work/romania/Romania.eurorig
```

Choose a fresh directory. Building needs more RAM/storage than using a map.
See [docs/NATIVE_REGIONS.md](docs/NATIVE_REGIONS.md) and [VERIFICATION.md](VERIFICATION.md).

## Development limits

This build has not passed road validation. Missing OSM restrictions cannot be
detected. Country laws, time-dependent restrictions, complete ADR rules,
emission zones, weekend/holiday bans, exemptions, trailer and emission rules and legal driver
hours need work. Romania was built without timezone polygons. ETA is approximate.
Complex-junction matching, lane guidance, alternatives, multiple stops, complete
geocoding and languages are unfinished. Guidance stops after process death.
Spoken output, low-end physical devices, intermediate Android versions and 32-bit/
16-KiB-page devices need runtime validation. This is not a finished Europe app.

## Research and licences

The supplied Eurowag APK was inspected with REA/Android tooling and its UI studied
on an authorized Tab S9; see
[analysis/REFERENCE.md](analysis/REFERENCE.md). No Eurowag/RoadLords/Sygic code,
maps, artwork or voices are included.

Original code: MIT. OSM-derived data and original PBF: ODbL 1.0,
© OpenStreetMap contributors. Fictional fixtures: CC0. Dependency licences are
included. Andorra fixtures are source/test assets only; no maps ship in the app.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
[OpenStreetMap copyright](https://www.openstreetmap.org/copyright).
