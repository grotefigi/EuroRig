# Delivery plan: Europe excluding Russia

The current app is an independent, executable prototype. Completion of the
original product request requires all of the following milestones.

1. **Production offline engine and renderer.** Valhalla Mobile 0.6.3 is embedded
   and routes a full Romania country offline. Indexed SQLite roads and FTS
   search are implemented. Improve rendering/geocoding, validate native rules,
   and benchmark
   Android 8 devices as well as API 37. Keep the current small Java graph as a
   transparent restriction test harness, not a continental-scale engine.
2. **European map pipeline.** Romania has a dated country package, resumable
   downloads and a public catalogue. Extend OSM PBF preprocessing into
   indexed regional packages; partition by actual geometry, exclude Russia,
   retain cross-border connector IDs, publish checksummed version manifests,
   supply source dates and attribution, support interrupted updates and rollback.
   Validate independent package installation and offline cross-country trips.
   Clarify coverage treatment for transcontinental Turkey and the Caucasus.
3. **Truck rules.** Mapped dimensions, loaded weights, access evidence and ADR
   load/tunnel codes are now audited; delivery access has a bounded per-trip
   permission. Complete global restricted-distance minimization, literal minimum
   turn counts, stronger highway continuity and verified municipal restriction
   overlays. Directional and conditional tags, node and via-way turns,
   ADR/tunnel category, trailers, axle count and loads, low-emission/access zones,
   destination exemptions, local default laws, scheduled heavy-goods bans and
   conservative unknown-data display. Maintain traceable source-backed fixtures.
4. **Navigation.** Edge snapping, map matching at complex junctions, roundabout
   exits, lane/signpost instructions, missed-turn recovery, GPS loss/tunnels,
   multiple stops, route alternatives, traffic-free ETA, route persistence,
   offline address/POI index, languages and driver-friendly landscape mode.
5. **Driver validation.** A published route corpus across every supported
   country, sign/restriction audits, independent truck-driver review and logged
   test drives without exposing private driver tracks. Test airplane mode,
   denied/revoked permissions, screen-off guidance, low memory and damaged maps.
6. **Public release.** A public Git repository and free map-download hosting
   are available. Complete contributor guidelines, issue
   templates, reproducible builds, dependency/data licence audit, signed APK
   updates with a maintained signing key, accessible distribution, and a funded
   map-build/distribution plan without charging drivers a subscription.

All core navigation remains on device. Fresh maps need a way to reach the device
(manual import or optional downloads); real-time traffic and crowdsourced reports
cannot stay fresh with an entirely disconnected device. These should be optional
future features and never required for routing.

Reference candidates:

- [Valhalla](https://github.com/valhalla/valhalla): routing/costing engine candidate.
- [OsmAnd](https://github.com/osmandapp/OsmAnd): mature Android/offline ecosystem
  to evaluate, with its own licence and native build requirements.
- [BRouter](https://github.com/abrensch/brouter): offline Java routing candidate;
  verify truck-tag preservation before adoption.

Valhalla Mobile is integrated. OsmAnd and BRouter were evaluated as alternatives.
