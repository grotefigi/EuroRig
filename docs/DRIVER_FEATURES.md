# Driver feature priorities

The European truck GPS reference document was reviewed as a source of ideas.
The following items extend the roadmap; they are not claims of implemented
features. Existing offline Valhalla routing, Android location, SQLite search and
country installation remain in place.

## Next data and navigation work

1. **Traceable restrictions.** Store the issuing source, effective date, update
   date, coverage, licence and confidence for each imported rule. Retain conflicting
   evidence for investigation. Missing dimensions mean unknown clearance. Research
   Romanian road-authority and municipal data before adding local truck bans.
2. **Truck parking and services.** Model truck entrances, carriageway direction,
   truck capacity, fees, ADR acceptance, amenities and certified security separately.
   Rank parking along a route by a truck-drivable detour, not straight-line distance.
   Unknown suitability and unverified certification must remain explicit.
3. **Walking after parking.** Retain pedestrian geometry in source packages. A
   separate future walking mode should use legal crossings, gates and walkable
   exits and calculate actual walking routes to shops and services. Hiding footpaths
   from the truck display must not delete the evidence needed for this feature.
4. **Richer offline maps.** Evaluate MapLibre Native and Planetiler/OpenMapTiles
   against the current renderer, verifying current API 26 support, licences,
   local styles/glyphs/sprites and fully local tile access first. Compare measured
   frame time, memory and startup on physical low-end hardware before replacing
   a working subsystem. Rotation and pitch remain unimplemented in 0.5.
5. **Country and load rules.** Expand articulated/trailer modelling, conditional
   bans, loaded versus rated weight, ADR mixed-load/quantity rules and emission
   zones only with documented engine support and authoritative validation.
6. **Ferries and terminals.** Preserve static freight connectivity offline and
   validate truck/ADR acceptance, size limits and actual truck entrances. A ferry
   avoidance preference is not complete ferry planning.
7. **Optional current information.** Research DATEX II, NAPCORE directories and
   TN-ITS feeds for incidents, temporary restrictions and parking occupancy.
   Match external locations to the local graph, retain timestamps and expiry,
   and distinguish last-known data from current data. Normal navigation stays
   local. Any future community location sharing requires explicit opt-in.

These tasks require independent source and licence review. Camera-warning laws,
toll prices, driving-time compliance and country bans are not inferred from this
reference document or hardcoded without current primary sources.

## Truck display filtering

`TruckMap` keeps motorway/main-road classes, local residential streets and
industrial service roads. It hides ordinary footways, paths, pedestrian/cycle
roads and tracks unless stored vehicle-access evidence warrants displaying them.
Truck/delivery access and conditional exceptions stay visible for verification;
display visibility does not grant route permission or prove sufficient clearance.
Older enriched packages retain their explicitly HGV-allowed path behaviour.

The distinction follows OSM's [highway classification](https://wiki.openstreetmap.org/wiki/Key:highway)
and [HGV access tags](https://wiki.openstreetmap.org/wiki/Key:hgv). Highway class
describes road function, not a measured width. Never remove a residential or
communal road solely because its name or class sounds small. Route overlays and
restriction audits continue to use the retained data.
