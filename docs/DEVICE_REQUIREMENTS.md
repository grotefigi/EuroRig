# Device and storage requirements

Driver requirements, updated 2026-10-07:

- Complete and verify the app on the Galaxy Tab S9 first (8 GB RAM, 128 GB storage).
- Android compatibility remains API 26 and newer. Subsequent performance work must
  cover an older Android 8 phone with 4 GB RAM and 32 GB total device storage.
- The installed application plus all published European maps excluding Russia
  must use no more than **20,000,000,000 bytes (20 GB)**. Count application files,
  map payloads, retained downloads and map caches together. Updates and temporary
  files must have a bounded storage budget and preserve working maps on failure.
- A driver may install one country only. Country downloads must remain separate.
- Keep truck access, dimensions, axle/gross weight, ADR and delivery-road evidence;
  removing useful roads or warehouse search is not an acceptable storage shortcut.

The current 82–90 GB extrapolation for uncompressed continental data exceeds this
requirement. It is not an approved device format or a production build target.
The 35.15 GB Europe OSM source download is PC build input, not an Android map.

Compression, shared tile ownership and display/search index changes require
measured installed-size receipts and native routing/search parity before shipping.
Do not infer that a continental set fits from one country or from ZIP download sizes.
Compressed native tile support must be tested against the exact pinned Android
engine, including truck restrictions and cold/warm route performance.

Current evidence: Romania's transport is 407,495,153 bytes; routing/display payloads
total 873,488,384 bytes. A retained archive adds the transport size again. Experimental
FTS savings are copy-only and are not yet compatible with composed-country checks.
The 20 GB continental target and old-phone performance remain unverified.
