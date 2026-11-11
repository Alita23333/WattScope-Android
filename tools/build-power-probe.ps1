$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (Test-Path 'C:\DevTools\Activate-DevTools.ps1') { & 'C:\DevTools\Activate-DevTools.ps1' }
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { throw 'Set ANDROID_SDK_ROOT to an existing Android SDK.' }
$version = [regex]::Match((Get-Content -Raw "$project\app\build.gradle.kts"), 'ndkVersion\s*=\s*"([^"]+)"').Groups[1].Value
$ndk = Join-Path $sdk "ndk\$version"
$compiler = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android31-clang++.cmd'
$stripper = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw "Reuse/install the project NDK through the tool center: $ndk" }
$out = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& $compiler (Join-Path $PSScriptRoot 'cpu_power_probe.cpp') '-std=c++17' '-O2' '-Wall' '-Wextra' '-static-libstdc++' '-o' (Join-Path $out 'cpu-power-probe')
if ($LASTEXITCODE -ne 0) { throw 'Power observer compilation failed.' }
& $stripper (Join-Path $out 'cpu-power-probe')
if ($LASTEXITCODE -ne 0) { throw 'Power observer stripping failed.' }
Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $out 'cpu-power-probe')
