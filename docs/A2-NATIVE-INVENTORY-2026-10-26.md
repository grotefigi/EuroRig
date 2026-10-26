# A2 native failure inventory — 26 October 2026

The complete six-suite run passes against A1 archival source `7aa7c24cbef9edd6ad7e96e19f0147838a5e2e1c` and its preserved Preview APKs, maps and private profile inputs. The profile/ADR suite runs first, including the historical category-C tunnel case in every routing mode.

| Selector | Current outcome | Scope exercised |
| --- | --- | --- |
| `profilesOnly` | PASS | Synthetic native truck dimensions, gross/axle limits, delivery refusal, category-C tunnel exclusion and display retention; all routing modes. |
| `navigationOnly` | PASS | Real offline route, original synthetic lane split, lane timing/settings and camera/building independence. |
| `interfaceOnly` | PASS | Real offline route, map/marker independence, simulator speed/pause, portrait/landscape and profile UI. |
| `gpsOnly` | PASS | Synthetic fixes through the emulator's platform GNSS provider, foreground guidance, progress, route paint and Stop. |
| `parkingOnly` | PASS | NDW table/cache, scheduled refresh and the current speech-callback assertion; coverage limits below. |
| `importOnly` | PASS | Eleven malformed-package refusals and preservation of the previously selected map and payloads. |

## Reproduced failures and correction

The first full run failed `interfaceOnly` at “Landscape marker stays clear of trip panel” and `gpsOnly` at “Platform GPS fix received”. Both failures reproduced individually. The emulator template disabled GPS and used natural landscape orientation, contrary to the tests' explicit portrait/rotation preconditions.

The dedicated AVD now enables GPS and uses an 800 × 1280 portrait display at 160 dpi. A focused profile/interface/GPS replay passed, followed by a new complete six-suite run in that environment. No production assertion or routing restriction was relaxed. All earlier attempts and raw logs remain preserved. Natural-landscape compatibility remains unqualified; the portrait-native pass does not establish that the original landscape failure was solely an environment defect.

## Evidence and limits

Ignored local evidence under `analysis/private/a2-current/` retains all three runs, exact runner/AVD configurations, receipt and log hashes, source/APK/map/input bindings, device model/OS and restored emulator preferences. `current-run.json` selects the complete accepted run; `verified-inventory.json` reconciles its evidence and retains the failure history. The receipt verifier rejects four corrupted controls. A1 preserved the physical installations and driver settings; A2 operates only on the dedicated emulator.

Independent review verified all six current suites, 17 historical logs, exact APK/map/input hashes, five byte-identical restored preferences, seven relevant source files and four rejected negative controls.

This closes the current native-suite inventory, not complete truck support or release qualification. GNSS inputs are synthetic and no road test occurred. The emulator runs without host audio. More seriously, the existing parking speech assertion accepts `onError` as completion and reports “delivered”; its PASS cannot prove successful speech. D6 must fix that assertion and qualify actual speech. The supplied NDW table declares `informationStatus=test`, and Dutch entrance-route verification is skipped without its optional country package; E4/E5 remain unqualified. One ADR fixture does not establish a complete legal corridor or consignment matrix. Physical Pixel qualification, RO speech and the broader feature packages remain open.
