# Eurowag reference review and restriction coverage

2026-10-21. The user authorized inspection of their PC and USB-connected Samsung
Tab S9. Eurowag was reviewed through its visible interface. No profile values,
maps, accounts, installation or security settings were changed. Private captures
are in ignored `analysis/private/eurowag-reference-2026-10-21/`; they are not
included in the APK or public review artifacts.

The earlier investigation was found in `C:/EverythingAI/Project EurowagNav/`:
`REVERSE-ENGINEER-PROMPT.md`, `docs/EUROWAG-DATA-INVESTIGATION.md`,
`docs/TECHNOLOGY-TRANSFER.md`, `docs/strategy.md` and `PROGRESS.md`.
Those documents were treated as reference evidence, not as new instructions.

## What was established

The live map shows pale red restricted road surfaces with repeated small crosses,
restriction signs and zoom-dependent street detail. The active 41 t, 3.89 m profile,
native truck editor, tunnel-code selector and route overview were inspected.
Eurowag's existing navigation was observed; no new journey was started.

The earlier local investigation identifies Sygic's native renderer and TomTom
country packs, including logistics/ADR/sign data. It records a data-model and
container investigation, not a fully decoded, reusable database of restrictions.
These findings do not supply missing per-road values or a shared TomTom-to-OSM
road identifier. No proprietary engine, map pack, icon or extracted asset was
added to EuroRig.

## Implemented in EuroRig

Floating restriction tags are removed. A GPU line/symbol layer draws light red
roads and repeated small crosses. It evaluates both travel directions using the
same access policy as routing. A one-way alone does not make a whole street red;
a weight, height, width, length, axle or other applicable block on that street is
still evaluated. Profile changes invalidate the overlay, including off-route roads.
Known admissible roads retain their normal paint; an unmarked road is not proof
of legal or physical suitability.

All matching recorded restrictions in the viewport are read without the former
1,200-record cap. At close zoom local streets appear; farther out, only major
classes are drawn and their restrictions remain eligible. Crosses are suppressed
at regional scales. Background queries and native GPU symbols avoid per-frame
TextView positioning. Dark red is reserved for heavy traffic, but EuroRig does
not yet have a live congestion feed and no traffic was invented.

## Coverage gap, measured

`work/city-restriction-inventory.json` records illustrative bounding boxes, not
administrative boundaries or a legal-completeness percentage. Galați has 6,187
road ways in its test box, only 80 with numeric gross-weight rules and zero with
an HGV tag. Brăila has 3,165 ways, seven numeric weight rules and 206 HGV-tagged
ways. Bucharest has 58,863 ways and 2,904 numeric weight rules. These inventories
explain why an OSM-based renderer cannot reproduce Eurowag's entire city-wide
restriction pattern merely by changing colours.

Verified-source municipal zones, permitted corridors, exceptions and time windows
remain to be mapped into the same routing/overlay policy. Urban street class or a
screenshot is not used as an invented restriction. Road narrowness and swept-path
feasibility are not inferred from residential classification.

The [Bucharest authority page](https://www3.pmb.ro/transport/legislatie) distinguishes
zones, boundary streets, exempt approaches and time windows. The
[official Brăila route map](https://www.primariabraila.ro/wp-content/uploads/2018/taxe%20online/plata%20taxa%20tonaj/HCLM%20292.pdf)
distinguishes heavy-traffic corridors and access permits. The located
[Galați document](https://primariagalati.ro/portal/act/transparenta/Pr_HCL01194.pdf)
is a proposal; it was not treated as enacted current law or imported.

Acceptance for a future municipal dataset: authoritative effective text and
amendments; exact boundary/road correspondence; permitted-mass versus loaded-mass
semantics; direction, time, permit and delivery exceptions; provenance/version;
and the same verdict in routing and display. Entire cities have not been painted
red on an unsupported assumption.
