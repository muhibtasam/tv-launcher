> This is the history log of the port and the VYRO releases. Current state: [HANDOFF.md](HANDOFF.md).

# Ibtzy UI port: handoff

VYRO (this repo, `io.vyro.tv`) now draws Ibtzy TV Launcher's home screen. Ibtzy (`~/Codes/ibtzy-tv-launcher`)
was the reference only and is unchanged. Started 2026-11-06. Nothing pushed; one local commit (the baseline).

## State

- Git: `main`, baseline commit `9fad214` = VYRO exactly as added (the way back: `git diff 9fad214`, or
  `git checkout 9fad214 -- <file>`). Everything below is **uncommitted** in the working tree, waiting for review.
- `./build.sh --generic` → `VYRO.apk` (5.1 MB; 1.6 MB before). `tools/check.sh` passes (build, remote and order
  tests, 16 Ibtzy unit tests, translations, LG and Samsung packages, ES5).
- Not installed on a TV yet: nothing here is verified on a device.

## How the port is split

| From Ibtzy (renamed to `io.vyro.tv`) | Kept from VYRO |
|---|---|
| `HomeView` and every class it draws with: `CardCache`, `ControlCenter`(+`Nav`), `BootSkeleton`, `Discover`, `Games*`, `Moods`, `Movies/Tabs/Mix/Arrivals/AppContent/Continue/Apps Logic`, `TabIcons`, `TitleLaunch`, `TitleWrap`, `Tr`, `HomeNav`, `IconShape`, `MemoryPolicy`, `NetRecovery*`, `Radios`, `ServiceRepair`, `Startup`, `AppsVoice` | `HomeMode`, `Adb`, `ModeJob`, `HomeReceiver`, `Watchdog`, `AppLog`, `RemoteKeys`, `Prefs`, `SettingsActivity`, `WatchNext`, `ScreensaverAlone`, `Recording`, `Frost`, `Glyphs`, `Ui` |
| Data and drawing: `Recs`, `HomeApps`, `HomeData`, `Trakt`, `HomeActivity`, `DreamView`, `Screensaver`, `Scene`, `Backdrop`, `Shaders`, `Shadows`, `Spring`, `Symbols`, `Click`, `App`, `RemoteKeyService` | Every VYRO-only class (Updates, KeyPage/Qr, Secrets, Place, Capabilities, Photos/Immich, SleepTimer/Overlay, QuickPanel/QuickSettings, HomeAssistant, NowPlaying, RecentApps, WatchArt, FireHomeActivity, SkyworthRemote, VisibleOrder) |

Grafted so both halves meet (search for "VYRO" in these files): `Recs.key()`/`watchmodeKey()` (keys typed on the
TV win), `checkKey`, `installedServices`, `api.tmdb.org`, `REGION`/currency from `Place` (generic builds),
`Prefs.hideAnimation()` and `serviceOff` (Picks from) in `Recs`; `HomeApps` Android apps filter, `tvSettings` with the
MainSettings fallback, `isHidden`, `storePackage`, `lowRam`, `liveTvItem`; `HomeActivity` sleep timer, power-on Live
TV, TCL Android 11 opaque fix, update notice, All Apps extra; `Ui` Ibtzy fonts (Fraunces, Sora) + `Tr` wiring on Free My
TV's language code; `Prefs` tabs and Continue removals; `Trakt` adapter for the settings row; `DreamView` + `HomeView`
import My Photos / Immich; Ibtzy's mic voice search in `RemoteKeyService`.
`HomeMode` additions: `WRITE_SECURE_SETTINGS` grant (ServiceRepair), `GET_USAGE_STATS` allowed with the switch and reset
by Original (Apps > Most used), YouTube Music protected (it's on the dock).
Settings: Ibtzy's look (warm ground, serif-italic name, ivory pills and ring) and its **Tabs** screen (main row 4).
`build.sh`: Ibtzy's Config keys (IGDB/Twitch, RAWG, SteamGridDB, OpenCritic, tabs, shelf); **Games is off in
`--generic` builds** (it reads Steam, PlayStation Store, Wikipedia: new sources need the owner's OK, CLAUDE.md).
Tools: Ibtzy's TV scripts (`tv_install.sh`, `perf.sh`, `perf_suite.sh`, `ab.sh`, `bench.sh`, `trace.sh`, `startup.sh`,
`stalls.sh`, `tv_shot.sh`, `compiled.sh`, `keys.sh`, `refresh_cost.sh`, `screensaver.sh`) and `tools/tests/`.
Translations: Ibtzy's strings merged in (zh-TW from zh-CN through macOS's Hans→Hant); 61 obsolete keys pruned (they're
in `9fad214` for when their features return).

