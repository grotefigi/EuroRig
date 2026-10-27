# Map distribution and private candidate preparation

EuroRig keeps route calculation, search and installed map use on-device. The catalogue and map files are optional download inputs; an installed map continues to work if the catalogue host is down. Manual import remains available at **Offline maps → Import**.

## Static map hosting

The current split fits GitHub's public static services: `maps/catalog.json` is served from the repository, while each `.eurorig` file is a versioned GitHub Release asset. GitHub permits up to 1,000 assets per release; every asset must be smaller than 2 GiB, with no total release-size or release-bandwidth cap. A GitHub Free account includes 500 MB of Actions artifact storage, shared with GitHub Packages, so Actions artifacts are build receipts, not durable map hosting. See [release storage and bandwidth](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases) and [Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions).

Build a map from an approved, licensed source using the pinned tooling, then generate a catalogue for the directory containing the release assets:

```powershell
python tools/build_country.py work/romania --pbf romania.osm.pbf
python tools/make_catalog.py work/catalog.json `
  --base-url https://github.com/grotefigi/EuroRig/releases/download/maps-current/ `
  romania=work/romania/Romania.eurorig
```

Review the map manifest, licence/attribution, source dates, package size, compatibility and resulting SHA-256 before replacing `maps/catalog.json` or publishing assets. The catalogue builder validates ZIP CRCs, format/engine compatibility, required source/licence/attribution metadata, and the routing/display SHA-256 values inside each package; it carries those licence and attribution fields into the catalogue. It also rejects empty or 2-GiB-and-larger assets, Russia entries and duplicate IDs. Keep `.eurorig` assets out of Git history and Actions artifact storage. This preparation does not authorize a release, source-data redistribution or public-readiness claim.

## Installation, storage and rollback

The importer streams a package into app-private storage, verifies its manifest and inner checksums, and activates only after the native engine accepts it. A failed import leaves the selected map in place. Complete older map folders remain installed; **More → Select installed map** revalidates and reactivates one. Switching maps requires confirmation and clears route endpoints/interrupted-trip progress because those selections may not fit the other map. It preserves the map files and truck profiles.

Reserve space for the new uncompressed routing and display files, the current installed map, and the importer's 200 MiB free-space reserve. Catalogue download also keeps its compressed `.eurorig` archive. For an update, budget for both old and new installed maps until the new one passes activation; do not estimate from the archive size alone. Exact expansion varies by source and compression. The importer can reject a low-space install after staging has begun; it removes that failed staging folder and keeps existing complete maps.

Manual import uses Android's document picker and does not require a catalogue. If network hosting fails, install/import a licensed package once; routing and search then use that local map. The API 36 offline-route check with network unavailable is recorded in `analysis/private/c2-g4-current/f3-current-physical-matrix.json`. SHA-256 detects accidental/corrupt content and does not authenticate the publisher.

## Compatibility and support disclosure

`manifest.json` pins the package format, Valhalla engine version, data source/date, attribution and inner-file hashes. The catalogue repeats engine compatibility, exact package byte length and SHA-256. An incompatible package is refused; never edit the manifest or checksum to force an install.

The installed-map details screen reports source dates and the display database's bounding extent. That rectangle is not proof of complete road/place coverage. Address and POI search returns recorded map entries only; missing results do not prove a place is absent. Truck entrances, final approaches, local restrictions and legal suitability remain unverified where the source does not provide reviewed evidence. Check road signs and do not treat a route or map extent as legal approval.

## Candidate build evidence

Use `tools/build.ps1` with the repository's verified JDK 21/API 36 toolchain for reproducible Windows verification. A Preview APK is a debug-signed QA build with isolated package/storage identity, not a private release. Before any private release, create a separately signed candidate under the owner's approved signing process, retain the signing key privately with recovery, hash the APK and map, test fresh install and upgrade preservation, and bind installation/rollback instructions to that candidate. No release signing key or approval is stored in this repository.
