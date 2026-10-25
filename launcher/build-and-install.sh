#!/usr/bin/env bash
# Build the launcher APK, install it on the TV, and make it the home screen.
#
# Android TV / Google TV: sets the launcher as the default home and disables the stock launcher
# (STOCK_LAUNCHER, default com.google.android.tvlauncher).
# Fire TV: never touches Amazon's launcher, which also hosts Settings. Instead it turns on the
# launcher's HomeRedirectService and its photo screensaver. See "Fire TV" in the README.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python   # Windows venv layout
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
MAKER=$("$PY" -I ../tools/tv.py 'getprop ro.product.manufacturer' | tr -d '\r\n')
if [ "$MAKER" = "Amazon" ]; then
  printf '%s\n' \
    "pm install -r /data/local/tmp/tvhome.apk" \
    "rm -f /data/local/tmp/tvhome.apk" \
    "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS" \
    "appops set com.gathin.tvhome GET_USAGE_STATS allow" \
    "settings put secure enabled_accessibility_services ''" \
    "settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService" \
    "settings put secure accessibility_enabled 1" \
    "settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.PhotoDream" \
    "am start -n com.gathin.tvhome/.MainActivity" \
    | "$PY" -I ../tools/tv.py --batch
  echo "Installed on Fire TV. Home opens the launcher. To go back to Amazon's home screen, press Menu on"
  echo "the launcher's Settings button and choose Use Amazon Home Screen."
else
  printf '%s\n' \
    "pm install -r /data/local/tmp/tvhome.apk" \
    "rm -f /data/local/tmp/tvhome.apk" \
    "cmd package set-home-activity --user 0 com.gathin.tvhome" \
    "pm disable-user --user 0 $STOCK_LAUNCHER" \
    | "$PY" -I ../tools/tv.py --batch
  echo "Installed. Press Home on the remote to see it."
fi
