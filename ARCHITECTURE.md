# VYRO — architecture

Plain Java 8 Android app (`app/src/io/vyro/tv`, ~90 classes), no Gradle, no libraries. minSdk 26, targetSdk 33.
Everything is drawn by hand on Canvas (no XML layouts). Pure logic lives in `*Logic` classes with no Android imports,
tested on the JVM by `tools/tests/` (`run.sh`, wired into `tools/check.sh`).

## Processes

| Process | What runs there |
|---|---|
| `:home` | `HomeActivity` + `HomeView` (the home screen), `SettingsActivity`, `GoogleAlerts` (notification listener), `UpdateReceiver`, the screensaver views |
| main | `ModeJob` (JobService: mode switch, verify, update check, grants), `HomeMode`, `HomeReceiver` (boot, package replaced), `Watchdog` |
| `:keys` | `RemoteKeyService` (accessibility service for the remote's app buttons and voice) |

Cross-process state: `SharedPreferences` (`Prefs`, reloaded on `Prefs.version`), small JSON files in `filesDir`
(`update.json`, mode state), never shared memory.

## Build

`build.sh` reads `config.properties` → generates `out/gen/io/vyro/tv/Config.java` (keys, city, shelf apps, tabs,
feature flags) → aapt2 → javac → d8 → zipalign → apksigner (`freemytv.keystore`). `--generic` writes empty keys and
defaults (`Config.GENERIC = true`): the release build. `BuildKeys` bridges the two: a personal build saves its keys
and settings privately on the TV; a generic build reads them back, so release updates keep them.

## Areas and their classes

**Mode switch (Free My TV core)** — `HomeMode` (switch to VYRO / back to Original through the TV's own ADB on
127.0.0.1:5555 with `Adb`; disables the stock launcher and `setupwraith` in the right order; records disabled apps in
the global setting `vyro_disabled`; grants WRITE_SECURE_SETTINGS, usage access, READ_TV_LISTINGS; TCL AUTO_START),
`ModeJob`, `HomeReceiver`, `Watchdog`, `Capabilities` (what this TV can do). `Adb` asks "Allow USB debugging?" for
background work at most once per 10 minutes (`Adb.mayAsk`); a switch the owner starts always asks.

**Home screen** — `HomeActivity` (lifecycle, direct-boot skeleton `BootSkeleton`, `handOffHome`: gives the screen to
the TV's chosen home when VYRO is not the default), `HomeView` (layers: sky/backdrop, tabs Home / For You / Apps,
bento grid, dock, drawer, Control Center, update sheet, sheets), `HomeNav` / `TabsLogic` / `VisibleOrder` (focus),
`CardCache`, `Backdrop`, `Shaders`, `Shadows`, `Spring` (animation), `Symbols`/`Glyphs`/`TabIcons`/`IconShape`.

**Home grid (bento)** — `AppContent` reads what the streaming apps publish to Android TV (TvProvider preview
programs, READ_TV_LISTINGS) → `AppContentLogic` (select/merge) → `MixLogic` (mixes across apps, seeded per part of
day) → `Recs.homeShown`. TMDB is not used for Home. `ContinueLogic` + `WatchNext` = the Continue Watching row
(hold OK to remove; returns after new viewing).

**For You: Movies & Shows** — `Recs` (TMDB via `api.tmdb.org`, Watchmode for deep links, Trakt, Plex), `Discover`,
`MoviesLogic`, `Arrivals`/`ArrivalsLogic`, `TitleLaunch` (open a title in its app; Nuvio/Stremio "open in").
Needs a TMDB key (`BuildKeys.tmdb()` or typed on the TV).

**For You: Games** — `Games`: rows (recently released, popular this year, upcoming) from **IGDB first**
(Twitch client id + secret; `GamesLogic.igdbQuery/igdbImage/igdbDate/igdbPlatforms`), **RAWG as fallback**;
stage 2 enrichment: SteamGridDB art, OpenCritic scores, Wikipedia views, IGDB trailers. `GamesV1Logic` = layout
(hero countdown, release calendar). Off by default; Settings › Tabs.

**Apps** — `HomeApps` (launchable apps, dock order: saved order → `shelf_apps` → the rest; Live TV, inputs, store),
`AppsLogic`, `AppsVoice`, `RecentApps`.

**Control Center** — `ControlCenter` + `ControlCenterNav` (quick tiles, sleep timer, inputs) and the **Google
notifications section** (`GoogleAlerts` listener in `:home`, memory only, ≤40 alerts; `AlertsLogic` rules; unread
dot on the CC button).

**Updates** — `UpdateLogic` (parse GitHub latest release, compare versions, changelog, when to offer; tested),
`Updates` (daily job + `due()` hourly check from `HomeView.offerUpdate`; download, size/SHA-256/signer/package
checks; `update.json`), `UpdateSheet` (design B1), `UpdateReceiver` (installer result). Source:
`UpdateLogic.LATEST` = `api.github.com/repos/muhibtasam/vyro/releases/latest`, asset `VYRO.apk`.

**Settings** — `SettingsActivity` (one hand-drawn view, screens MAIN / HOME_SETTINGS / TABS / shelf editors /
remote keys / power / keys…). Main list (design A3): Home Screen, Screensaver, Home Settings, Tabs, Shelf, Top
Buttons, Remote Buttons, Sleep Timer, **Updates**, **Google notifications**, TV Settings; left column: name,
version, comparison, notices; right under the list: recommendations status.

**Screensaver** — `Screensaver` (DreamService) / `ScreensaverAlone` → `DreamView` (picks' art or own photos;
portraits and panoramas shown whole), `Photos`, `ImmichPhotos`, `PhotoInbox`.

**Other** — `Place` (city from time zone or config), `HomeData` (weather Open-Meteo, Binance prices),
`HomeAssistant`, `NowPlaying`, `SleepTimer`, `NetRecovery`, `MemoryPolicy`, `Startup` (timing marks), `AppLog`
(logcat tag `VYRO`), `Ui` + `Tr` (fonts Fraunces/Sora, `Ui.tr` translations from `app/assets/i18n/<lang>.json`).

## Translations

Every `Ui.tr("…")` string is a key in `app/assets/i18n/en.json` and all 26 catalogues. `python3 tools/translate.py
--check` (in check.sh) fails on missing or obsolete keys; `--prune` removes obsolete ones; `--fill` needs
ANTHROPIC_API_KEY. pt, es, de, ru, th, tr are hand catalogues.

## Tests (tools/tests)

AlertsLogic, AppContentLogic, AppsLogic, ArrivalsLogic, ContinueLogic, ControlCenterNav, GamesLogic (incl. IGDB),
GamesV1Logic, HomeNav, IconShape, MemoryPolicy, MixLogic, MoviesLogic, NetRecoveryLogic, TabsLogic, TitleWrap, Tr,
UpdateLogic. Plus `tools/test-web.sh` for the LG/Samsung web app.

## TV tools (tools/)

`tv_install.sh`, `tv_shot.sh` (screenshots), `keys.sh` (send keys), `startup.sh`, `perf.sh` / `perf_suite.sh` /
`bench.sh` / `stalls.sh` / `trace.sh` (performance), `send_key.py` (send a key to the app over the network),
`photos.py` (send photos). Most take `TV=<ip:port>`.
