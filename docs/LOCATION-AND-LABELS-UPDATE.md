# EuroRig 0.5.1 — location, map labels and distance display

2026-10-21. APK version code 6; debug package `org.eurorig.app.dev`.
SHA256 `9c323c1d2d3fb51cedaa09216cd7c84b6637ebed41eb75d6b62e1e851a06f3b7`. Desktop copy: `EuroRig-Romania.apk`.

## Delivered

- Search is at the top, with an original magnifying-glass vector at the start
  and the contrasting menu button integrated at the end. Truck/name, weight and
  height icons remain beneath it. The menu remains available during guidance.
- Street/place labels now use the native map renderer and its camera transform,
  replacing the separately composited Android Canvas overlay. Native collision
  placement and a bounded offline system-font image atlas are used. Labels stay
  above dynamically added map/building layers; no downloaded glyph service or
  proprietary Eurowag assets are used.
- Foreground map browsing obtains fresh platform GPS/network fixes, shows their
  accuracy and a native accuracy area, and follows/recenters without replacing a
  manual route origin. A manual origin cancels a queued GPS-origin request.
  Panning cancels pending automatic follow. Activity pause stops browsing updates;
  user-started guidance continues through its existing foreground service.
- Device-location settings and precise-permission flows are connected. Coarse
  results are explicitly approximate. Route starts/guidance require a fresh fix
  accurate to 50 m or better; stale/future/nonfinite fixes are rejected. Guidance
  confirmation obtains a new fix rather than reusing an expired dialog-time fix.
- Padding changes no longer cancel search/GPS camera animations. Short portrait
  layouts keep map controls clear of the speed readout.
- Maneuver, remaining and route-summary distances share one formatter: 3980 m
  becomes 3.9 km; 1000 m becomes 1.0 km; 980, 820, 100 and 50 remain metres.
  Kilometre tenths are truncated to match the requested example.

## Using GPS

Turn on Android Location and allow precise location while using EuroRig. Tap GPS
to find/follow your position without changing the planned route. In Edit route,
Use current location explicitly selects a live origin; wait for an accurate fix,
choose the destination and plan. Start guidance uses the foreground notification
with a Stop action. Offline planning remains available without a location grant.
No location-history recording or background-location permission was added.

The connected Tab S9 supplied approximate fixes around ±100 m during this indoor
session. Physical outdoor GNSS guidance and road validation remain unperformed.
The emulator GPS-provider check used a fictional A3 route and passed foreground
guidance, actual provider-fix reception, route progress and Stop.

## Verification and performance

Build succeeded; 178 JVM routing tests, 0 failures;
lint 0 errors/74 warnings. Native small-screen rotation/simulation,
GPS/marker independence, location callbacks/lifecycle, exact coordinate/address
search, audited waypoints and the real 30-second Re-center checks pass. 4K layout
and label checks pass. Actual injected touch swipes on the Tab S9 verified label
anchors in both themes. Tablet settings, truck and endpoint values were compared
before/after and preserved. Final distance units were checked in the running UI.

Tab S9 native map surface: 1600×2394 within its 1600×2560 display, reporting 120 Hz.
Each six-second city pan was observed for approximately 6.2 seconds: both themes
produced 725 renderer callbacks, roughly 117 callbacks/s. Median intervals were
8.45 ms (cream) and 8.43 ms (OLED);
p95 was 9.66/9.59 ms.
These are short renderer measurements, not proof of sustained 4K/120 FPS.

4K emulator surface 3840×2112 on a 60 Hz emulated display: light/OLED pans yielded
154/189 callbacks in approximately 6.2 seconds.
Rendering measurements preceded the final distance-only/UI overlap polish; the
native label-rendering implementation is the same. Reliable 4K/120 FPS remains
unachieved. The emulator disconnected once during a later check; it was restarted
with its map data retained and the final navigation check passed.

Municipal truck-zone completeness, the known ADR-C legal-alternate-corridor
failure, country-law/cross-border/API26 and physical-road validation remain open.
The prohibited tunnel is still refused. No complete-native-routing-suite pass
or road-suitability guarantee is claimed.

Private tablet captures, settings and raw device logs remain in ignored
`analysis/private/resume-2026-10-21/` and are not exported. Public evidence contains
only emulator screenshots/logs and aggregate measurements.

Implementation references: [MapLibre symbols](https://maplibre.org/maplibre-style-spec/layers/)
and [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager).
