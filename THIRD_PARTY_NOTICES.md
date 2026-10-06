# Third-party notices

The APK and source archive include `app/src/main/assets/NOTICE.txt` and full
licence texts in `app/src/main/assets/licenses`.

- Valhalla Mobile 0.6.3 and models/config 0.5.2: MIT, Adventure Consortium Inc
  (Rallista), Valhalla contributors, Mapillary AB and Mapzen. Valhalla core
  revision: `e2f017b16080f49203de245a211b09efab09cf72`.
- Native dependencies include Boost (BSL), Protobuf (BSD), RapidJSON (MIT/BSD),
  robin-hood/unordered_dense (MIT), LZ4 (BSD), date (MIT), Abseil (Apache 2.0)
  and LLVM libc++ (Apache 2.0 with LLVM exceptions).
- AndroidX, Kotlin/coroutines, JetBrains annotations, Moshi, Okio and Guava
  listenablefuture use Apache 2.0. Stadia Maps OSRM OpenAPI 0.0.10 uses BSD
  3-Clause; it supplies transitive models, not a navigation server.

- Android/Gradle tooling is used to build the app and is not part of EuroRig's
  original source licence. Its own notices and terms apply.
- JUnit 4.13.2 is a test-only dependency (EPL 1.0); it is not packaged in the APK.
- No maps are bundled in the application APK. Romania country packages, derived
  routing/display databases and original Geofabrik PBF are offered under ODbL 1.0
  in the separate map release. Its notice, snapshot date, hashes and build
  provenance accompany the download. See https://www.openstreetmap.org/copyright.
- Andorra validation data remains in `app/src/androidTest/assets` under ODbL 1.0,
  with provenance in its manifest. These fixtures are included in the source ZIP
  and test APK only. The old native fixture's routing snapshot date is unknown.
- `app/src/androidTest/assets/demo.europack` is fictional EuroRig data under CC0.
- Map-building dependencies `pyvalhalla` and `osmium` are used on the build host;
  they are not Android runtime dependencies.
- REA (MIT) is used as a private research tool, not linked into the APK.
- No Eurowag, RoadLords or Sygic assets or implementations are redistributed.

A production release still needs a full dependency/provenance audit and
reproducible native builds. This development APK uses upstream's published AAR.
