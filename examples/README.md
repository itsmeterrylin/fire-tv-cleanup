# Examples

One folder per TV model, each with a `debloat.txt` listing the packages that are safe to disable
on that firmware, grouped and commented. These are starting points: comment out anything you use.

| Folder | TV | OS | Notes |
|---|---|---|---|
| `xiaomi-mi-tv-4a/` | Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 | PatchWall, Mi analytics, SmartShare, MiLink |
| `amazon-fire-tv-aftalmo/` | Amazon Fire TV (AFTALMO, almond) | Fire OS 7.7.1.5 (Android 9) | Aggressive list: ads, telemetry, Alexa side services, setup and support apps |

To add your TV, see [CONTRIBUTING.md](../CONTRIBUTING.md). The quickest way to get a list is to
run the prompt in [PROMPT.md](../PROMPT.md) and copy the packages your assistant disabled.
