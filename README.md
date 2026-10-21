# fire-tv-cleanup

**Your TV, minus the ads.** Debloat an Android TV, Google TV or Fire TV over ADB and give it a
clean launcher with a macOS-style dock. Hand the prompt to Claude Code or any coding assistant,
answer a few questions about which apps you use, and it does the rest on the real TV.

Forked from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack), which covers Android TV
and Google TV. This fork adds Fire TV support and the launcher features listed below.

![Fire TV home screen with ads (top) and the launcher (bottom)](docs/firetv-home-before-after.jpg)

Everything is reversible: packages are disabled, never uninstalled, and a restore script puts the
stock launcher back.

## Quick start

1. On the TV: Settings → Device Preferences → About → press OK on **Build** seven times → back →
   Developer options → turn on **USB debugging** (and Network/ADB debugging if your TV shows it).
   Keep the TV on and on the same Wi-Fi as your computer.
2. Open Claude Code (or your assistant of choice) on your computer and paste the prompt from
   [`PROMPT.md`](PROMPT.md), filling in your TV model.
3. Accept the "Allow USB debugging?" prompt on the TV when told to, then answer the checklist
   questions (which apps you use, which four go in the dock).

That's it. The assistant backs up, debloats, builds and installs the launcher, and verifies each
step with screenshots from the TV. If you'd rather do it by hand, the pieces are below.

## What's in the repo

