param([string]$Serial = $env:ANDROID_SERIAL)
$ErrorActionPreference = 'Stop'
if (-not $Serial) { throw 'Set ANDROID_SERIAL to the intended Preview test device.' }
$adb = Join-Path (Split-Path -Parent $PSScriptRoot) '.toolchain/sdk/platform-tools/adb.exe'
$package = 'org.eurorig.app.preview'
$component = 'org.eurorig.app.preview.test/org.eurorig.app.NativeSmokeInstrumentation'
$appApk = 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'

function Invoke-Adb([string[]]$Arguments, [string]$Label) {
    $output = & $adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $output | Write-Output
        throw "$Label failed with exit code $LASTEXITCODE"
    }
    return ($output -join "`n")
}

Push-Location (Split-Path -Parent $PSScriptRoot)
$prepared = $false
$verified = $false
try {
    Invoke-Adb @('-s',$serial,'install','-r',$appApk) 'Preview app install' | Write-Output
    Invoke-Adb @('-s',$serial,'install','-r','-t',$testApk) 'Instrumentation install' | Write-Output
    $prepare = Invoke-Adb @('-s',$serial,'shell','am','instrument','-w','-e','e1LifecyclePrepareOnly','true',$component) 'E1 prepare'
    $prepared = $true
    if ($prepare -notmatch 'PASS: E1 ten-stop order persisted for external process-death verification') { $prepare | Write-Output; throw 'E1 prepare marker missing' }
    $prepare | Write-Output
    Invoke-Adb @('-s',$serial,'shell','am','force-stop',$package) 'Force-stop Preview process' | Write-Output
    $verify = Invoke-Adb @('-s',$serial,'shell','am','instrument','-w','-e','e1LifecycleVerifyOnly','true',$component) 'E1 post-restart verification'
    if ($verify -notmatch '"routeOutcome":"routed:') { $verify | Write-Output; throw 'Ten-stop reroute did not produce a connected route after process death' }
    if ($verify -notmatch '"orderRetained":true') { $verify | Write-Output; throw 'Chosen stop order was not retained after process death' }
    $verify | Write-Output
    $verified = $true
    Write-Output 'PASS: E1 ten-stop order survived force-stop, reopened, and rerouted'
} finally {
    if ($prepared -and -not $verified) {
        $backup = & $adb -s $serial shell run-as $package sh -c 'test -f files/qa-e1-restart-preferences.bin'
        if ($LASTEXITCODE -eq 0) {
            try { Invoke-Adb @('-s',$serial,'shell','am','instrument','-w','-e','e1LifecycleRestoreOnly','true',$component) 'E1 recovery restore' | Write-Output }
            catch { Write-Output "E1 recovery restore requires follow-up: $_" }
        }
    }
    Pop-Location
}
