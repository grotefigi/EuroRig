# Region package import fixture

`region-package.eurorig` contains the original EuroRig synthetic profile network,
packaged by `tools/package_region.py`. It is not a country map. The `.eurorig`
extension matches actual region packages and keeps this test fixture outside the
ignore rule for generated `.europack` display graphs.

Regenerate from the repository root with Python 3.12 or later:

```text
python tools/package_region.py app/src/androidTest/assets/profile-routing.tar app/src/androidTest/assets/profile-display.sqlite routing/src/test/resources/region-package.eurorig --name "Synthetic package import fixture" --source "Original EuroRig synthetic profile network" --date synthetic
```

`PackageImportTest` streams the resulting archive through Java's `ZipInputStream`,
matching the Android import mechanism. Keep the fixture with the test when
changing the packager or synthetic input assets.
