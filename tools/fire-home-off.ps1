param(
  [string]$Tv = $env:TV_HOST,
  [string]$Adb = $(if ($env:ADB) { $env:ADB } else { 'adb' }),
  [string]$AppId = "com.gathin.tvhome",
  [switch]$Uninstall,
  [switch]$DryRun
)

# Turns the My Home redirect off (Home button goes back to Amazon's launcher): removes our accessibility service from
# the enabled list, keeping other services, and switches accessibility off if nothing is left. -Uninstall also removes the app.
. "$PSScriptRoot\fire-lib.ps1"
$Tv = Resolve-TvHost $Tv
$key = 'enabled_accessibility_services'
$cur = (Adb-Sh "settings get secure $key") -join ''
$new = Update-A11yList $cur -RemovePrefixes @("$AppId/")
if ($new) { Adb-Sh "settings put secure $key $new" -Mutate }
else {
  Adb-Sh "settings delete secure $key" -Mutate
  Adb-Sh "settings put secure accessibility_enabled 0" -Mutate
}
if ($Uninstall) { Adb-Sh "pm uninstall $AppId" -Mutate }
if ($DryRun) { Write-Output "[dry-run] enabled list was '$cur', would become '$new'"; exit 0 }
$after = (Adb-Sh "settings get secure $key") -join ''
if ($after -match [regex]::Escape("$AppId/")) { Write-Output "FAIL: still enabled: $after"; exit 1 }
Write-Output "Home redirect off. Enabled list now: $after"
