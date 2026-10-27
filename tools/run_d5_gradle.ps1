param([Parameter(Mandatory = $true)][ValidateSet('routing', 'app')][string]$Target)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalJdk = Get-ChildItem -LiteralPath "$ProjectRoot/.toolchain/jdk21" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($LocalJdk) { $env:JAVA_HOME = $LocalJdk.FullName }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }

# Keep JDK Unix-domain socket paths short on Windows hosts.
$SocketDirectory = "$ProjectRoot/.toolchain/t"
New-Item -ItemType Directory -Force $SocketDirectory | Out-Null
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$SocketDirectory".Trim()

Push-Location $ProjectRoot
try {
    $Tasks = if ($Target -eq 'routing') {
        @(':routing:test', '--tests', 'org.eurorig.routing.NavigationCameraLaneTest')
    } else {
        @(':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug')
    }
    & .\gradlew.bat --offline -I tools/preview.gradle @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
