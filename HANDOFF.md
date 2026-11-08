# VYRO — handoff

Read this first, then [ARCHITECTURE.md](ARCHITECTURE.md), [FEATURES.md](FEATURES.md) and [CLAUDE.md](CLAUDE.md).
The history of every step is in [IBTZY_UI_PORT.md](IBTZY_UI_PORT.md). Last updated 2026-11-08.

## What VYRO is

A home screen for Google TV / Android TV (plus a settings app that switches between VYRO and the TV's own home
screen). It is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT) with the look and features of
the owner's earlier **ibtzy TV Launcher** (Java) on top. Plain Java 8, no Gradle: `build.sh` (aapt2, javac, d8,
apksigner). LG (webOS) and Samsung (Tizen) builds are Free My TV's web app, unchanged apart from the name.

## Where things are

| What | Where |
|---|---|
| Repo (public) | github.com/muhibtasam/vyro, branch `main` (was `muhibtasam/tv-launcher`, redirects) |
| Local folder (owner's Mac) | `/Users/mij/Codes/vyro` |
| Package / app name | `io.vyro.tv` / VYRO |
| Version | 1.2.0 (versionCode 21) — `app/AndroidManifest.xml` |
| Releases | GitHub Releases, tag `vX.Y.Z`, one asset `VYRO.apk` (keyless, `build.sh --generic`) |
| UI reference (read only) | `/Users/mij/Codes/ibtzy-tv-launcher` (Java, `io.ibtzy.tvlauncher`) and worktrees `/Users/mij/Codes/ibtzy-wt/feature-2.0|2.1|2.2` |
| Upstream | git remote `upstream` = simbasjungles/free-my-tv; our base was upstream `a9e1027` (2026-10-31), merged to upstream main of 2026-11-07 |
| Designs (approved) | Updates & notifications V1: claude.ai/artifact/E4fjjnfRzY2rTpFGrge9fX (B1 update sheet, C2 Control Center alerts, A3 Settings rows) |
| Keys | `config.properties` (gitignored; never commit, print or copy into a release) |
| Signing | `freemytv.keystore`, password `freemytv` (gitignored; keep the name and password: every update must be signed with it) |

## The owner's TV

TCL Smart TV Pro, Android 14, `adb connect 192.168.1.69:5555` (`adb kill-server` if "No route to host").
- VYRO 1.0.8 **personal build** (made from `config.properties`, with keys) is installed and is the Home screen
  (VYRO mode). Old builds `io.freemytv` and `io.ibtzy.tvlauncher` were uninstalled on 2026-11-07.
- Granted: READ_TV_LISTINGS, GET_USAGE_STATS, TCL AUTO_START, notification listener (Google notifications On).
- The "Allow USB debugging?" prompt for VYRO's own key: the owner must tick **Always allow** once.
- RAWG (`api.rawg.io`) is blocked on the owner's network (Pakistan): games come from IGDB (Twitch keys).

## Working rules (the owner's, keep them)

- No commit, push, release or TV install without the owner's OK for that step (they approve in chat; recent
  messages approved releasing each fix). Never TV-test unless asked; the owner wants code first, tests at the end.
- Short replies: what changed, files, checks that actually ran, open issues, what needs approval. Separate Mac
  checks from TV checks. Under 15 bullets.
- Never put API keys in commits, logs or releases. Releases are `build.sh --generic` only; verify the keys in
  `out/gen/io/vyro/tv/Config.java` are empty before uploading.
- New designs need the owner's approval before code. Follow the ibtzy design language; no AI-generated assets, never
  the owner's personal photos.
- Text wraps inside its container; no overlap or clipping; no work during focus, scroll or animation (TV perf).
- Don't touch the ibtzy repo or worktrees (read only). Don't change USB debugging or other TV settings yourself.

## Build, check, release, install

```sh
./build.sh                 # personal build: keys and settings from config.properties → VYRO.apk
./build.sh --generic       # release build: no keys, no personal settings, Games tab off by default
bash tools/check.sh        # MUST pass: build, 18 test suites (tools/tests), translations, LG + Samsung packages
```
Release (owner's OK first): raise `versionCode` + `versionName` in `app/AndroidManifest.xml`, `bash tools/check.sh`,
`./build.sh --generic`, check keys are empty, copy `VYRO.apk` out of the tree, commit + push, then
`gh release create vX.Y.Z <copy>/VYRO.apk -R muhibtasam/vyro --title "VYRO X.Y.Z" --notes-file notes.md`.
The release notes' bullets become the update sheet's "What's new".
Install on the TV (owner's OK first): `adb -s 192.168.1.69:5555 install -r VYRO.apk`.

## How updates reach the TV

VYRO checks `muhibtasam/vyro` releases: once a day (job) and, while the home screen runs, once an hour (HomeView).
A newer, verified APK (size + SHA-256 from GitHub, higher versionCode, same signer, same package) opens the update
sheet: Install / Later. Nothing installs by itself. Personal settings and keys survive keyless updates
(`BuildKeys`): build your own once, then update from releases.

## Open items

1. Games loading skeleton: the owner says it is "not per design" — needs the Games V1 design link (not in the
   updates canvas). Not started.
2. Verify on the TV: automatic update sheet (next release), Settings left-column layout (1.0.6+), power-on hand-off
   to Google TV when it is the default (1.0.2+, not tested with a reboot), keys kept after a keyless update.
3. Free My TV upstream items not ported: Slovak, Fire TV HDMI switch, projector / App Settings drawer shortcuts,
   upstream's Trakt and "count after watched" picks changes (our Recs is ibtzy's).
4. Known ibtzy-inherited issue: with no Continue Watching row, the dock's focus label overlaps the bottom bento row.
5. Play Protect may flag the APK (targetSdk 33); a fix is targetSdk 34+, needs approval.
6. The website in `docs/` is still Free My TV's; `freemytv.io` links in docs are upstream's.
