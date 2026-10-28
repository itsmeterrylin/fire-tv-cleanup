param(
  [string]$Tv = $env:TV_HOST,
  [string]$Adb = $(if ($env:ADB) { $env:ADB } else { 'adb' }),
  [Parameter(Mandatory = $true)][string]$ListFile,
  [string]$Restore = "$PSScriptRoot\..\backup\restore.ps1",
  [string]$AppId = "com.gathin.tvhome",
  [switch]$DryRun
)

# Disables the packages in a debloat list one at a time (pm disable-user --user 0), health-checks the TV after each,
# and re-enables the package if the TV looks unhealthy. Each success appends a restore line to $Restore (never overwritten). Default: backup\restore.ps1 under the repo (git-ignored), created if missing.
# Healthy = Home resolves to Amazon's launcher or $AppId, that app is running, and the TV can still reach the network.
# The never-list in fire-lib.ps1 (home, settings, input, network, auth, OTA, the launcher's dock apps, ...) is enforced whatever the file says.
# -DryRun only lists what it would do; it changes nothing on the TV.
. "$PSScriptRoot\fire-lib.ps1"
$Tv = Resolve-TvHost $Tv
$candidates = Read-DebloatList $ListFile -AppId $AppId

function Healthy {
  $h = (Adb-Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME") | Select-Object -Last 1
  if ($h -match 'com\.amazon\.tv\.launcher') { $pkg = 'com.amazon.tv.launcher' }
  elseif ($h -match [regex]::Escape($AppId)) { $pkg = $AppId }
  else { return $false }
  # Either launcher counts as healthy if it is the home handler and its process is alive (ours may be idle: fall back to Amazon's).
  if (-not (Adb-Sh "pidof $pkg") -and -not (Adb-Sh "pidof com.amazon.tv.launcher")) { return $false }
  $p = (Adb-Sh "ping -c 1 -W 2 8.8.8.8") -join ' '
  return ($p -match '1 received')
}

if (-not $DryRun -and -not (Test-Path -LiteralPath $Restore)) { New-Item -ItemType File -Force -Path $Restore | Out-Null }
if (-not (Healthy)) { Write-Output "ABORT: TV not healthy before start"; exit 1 }
$installed = (Adb-Sh "pm list packages") -replace '^package:', ''
$disabledNow = (Adb-Sh "pm list packages -d") -replace '^package:', ''
$log = @()
foreach ($p in $candidates) {
  if ($installed -notcontains $p) { $log += "$p -> not installed"; continue }
  if ($disabledNow -contains $p) { $log += "$p -> already disabled"; continue }
  if ($DryRun) { $log += "$p -> [dry-run] would disable (pm disable-user --user 0 $p), health-check, restore line"; continue }
  $out = (Adb-Sh "pm disable-user --user 0 $p") -join ' '
  if ($out -notmatch 'disabled-user') { $log += "$p -> REFUSED (protected)"; continue }
  Start-Sleep -Seconds 3
  if (Healthy) {
    $log += "$p -> disabled, healthy"
    Add-Content $Restore ('& $a -s $s shell pm enable ' + $p) -Encoding utf8
  } else {
    Adb-Sh "pm enable $p" | Out-Null
    $log += "$p -> UNHEALTHY, rolled back"
    Start-Sleep -Seconds 5
    if (-not (Healthy)) { $log += "STOP: TV still unhealthy after rollback of $p"; break }
  }
}
$log
if ($log -match '^STOP|UNHEALTHY') { exit 1 }
