# KnightStalker

Open source Stalker middleware IPTV client for Android, optimized for tablets.
Built with Kotlin, Jetpack Compose, and Media3 ExoPlayer.
Fully [F-Droid](https://f-droid.org) compliant — no proprietary dependencies, no tracking.

## Features

- **Portal Management** — Add, edit, and connect to Stalker/Ministra portals using URL + MAC address
- **Live TV** — Browse channels by category with EPG now/next info, channel logos, and progress bars
- **Electronic Program Guide** — Horizontal timeline grid with day selector, per-channel program listings
- **Video on Demand** — Browse movies by category with poster grid, detail view (rating, cast, description)
- **Favorites** — Long-press any channel or movie to save it; separate tabs for channels and VOD
- **Search** — Debounced search across both channels and movies
- **Media Player** — Full-screen ExoPlayer with touch controls, auto-hide overlay, seek, play/pause, retry on error
- **Settings** — Buffer size, auto-play toggle, portal management

## Screenshots

_Coming soon_

## Architecture

```
app/src/main/java/org/knightstalker/app/
├── data/
│   ├── local/          # Room database, DAOs, DataStore preferences
│   ├── remote/         # Stalker API client, auth manager, interceptors, DTOs
│   └── repository/     # Repositories bridging local + remote
├── domain/model/       # Pure Kotlin domain models
├── di/                 # Manual dependency injection (AppModule)
└── ui/
    ├── theme/          # Material 3 dark theme
    ├── components/     # Reusable composables (ChannelCard, VodCard, etc.)
    ├── navigation/     # NavHost with bottom navigation
    ├── portal/         # Portal list & edit screens
    ├── livetv/         # Live TV with category sidebar
    ├── epg/            # EPG timeline grid
    ├── vod/            # VOD browser & detail screen
    ├── favorites/      # Favorites with channel/VOD tabs
    ├── search/         # Search screen
    ├── settings/       # Settings screen
    └── player/         # ExoPlayer full-screen player
```

**Pattern:** Clean Architecture + MVVM
**DI:** Manual service locator (no Hilt/Dagger — keeps builds reproducible for F-Droid)

## Tech Stack

| Component | Library | License |
|---|---|---|
| Language | Kotlin 1.9 | Apache 2.0 |
| UI | Jetpack Compose (Material 3) | Apache 2.0 |
| Video | AndroidX Media3 / ExoPlayer | Apache 2.0 |
| HTTP | OkHttp | Apache 2.0 |
| JSON | kotlinx.serialization | Apache 2.0 |
| Database | Room | Apache 2.0 |
| Images | Coil | Apache 2.0 |
| Navigation | Compose Navigation | Apache 2.0 |
| Preferences | DataStore | Apache 2.0 |

Zero Google Play Services. Zero Firebase. Zero proprietary SDKs.

## Build

```bash
# Clone
git clone https://gitlab.com/hc51t/knightstalker.git
cd knightstalker

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

**Requirements:** Android SDK 34, JDK 17, Gradle 8.5

**Target:** Android 7.0+ (API 24)

## Stalker Protocol

KnightStalker implements the Stalker/Ministra middleware protocol:

1. **Handshake** — `type=stb&action=handshake` to obtain auth token
2. **Profile** — `type=stb&action=get_profile` to validate account
3. **Watchdog** — Periodic `type=stb&action=do_auth` keepalive (every 2 min)
4. **Channels** — `type=itv&action=get_ordered_list` with genre filtering and pagination
5. **Stream** — `type=itv&action=create_link` to resolve playback URL
6. **EPG** — `type=itv&action=get_short_epg` and `type=epg&action=get_simple_data_table`
7. **VOD** — `type=vod&action=get_categories` / `get_ordered_list` / `create_link`

## F-Droid Compliance

- [x] GPL-3.0-or-later license
- [x] All dependencies are FOSS (Apache 2.0)
- [x] No proprietary Google services
- [x] No analytics or tracking
- [x] No non-free network services
- [x] Pinned dependency versions for reproducible builds
- [x] SPDX license headers on all source files
- [x] Standard Gradle build (`./gradlew assembleRelease`)

## Contributing

Contributions are welcome. Please open an issue first to discuss proposed changes.

## License

```
Copyright (C) 2026 KnightStalker Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```

See [LICENSE](LICENSE) for the full text.
