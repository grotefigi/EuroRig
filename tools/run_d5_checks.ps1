$ErrorActionPreference = 'Stop'

$adb = '.toolchain/sdk/platform-tools/adb.exe'
$python = '.toolchain/qa-python/Scripts/python.exe'
$appApk = 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$device = 'emulator-5582'
$testComponent = 'org.eurorig.app.preview.test/org.eurorig.app.NativeSmokeInstrumentation'

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$Label) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

Invoke-Checked $adb @('-s', $device, 'install', '-r', $appApk) 'Install Preview APK'
Invoke-Checked $adb @('-s', $device, 'install', '-r', '-t', $testApk) 'Install Android test APK'
Invoke-Checked $adb @('-s', $device, 'shell', 'am', 'instrument', '-w', '-e', 'navigationEvidenceOnly', 'true', $testComponent) 'D5 API36 route-evidence fixture'
Invoke-Checked $python @('-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_enrich_navigation.py', '-v') 'D5 map-enrichment tests'
Invoke-Checked $adb @('-s', $device, 'shell', 'am', 'instrument', '-w', '-e', 'languageOnly', 'true', $testComponent) 'English/Romanian regression'

Write-Output 'PASS: D5 API36 route evidence, map enrichment, MainActivity display, and RO/EN regression'
