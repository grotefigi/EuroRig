param([string[]]$Tasks = @(':routing:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalJdk = Get-ChildItem -LiteralPath "$ProjectRoot/.toolchain/jdk21" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($LocalJdk) { $env:JAVA_HOME = $LocalJdk.FullName }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
# A short socket directory avoids JDK Unix-domain socket failures on Windows.
New-Item -ItemType Directory -Force "$ProjectRoot/.toolchain/t" | Out-Null
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$ProjectRoot/.toolchain/t"
Push-Location $ProjectRoot
try {
    if (Test-Path "$ProjectRoot/gradlew.bat") { & "$ProjectRoot/gradlew.bat" @Tasks }
    else { & "$ProjectRoot/.toolchain/gradle/gradle-9.6.0/bin/gradle.bat" @Tasks }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
