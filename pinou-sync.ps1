# Nightly PinouTube update: merges the official SmartTube changes (upstream/master) into the fork,
# builds the release APK and installs it on the Shield when PinouTube isn't the app on screen.
# Started daily at 03:00 by the Windows task "PinouTube auto-update" (see -Register), and by the
# PiDex updater agent with -Check. How it works and what to do when it fails: PINOU-AUTOUPDATE.md
# Usage: .\pinou-sync.ps1            run the update (does nothing if another run is in progress)
#        .\pinou-sync.ps1 -Check     report tonight's run: waits for a run in progress, runs one if none
#                                    happened since 03:00, exits 1 if the update failed
#        .\pinou-sync.ps1 -Register  create or update the Windows scheduled task
# Windows PowerShell 5.1 compatible (the scheduled task uses powershell.exe).
param([switch]$Check, [switch]$Register)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$stateDir = Join-Path $root '.pinou-sync'
$statusFile = Join-Path $stateDir 'status.json'
$logFile = Join-Path $stateDir 'last-run.log'
$lockFile = Join-Path $stateDir 'sync.lock'
$apk = Join-Path $root 'dist\PinouTube.apk'
$branch = 'tabbed-grid-home'
$upstreamUrl = 'https://github.com/yuliskov/SmartTube.git'
$upstreamRef = 'upstream/master'
$package = 'home.pinou.smarttube'
$adb = 'C:\Users\cossu\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\CodexTools\android\platform-tools\adb.exe'
$adbPort = '5041'   # private adb server: other tools restarting the default one can't drop our connection
$shield = '192.168.68.52:5555'
$taskName = 'PinouTube auto-update'
$nightlyHour = 3
# How long a run waits for the TV to be free before postponing the install to the next run.
# -Check runs (the agent) don't wait, so the agent isn't kept busy.
$installWaitHours = if ($Check) { 0 } else { 4 }
$maxPostponed = 3   # postponed runs in a row before it counts as a failure

$script:status = @{}

class SyncFailure : System.Exception {
    SyncFailure([string]$message) : base($message) {}
}

class SyncPostponed : System.Exception {
    SyncPostponed([string]$message) : base($message) {}
}

function Write-Log([string]$Message) {
    $line = '{0} {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Add-Content -Path $logFile -Value $line -Encoding UTF8
    Write-Host $line
}

# Runs a native command, appends its output to the log, returns the exit code
function Invoke-Logged([string]$Exe, [string[]]$Arguments) {
    Write-Log ('> {0} {1}' -f (Split-Path $Exe -Leaf), ($Arguments -join ' '))
    & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } | Add-Content -Path $logFile -Encoding UTF8
    return $LASTEXITCODE
}

function Invoke-Git([string[]]$Arguments) {
    $output = & git -C $root @Arguments 2>&1 | ForEach-Object { "$_" }
    return ($output -join "`n").Trim()
}

function Invoke-Adb([string[]]$Arguments) {
    return ((& $adb -P $adbPort @Arguments 2>&1 | ForEach-Object { "$_" }) -join "`n")
}

function Read-Status {
    $result = @{}
    if (Test-Path $statusFile) {
        try {
            (Get-Content -Raw $statusFile | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $result[$_.Name] = $_.Value }
        } catch {
            # unreadable status: start from scratch
        }
    }
    return $result
}

function Save-Status {
    $script:status | ConvertTo-Json | Set-Content -Path $statusFile -Encoding UTF8
}

function Set-Step([string]$Step) {
    $script:status.step = $Step
    Save-Status
    Write-Log "== $Step"
}

