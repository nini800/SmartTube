# Builds the household fork (stpinou flavor) and keeps exactly one APK in .\dist.
# Usage: .\pinou-build.ps1 [-Install]   (-Install also pushes it to the Shield)
param([switch]$Install)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$env:JAVA_HOME = 'C:\Apps\Java\jdk-11'   # SmartTube needs JDK <= 14 (README); AGP 7.4 needs >= 11
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$adb = 'C:\Users\cossu\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\CodexTools\android\platform-tools\adb.exe'
$shield = '192.168.68.52:5555'

Push-Location $root
try {
    & "$root\gradlew.bat" :smarttubetv:assembleStpinouDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$apk = Get-ChildItem "$root\smarttubetv\build\outputs\apk\stpinou\debug" -Filter '*_armeabi-v7a.apk' | Select-Object -First 1
if (-not $apk) { throw 'armeabi-v7a APK not found' }

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Get-ChildItem $dist -Filter '*.apk' | Remove-Item -Force   # one slot: the latest build only
$out = Join-Path $dist 'SmartTube-Pinou.apk'
Copy-Item $apk.FullName $out
Write-Host "APK: $out"

if ($Install) {
    & $adb connect $shield | Out-Host
    & $adb -s $shield install -r $out | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "adb install failed ($LASTEXITCODE)" }
}
