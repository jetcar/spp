# Tuneveil Android

Android app that opens the Web Player (`https://open.spotify.com/`) inside a `WebView` using the same pattern as the component style in your `vidrox` project (`AndroidView` + helper `WebViewClient` + request logging).

## Project Structure

- `app/src/main/java/com/spp/tuneveil/ui/WebPlayerScreen.kt`: Compose screen hosting `WebView`.
- `app/src/main/java/com/spp/tuneveil/ui/WebViewSupport.kt`: WebView setup, protected media permission handling, and request logging.
- `app/src/main/java/com/spp/tuneveil/MainActivity.kt`: Entry point.

## Run

1. Open project in Android Studio.
2. Sync Gradle.
3. Run the `app` module on a device or emulator.

## Notes

- The app configures the `WebView` with a desktop-style Chrome user agent, popup hand-off, third-party cookies, and protected-media permission handling to maximize Web Player compatibility.
- This implementation attempts to render Tuneveil web like a browser, but playback support can still be limited by WebView DRM/browser capability restrictions.
- URL, request, console, and media-permission logs are available in Logcat with tag `TuneveilWV`.

## Quick Troubleshooting

1. Update Android System WebView from Play Store on the device.
2. Use email/password login inside the page when possible; many third-party OAuth providers deliberately limit embedded WebView sign-in flows.
3. Check Logcat (`TuneveilWV`) for blocked protected-media requests, popup redirects, or web console errors.
4. If the site still refuses playback, this is likely an enforced service limitation for embedded/in-app browsers.
