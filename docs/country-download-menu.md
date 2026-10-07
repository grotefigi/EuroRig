# Country download menu

The country chooser uses original EuroRig controls and the app's shared light/dark
palette: a circular country flag, bold country name, actual compressed download
size, and a blue download action. Countries are sorted by their displayed names.
Names and sizes wrap; the list scrolls independently of the fixed footer. Both
the country identity and its icon are accessible actions with at least 48 dp
touch targets. Flags are decorative device-font glyphs, with an ISO-code fallback.

Catalogue format 1 remains compatible with the published Romania entry. Optional
`country` is an uppercase two-letter country code. A regional entry also supplies
`region_name`; entries for that country become a row with a chevron, the summed
download size and the number of regions. Region rows show their individual
download sizes. The Back button and Android Back return to the country list.
Region IDs remain distinct download IDs; selecting all regions queues those IDs.
Malformed IDs, duplicate IDs, invalid sizes and conflicting region groups are
rejected. The chooser accepts at most 512 entries and uses the transport client's
128 GiB maximum for an individual package.

Sizes use decimal MB/GB and come from catalogue `bytes`, not reference-app labels.
They describe transport size, not installed size. Russia is excluded by country
code and legacy ID. The all-Europe action is disabled and visually muted until
`europe_complete` is true and there are published packages. Unpublished countries
are not displayed as downloadable maps.

The public catalogue currently contains Romania only. Regional presentation is
tested with a local synthetic catalogue; that does not publish regional maps or
establish seamless routing between downloaded regions. Coherent country-set
activation and full European coverage are separate unfinished work.

Run `python tools/audit_country_menu.py --device emulator-5554` on a dedicated
emulator with a map installed. The audit serves its own temporary catalogue,
checks real native action bounds, sizes, region navigation, malformed/empty
catalogues, 200% text, disabled motion, and both themes. It preserves existing
map/settings preferences and restores display, font and animation settings.
Private screenshots and receipts are written to `analysis/private/country-menu`.
Review the screenshots separately; the automated checks cannot establish visual
quality or physical-device performance.
