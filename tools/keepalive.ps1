param(
  [string]$Tv = $env:TV_HOST,
  [string]$Adb = $(if ($env:ADB) { $env:ADB } else { 'adb' }),
  [int]$Seconds = 60
)

# Keeps a Fire TV reachable over ADB during a work session. Fire OS drops network ADB when it sleeps, so every
# $Seconds this reconnects and sends KEYCODE_WAKEUP, which does nothing on screen when the TV is already awake but
# resets its sleep timer. Stop with Ctrl+C (or stop the background task); nothing on the TV is changed.
. "$PSScriptRoot\fire-lib.ps1"
$Tv = Resolve-TvHost $Tv
while ($true) {
  $t = Get-Date -Format HH:mm:ss
  $c = (& $Adb connect $Tv 2>&1) -join ' '
  $w = (& $Adb -s $Tv shell input keyevent KEYCODE_WAKEUP 2>&1) -join ' '
  if ($LASTEXITCODE -eq 0) { Write-Output "$t ok" } else { Write-Output "$t FAIL: $c $w" }
  Start-Sleep -Seconds $Seconds
}
