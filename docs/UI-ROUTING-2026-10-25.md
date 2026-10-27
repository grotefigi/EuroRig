# Route planning and interface verification, 25 October 2026

The approved MapLibre interface remains the baseline. The journey editor and country options now use original native EuroRig controls matching the supplied reference layout: black panels, blue icons, a connected stop timeline, drag handles, route metrics and independent country sections. The landscape editor keeps the map visible. Light mode remains available.

## Delivered behavior

- Ten intermediate stops, with the driver's order preserved. Drag-and-drop and accessible earlier/later actions reorder stops. Replacing a stop preserves its slot; canceling search preserves the trip.
- Distinct selected endpoints calculate an audited native route. Equal endpoints receive a specific editor/destination action before calculation. A round trip with a distinct intermediate stop is permitted by validation.
- The main map no longer has GPS or Route buttons. Current location and route overview remain in the navigation menu; current position is also available when choosing an origin.
- Every country has highways, toll roads, congestion charge zones, ferries, unpaved roads and country avoidance. Settings persist independently by ISO country code. Congestion avoidance is disabled and explicitly marked unavailable because verified zone data are absent.
- Country preferences use each traced edge's native administrative metadata. Missing required metadata causes an explicit refusal. Legacy maps cannot silently ignore active country settings. Truck dimensions, weight, axle, ADR and access checks remain in the acceptance path.
- The planner offers the primary route and up to two distinct audited candidates, when available. Candidates are found through bounded seed exclusions, not a claim of mathematically optimal k-shortest paths. Only successful audited candidates appear. Selecting a candidate restores its own warnings and endpoint-relocation information.

## Restriction coverage: unresolved data work

The recovered Romania–Moldova package has SHA-256 `ec5e8826c63101042c3fdf79ce58685225b5d57fe5749781c0ca279fae82cc88`. Its extracted display database has SHA-256 `8ffe21db72b6e4cf64a1cce4e53a971edab8e4d3a1489f503a5d195971a77b5f`.

An illustrative Galați bounding box, 45.38–45.49° N and 27.93–28.12° E, contains 6,859 road records. Only 84 have a positive numeric weight limit, 18 a numeric height limit and two an explicit `hgv` tag. There are 2,998 joined road-rule records, many describing ordinary access or direction rather than truck prohibitions. These are record counts, not a restriction-completeness percentage.

The overlay queries every road class at street zoom with `LIMIT -1`; no arbitrary city feature cap applies. Native checks compare applicable database records against the actual MapLibre source across eight cities, two zoom levels and both themes. Native tiling can collapse segments shorter than one screen pixel; a roughly 0.5 m private service segment in Cluj explains the initial one-record source mismatch. No restriction was invented or lengthened to make a pixel test pass.

The historical [Galați HCL 245/2015 text](https://hcl.usr.ro/galati/2015/h245) describes municipal access rules and refers to annexed road lists. Its [official PDF](https://www.primariagalati.ro/portal/hotarari/010615/HCL%20245.pdf) was inaccessible during verification. The current amendment chain, complete authoritative annexes and exact road correspondence have **not** been established. The separate `Pr_HCL01194.pdf` document is a proposal, not proof of enacted current restrictions. These sources were not imported as current routing law.

**Citywide municipal coverage is unfinished.** Rendering existing records does not fill missing municipal bans, permitted corridors or permit exceptions. The next data gate must establish current authoritative rules, licensing/reuse rights, boundaries and exact road matches, then rebuild the routing/display package and verify both routing and paint. Empty/unmarked streets are not proven legal truck access.

## Test and delivery evidence

Private references, screenshots, map inventory, build logs and native receipts are under ignored `analysis/private/ui-routing-2026-10-25/`. Original development app data and recovered maps remain untouched.

An isolated **EuroRig Preview**, package `org.eurorig.app.preview`, is installed on the authorized Tab S9 (SM-X716B, Android 16). The original `org.eurorig.app.dev` cannot be updated with the local debug key: the certificates differ. No uninstall or data clearing was used. The preview imports its own validated copy of the recovered map.

Rebuild the isolated app and companion with `gradlew.bat -I tools/preview.gradle :app:assembleDebug :app:assembleDebugAndroidTest`. The checked-in init script changes the debug package suffix and label only when explicitly supplied; normal builds retain their existing identity. After installing/importing a country into preview, run `adb -s DEVICE_SERIAL shell am instrument -w -e uiRoutingOnly true org.eurorig.app.preview.test/org.eurorig.app.NativeSmokeInstrumentation`. The focused suite refuses the original app package. Do not run app-data-clearing device scripts on physical devices.

That checked-in build configuration reproduces the installed APK bytes on this host: preview SHA-256 `3b7730d1ba353612b37d77afad612e76303cf621b1fbfdbfe994285a67436ce8`, companion `2e00fca1b18e3d12c07c09ece2400c3b2a0e443b871a419b2dfe4e69e0b49c15`. APK and private data hashes are separate checks; no claim is made about reproducible signing on another host.

The owner's saved truck/trip inputs are restored into the preview after testing. The original 527,015,936-byte OpenMapTiles detail map is also copied into preview storage; its SQLite quick check passes, attribution identifies OpenMapTiles/OpenStreetMap, and original/backup/preview SHA-256 values match: `a9938dc3e3401c21f6bc78bebc7dd50b7699efb6802420298b4a20d1a61a7484`. It is existing licensed cartography from EuroRig, not reference-app map extraction.

Native checks cover independent country options, real Romania administrative metadata, country avoidance, legacy-map refusal, ten audited stop boundaries, physical drag gestures, stop replacement/restoration, destination selection, location-origin handoff, equal-endpoint validation, removed map buttons and both themes/orientations. Compact 412 × 915 dp checks run on the tablet with temporary display overrides; the original display configuration is verified restored afterward. This is compact-layout evidence, not Pixel hardware evidence.

The final build passes 205 Java routing tests and 43 Python tooling tests, without failures, errors or skips. Both Android APKs build. Android lint reports zero errors and 85 warnings. Warnings remain and are not represented as a clean lint report.

Current native profile/ADR, interface/simulator, restriction-overlay, browsing and remaining-stop reroute checks have separate passing receipts. Detailed-map checks also pass: 165 rendered 3D buildings in the measured scene, 2D footprints, fields, water/forest, preserved map after a rejected bad import and separate day/night palettes. The test now sets both camera and building preferences explicitly, respecting their independent settings. Route/editor checks also pass with the recovered detail map loaded.

The historical ADR category-C profile fixture now passes on this branch. This does not establish complete ADR or on-road validation. The task ledger has seven met gates and one required municipal-data handoff. Actual driving, Pixel 8 Pro hardware, complete municipal coverage and congestion-zone data remain unverified or unavailable.