## Pass 2 (2026-11-06): audit, fixes, compatibility

### Feature audit against `9fad214`

| VYRO feature | Now |
|---|---|
| Mode switch, Original, watchdog, boot check, Wi-Fi owners, setup-app order, Huawei/Kalley/Fire TV/phone refusals, TCL stalled-switch message | Unchanged (`HomeMode`, `ModeJob`, Settings) |
| Self-updates (weekly job, Settings row, install) | Unchanged; "update ready" notice now also from the new `HomeActivity` |
| TMDB / Watchmode keys typed on the TV (QR page) | Work: the new `Recs`/`Discover`/`TitleLaunch` read `Recs.key()`/`watchmodeKey()` |
| Remote Buttons, every brand; TCL Android 11 (no key filter); Xiaomi bursts; All Apps button; Quick Panel; JVC Quick Settings | Work; All Apps opens the Apps tab; Settings button = Control Center on the home screen (TCL/Hisense rows first) |
| Sleep timer, its overlay | Works; the home screen's own "Off in…" line (shown only without the overlay permission) is gone |
| Language, temperature, clock 12/24 h, city, weather, navigation sounds, theme | Work (theme now defaults to dark, as Ibtzy) |
| Picks from, Cartoons and anime, Android apps, Continue Watching apps | Work (grafted into the Ibtzy `Recs`/`HomeApps`) |
| Continue Watching on/off (+ its `READ_TV_LISTINGS` grant through ADB) | Works: the old "Top of the screen" row is now **Continue Watching** On/Off, the Ibtzy row under the picks; off by default (privacy rule) |
| Watch from the screensaver | Works again: `DreamView.watch()` opens the slide's title with the Ibtzy link code |
| My Photos, Immich | Screensaver: yes. Home background from photos: no (the Ibtzy background follows the focused card) |
| Quotes widget (Binance) | Screensaver only (Ibtzy's home has no quotes card); default off, as Ibtzy |
| At power-on (TV, Home, Live TV) | Works |
| Trakt sign-in | Works (Ibtzy's Trakt: taste and trending). VYRO's Trakt lists (up next, watchlist in the picks): not ported |
| Plex picks, open_in_stremio | **Not ported** (config-only features; the Ibtzy pick pipeline differs: next work) |
| Recent Apps, Now Playing, Status icons, Home Assistant tiles, Forecast, Dollar, Picks per tab, mouse/air mouse, double Home | **Dimmed "Unavailable"**: each needs a place in the Ibtzy design that doesn't exist (no invented UI). Picks per tab has no meaning on the fixed bento. Wi-Fi/Bluetooth state already shows on the Ibtzy top buttons |

### UI accuracy

The screens are Ibtzy's own files; differences came only from the kept VYRO classes, now aligned: theme default
dark and quotes off (`build.sh`, `config.example.properties`), Settings rows at Ibtzy's height (66 px, 9 rows, was
53.75), Settings ground/title/ivory ring as Ibtzy, Ibtzy fonts. Dock limit: Shelf editor capped at the dock's
`MAX_SHELF`. Kept on purpose: "Home Settings" (Ibtzy says "Personalize"; VYRO's README and notes use the name).

### Performance

**No measurements this pass**: every benchmark (`tools/perf_suite.sh`, `startup.sh`, `ab.sh`) runs on the TV, and nothing
was installed. Reference numbers to compare against once installed: Ibtzy on the TCL C6K (its `docs/TECHNICAL_AUDIT.md`,
2026-11-01): cold start ~320 ms to first frame, warm ~100 ms, 3–8 dropped of ~119 frames per scenario, Home cards p50
22 ms, `:home` 92–122 MB PSS at rest. VYRO `9fad214` has no numbers on that TV: measure it with `tools/ab.sh`.
Code-level fixes only where the port added work: the Immich check (reads its key file) runs off the UI thread on one
serial worker; "Picks from" lookups are memoised per settings change (`Recs.hidden`, called for every service).

### Compatibility findings

- Android 8 (API 26, platform now installed on the Mac): compiling against it flags only guarded calls (AGSL,
  `setFilterMode`, hardware-bitmap card plates and `RenderEffect` behind SDK checks, `Recording.Node`, `Ui.Weighted`,
  `setTranslucent`, `getProcessName`, `FLAG_MUTABLE`; int constants inline). No unguarded call.
- TCL Android 11: key filter stays off (`RemoteKeys.FILTER_SAFE`), so the mic's Apps voice search there goes to the
  Assistant; home activity forced opaque on resume.
- Android 11–12L screensaver runs `ScreensaverAlone` (VYRO's, with `Prefs.reload()`), restored from `9fad214`.
- Crash risks fixed: `HomeApps.open` with no source view (Settings' TV Settings row would have thrown), a kind-number
  clash (`HOME_ASSISTANT` = `INPUTS`), missing MainSettings fallback (#379).

### zh-TW

147 strings converted Hans→Hant, then 70 corrected by hand to Taiwan usage and VYRO's own zh-TW terms
(應用程式, 設定, 搜尋, 螢幕, 載入, 連線, 訊號, 影集, 串流, 金鑰, 重新整理, 「」quotes; "Plugged in" heading 已接 + 上).

## Remaining work

1. Port Plex picks and open_in_stremio into the Ibtzy `Recs` (no UI; config-only).
2. Designs (owner approval, three concepts each) for: Recent Apps, Now Playing, Status icons, Home Assistant,
   Forecast, home-screen sleep countdown, mouse pointer.
3. Measure on the TV (above), then commit in pieces.

## Final TV testing checklist

- Install: `tools/tv_install.sh` (compiled), then Home. TCL: `appops` AUTO_START if the switch stalls (Settings says so).
- Home bento, Continue row (turn on Home Settings > Continue Watching; the TV asks to allow), Inputs rail, dock: remote
  focus, no overlap, titles wrap, ivory ring.
- For You Movies and Games (Games: Settings > Tabs, off in the release build), Apps search (typed and mic), Most used.
- Control Center (gear and the remote's Settings button), AirPlay, screen off, volume, TV Settings.
- Settings: every screen, Tabs toggles, dimmed rows show their reason on OK, TMDB key by QR, Updates row.
- Switch to VYRO and back to Original; boot check after a restart; remote buttons inside Netflix/YouTube.
- Screensaver: picks, your photos, OK opens the title (with Watch from the screensaver on); boot skeleton at power-on.
- `tools/perf_suite.sh`, `tools/startup.sh` x3, `dumpsys meminfo io.vyro.tv:home`; compare with the numbers above.

## Pre-install record (2026-11-06 15:40)

- Source: `main` at `9fad214` + uncommitted work (54 files changed, 74 new; diff+new-files fingerprint `e16ce45586346d7f`).
  Ibtzy repo untouched (`4ede57a`, clean).
- Test APK: `VYRO.apk`, `./build.sh --generic` (Games off, dark, no keys, city from the time zone), 4,766,032 bytes,
  SHA-256 `32e2234be1f8678b48ef1466af790fdd60a0e28b19c51682e68b2b3962d962c0`, `io.vyro.tv` 1.3 (code 4), signed with the
  local `freemytv.keystore` (cert SHA-256 `49aaf013…ae76f`, not the release key: Updates only checks, never downloads).
- A/B baseline: `VYRO-baseline-9fad214.apk` (same key, so `tools/ab.sh` can swap them), SHA-256 `34e0a146…30ae`.
- Safety code vs `9fad214`: `Adb`, `ModeJob`, `HomeReceiver`, `Watchdog`, `RemoteKeys`, `ScreensaverAlone`, `Updates`,
  `Secrets`, `KeyPage`, `Capabilities` unchanged; `HomeMode` +18/−1 (YouTube Music protected, WRITE_SECURE_SETTINGS
  grant, usage access allow/default). Setup-app order, Wi-Fi owners, refusals, Original, watchdog: untouched.
- **Two launchers on one TV:** the TV's home is ibtzy TV Launcher (it turned launcherx off). VYRO records whatever
  home is current as "original", so switching it now would make Original return to Ibtzy and count launcherx as
  turned off by you. Switch Ibtzy to Original first.

## Phase A, attempt 1 (2026-11-06 15:47–15:55): blocked

- TV state before: Google TV Home in front and HOME role holder, nothing disabled, no Ibtzy record; ibtzy TV Launcher
  installed only for user 10 (not the main user). Nothing changed there.
- Installed `32e2234b…62c0` for user 0 only (Play Protect: "Install anyway" by the owner). On-TV `base.apk` SHA-256
  matched.
- **Failure:** `io.vyro.tv:home` crashed on every start: `StackOverflowError` in `Recs.key()` calling itself
  (`HomeActivity.start` → `new HomeView` → `picksOn` → `Recs.hasKey`). Cause: the port's bulk replace of
  `Config.TMDB_KEY.trim()` with `key()` also hit `key()`'s own fallback (and `watchmodeKey()`'s). Fixed on the Mac;
  `tools/check.sh` passes; new APK SHA-256 `9f90dc3f517e51772bee41ff260d8061d0118f09fe90653c17fb4aa9d11dee1e`.
- Watchdog: 1 crash recorded in the crash buffer; no revert ran (no mode), no ADB prompt, Home unchanged.
- Compile: `speed-profile` stayed `verify` (the app never ran long enough to save a profile); `compile -m everything`
  succeeded on this TV. The scripts gained `AS_APP=1` (open it as an app, never press Home into it).

## Phase A, attempt 2 (2026-11-06 16:00): stopped at the key rebuild

- Installed `9f90dc3f…ee1e` over it (user 0); on-TV SHA-256 matched. 5/5 cold starts opened `HomeActivity`, 0
  crashes, `am start -W` TotalTime 507–597 ms (uncompiled install), first frame 60–69 ms after `App` start in the log.
- `READ_TV_LISTINGS` granted for user 0 only (verified `granted=true`); nothing else granted. Compiled: `speed-profile`.
- Found: `HomeView.warmOtherTabs` refreshed Games data even with the Games tab off. No traffic happened on the TV
  (`Games.configured()` needs a RAWG key; the generic build has none). Fixed on the Mac (only while Settings > Tabs >
  Games is on). The owner's `config.properties` now has the Games keys and no `tab_games=false`: a build from it turns
  Games on by default. Waiting for the owner's decision.

## Phase A, attempt 3 (2026-11-06 16:04–16:20)

- Owner added the keys to `config.properties` (values never printed); `tab_games=false` added with approval. Build from
  it (Games off, keys built in): SHA-256 `d8eadfd56641fa0d277c7e1659a75144d08c1a56721ea1c25e860242c6975546`, installed for
  user 0, on-TV SHA-256 matched, `READ_TV_LISTINGS` kept. 5/5 starts, 0 crashes; no Games traffic (0 Games log lines).
- UI (as an app, screenshots in `out/tv/`): Home bento, focus ring, dock focus label, For You (Movies only, as Ibtzy with
  Games off), Apps (search, chips, Recently installed, All apps), Control Center, Settings main / Home Settings: match
  Ibtzy. Continue Watching row on: shown with Ibtzy's heading, focus ring, play button, 2-line title, "S1 E1 · 3% watched".
- Failures found on the TV:
  1. **Crash:** Settings > Tabs after scrolling Home Settings (`ArrayIndexOutOfBounds` in `drawList`: `pickScroll` kept).
  2. Settings main: the notices ran off the bottom of the screen (my taller rows); "Version 1.3" drawn centred (left of
     the name) after the first frame (`version` paint left centred by the TMDB notice).
  3. Watchmode 400 for Continue cards (`search_field=tmdb_watchnext_id`); any error pauses all title-link lookups 10 min.
  4. Not confirmed: the toast when OK is pressed on a dimmed row (not visible in the screenshot).
  5. Seen, from Ibtzy, not changed: without a Continue row, the dock's focused-app label overlaps the bottom bento row.
  All of 1–3 fixed on the Mac (`pickScroll = 0`; VYRO's row metrics back; label left-aligned; Watchmode only for
  movie/tv). `tools/check.sh` passes. Not installed: SHA-256 `31bc01c42ad26db045e778fdd0d8d98d5fced3ff1dcffc69f4afb43ac810a935`.
- Performance (`d8eadfd5…`, speed-profile, opened as an app; Ibtzy's 2026-11-01 numbers on this TV as Home in brackets):

  | Scenario | Dropped frames | Frame p50 / p90 ms |
  |---|---|---|
  | home-cards | 3/119 (3) | 14.3 / 22.2 (22 p50) |
  | apps-search | 5/119 | 13.9 / 22.6 |
  | home-dock | 6/119 (6) | 7.7 / 35.2 |
  | home-drawer | 4/119 | 9.2 / 15.3 |
  | movies* | 0/119 (5–7) | 57.5 / 58.2 |
  | games* | 0/119 | 24.2 / 24.9 |
  | apps | 1/119 | 15.0 / 22.8 |
  | tab-switch | 0/114 | 22.6 / 26.2 |
  | home-scroll | 0/105 | 22.3 / 22.3 |
  | control-center | 5/117 | 27.7 / 37.6 |

  *With Games off For You has no sub tabs, so these key sequences land elsewhere than in Ibtzy's runs: not comparable.
  Startup (3 runs): cold 440 / 390 / 370 ms (Ibtzy ~320 as Home), warm 83 / 68 / 72 ms (~100). `:home` PSS 77–81 MB
  after start, 95 MB at rest after the suite (Ibtzy 92–122). UI-thread draw p50 0.6–5.5 ms everywhere: the frame time is
  GPU/render, as in Ibtzy. 0 crashes during the suite. VYRO `9fad214` not measured (needs its own install).

## Phase A, fixed build (2026-11-06 16:26–16:32)

- Installed `31bc01c42ad26db045e778fdd0d8d98d5fced3ff1dcffc69f4afb43ac810a935` (user 0); on-TV SHA-256 matched.
- 5/5 cold starts on `HomeActivity`, 0 crashes (`am start -W` 703–998 ms, uncompiled after the update).
- Settings > Home Settings scrolled to Recent Apps > Back > Tabs: no crash, Tabs starts at its first row (Movies & Shows
  On, Games Off, Apps On) — `out/tv/53-tabs.png`.
- Settings main after 6 s: all notices on screen (Comparison, Recommendations, Notices "None yet."), "Version 1.3"
  left-aligned under the subtitle — `50-settings-main-6s.png`.
- OK on dimmed Recent Apps: the toast "Not on the new home screen yet: this setting comes back with its design." shows
  — `52-dimmed-ok.png`.
- Watchmode: 0 HTTP 400 and 0 watchnext/app lookups since the fix with the Continue row on (before it: five 400s,
  16:09–16:15). Movie/TV lookups succeeded earlier on the same code (`titles.txt`, 16:07–16:08: Netflix and Prime links);
  after the fix every title on screen was already cached, so no new lookup ran in this window.
- Games: tab Off; 0 Games log lines (no RAWG/IGDB/Steam/OpenCritic/Wikipedia/PlayStation activity). 0 crashes overall.

## Phase B, before the switch (2026-11-06 16:31)

- HOME role and resolved home: `launcherx/.home.HomeActivity`; no package disabled; `screensaver_components` null;
  no accessibility services; no `vyro_disabled` record; 0 Wi-Fi networks saved by `setupwraith`.
- ibtzy TV Launcher: not installed for user 0, installed for user 10 (unchanged).
- VYRO on the TV: `31bc01c4…a935` (user 0), compiled `speed-profile`. Games off.

## Phase B, switch attempt 1 (16:31): stopped, nothing changed

- Owner chose Home Screen > VYRO. TCL's `TclAppBoot` refused the job (`forbid bind service io.vyro.tv/.ModeJob`;
  `appops AUTO_START: ignore`; `APP_AUTO_START` unknown on this firmware). After 10 s Settings showed the stalled-switch
  message (as designed, VYRO #364). TV unchanged: HOME = launcherx, 0 disabled, screensaver null.
- Needs the owner's OK: `adb shell appops set io.vyro.tv AUTO_START allow` (an app-op, not a permission).

## Phase C — Feature 2.2 (updates, release, notifications) — 2026-11-07
- Repo: github.com/muhibtasam/vyro (public since 2026-11-07), `main`: 806be8d UI port, 3b955d3 Phase C + README + WatchNext removal fix (stillHidden).
- Updates: FMT's engine replaced by Ibtzy's ask-first one (Updates, UpdateLogic, UpdateSheet B1, UpdateReceiver); source `muhibtasam/vyro`, asset `VYRO.apk`, same-signer check (old RELEASE_CERT gone). ModeJob INSTALL removed.
- Notifications: GoogleAlerts listener (:home) + AlertsLogic; Control Center section C2, unread dot on its button, empty state.
- Settings (A3): Home Settings › Updates (value inline; ready → opens the sheet) and Google notifications (on/off through the TV's ADB) as the last two rows.
- HomeMode: TCL AUTO_START ensured before the switch job and once per home process in VYRO mode.
- i18n: 29 new strings are English placeholders in every catalogue (no ANTHROPIC_API_KEY for `translate.py --fill`); obsolete update strings pruned.
- Mac: build OK, tools/check.sh all passed, UpdateLogicTest + AlertsLogicTest pass. TV: not tested.
- Upstream gap: baseline ≈ upstream main of 2026-10-31 (v1.3.0 + early fixes); ~60 upstream commits since (unreleased, no v1.4 yet) not ported.

## Phase D — VYRO 1.0.0 (2026-11-07)
- Rebrand: app name VYRO, package io.vyro.tv (new app: on the TV, switch the old io.freemytv build to Original and uninstall it first), icon + TV banner from the owner's VYRO artwork, repo github.com/muhibtasam/vyro, APK VYRO.apk, disabled-apps record vyro_disabled. Signing keystore and password unchanged (freemytv.keystore).
- Release v1.0.0 (versionCode 10) built with --generic: no API keys, Games off.
- LG/Samsung: display name VYRO; Tizen package id and docs/ (upstream site) unchanged.
- 1.0.1: Home grid without a TMDB key (picksOn always on; Movies tab needs the key); VYRO grants itself READ_TV_LISTINGS + usage access + TCL AUTO_START from :home via the TV ADB (prompt accepted once). TV: grid verified (15 titles, Prime + Crunchyroll). Old io.freemytv and io.ibtzy.tvlauncher removed from the TV.
- 1.0.2 (pushed, not released): HomeActivity.handOffHome — as HOME without being the chosen home (boot with Google TV as default; locked-boot start because VYRO is direct-boot aware), hands to the TV home and closes; checked again at unlock. TV: HOME-intent hand-off verified; reboot not yet verified.
- 1.0.3 released (keyless): BuildKeys keeps built-in API keys on the TV (prefs "build-keys") so keyless updates keep them. TV runs personal 1.0.2 with keys (code 12) to test the in-app update to 1.0.3.
- 1.0.4 released: Adb.mayAsk — background ADB connections offer the key (TV prompt) at most once per 10 min across processes.
- 1.0.5 released: Games rows IGDB first (GamesLogic.igdbQuery/igdbImage/igdbDate/igdbPlatforms + tests), RAWG fallback; Updates + Google notifications moved to Settings MAIN rows 8-9 (design A3). TV (personal keyed 1.0.4): IGDB games verified (upcoming hero GTA VI), Settings rows verified; Home Screen = VYRO.
- 1.0.6 released: Settings main — comparison + notices drawn in the left column (no clipping with 11 rows). TV runs release 1.0.5 (keys kept via BuildKeys: recs on); 1.0.6 to come via the update sheet.
- 1.0.7 released: home screen checks GitHub hourly itself (Updates.due + HomeView.offerUpdate, every 15 min look, 1 h check) and shows the sheet.
- 1.0.8 released: BuildKeys.conf keeps config settings (shelf_apps, city/lat/lon, currency, show_crypto, hide_services, open_in_*, temperature, plex) from a personal build for --generic updates; Prefs.shelf resets the saved order once per new personal shelf_apps. TV: personal 1.0.8 installed, dock = shelf_apps order verified.
