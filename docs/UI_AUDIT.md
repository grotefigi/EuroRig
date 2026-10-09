# EuroRig 0.5 UI and navigation audit

## 0.6 visual and accessibility follow-up, 2026-10-07

The follow-up keeps the tested navigation behaviour and replaces visual tokens
and controls using the installed UI UX Pro Max guidance. Opaque light/dark cards,
system fonts, original vector icons, blue primary actions, a red Stop action and
native focus/ripple feedback add no UI library or font dependency. The launcher
uses the same blue/night colours. See `design-system/eurorig/MASTER.md`.

Both API 26 and API 37 pass the existing 22-check UI sweep. Native camera,
arrow/progress and profile/ADR checks pass on both. API 37 passes injected GPS
loss/recovery/stop and theme switching during guidance. Focused map and profile
audits exercise the existing flows; no physical drive or spoken-output result
is inferred from them.

`tools/audit_design.py` checks actual touch bounds, control reachability and
paired contrast across small phones, landscape and tablets, with 200% system
text and disabled animations. Scrolling landscape controls are measured when
fully reachable. Dialog checks verify all profile fields, checkboxes, the ADR
selector, routing options and Maps actions at 48 dp or larger. Visual inspection
found and corrected split dock labels and a clipped download-status label.

Open Code Review delegation mode and the independent peer review cover the
changed UI and routing/QA files. A colour-only styling cache has no conflicting
foreground/outline states in the current flow; no speculative state machinery
was added. Europe/cross-border and real-road limitations below still apply.

Test date: 2026-10-06. API 26 and API 37 use dedicated emulators. The physical
Galaxy Tab S9 runs Android 16/API 36. Private screenshots, GPS/reference evidence
and account information are not included in the public source archive.

## Reference observations and resulting changes

The authorized Eurowag session exercised offline city search, destination detail,
route preview and navigation start. Its route preview warned of truck restrictions.
Navigation start changed from a whole-route view to a driver arrow near the lower
part of a zoomed map, with the next maneuver above and trip information below.
The tablet was stationary; this was not a reference test drive.

EuroRig now uses a map-first portrait layout and a scrollable landscape sidebar.
Search, profile and preferences are at the top; editable endpoints and guidance
actions are below. Starting guidance zooms to the arrow in the usable viewport.
Fresh fixes keep it at a look-ahead position. Dragging/Overview preserve a chosen
view until Recenter. The map remains north-up and two-dimensional; tilt, heading-up
rotation and speed-dependent zoom are not implemented.

The driver marker is an original blue faceted direction arrow with a white outline.
Moving GPS bearing rotates it; stale positions use a gray dot. Matched route
progress removes the colored portion behind the driver, including partial edges
and restricted amber sections. Arrival clears the trail; rerouting resets it.
API 26 and API 37 pixel checks verify these states and confirm that progress does
not replace the cached road bitmap.

## Exercised functions

| Flow | Evidence and result |
| --- | --- |
| Offline search | Accent-normalized Galați city selection, no results, invalid/outside coordinates, manual endpoints and keyboard-visible Cancel pass. Cities rank before similarly named objects. Full address disambiguation remains incomplete. |
| Route planner | Endpoint editing, swap, restart persistence and all three preferences pass. Hidden sample trips were removed. |
| Truck profile | All seven numeric fields, six checkboxes and ADR E save; invalid height preserves the previous profile. Decimal precision survives restart and reopening. |
| Truck routing | Native synthetic networks pass dimensions, loaded/axle weight, ADR tunnel detours and permitted delivery tests in every mode. Actual Romania sample routes pass. Global optimality and complete legal-rule coverage are not established. |
| Restrictions and map filtering | Ordinary pedestrian/cycle paths and tracks are hidden unless vehicle-access evidence warrants displaying them. Through roads, residential and industrial access stay visible. Android SQLite fixtures verify retained width/access evidence for hidden ways and legacy allowed paths. Profile-dependent ADR display is refreshed after profile changes. |
| Map controls | Road-segment picking, both endpoint actions, favourites, ± zoom, overview, fit, pan and recenter pass. Actual camera instrumentation checks usable viewport, navigation zoom, every route-shape fix and rotation. |
| Guidance | Foreground GPS guidance, fresh acquisition, stale-signal feedback, unavailable stale speed, recovery, profile-edit guard and stop pass. Synthetic GPS checks are not physical road validation. |
| Appearance | More → Appearance → Dark mode changes actual rendered map and control colors, retains the choice after restart and preserves foreground guidance/following. Android 8, Android 17 and physical Tab S9 switching are exercised. |
| Maps | Empty install, real public Romania download, foreground progress, checksum/install, airplane-mode routing/cold restart, catalogue list, map metadata, idle pause and import-picker opening pass across the 0.5 candidates. All-Europe correctly reports unavailable coverage. Native bad-path/checksum import rollback passes. |
| Route details/export | Maneuver list opens and GPX export writes actual track points through the Android document picker. |
| Other controls | Voice preference toggles both ways, empty/saved favourites, About, actual ODbL content and catalogue-editor cancel pass. Spoken output has not been verified. |
| Layout | Portrait and landscape pass on both emulator versions. A 320×480 emulator check verifies Maps Close and small-landscape search. The Tab S9 verifies search results/Cancel above its keyboard. |

The 22-check UI sweep runs on Android 8 and 17. Additional focused scripts cover
truck profile, map actions, themes and guidance. Full Romania native tests were
run during the iteration; profile/map-filter and camera checks were repeated after
the final display changes. Download evidence uses the unchanged 407,495,153-byte
Romania package, not a regenerated country archive.

## Performance and remaining validation

Four alternating 300-pixel, 700-ms swipes on the installed 0.5 Tab S9 build
rendered 310 frames: 5-ms median, 6-ms 90th percentile, 7-ms 95th percentile,
8-ms 99th percentile and 1.29% missed deadlines. This is a warm sample of one
viewport, measured before the final dynamic route overlay. Startup/cache replacement can still pause and these numbers do not
establish performance on low-end physical devices.

Real driving, physical GPS following/accuracy, spoken output, screen-off driving,
complex off-route recovery, intermediate Android versions, memory pressure and
32-bit devices remain to be validated. Lane guidance, multi-stop trips, parking
filters, walking mode, richer cartography, speed-limit warnings, legal country
rules and complete Europe/cross-border routing are unfinished. This is a development
release, not a claim that every feature of the reference app has been reproduced.

## Reproduce

Build and run `tools/smoke_native.py` with the Romania archive on dedicated
emulators first; it clears EuroRig test data. Then run:

```text
python tools/audit_ui.py --device emulator-5554 --prefix android8-audit
python tools/audit_profile.py --device emulator-5554
python tools/audit_map_actions.py --device emulator-5554
python tools/audit_theme.py --device emulator-5554 --prefix android8-theme
python tools/audit_guidance.py --device emulator-5556 --prefix android17-guidance
```

Guidance loss injection uses `cmd location` on modern images; use API 37 for that
script. Repeat other scripts on API 37. APK/private screenshots are ignored;
scripts reject physical serials before fault or destructive test actions.
