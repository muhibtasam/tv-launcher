# VYRO — features

Where each feature came from (FMT = Free My TV, ibtzy = the owner's ibtzy TV Launcher, VYRO = added here), its
state, and what was checked. "TV ✓" = seen working on the owner's TCL TV; "Mac ✓" = build + tests only.

## Home screen

| Feature | From | State |
|---|---|---|
| ibtzy look: greeting, tabs (Home / For You / Apps), bento grid, dock, Fraunces + Sora, dark warm palette | ibtzy | TV ✓ |
| Home grid from the streaming apps' own published titles (no TMDB key needed) | ibtzy 2.1 + VYRO 1.0.1 | TV ✓ (Prime Video, Crunchyroll) |
| Continue Watching row; hold OK → Remove; a removed title returns after new viewing | ibtzy 2.0 + VYRO fix | Mac ✓ |
| Dock: your order (hold OK to move), then `shelf_apps`, then every app | FMT/ibtzy | TV ✓ |
| Control Center: quick tiles, inputs, sleep timer | ibtzy | TV ✓ |
| Google notifications section in Control Center (C2), unread dot, empty state, See all / Clear all | ibtzy 2.2 | Mac ✓ (access On on TV) |
| Greeting in many languages, weather, clock | ibtzy | TV ✓ |
| Hands the screen back to the TV's chosen home at power-on / Home when VYRO isn't the default | VYRO 1.0.2 | TV ✓ (Home intent); reboot not tested |

## For You

| Feature | From | State |
|---|---|---|
| Movies & Shows: trending, popular searches, picks from your services (TMDB + Watchmode) | ibtzy/FMT | TV ✓ |
| Free and ad-supported services count in picks | FMT upstream | Mac ✓ |
| Open titles in Nuvio (`open_in_nuvio`) or Stremio | FMT upstream | Mac ✓ |
| Games (off by default, Settings › Tabs): rows from **IGDB first**, RAWG fallback; hero countdown, release calendar, critics, art | ibtzy + VYRO 1.0.5 | TV ✓ (IGDB) |
| Games loading skeleton per Games V1 design | — | **open** (design link needed) |

## Apps tab

Search (remote or voice), filters (All / Watch / Listen / Sports / Tools), Most used, All apps A–Z — ibtzy — TV ✓.

## Updates (design B1 + A3)

| Feature | State |
|---|---|
| Checks `muhibtasam/vyro` releases daily, and hourly while the home screen runs (1.0.7) | Mac ✓ |
| Verified download (size, SHA-256, higher version, same signer and package) | Mac ✓ (tested parser on the live release) |
| Update sheet: version, size, date, what's new, Install / Later; Android asks again | Mac ✓ |
| Settings main list: Updates row (status inline, orange dot when ready) | TV ✓ |
| Keys and personal settings kept through keyless release updates (`BuildKeys`, 1.0.3 / 1.0.8) | TV ✓ (keys after 1.0.5) |

## Settings

Main list (A3): Home Screen (VYRO / Original), Screensaver, Home Settings, Tabs, Shelf, Top Buttons, Remote Buttons,
Sleep Timer, Updates, Google notifications (switch), TV Settings. Left column: name, version, comparison, notices
(1.0.6). Home Settings: language, temperature, theme, clock, photos, sounds, Continue Watching, picks, keys (TMDB,
Watchmode) and more — TV ✓ (main list), left column Mac ✓.

## Mode switch and safety (FMT core, kept)

Switch to VYRO / Original from Settings through the TV's own ADB; setupwraith ordering; watchdog; disabled apps
recorded (`vyro_disabled`) and restored; refuses on phones, Fire OS, known bricking models. VYRO grants itself
READ_TV_LISTINGS + usage access + TCL AUTO_START from the home screen (1.0.1). Background ADB prompts at most once
per 10 minutes (1.0.4). TV ✓ (switch done by the owner).

## Screensaver

Picks' art or your photos (portraits and panoramas whole, 1.0.0 from upstream), Immich sync, clock, weather, OK to
watch. Mac ✓.

## Branding

Name VYRO, package `io.vyro.tv`, icon + TV banner from the owner's VYRO artwork (`app/res/drawable-nodpi`,
`docs/assets`), repo `muhibtasam/vyro`, README with screenshots (`.github/screens`). 26 languages.

## Releases

1.0.0 rebrand · 1.0.1 Home grid without TMDB, self-granted permissions · 1.0.2 home hand-off · 1.0.3 keys kept ·
1.0.4 fewer debugging prompts · 1.0.5 IGDB games, A3 Settings rows · 1.0.6 Settings layout · 1.0.7 hourly update
check · 1.0.8 personal settings kept.
