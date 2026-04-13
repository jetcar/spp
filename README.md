# SPP – Spotify++

An Android Spotify client built with Kotlin, MVVM architecture, and the Spotify Web API.

## Features

- **Login** – Spotify PKCE OAuth2 via Chrome Custom Tabs
- **Home** – Recently Played, Featured Playlists, New Releases
- **Search** – Browse categories grid + search results (tracks / albums / playlists)
- **Library** – Playlists, Albums, Liked Songs tabs
- **Now Playing** – Album art with Palette-based background, full playback controls, seek bar, like/shuffle/repeat
- **Mini Player** – Persistent mini player bar above the bottom navigation

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK with API 34 (compileSdk) and API 26 (minSdk)
- A Spotify Developer account

## Spotify Developer Setup

1. Go to [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Create a new app (or use an existing one).
3. Under **Edit Settings**, add `spp://callback` as a **Redirect URI**.
4. Copy your **Client ID**.

## Configuration

1. Open `app/build.gradle`.
2. Replace `YOUR_SPOTIFY_CLIENT_ID` with your actual Client ID:
   ```groovy
   buildConfigField "String", "SPOTIFY_CLIENT_ID", "\"<your_client_id_here>\""
   ```

## Building

```bash
# Clone the repo (if not already done)
git clone https://github.com/<your-org>/spp.git
cd spp

# Build debug APK
./gradlew assembleDebug

# Install on a connected device / emulator
./gradlew installDebug
```

## Architecture

```
com.spp.spotify
├── auth/               # PKCE auth flow, TokenManager (DataStore)
├── data/
│   ├── api/            # Retrofit services, OkHttp interceptor
│   ├── model/          # Kotlin data classes (Spotify models)
│   └── repository/     # SpotifyRepository (single source of truth)
└── ui/
    ├── adapter/        # RecyclerView ListAdapters
    ├── home/           # HomeFragment + HomeViewModel
    ├── library/        # LibraryFragment + LibraryViewModel
    ├── player/         # NowPlayingFragment + PlayerViewModel
    └── search/         # SearchFragment + SearchViewModel
```

## Tech Stack

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 1.9.10 | Language |
| AGP | 8.1.4 | Build tooling |
| Retrofit 2 | 2.9.0 | HTTP client |
| OkHttp | 4.12.0 | HTTP engine |
| Glide | 4.16.0 | Image loading |
| Navigation Component | 2.7.6 | Fragment navigation |
| DataStore Preferences | 1.0.0 | Token persistence |
| Palette KTX | 1.0.0 | Dynamic album art colors |
| Chrome Custom Tabs | 1.7.0 | OAuth login browser |

## Notes

- The app targets the **Spotify Web API** for playback control; a **Spotify Premium** account is required for playback commands.
- Token refresh is handled transparently by `SpotifyRepository.safeCall()`.
