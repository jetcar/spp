# AGENTS.md

## Project Snapshot
- Single-module Android app (`:app`) that wraps Spotify Web Player in a `WebView` (no backend/services outside Android app process).
- Entry flow is `MainActivity` -> `WebPlayerScreen()` (`app/src/main/java/com/spp/spotify/MainActivity.kt`).
- Root build uses AGP `9.2.1`, Compose plugin `2.3.21`, Gradle wrapper `9.4.1`, Java 17 (`app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`).
- Existing human docs are in `README.md`; no prior agent-specific rules were found besides that file.

## Architecture And Data Flow
- `WebPlayerScreen.kt` is the runtime hub: creates the `WebView`, overlays native playback controls, owns audio focus, and handles lifecycle pause/resume + destroy.
- `WebViewSupport.kt` centralizes browser behavior via extension helpers:
  - `configureSpotifyWebSettings()` sets permissive media/web settings and desktop UA spoofing.
  - `createLoggingWebViewClient()` and `createLoggingWebChromeClient()` handle navigation, permission requests, popups, and logging.
  - JS bridge helpers (`clickSpotifyButton`, `queryIsPlaying`, `pauseAllMedia`) drive Spotify UI via `data-testid` selectors.
- Native controls call JS selectors like `[data-testid=control-button-playpause]` from `WebPlayerScreen.kt` constants.
- Internal-vs-external routing is host-based (`INTERNAL_WEBVIEW_HOSTS = spotify.com, scdn.co`); non-matching URLs open via Custom Tabs/intents.

## Important Integration Points
- Spotify Web is loaded from `https://open.spotify.com/` and controlled by DOM scraping/clicking, not a Spotify native SDK.
- Protected-media permission grant is explicit in `onPermissionRequest` (only `RESOURCE_PROTECTED_MEDIA_ID` is granted).
- Media activation script is injected on page finish to mitigate autoplay restrictions (`installMediaActivationScript`).
- Debug signal is Logcat tag `SpotifyWV` for page load, requests, console messages, popup hand-off, and media script outcomes.

## Build/Test/Debug Workflows
- Use wrapper from repo root on Windows:
```powershell
.\gradlew.bat :app:tasks --all
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
- Verified in this workspace (2026-05-28): `:app:tasks --all` succeeds, but `:app:assembleDebug` currently fails.
- Current compile blockers to resolve before feature work:
  - `MediaPlaybackService.kt` imports `android.support.v4.media...` / `androidx.media...` without matching dependencies in `app/build.gradle.kts`.
  - `WebPlayerScreen.kt` fails on `Modifier.weight` usage with current Compose/Kotlin setup.

## Codebase-Specific Conventions
- Keep WebView behavior in `ui/WebViewSupport.kt` extension functions rather than in `Activity`.
- Prefer `internal` top-level helpers for WebView bridge actions (`WebView.pauseAllMedia`, `WebView.clickSpotifyButton`, etc.).
- When adding controls, mirror existing pattern: native callback in `PlaybackOverlay` -> JS selector click -> delayed state re-query.
- Maintain host allowlist policy (`shouldOpenExternally`) so OAuth/payment/non-Spotify domains leave the embedded WebView.
- Preserve lifecycle cleanup pattern in `WebPlayerScreen` (`onPause`, `pauseTimers`, `about:blank`, `destroy`) to avoid leaked WebView/audio resources.

## Manifest/Platform Notes
- `AndroidManifest.xml` currently declares only `INTERNET` permission and one launcher activity.
- `MediaPlaybackService.kt` exists but is not declared in manifest yet; integrate deliberately if enabling foreground media notifications.
- Theme is AppCompat DayNight NoActionBar (`app/src/main/res/values/themes.xml`), while UI layer is Compose + `AndroidView` interop.

