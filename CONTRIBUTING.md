# Contributing

EuroRig welcomes truck drivers, mappers, translators and developers.
Describe the vehicle dimensions and loaded weights when reporting a route
problem. Include road/OSM identifiers and the map version; avoid publishing
private trip history, home/depot addresses or customer delivery details.

Restriction changes need a fixture proving both a prohibited route and the
permitted alternative (or an explicit no-route result). Run the Java routing
tests, Python compiler tests, Android build and lint before contributing.

Use original code and appropriately licensed data. Keep third-party APKs,
decompiled proprietary implementations and analysis tools in `analysis/private`.
Do not commit secrets, production signing keys, local SDK paths or generated
large maps. Record source data dates and licence notices for any map contribution.
