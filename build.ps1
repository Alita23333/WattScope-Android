param([switch]$Benchmark)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath 'C:\DevTools\Activate-DevTools.ps1') {
    & 'C:\DevTools\Activate-DevTools.ps1'
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Set JAVA_HOME to JDK 17.' }
$javaDescription = (& java -version 2>&1 | Out-String)
if ($javaDescription -notmatch 'version "17\.') { throw "This project requires JDK 17. Found: $javaDescription" }
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'local.properties'))) {
    throw 'Set ANDROID_SDK_ROOT or create local.properties with sdk.dir for your existing Android SDK.'
}
Push-Location $PSScriptRoot
try {
    $tasks = @('assembleRelease', 'testDebugUnitTest', 'lintRelease')
    if ($Benchmark) { $tasks += 'assembleBenchmark' }
    & .\gradlew.bat @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
    Get-FileHash -Algorithm SHA256 -LiteralPath 'app\build\outputs\apk\release\app-release.apk'
} finally { Pop-Location }
