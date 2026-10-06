# Supplied Eurowag build: observed structure

Target: `Eurowag+Navigation+-+Truck+GPS_4.14.5-91dba1e4_APKPure.xapk`

SHA-256: `13c87608e56d22ffb60229828ec791a0d8ce09a4e9965cdef96f7a41dce2ccfb`

## Executed analysis

- Installed REA 3.2.1 locally under `analysis/private/rea-cli` with dependency
  lifecycle scripts disabled. No global MCP/agent configuration was changed.
- Ran `rea doctor --json` and `rea inspect-artifact` on the XAPK, base APK and
  ARM64 split. REA's safe archive provider successfully inventoried the packages.
  Raw evidence remains local under `analysis/private` and is excluded from Git.
- Used Android build-tools `aapt dump badging` and `aapt dump xmltree` on the
  base APK. Read ZIP entry metadata and DEX class descriptors statically.
- The initial archive pass did not execute the target. Authorized tablet UI
  observations are recorded separately below. No paid feature was unlocked or bypassed.

## Direct observations

| Evidence | Observed fact |
|---|---|
| XAPK manifest + compiled APK manifest | Package `com.roadlords.android`, version `4.14.5-91dba1e4`, code 41405 |
| `aapt dump badging` | Minimum API **32**, target API 36 |
| XAPK inventory | Base APK, ARM64 split, MDPI split, icon and manifest |
| ARM64 split inventory | `lib/arm64-v8a/libsygic.so`, 76,176,480 uncompressed bytes |
| Base assets | Third-party licence file identifies software used by Sygic Maps SDK |
| DEX descriptors | Sygic namespaces for route planning, navigation, favourites, search, lane assistance and route errors |
| Declared permissions | Network, location, foreground location service, billing and other application permissions |

Class/library names establish presence, not implementation details or executed
behavior. A third-party APK distributor's metadata does not authenticate the
publisher's signing identity. The compiled manifest independently corroborates
the minimum/target API values.

## Implications and limits

The evidence is consistent with an Android interface backed by a substantial
Sygic native component. It does not recover routing algorithms, restriction
semantics, map formats, offline capability, subscription control flow or source
licence rights. Those questions remain unresolved.

The supplied build cannot directly satisfy Android 8 support. Repackaging it
would also leave EuroRig dependent on an unreconstructed native engine. The
current EuroRig implementation therefore has its own code, synthetic fixture,
map format and renderer.

REA's archive tools work on this Windows host. Its doctor reports missing native
analysis engines. The upstream [REA documentation](https://github.com/morluto/rea)
states that Windows Ghidra operations are unavailable; this host also has no WSL
installation. Native decompilation has **not** been completed. A supported
Linux/macOS analysis environment with Ghidra or Hopper is needed for that work.

Reproduce the non-executing inventory:

```text
python tools/inspect_reference.py /path/to/supplied.xapk
rea inspect-artifact /path/to/supplied.xapk --json
```

Public feature reference:
[Eurowag navigation FAQ](https://www.eurowag.com/faqs-eurowag-navigation), which
describes vehicle profiles, route endpoints, stops and road-type avoidances.
These descriptions are feature requirements, not recovered implementation.

## Authorized tablet observations, 2026-10-06

The installed 4.14.5 build was opened through its normal launcher on a Galaxy
Tab S9 (Android 16/API 36). The base and ARM64 APKs match the supplied XAPK
byte for byte. The tablet density split differs. APK presence and class names
still do not establish the native algorithm. Static comparison found profile,
search, route-overview, voice, parking and lane-assistance class declarations.

Normal UI inspection covered the map, menu, search, offline maps, vehicle profile,
load types, tunnel codes and navigation settings. Observed profile fields include
dimensions, loaded gross/axle weight, vehicle/trailer axle and trailer counts,
maximum speed, fuel, year and emission class. Hazardous load choices distinguish
general material, water pollution and explosives. Tunnel choices B-E exclude
their own category and higher categories; no restriction is a separate choice.
The map shows red restrictions and numeric weight signs, with compact search,
profile, parking/services and position controls. Its offline screen reported no
maps downloaded; the reference map comparison therefore used its online data.

These observations inform independent EuroRig requirements. No APK code, map
features, imagery, icons or voices are transferred into EuroRig. Personal account
information, location/history, screenshots, UI dumps and pulled APKs remain in
ignored private evidence. No subscriptions were changed, no protected feature
was bypassed and no root/instrumentation hooks were installed on the tablet.

A sample Galati weight sign could not be corroborated from the corresponding
OSM road tags. Matching visual colour alone cannot establish a legal restriction
or exemption. EuroRig needs verified public/local sources for missing restrictions.
Native Sygic source, paid route behavior and proprietary map semantics remain
unrecovered. EuroRig uses its own code and licensed Valhalla/OSM data.
