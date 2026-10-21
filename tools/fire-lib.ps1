# Shared helpers for the Fire TV scripts (dot-source this file). Pure functions are unit-tested in tests/fire-lib.Tests.ps1.
# Adb-Sh reads $Adb, $Tv and $DryRun from the calling script's scope.

$DefaultAppId = 'com.gathin.tvhome'
$ServiceClass = 'com.gathin.tvhome.HomeRedirectService'
# Full class names: the Java package stays com.gathin.tvhome whatever the app id, so '<appId>/.MainActivity' is wrong.
$MainClass = 'com.gathin.tvhome.MainActivity'

# Packages no list file may ever disable (wildcards allowed). Home, settings, input, network, auth, updates, keyboard, the launcher's default dock apps.
$NeverPackages = @(
  'com.amazon.tv.launcher', 'com.amazon.firehomestarter', 'com.amazon.tv.settings.v2*', 'com.amazon.tv.quicksettings*',
  'com.amazon.tv.livetv', 'com.amazon.tv.ime*', 'com.droidlogic*', 'com.amazon.tv.inputpreference*', 'com.amazon.tv.devicecontrol*',
  'com.amazon.fireinputdevices', 'com.amazon.franktvinput', 'com.amazon.autopairservice', 'com.amazon.uxcontrollerservice',
  'com.amazon.device.blepa', 'com.amazon.device.bluetoothpa', 'com.amazon.device.bluetoothkeymaplib', 'com.android.bluetooth',
  'com.amazon.connectivitycontroller', 'com.amazon.net.smartconnect', 'com.amazon.cpl', 'com.amazon.device.services*',
  'com.amazon.dcp*', 'com.amazon.imp', 'com.amazon.identity*', 'com.amazon.tv.turnstile', 'com.amazon.device.software.ota*',
  'com.amazon.venezia', 'com.amazon.android.marketplace', 'com.amazon.firetv.youtube', 'com.netflix.ninja',
  'com.amazon.firebat', 'com.amazon.webview.chromium', 'com.android.systemui*', 'com.android.providers.*',
  'com.android.packageinstaller', 'com.android.shell', 'com.android.webview', 'com.google.android.webview', 'android', 'android.*',
  'com.amazon.tv.keypolicymanager', 'com.amazon.tv.settings.*', 'com.amazon.device.settings*', 'com.amazon.android.settings',
  'com.android.settings*', 'com.amazon.tv.settingsprovider*'
) + @($DefaultAppId)

# The TV to talk to: -Tv, else $env:TV_HOST (same variable the .sh scripts use); stops with a clear message if neither is set.
function Resolve-TvHost([string]$Tv) {
  if (-not $Tv) { $Tv = $env:TV_HOST }
  if (-not $Tv) { throw "No TV given. Pass -Tv <ip:port> or set TV_HOST, e.g. `$env:TV_HOST = '192.168.1.50:5555'" }
  return $Tv
}

function Test-NeverPackage([string]$Package, [string[]]$Never = $NeverPackages, [string]$AppId = '') {
  if ($AppId -and $Package -eq $AppId) { return $true }
  foreach ($n in $Never) { if ($Package -like $n) { return $true } }
  return $false
}

# Parse a debloat.txt (package, optional '# comment'); returns valid package names, never the never-list.
function Read-DebloatList([string]$Path, [string]$AppId = '') {
  Get-Content -LiteralPath $Path | ForEach-Object { ($_ -split '#')[0].Trim() } |
    Where-Object { $_ -match '^[a-z0-9_]+(\.[a-zA-Z0-9_]+)*$' -and -not (Test-NeverPackage $_ -AppId $AppId) } |
    Select-Object -Unique
}

# Split the value of `settings get secure enabled_accessibility_services` into entries ('null'/empty -> none).
function ConvertTo-A11yEntries([string]$Value) {
  if (-not $Value) { return @() }
  $v = $Value.Trim()
  if ($v -eq 'null') { return @() }
  @($v -split ':' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

# Add $Add (if given) and drop entries from $RemovePrefixes (e.g. 'com.example.old/'), keeping everything else in order.
function Update-A11yList([string]$Current, [string]$Add = '', [string[]]$RemovePrefixes = @()) {
  $out = @(ConvertTo-A11yEntries $Current | Where-Object {
    $e = $_; -not ($RemovePrefixes | Where-Object { $_ -and $e.StartsWith($_) }) -and $e -ne $Add })
  if ($Add) { $out += $Add }
  return ($out -join ':')
}

# Is our service bound? Fire OS 7 prints services:{Service[label=<label>,...]} under User state, without the component id.
# $Label is the service's label (strings.xml home_redirect_label), not the app name.
function Test-ServiceBound([string]$DumpText, [string]$Component, [string]$Label = 'My Home redirect') {
  if (-not $DumpText) { return $false }
  ($DumpText -match [regex]::Escape($Component)) -or ($DumpText -match ('Service\[label=' + [regex]::Escape($Label) + '[,\]]'))
}

function Adb-Sh([string]$Cmd, [switch]$Mutate) {
  if ($Mutate -and $DryRun) { Write-Output "[dry-run] adb -s $Tv shell $Cmd"; return }
  (& $Adb -s $Tv shell $Cmd 2>&1) | ForEach-Object { "$_".TrimEnd() }
}
