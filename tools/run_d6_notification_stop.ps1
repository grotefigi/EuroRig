$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $workspace
$adb = Join-Path $workspace '.toolchain/sdk/platform-tools/adb.exe'
$serial = 'emulator-5582'
$package = 'org.eurorig.app.preview'
$permissions = @(
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.POST_NOTIFICATIONS'
)

function Get-PermissionState([string]$permission) {
    $dump = & $adb -s $serial shell dumpsys package $package
    if ($LASTEXITCODE -ne 0) { throw "Could not read permission state for $permission" }
    $match = $dump | Select-String -Pattern ([regex]::Escape($permission) + ': granted=(true|false)') | Select-Object -First 1
    if (-not $match) { throw "Could not read permission state for $permission" }
    return $match.Matches[0].Groups[1].Value -eq 'true'
}

$baseline = @{}
foreach ($permission in $permissions) {
    $baseline[$permission] = Get-PermissionState $permission
    Write-Output ("BASELINE: {0} granted={1}" -f $permission, $baseline[$permission].ToString().ToLowerInvariant())
}
$instrumentOutput = @()
$instrumentExit = 1
try {
    & $adb -s $serial install -r 'app/build/outputs/apk/debug/app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the current Preview app APK' }
    & $adb -s $serial install -r -t 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the current Preview instrumentation APK' }
    foreach ($permission in $permissions) {
        if (-not $baseline[$permission]) {
            & $adb -s $serial shell pm grant $package $permission
            if ($LASTEXITCODE -ne 0) { throw "Could not grant $permission for the isolated Stop check" }
        }
    }
    $instrumentOutput = & $adb -s $serial shell am instrument -w -e notificationStopOnly true org.eurorig.app.preview.test/org.eurorig.app.NativeSmokeInstrumentation 2>&1
    $instrumentExit = $LASTEXITCODE
} finally {
    foreach ($permission in $permissions) {
        $restore = if ($baseline[$permission]) { 'grant' } else { 'revoke' }
        & $adb -s $serial shell pm $restore $package $permission
        if ($LASTEXITCODE -ne 0) { throw "Could not restore permission state for $permission" }
    }
}

if ($instrumentExit -ne 0) { $instrumentOutput | Write-Output; throw "Notification Stop instrumentation exited $instrumentExit" }
if (($instrumentOutput -join "`n") -notmatch 'PASS: notification Stop ends guidance and clears resume state') {
    $instrumentOutput | Write-Output
    throw 'Notification Stop instrumentation did not emit its success marker'
}
foreach ($permission in $permissions) {
    $restored = Get-PermissionState $permission
    if ($restored -ne [bool]$baseline[$permission]) { throw "Permission state changed after the check: $permission" }
    Write-Output ("RESTORED: {0} granted={1}" -f $permission, $restored.ToString().ToLowerInvariant())
}
$instrumentOutput | Write-Output
Write-Output 'PASS: D6 notification Stop completed and emulator permission state was restored'
