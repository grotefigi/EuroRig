$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalJdk = Get-ChildItem -LiteralPath "$ProjectRoot/.toolchain/jdk21" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($LocalJdk) { $env:JAVA_HOME = $LocalJdk.FullName }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
New-Item -ItemType Directory -Force "$ProjectRoot/.toolchain/t" | Out-Null
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS `"-Djdk.net.unixdomain.tmpdir=$ProjectRoot/.toolchain/t`"".Trim()
Push-Location $ProjectRoot
try {
    & .\gradlew.bat --offline -I tools/preview.gradle :routing:test --tests org.eurorig.routing.RouteAlternativesTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "E2 alternative checks failed with exit code $LASTEXITCODE" }
    Write-Output 'BUILD SUCCESSFUL'
} finally { Pop-Location }
