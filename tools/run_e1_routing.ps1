$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalJdk = Get-ChildItem -LiteralPath "$ProjectRoot/.toolchain/jdk21" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($LocalJdk) { $env:JAVA_HOME = $LocalJdk.FullName }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
$SocketDirectory = "$ProjectRoot/.toolchain/t"
New-Item -ItemType Directory -Force $SocketDirectory | Out-Null
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$SocketDirectory".Trim()
Push-Location $ProjectRoot
try {
    & .\gradlew.bat --offline -I tools/preview.gradle :routing:test --tests org.eurorig.routing.JourneyPlanTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "E1 routing tests failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
