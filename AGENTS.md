# AGENTS.md

## Project Snapshot
- Single-module Android app (`:app`) that wraps Spotify Web Player in a `WebView` (no backend/services outside Android app process).
- Entry flow is `SpotifyApp` (Application) → `MainActivity` → `WebPlayerScreen()`.
- Root build uses AGP `9.2.1`, Compose plugin `2.3.21`, Gradle wrapper `9.4.1`, Java 17 (`app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`).
- **`:app:assembleDebug` builds and runs correctly** as of 2026-05-29 (all prior blockers resolved).
- Full change history is in **`DEVELOPMENT_HISTORY.md`** — read it before making any changes.

## Architecture And Data Flow
- `WebPlayerScreen.kt` is the runtime hub: creates the `WebView`, overlays native playback controls, owns audio focus, handles lifecycle pause/resume + destroy, and coordinates crash recovery.
- `WebViewSupport.kt` centralises browser behaviour via extension helpers:
  - `configureSpotifyWebSettings()` sets permissive media/web settings, desktop UA spoofing, and installs the ad-blocker at document-start.
  - `createLoggingWebViewClient()` handles navigation, network-level ad blocking, and logging.
  - `createLoggingWebChromeClient()` handles permission requests, popups, and console messages.
  - JS bridge helpers: `clickSpotifyButton`, `queryIsPlaying`, `queryIsLiked`, `queryTrackInfo`, `pauseAllMedia`, `skipAdIfPresent`, `dumpNowPlayingState`.
- `MediaPlaybackService.kt` — foreground service owning `MediaSessionCompat`; provides lock-screen / Bluetooth / notification controls with real playback position.
- `CrashRecoveryHandler.kt` — global `UncaughtExceptionHandler`; saves playback state, schedules AlarmManager restart 1.5 s after crash, auto-resumes playback on recovery.
- `SpotifyApp.kt` — Application subclass; installs `CrashRecoveryHandler` on startup.
- `app/src/main/res/raw/spotify_ad_blocker.js` — injected at document-start; hooks `window.fetch` and `window.WebSocket` (safe passive listener only) to remove ad states from Spotify's state machine before playback.

## Important Integration Points
- Spotify Web is loaded from `https://open.spotify.com/` and controlled by DOM scraping/clicking, not a Spotify native SDK.
- Protected-media permission grant is explicit in `onPermissionRequest` (only `RESOURCE_PROTECTED_MEDIA_ID` is granted).
- Media activation script is injected on page finish to mitigate autoplay restrictions (`installMediaActivationScript`).
- Ad blocker is injected via `WebViewCompat.addDocumentStartJavaScript()` — requires `WebViewFeature.DOCUMENT_START_SCRIPT` (guarded at runtime).
- Network-level ad CDN blocking via `shouldInterceptRequest` returning empty 200 OK for `AD_NETWORK_URL_PATTERNS`.
- Debug signal is Logcat tag `SpotifyWV` for page load, ad-skip, requests, console messages. Tag `CrashRecovery` for crash/restart events.

## Build/Test/Debug Workflows
- Use wrapper from repo root on Windows:
```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
```
- Deploy: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Logcat filter: `adb logcat -s SpotifyWV CrashRecovery chromium`

## Codebase-Specific Conventions
- Keep WebView behaviour in `ui/WebViewSupport.kt` extension functions rather than in `Activity`.
- Prefer `internal` top-level helpers for WebView bridge actions (`WebView.pauseAllMedia`, `WebView.clickSpotifyButton`, etc.).
- When adding controls, mirror existing pattern: native callback in `PlaybackOverlay` → JS selector click → delayed state re-query.
- Maintain host allowlist policy (`shouldOpenExternally`) so OAuth/payment/non-Spotify domains leave the embedded WebView.
- Preserve lifecycle cleanup pattern in `WebPlayerScreen` (`onPause`, `pauseTimers`, `about:blank`, `destroy`) to avoid leaked WebView/audio resources.
- **Never call `WebView.pauseTimers()` on lifecycle ON_PAUSE** — it stops all JS timers globally and kills audio. Only call it in `onDispose` when the WebView is about to be destroyed.
- **Never use `Object.defineProperty` on a native WebSocket instance** — it triggers a Chromium CHECK() assertion (SIGTRAP) on Chrome_IOThread after ~30 min.

## Manifest/Platform Notes
- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- `<attribution android:tag="media_playback"/>` declared to silence AppOps warnings on Android 12+.
- `MediaPlaybackService` declared with `foregroundServiceType="mediaPlayback"` and a `MediaButtonReceiver` intent filter.
- `androidx.media.session.MediaButtonReceiver` declared as a broadcast receiver for hardware media button routing.
- Theme is AppCompat DayNight NoActionBar (`app/src/main/res/values/themes.xml`), UI layer is Compose + `AndroidView` interop.
