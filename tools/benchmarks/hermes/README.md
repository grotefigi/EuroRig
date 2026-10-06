# Hermes / EuroAxel contributions

Selected from handoff commit `62517c9fb777b7e0298851b2847e4cef441269e8`.
Seven original files were SHA-256 checked before copying. `UPSTREAM.sha256`
records those bytes; EuroRig subsequently hardened the ETA helper's input
validation. Code is MIT; corridor data is ODbL 1.0, © OpenStreetMap contributors.
The full notice is in `LICENSE`.

The corpus, three historical comparison datasets and runner are research
evidence. Host Valhalla is 3.6.3, GraphHopper is 10.0, and the custom engine is
EuroAxel G3. Complete configuration parity has not been independently established
for every historical row. Missing metadata is unknown. A close distance is not
route approval or proof of truck legality.

Run `python tools/benchmarks/hermes/runner/compare.py` for historical divergence.
The runner does not fail a build on cross-engine distance differences.

The host Valhalla reference and EuroRig's original Romania PBF share SHA-256
`5af1bc2e85cfd6df516173207dd5302dde8b92ea05e59a3beaab27893c605bf1`,
329,689,991 bytes. Graph construction, preferences, correlation, distance
measurement and the additional EuroRig restriction audit can still change routes.
A newest-object timestamp is not an extract replication timestamp.

The ETA helper supplies a physical lower bound and permits additional turn/wait
time. Preference cost must never replace ETA. `tools/check_corridors.py` applies
it to real Android results. The arbitrary percentage tolerance, disconnected-spur
toy, proprietary reference files and alternative runtime engine were not imported.