| Path | What it is |
|---|---|
| [`PROMPT.md`](PROMPT.md) | The prompt. Brand-neutral; the assistant discovers your TV's bloat. |
| [`launcher/`](launcher/) | The tvOS-style launcher. Edit `app/src/main/res/values/config.xml` for your dock, hidden apps and Live TV package, then run `build-and-install.sh`. |
| [`tools/tv.py`](tools/tv.py) | Pure-Python ADB client (shell, batch, push, pull). Works where the stock `adb` is blocked. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files (APKs) as base64 chunks with an md5 check. |
| [`tools/disable.sh`](tools/disable.sh), [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. |
| [`examples/`](examples/) | Per-TV debloat lists. Add yours, see [CONTRIBUTING.md](CONTRIBUTING.md). |

## The launcher

A small native Android TV home screen (`com.gathin.tvhome`), built for sets with 1 GB of RAM:

- A full-screen photo that changes with the time of day: morning, afternoon, evening and night.
  The greeting changes with it ("Good evening, Alex").
- A macOS-style dock of up to four apps with square icons. The focused icon grows and lifts, its
  neighbours grow a little, and a capsule label shows its name. Press Down for the rest of your
  apps.
- Pin, unpin and reorder dock apps on the TV: hold OK or press Menu on an app.
- Round buttons top right: **Inputs** (HDMI ports, AV and Live TV), **Wi-Fi** (signal bars, plus a
  warning when there is no internet) and **Settings**. Greeting, clock and date top left, set in
  [Open Sans](https://fonts.google.com/specimen/Open+Sans).
- A screensaver of the same photos with a slow zoom, a crossfade every 20 seconds and a clock.

Configure it in [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
the starting dock, hidden apps, Live TV package and columns. To put your name in the greeting, add
`tvhome.userName=Alex` to `launcher/local.properties`. The YouTube, Netflix, Prime Video and AirPlay
dock icons are trademarked logos, so they are not in the repo. Draw them locally with
[`tools/make_dock_icons.py`](tools/make_dock_icons.py). Apps without one show their own icon.
Build and install with

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:
`brew install openjdk@17 android-commandlinetools && sdkmanager "platforms;android-34" "build-tools;34.0.0"`.

## Talking to the TV by hand

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP
tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # one command
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # apply a list
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # undo it, restore stock launcher
```

### Gotchas we hit

- **macOS: `adb connect` says "No route to host"** while the port is clearly open. Recent macOS
  treats the Google-signed `adb` as its own app under Local Network privacy and silently blocks it.
  `tools/tv.py` avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs (Xiaomi among them). USB debugging alone opens
  network ADB on port 5555.
- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run
  one per shell call; `--batch` does this.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Disable the stock launcher only after the new one is installed**, or the Home button lands on
  a factory test app.
- **Third-party launchers** often charge for basics like a custom wallpaper. Hence `launcher/`.
- **No on-screen keyboard** after switching launchers (seen on Xiaomi): the default input method
  pointed at a Baidu IME that isn't installed. Fix with
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`.

## Fire TV

Fire OS is Android underneath, but it guards its home screen. Tested on a Hisense Fire TV Edition
(Fire OS 8, Android 11, model AFTOLCBC82).

![Amazon screensaver (top) and the launcher's screensaver (bottom)](docs/firetv-screensaver-before-after.jpg)

To set it up:

1. On the TV, open **Settings → Device & Software → About**, select the device name seven times,
   then go back to **Developer Options** and turn on **ADB debugging**.
2. Run `TV_HOST=<tv ip> launcher/build-and-install.sh`. On Fire TV it installs the launcher, turns
   on its Home redirect and sets its screensaver. It leaves Amazon's launcher alone.
3. Optional: disable the ad and telemetry packages with
   `tools/disable.sh examples/hisense-fire-tv-AFTOLCBC82/debloat.txt`.

How it differs from Android TV:

- **Amazon's launcher stays.** Fire OS ignores the default-home setting, and Amazon's launcher also
  hosts Settings. Disabling it locks you out of Settings. The launcher's `HomeRedirectService`
  opens the launcher whenever Amazon's home screen comes up, so Amazon's screen shows for a moment
  before yours.
- **Settings keeps working.** Amazon's home screen also flashes up on the way into Settings, right
  after a window from Amazon's settings app. The redirect leaves that flash alone.
- **To turn the redirect off on the TV,** press Menu on the launcher's Settings button and choose
  **Use Amazon Home Screen**.
- **ADB turns off when the TV restarts.** Turn **ADB debugging** back on in Developer Options
  before you connect again.
- **Some packages are protected.** Fire OS refuses to disable the four packages listed in the
  example debloat list. Undo the rest with `tools/restore.sh` and the same list.
- **AirPlay has no screen of its own.** The AirPlay tile opens nothing, and casting from an iPhone
  works anyway.

### Fire OS 7 (Android 9) and Windows

Tested on an Amazon Fire TV AFTALMO (codename almond, Fire OS 7.7.1.5). Fire OS 7 blocks background
apps from opening a screen for about 5 s after Home is pressed, and never passes the Home key to
accessibility services, so the redirect needs more than the Fire OS 8 approach above. The pieces
below are provided separately; this section describes how they fit together.

- **Fast Home** (requires PR: Fire OS 7 fast Home). The launcher installs as **My Home**
  (`com.gathin.tvhome`; change the id with `-PlauncherApplicationId=`). When Amazon's home appears,
  the redirect clicks the My Home tile in Amazon's apps row: Amazon opens it, which is not blocked,
  in about 0.6 s. Pin the tile once with Apps, highlight My Home, Menu, Move to front. Without the
  tile the redirect covers Amazon's screen in black and opens My Home after the 5 s.
- **Boot guard** (same PR). For 3 minutes after boot, until the first remote press, an app that
  opens itself is sent back to My Home. It can still flash for a moment.
- **Loop guard** (same PR). After 3 redirects in 10 s the redirect pauses for a minute.
- **Windows scripts** (requires PR: Windows scripts). Set `TV_HOST` to the TV's `ip:port`
  (or pass `-Tv`) and run:
  - `launcher\build-and-install.ps1`: builds through Git Bash, installs, turns on the redirect,
    verifies it and rolls back on failure. `-DryRun` prints the commands.
  - `tools\fire-check.ps1 -ListFile <list>`: lists packages an Amazon update turned back on
    (`-Fix` turns them off again) and checks the redirect is still running.
  - `tools\debloat-loop.ps1 -ListFile <list>`: disables one package at a time with a health check
    and rollback, and appends restore lines to `backup\restore.ps1`.
  - `tools\fire-home-off.ps1`: turns the redirect off (`-Uninstall` also removes the app).
  - `tools\keepalive.ps1`: keeps ADB reachable while you work.
- **Debloat list:** [`examples/amazon-fire-tv-aftalmo/debloat.txt`](examples/amazon-fire-tv-aftalmo/debloat.txt)
  is an aggressive list for this model. Fire OS turns Alexa (`com.amazon.vizzini`) back on at every
  boot, so it is not listed.

## Supported TVs

Android TV and Google TV: Xiaomi/Redmi, TCL, Hisense, Sony, OnePlus, Realme, Nvidia Shield and most
others. Fire TV: see [Fire TV](#fire-tv). Not Samsung Tizen or LG webOS. Tested so far on the models
in [`examples/`](examples/).

## Contributing

Add your TV's debloat list, improve the launcher, or sharpen the prompt. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT, see [`LICENSE`](LICENSE). The Outfit and Open Sans typefaces are under the SIL Open Font
License, see `launcher/FONT-LICENSE-OFL.txt`. Photo credits are in [`CREDITS.md`](CREDITS.md).