function ConvertTo-Date($Value) {
    if ($Value -is [datetime]) { return $Value }
    return [datetime]::ParseExact([string]$Value, 'yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
}

function Get-Lock {
    try {
        return [System.IO.File]::Open($lockFile, 'OpenOrCreate', 'ReadWrite', 'None')
    } catch {
        return $null
    }
}

# 'free' when the Shield sleeps or shows another app, 'busy' when PinouTube is on screen, 'unreachable' otherwise
function Get-ShieldState {
    Invoke-Adb @('connect', $shield) | Out-Null
    $out = Invoke-Adb @('-s', $shield, 'shell', 'dumpsys power | grep mWakefulness=; dumpsys activity activities | grep mResumedActivity | head -1')
    if ($out -notmatch 'mWakefulness=') { return 'unreachable' }
    if ($out -match 'mWakefulness=Awake' -and $out -match ('mResumedActivity.* ' + [regex]::Escape($package) + '/')) { return 'busy' }
    return 'free'
}

function Get-InstalledApkHash {
    $path = Invoke-Adb @('-s', $shield, 'shell', "pm path $package")
    if ($path -notmatch 'package:(\S+base\.apk)') { return $null }
    $sum = Invoke-Adb @('-s', $shield, 'shell', "sha256sum '$($Matches[1])'")
    if ($sum -match '([0-9a-f]{64})') { return $Matches[1] }
    return $null
}

# Puts the branch and the submodules back to a commit after a failed merge or build.
# Safe: the run only starts on a clean tree, so the only changes are its own merge.
function Restore-Head([string]$Commit) {
    Invoke-Logged 'git' @('-C', $root, 'reset', '--hard', $Commit) | Out-Null
    Invoke-Logged 'git' @('-C', $root, 'submodule', 'update', '--init', '--recursive') | Out-Null
}

function Install-WhenFree {
    $localHash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
    if ($localHash -eq $script:status.installedHash) {
        return 'already installed'
    }

    $deadline = (Get-Date).AddHours($installWaitHours)
    while ($true) {
        $state = Get-ShieldState
        if ($state -ne 'unreachable') {
            if ((Get-InstalledApkHash) -eq $localHash) {
                $script:status.installedHash = $localHash
                return 'already installed'
            }
            # Checked right before installing: installing closes PinouTube
            if ($state -eq 'free' -and (Get-ShieldState) -eq 'free') {
                $out = Invoke-Adb @('-s', $shield, 'install', '-r', $apk)
                Add-Content -Path $logFile -Value $out -Encoding UTF8
                if ($out -notmatch 'Success') {
                    throw [SyncFailure]::new("adb install failed: $(($out -split "`n" | Select-Object -Last 1))")
                }
                if ((Get-InstalledApkHash) -ne $localHash) {
                    throw [SyncFailure]::new('The APK on the Shield differs from dist\PinouTube.apk after installing')
                }
                $script:status.installedHash = $localHash
                return 'installed'
            }
        }
        $reason = if ($state -eq 'busy') { 'PinouTube was on screen' } else { 'the Shield was unreachable' }
        if ((Get-Date) -ge $deadline) {
            throw [SyncPostponed]::new("Install postponed: $reason")
        }
        Set-Step 'install-wait'
        Write-Log "Can't install yet: $reason. Checking again in 5 minutes."
        Start-Sleep -Seconds 300
    }
}

function Invoke-Sync {
    $previous = Read-Status
    $script:status = $previous
    $script:status.state = 'running'
    $script:status.startedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    $script:status.finishedAt = $null
    $script:status.summary = 'Running'
    $script:status.warning = $null
    Set-Content -Path $logFile -Value "PinouTube auto-update, $($script:status.startedAt)" -Encoding UTF8
    $parts = @()

    try {
        Set-Step 'check-repo'
        if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw [SyncFailure]::new('git is not on PATH') }
        $current = Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')
        if ($current -ne $branch) { throw [SyncFailure]::new("The fork is on branch '$current' instead of '$branch'") }
        $dirty = Invoke-Git @('status', '--porcelain', '--untracked-files=no')
        if ($dirty) { throw [SyncFailure]::new("The fork has uncommitted changes, left untouched: $($dirty -replace "`n", ', ')") }
        if (-not (Invoke-Git @('remote')).Split("`n").Contains('upstream')) {
            Invoke-Logged 'git' @('-C', $root, 'remote', 'add', 'upstream', $upstreamUrl) | Out-Null
        }

        Set-Step 'fetch'
        $fetched = $false
        foreach ($attempt in 1..3) {
            if ((Invoke-Logged 'git' @('-C', $root, 'fetch', '--quiet', 'upstream')) -eq 0) { $fetched = $true; break }
            Start-Sleep -Seconds 60
        }
        if (-not $fetched) { throw [SyncPostponed]::new("Couldn't reach GitHub to fetch the official changes") }

        $before = Invoke-Git @('rev-parse', 'HEAD')
        $newCount = [int](Invoke-Git @('rev-list', '--count', "HEAD..$upstreamRef"))
        $upstreamHead = Invoke-Git @('rev-parse', '--short', $upstreamRef)
        $merged = $false
        if ($newCount -gt 0) {
            Set-Step 'merge'
            if ((Invoke-Logged 'git' @('-C', $root, 'merge', '--no-edit', $upstreamRef)) -ne 0) {
                $conflicts = Invoke-Git @('diff', '--name-only', '--diff-filter=U')
                Invoke-Logged 'git' @('-C', $root, 'merge', '--abort') | Out-Null
                if (-not $conflicts) {
                    throw [SyncFailure]::new("Merging $newCount official changes (upstream $upstreamHead) failed without a conflict (see log)")
                }
                throw [SyncFailure]::new("Merging $newCount official changes (upstream $upstreamHead) conflicts in: $($conflicts -replace "`n", ', ')")
            }
            $merged = $true
            Set-Step 'submodules'
            Invoke-Logged 'git' @('-C', $root, 'submodule', 'sync', '--recursive') | Out-Null
            if ((Invoke-Logged 'git' @('-C', $root, 'submodule', 'update', '--init', '--recursive')) -ne 0) {
                Restore-Head $before
                throw [SyncFailure]::new("Couldn't update MediaServiceCore/SharedModules after merging upstream $upstreamHead; the fork was put back to $($before.Substring(0, 7))")
            }
            $parts += "merged $newCount official changes (upstream $upstreamHead)"
        }

        $head = Invoke-Git @('rev-parse', 'HEAD')
        if ($merged -or -not (Test-Path $apk) -or $script:status.builtCommit -ne $head) {
            Set-Step 'build'
            $code = Invoke-Logged 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'pinou-build.ps1'), '-NoDaemon')
            if ($code -ne 0) {
                if ($merged) {
                    Restore-Head $before
                    throw [SyncFailure]::new("The build failed after merging upstream $upstreamHead; the fork was put back to $($before.Substring(0, 7)) and the installed app is unchanged")
                }
                throw [SyncFailure]::new('The build failed (no new official changes)')
            }
            $script:status.builtCommit = $head
            $parts += "built $($head.Substring(0, 7))"
        }

        if ($merged) {
            Set-Step 'push'
            # Never let Git Credential Manager open a sign-in window at night
            $env:GCM_INTERACTIVE = 'never'
            $env:GIT_TERMINAL_PROMPT = '0'
            if ((Invoke-Logged 'git' @('-C', $root, 'push', 'origin', $branch)) -ne 0) {
                $script:status.warning = "Pushing $branch to GitHub failed (see log)"
            }
        }

        Set-Step 'install'
        $installed = Install-WhenFree
        if ($installed -eq 'installed') { $parts += 'installed on the Shield' }

        $script:status.state = 'succeeded'
        $script:status.postponed = 0
        $script:status.summary = if ($parts.Count -gt 0) { ($parts -join ', ') } else { 'already up to date' }
    } catch [SyncPostponed] {
        $count = 1 + [int]$script:status.postponed
        $script:status.postponed = $count
        $message = $_.Exception.Message
        if ($count -ge $maxPostponed) {
            $script:status.state = 'failed'
            $script:status.summary = "$message ($count runs in a row)"
        } else {
            $script:status.state = 'postponed'
            $script:status.summary = (@($parts) + "$message; next run tries again") -join ', '
        }
    } catch [SyncFailure] {
        $script:status.state = 'failed'
        $script:status.summary = $_.Exception.Message
    } catch {
        $script:status.state = 'failed'
        $script:status.summary = "Unexpected error: $($_.Exception.Message)"
        Write-Log $_.ScriptStackTrace
    }

    $script:status.finishedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    Save-Status
    Write-Log "Result: $($script:status.state): $($script:status.summary)"
    return $script:status
}

# Prints the result (Write-Host, so only the exit code is returned)
function Show-Report($Status) {
    Write-Host "PinouTube auto-update ($($Status.finishedAt)): $($Status.state): $($Status.summary)"
    if ($Status.warning) { Write-Host "Warning: $($Status.warning)" }
    if ($Status.state -ne 'failed') { return 0 }
    Write-Host "Failed at step '$($Status.step)'. Full log: $logFile"
    Write-Host '--- last 80 log lines ---'
    Get-Content $logFile -Tail 80 | ForEach-Object { Write-Host $_ }
    return 1
}

function Register-SyncTask {
    $launcher = Join-Path $root 'pinou-sync-hidden.vbs'
    $action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument "//B //NoLogo `"$launcher`""
    $trigger = New-ScheduledTaskTrigger -Daily -At ([datetime]::Today.AddHours($nightlyHour))
    # StartWhenAvailable: a run missed while the PC was off or asleep starts as soon as it's back
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit (New-TimeSpan -Hours 6) -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal `
        -Description "Merges the official SmartTube updates into PinouTube, builds it and installs it on the Shield. $root\PINOU-AUTOUPDATE.md" `
        -Force | Out-Null
    Write-Host "Scheduled task '$taskName' registered: daily at $($nightlyHour):00."
}

New-Item -ItemType Directory -Force $stateDir | Out-Null

if ($Register) {
    Register-SyncTask
    exit 0
}

$lock = Get-Lock
if (-not $Check) {
    if (-not $lock) { exit 0 }   # another run is in progress
    try { $result = Invoke-Sync } finally { $lock.Dispose() }
    if ($result.state -eq 'failed') { exit 1 }
    exit 0
}

# -Check
if (-not $lock) {
    Write-Host 'An update run is in progress, waiting for it.'
    $lastNote = Get-Date
    while (-not ($lock = Get-Lock)) {
        $current = Read-Status
        if ($current.step -eq 'install-wait') {
            Write-Host "PinouTube auto-update: in progress, waiting for the TV to be free to install the new build."
            exit 0
        }
        Start-Sleep -Seconds 20
        if (((Get-Date) - $lastNote).TotalMinutes -ge 5) {
            Write-Host "$(Get-Date -Format 'HH:mm') still running: $($current.step)"
            $lastNote = Get-Date
        }
    }
}
try {
    $result = Read-Status
    $since = [datetime]::Today.AddHours($nightlyHour)
    if ((Get-Date) -lt $since) { $since = $since.AddDays(-1) }
    if (-not $result.finishedAt -or (ConvertTo-Date $result.finishedAt) -lt $since) {
        Write-Host "No update run since $($since.ToString('yyyy-MM-dd HH:mm')), running it now."
        $result = Invoke-Sync
    }
} finally {
    $lock.Dispose()
}
exit (Show-Report $result)
