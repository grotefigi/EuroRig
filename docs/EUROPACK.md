# EuroPack v1

All numbers use big-endian byte order. Text is an int32 byte length followed by
UTF-8 bytes (not Java's modified UTF-8). Boolean values are one byte, 0 or 1.

| Field | Encoding |
|---|---|
| Magic | Four bytes `ERG1` (`0x45524731`) |
| Name, attribution, source date information | Three length-prefixed strings |
| Fictional training flag | Boolean |
| Node count | int32 |
| Nodes | float64 latitude, float64 longitude, string label |
| Directed edge count | int32 |
| Directed edges | int32 from index, int32 to index, int64 OSM way ID, string road name, string highway class, five float64 limits (height/width/length metres, gross/axle tonnes), int32 flags, float64 approximate speed km/h |
| Turn count | int32 |
| Turns | int32 via-node index, int64 incoming way, int64 outgoing way, boolean only-turn |

Restriction flag bits: 1 blocked access; 2 hazardous goods excluded; 4 toll;
8 ferry; 16 unpaved; 32 unrecognised/conditional restriction excluded.
Zero for a dimension limit means **no value in this package**, not verified
clearance. Indices refer to the node array. Roads are directed; reverse travel
requires a separate edge. Geometry retains each successive OSM node.

The compiler writes supported node-via `no_*`/`only_*` relations. Via-way,
conditional, ambiguous, missing-member and unsupported turn relations exclude
affected member ways. Immediate U-turns are excluded by the current router.

Structural limits: 250,000 road nodes, 1,000,000 directed segments, 1,000,000 turn
records, 65,536 bytes per text field, 128 MiB input-file limit in the Android
importer. The loader additionally rejects graphs whose estimated allocations
exceed half of the device heap. These are defensive caps, **not** tested mobile
performance guarantees. Production maps require a different indexed format.

Packages are uncompressed. Import uses the Storage Access Framework, validates
all records and rejects trailing bytes before atomically replacing the existing
local file. No map data is sent over a network. A SHA/signature catalogue and
download/update manager are future production work.

Compile an OSM XML extract with `tools/compile_map.py`. Preserve complete source
relations and geometry. Partial data outside an extract cannot be inferred.
Binary PBF preprocessing is not implemented in this format.
