# Builds the household fork (stpinou flavor) and keeps exactly one APK in .\dist.
# Release build: debug builds log every network response in full, which slows browsing down a lot.
# Usage: .\pinou-build.ps1 [-Install] [-NoDaemon]   (-Install also pushes it to the Shield;
#        -NoDaemon leaves no Gradle process running afterwards, for the nightly pinou-sync.ps1)
param([switch]$Install, [switch]$NoDaemon)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$env:JAVA_HOME = 'C:\Apps\Java\jdk-11'   # SmartTube needs JDK <= 14 (README); AGP 7.4 needs >= 11
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$adb = 'C:\Users\cossu\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\CodexTools\android\platform-tools\adb.exe'
$shield = '192.168.68.52:5555'

Push-Location $root
try {
    $gradleArgs = @(':smarttubetv:assembleStpinouRelease', '--console=plain')
    if ($NoDaemon) { $gradleArgs += '--no-daemon' }
    & "$root\gradlew.bat" @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$apk = Get-ChildItem "$root\smarttubetv\build\outputs\apk\stpinou\release" -Filter '*_armeabi-v7a.apk' | Select-Object -First 1
if (-not $apk) { throw 'armeabi-v7a APK not found' }

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Get-ChildItem $dist -Filter '*.apk' | Remove-Item -Force   # one slot: the latest build only
$out = Join-Path $dist 'PinouTube.apk'
Copy-Item $apk.FullName $out
Write-Host "APK: $out"

if ($Install) {
    & $adb connect $shield | Out-Host
    & $adb -s $shield install -r $out | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "adb install failed ($LASTEXITCODE)" }
}
