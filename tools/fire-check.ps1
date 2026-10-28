param(
  [string]$Tv = $env:TV_HOST,
  [string]$Adb = $(if ($env:ADB) { $env:ADB } else { 'adb' }),
  [Parameter(Mandatory = $true)][string]$ListFile,
  [string]$AppId = "com.gathin.tvhome",
  [switch]$Fix
)

# Drift check: Amazon updates re-enable packages. Lists packages from the debloat list that are installed but enabled,
# and whether the My Home accessibility service is enabled and bound. -Fix re-disables drifted packages. Read-only otherwise.
# Exit 0 = clean (or fixed), 1 = drift.
. "$PSScriptRoot\fire-lib.ps1"
$Tv = Resolve-TvHost $Tv
$DryRun = $false
$comp = "$AppId/$ServiceClass"
$want = Read-DebloatList $ListFile -AppId $AppId
$installed = (Adb-Sh "pm list packages") -replace '^package:', ''
$disabled = (Adb-Sh "pm list packages -d") -replace '^package:', ''
if (-not $installed) { Write-Output "FAIL: could not list packages (TV unreachable?)"; exit 1 }
$drift = @($want | Where-Object { $installed -contains $_ -and $disabled -notcontains $_ })
$bad = $false
foreach ($p in $drift) {
  if ($Fix) {
    $o = (Adb-Sh "pm disable-user --user 0 $p") -join ' '
    if ($o -match 'disabled-user') { Write-Output "FIXED   $p" } else { Write-Output "REFUSED $p ($o)"; $bad = $true }
  } else { Write-Output "ENABLED $p"; $bad = $true }
}
Write-Output ("{0} listed, {1} installed, {2} drifted" -f @($want).Count, @($want | Where-Object { $installed -contains $_ }).Count, $drift.Count)
$enabled = (Adb-Sh "settings get secure enabled_accessibility_services") -join ''
$inList = $enabled -match [regex]::Escape($comp)
$bound = Test-ServiceBound ((Adb-Sh "dumpsys accessibility") -join "`n") $comp
Write-Output "My Home service: in enabled list = $inList, bound = $bound"
if (-not ($inList -and $bound)) { Write-Output "Re-run launcher\build-and-install.ps1 to re-enable the service."; $bad = $true }
if ($bad) { exit 1 }
