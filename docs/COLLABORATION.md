# One EuroRig app

The user authorized direct collaboration with DeepSeek in Hermes. Both agents
selected EuroRig's Android app (`org.eurorig.app`) and this repository as the
product. Hermes contributes reviewed validation and map-building work, rather
than a second runtime. Each agent writes in its own directory; selected
contributions are reviewed before integration. Raw chat/device evidence stays local.

## Integrated result, 2026-10-06

Seven licensed files from Hermes handoff
`62517c9fb777b7e0298851b2847e4cef441269e8` provide nine corridor definitions,
historical measurements, a divergence runner, ETA helper and MIT notice. Imported
hashes remain in `tools/benchmarks/hermes/UPSTREAM.sha256`.

EuroRig's native adapter runs the actual Android router and evidence audit,
records requests, map manifest, snapped endpoints, timing and failures, and
preserves driver settings. Passing this harness alone is not route approval.
The separate checker validates real outcomes, physical ETA bounds and comparable
Shortest results. It retains pairs with different snaps as excluded comparisons,
without tuning a percentage tolerance.

Joint testing exposed hierarchy pruning in Shortest. The route request now
disables it with a 5,000 km straight-line configuration limit. The edge-walk
audit receives a separate copy of truck costing without this search option:
Mobile 0.6.3 crashes if it is passed to this matcher. Dimensions, loaded/axle
weights, ADR, access, one-ways and avoidances remain checked.

Audited API 26 Galați–Bucharest Shortest fell from 244.9284 to 226.3575 km;
Galați–Nădlac fell from 741.0014 to 722.3086 km. These development measurements
are not validated road instructions or proof of global optimality. The airport
centroid is correctly rejected beyond 250 m from a road; a separate mapped-road
fixture succeeds and is not labelled a verified truck delivery entrance.
The app now explains the cutoff and asks for a mapped road/signed truck entrance.
Published PBF hash metadata was added to the catalogue.

The ten-case corpus covers four modes: 28 route outcomes and 12 expected
rejections. Hard truck/ADR/delivery limits are tested separately in the original
synthetic native networks in every mode. Final device evidence is in
`VERIFICATION.md`.

## Reproduce

Build the app and test APK. Install Romania on a dedicated emulator, then:

```text
python tools/measure_corridors.py tools/benchmarks/romania-native.json --device emulator-5554 --output analysis/private/romania-native.json
python tools/check_corridors.py analysis/private/romania-native.json
```

The adapter stops the emulator app but does not clear data. It rejects physical
serials. Profile: 4 m / 2.55 m / 16.5 m, 40 t / 11.5 t axle, five axles,
80 km/h; ferries and unpaved roads excluded. Expected border failures apply only
to Romania-only coverage and must change when multi-country data is installed.

## Next joint implementation

Build coherent RO/HU/RS Valhalla data and prove partitions work alone and together.
Use stable tile IDs and a build-generation ID, preserve cross-tile hierarchy links,
track countries that own overlapping tiles, and remove shared tiles only after
their last owner is removed. Reject mixed generations. Separate country downloads
remain the requirement. Europe excluding Russia follows after regional seam tests.

Missing tiles may prevent any route from being returned. Inspecting a successful
route shape alone cannot reliably identify missing countries. A coverage index
and download suggestions need separate validation. Independent country graphs
cannot be concatenated to fabricate seamless routing.
