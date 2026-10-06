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
- No target app was executed; no paid feature was unlocked or bypassed.

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
