param(
  [string]$Tv = $env:TV_HOST,
  [string]$Adb = $(if ($env:ADB) { $env:ADB } else { 'adb' }),
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }),
  [string]$AppId = "com.gathin.tvhome",
  [switch]$DryRun
)

# Builds the launcher APK, installs it on a Fire TV and turns on its HomeRedirectService (Home button opens My Home).
# Amazon's launcher is never touched and neither is the screensaver.
# On verification failure our accessibility entry is removed again and the script exits 1. -DryRun prints every
# adb/gradle command instead of running it (read-only adb queries still run).
. "$PSScriptRoot\..\tools\fire-lib.ps1"
$Tv = Resolve-TvHost $Tv
if (-not $JavaHome) { throw "Set JAVA_HOME (JDK 17) or pass -JavaHome." }
$comp = "$AppId/$ServiceClass"
$key = 'enabled_accessibility_services'

# 1. Build. gradlew.bat fails from cmd here, so run the wrapper through Git Bash.
$bash = 'C:\Program Files\Git\bin\bash.exe'
function ConvertTo-Posix([string]$p) { '/' + $p.Substring(0, 1).ToLower() + $p.Substring(2).Replace('\', '/') }
$dir = ConvertTo-Posix $PSScriptRoot
$buildCmd = "cd '$dir' && export JAVA_HOME='$(ConvertTo-Posix $JavaHome)' && export PATH=`"`$JAVA_HOME/bin:`$PATH`" && ./gradlew assembleDebug -q --console=plain -PlauncherApplicationId=$AppId"
if (-not (Test-Path "$PSScriptRoot\local.properties") -or -not (Select-String -Path "$PSScriptRoot\local.properties" -Pattern '^sdk\.dir=' -Quiet)) {
  if ($DryRun) { Write-Output "[dry-run] write local.properties sdk.dir=$AndroidHome" }
  else { Set-Content "$PSScriptRoot\local.properties" ("sdk.dir=" + $AndroidHome.Replace('\', '/')) -Encoding ascii }
}
if ($DryRun) { Write-Output "[dry-run] bash -c $buildCmd" }
else {
  & $bash -c $buildCmd
  if ($LASTEXITCODE -ne 0) { Write-Output "FAIL: gradle build"; exit 1 }
}
$apk = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
if (-not $DryRun -and -not (Test-Path $apk)) { Write-Output "FAIL: $apk not found"; exit 1 }

# 2. Install
if ($DryRun) { Write-Output "[dry-run] adb -s $Tv install -r $apk" }
else {
  $r = (& $Adb -s $Tv install -r $apk 2>&1) -join ' '
  if ($LASTEXITCODE -ne 0 -or $r -notmatch 'Success') { Write-Output "FAIL: install: $r"; exit 1 }
}

# 3. Grant, merge our service into the enabled list (others kept), re-toggle accessibility so new capabilities apply
Adb-Sh "pm grant $AppId android.permission.WRITE_SECURE_SETTINGS" -Mutate
$cur = (Adb-Sh "settings get secure $key") -join ''
$new = Update-A11yList $cur -Add $comp -RemovePrefixes @()
foreach ($try in 1..2) {
  Adb-Sh "settings put secure $key $new" -Mutate
  Adb-Sh "settings put secure accessibility_enabled 0" -Mutate
  Adb-Sh "settings put secure accessibility_enabled 1" -Mutate
  if ($DryRun) { break }
  Start-Sleep -Seconds 2
  if (((Adb-Sh "settings get secure $key") -join '') -match [regex]::Escape($comp)) { break }
}

# 4. Verify; roll back our entry on failure
if ($DryRun) { Write-Output "[dry-run] verify: dumpsys accessibility must show $comp bound"; Write-Output "[dry-run] am start -n $AppId/$MainClass" }
else {
  Start-Sleep -Seconds 3
  $bound = Test-ServiceBound ((Adb-Sh "dumpsys accessibility") -join "`n") $comp
  $saved = (Adb-Sh "settings get secure $key") -join ''
  if (-not $bound -or $saved -notmatch [regex]::Escape($comp)) {
    Write-Output "FAIL: service not bound (enabled list: $saved); rolling back"
    $rb = Update-A11yList $saved -RemovePrefixes @("$AppId/")
    if ($rb) { Adb-Sh "settings put secure $key $rb" } else { Adb-Sh "settings delete secure $key"; Adb-Sh "settings put secure accessibility_enabled 0" }
    exit 1
  }
  $s = (Adb-Sh "am start -n $AppId/$MainClass") -join ' '
  if ($s -match 'Error') { Write-Output "WARN: could not open the launcher: $s" }
}
Write-Output "Installed $AppId and enabled $comp."
Write-Output "Pin 'My Home' into Amazon's apps row (Apps > highlight My Home > Menu > Move) so Home opens it in under a second."
