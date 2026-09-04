# Development History — Spotify WebView Android App

Last updated: 2026-06-11  
Covers all agent-driven changes made after the initial project scaffold.

---

## Current State of the App (as of last update)

The app is a single-Activity Android app (`com.spp.spotify`) that wraps the
Spotify Web Player (`https://open.spotify.com/`) in a `WebView`. Key features
now working end-to-end:

| Feature | Status |
|---|---|
| Spotify web player in WebView | ✅ |
| Native playback controls (play/pause, prev, next) | ✅ |
| Volume slider + buttons | ✅ |
| Add-to-Favorites button (synced to actual like state) | ✅ |
| Lock-screen / Bluetooth / notification controls | ✅ |
| Lock-screen seek bar with real position + duration | ✅ |
| Ad blocker (fetch hook + safe WS fast-forward) | ✅ |
| Network-level ad CDN blocking | ✅ |
| Crash recovery with auto-restart + auto-resume | ✅ |
| Screen stays on while app is visible | ✅ |
| Uptime timer badge (top-right) | ✅ |
| Refresh button (top-right, next to uptime) | ✅ |

---

## File Map

```
app/src/main/java/com/spp/spotify/
    MainActivity.kt                   — entry point, sets up Compose
    SpotifyApp.kt                     — Application subclass; installs CrashRecoveryHandler
    CrashRecoveryHandler.kt           — global crash handler + auto-restart via AlarmManager
    ui/
        WebPlayerScreen.kt            — main Compose screen; all state + UI
        WebViewSupport.kt             — WebView extension helpers, JS bridge
    media/
        MediaPlaybackService.kt       — foreground service, MediaSessionCompat, notification

app/src/main/res/raw/
    spotify_ad_blocker.js             — injected at document-start before Spotify's JS

app/build.gradle.kts                  — dependencies
AndroidManifest.xml                   — permissions, service declaration, attribution tag
```

---

## Change Log (chronological)

### 1 — Uptime Timer

**Request:** Show how long the app has been running in the top-right corner.

**Files changed:** `WebPlayerScreen.kt`

**What was added:**
- `uptimeSeconds: MutableState<Long>` state variable.
- `LaunchedEffect(Unit)` that increments it every 1 second.
- `formatUptime(seconds)` helper: `MM:SS` below 1 hour, `HH:MM:SS` above.
- Semi-transparent dark pill `Text` overlay (`Alignment.TopEnd`, `.statusBarsPadding()`).

---

### 2 — Add-to-Favorites Button

**Request:** Small icon next to Skip Next that uses the web player's own button.

**Files changed:** `WebPlayerScreen.kt`, `WebViewSupport.kt`

**What was added:**
- Constant `SEL_ADD_TO_LIKED = "[data-testid=add-button]"`.
- `onAddToFavorites` parameter to `PlaybackOverlay`.
- `FavoriteBorder` / `Favorite` icon toggle in the controls row.
- `queryIsLiked()` extension on `WebView` in `WebViewSupport.kt`:
  reads `aria-checked` and `aria-label` of `[data-testid="add-button"]`;
  returns `true`/`false`/`null`.
- `isLiked: MutableState<Boolean?>` polled every 2 s alongside `isPlaying`.
- After tapping, re-queries after 400 ms so the icon updates immediately.
- Icon is `Favorite` (filled, Spotify green `#1DB954`) when liked,
  `FavoriteBorder` (white outline) otherwise.

---

### 3 — Ad Blocker — Blockify Extension Approach

**Motivation:** DOM-polling mute strategy was unreliable; ads were slipping through.

**Research:** Downloaded and analysed the "Blockify" Spotify Ad Blocker Chrome
extension (`injected/ads_removal.js`, `hook.js`, `spot.js`). The real approach:
**intercept `window.fetch` and `window.WebSocket` at document-start** to
manipulate Spotify's state machine API before the ads ever start playing.

**New file:** `app/src/main/res/raw/spotify_ad_blocker.js`

**New dependency:** `androidx.webkit:webkit:1.11.0` in `app/build.gradle.kts`

**New function in `WebViewSupport.kt`:**
```kotlin
@SuppressLint("RequiresFeature")
private fun WebView.installAdBlockerAtDocumentStart()
```
Uses `WebViewCompat.addDocumentStartJavaScript()` scoped to
`https://open.spotify.com` so it runs before any of Spotify's own JS.

**`shouldInterceptRequest` in `createLoggingWebViewClient()`:**
Returns empty 200 OK for known ad CDN domains (see `AD_NETWORK_URL_PATTERNS`).

**How `spotify_ad_blocker.js` works:**
1. Replaces `window.fetch` to intercept `/state` REST responses.
2. When Spotify fetches its state machine, walks every state; if a state's
   track URI contains `:ad:` or `content_type === 'AD'`, replaces it with the
   next non-ad state.
3. If the next state is also an ad (consecutive ads), calls `_getStates()` to
   fetch further-ahead states from `spclient.wg.spotify.com`.
4. Passively captures Spotify's own access token by cloning the
   `get_access_token` fetch response.
5. Tracks `_deviceId` from Spotify's `/devices` registration call.
6. `_shortenAd()` fallback: sets `initial_playback_position` to the track
   duration so the ad fast-forwards to its end instantly.

---

### 4 — Bug Fixes in Ad Blocker JS

#### 4a — `TypeError: url.endsWith is not a function`
Spotify passes `Request` objects (not strings) to `fetch`.  
**Fix:** Added `_urlStr(input)` helper that handles `string`, `Request`, and
anything else gracefully.

#### 4b — Token fetch returning HTML/XML
Too early at document-start; no Spotify session exists yet.  
**Fix:** Switched from active `_refreshToken()` calls on startup to **passive
capture** — we clone Spotify's own `get_access_token` response and steal the
token from it.

#### 4c — 429 Too Many Requests
No rate limiting, mutex, or caching → Spotify rate-limited the device.  
**Fix:**
- `_manipulateSafe()` mutex: only one `_manipulate()` runs at a time; extras
  queue behind it.
- `_GET_STATES_MIN_INTERVAL_MS = 15000`: at most one `_getStates()` call per
  15 seconds globally.
- `_getStatesCache`: cache results per `(smId|stateId)` key (max 5 entries).
- No retry on any HTTP error — returns `null` immediately, falls back to
  `_shortenAd()`.
- Reads `Retry-After` header on 429 and advances `_lastGetStatesMs` by the
  full retry window.

#### 4d — `detected-no-action:text-node:advertisement` infinite loop
The old mute fallback had `if (!m.paused)` guard — Spotify's ad audio is
paused/buffering during polls.  
**Fix:** Removed `!m.paused` guard; mute all media regardless of paused state.

#### 4e — Infinite `ad detected` log spam
`_shortenAd()` only changes playback position, not the URI. The `do-while`
loop kept re-detecting the same ad every pass.  
**Fix:** Added `shortenedIdx = {}` set; states that were shortened are skipped
in subsequent passes. Removed `changed = true` from the `_shortenAd` branch.

---

### 5 — WebView SIGTRAP Crash Fix

**Symptom:** App crashed after ~30–50 minutes with `signal 5 (SIGTRAP), code 1
(TRAP_BRKPT)` in `Chrome_IOThread` inside `libwebviewchromium.so`. All 22
stack frames were in the system WebView — no app code involved.

**Root cause:** The original WebSocket hook used:
```javascript
Object.defineProperty(ws, 'onmessage', { get: ..., set: ... });
```
`onmessage` on a native `WebSocket` is a V8-backed C++ accessor. Overriding
its property descriptor with a JS getter/setter violates Chromium's internal
binding invariants, causing a `CHECK()` assertion on `Chrome_IOThread`.

**Fix:** Replaced the dangerous hook with a safe one that:
- Wraps `window.WebSocket` constructor to intercept new connections.
- Adds a **passive** `addEventListener('message', ...)` — reads only, never
  modifies the event or the `onmessage` property.
- When a `replace_state` WS payload contains ad states, calls
  `_wsFastForwardAd()` which sets `audio.currentTime = audio.duration - 0.1`
  to trigger Spotify's `ended` event → player advances to next track.
- Retries up to 10 times with 500 ms spacing if duration isn't loaded yet.

**Safety invariants of the new hook (written explicitly in the source):**
- ✓ No `Object.defineProperty` on any WebSocket instance.
- ✓ No touching of `ws.onmessage`.
- ✓ No modification of messages before Spotify sees them.
- ✓ Passive listener only.

---

### 6 — Lock-Screen Seek Bar (real position + duration)

**Problem:** Lock screen always showed `0:00` because `buildState()` passed
`PLAYBACK_POSITION_UNKNOWN` and metadata had no `METADATA_KEY_DURATION`.

**Files changed:** `WebViewSupport.kt`, `MediaPlaybackService.kt`,
`WebPlayerScreen.kt`

**`queryTrackInfo()` in `WebViewSupport.kt`:**
Extended to also read `audio.currentTime` → `positionMs` and
`audio.duration` → `durationMs`. Returns `-1` for each when unavailable.

**`MediaPlaybackService.update()`:**
Now accepts `positionMs: Long` and `durationMs: Long`.  
`buildState(positionMs)` passes the real position to
`PlaybackStateCompat.setState()` with playback speed `1f` when playing
(Android interpolates position forward between polls).  
`setMetadata()` includes `METADATA_KEY_DURATION` when `durationMs > 0`.

---

### 7 — Crash Recovery + Auto-Restart

**Request:** Catch app crashes and automatically restart; if music was playing,
auto-resume it.

**New file:** `CrashRecoveryHandler.kt`

**New file:** `SpotifyApp.kt` (Application subclass — calls
`CrashRecoveryHandler.install(this)` in `onCreate`)

**How it works:**

**On crash (`uncaughtException`):**
1. Writes `crash_recovery_pending = true` to SharedPreferences
   (`crash_recovery` prefs).
2. `was_playing` flag is already up-to-date (written on every 2 s poll).
3. Schedules `AlarmManager.set()` → `MainActivity` PendingIntent in 1.5 s
   (survives process death).
4. Calls `Process.killProcess()`.

**On restart (`WebPlayerScreen` startup):**
- Reads `isCrashRecovery` and `wasPlayingOnCrash` from SharedPreferences.
- `LaunchedEffect(isCrashRecovery)`: waits for WebView to be ready, then
  waits 5 s for Spotify to load, then if `wasPlayingOnCrash` clicks play.
- Clears `crash_recovery_pending` flag so normal launches are unaffected.

**Continuous state persistence:**
Every 2 s poll calls `CrashRecoveryHandler.savePlaybackState(context, playing)`
so the saved state is never more than 2 s stale.

---

### 8 — Refresh Button

**Request:** Add a reload button in the top-right corner.

**Files changed:** `WebPlayerScreen.kt`

**What was added:**
- `Icons.Rounded.Refresh` `IconButton` in the top-right `Row` (left of the
  uptime timer).
- On click: `webView.loadUrl(SPOTIFY_WEB_PLAYER_URL)` — full Spotify reload.
- Matches dark pill style of the uptime badge.

---

### 9 — `attributionTag` AppOps Warning Fix

**Symptom logs:**
```
attributionTag not declared in manifest of com.spp.spotify
Operation not started: op=CONTROL_AUDIO
```

**Fix:** Added `<attribution android:tag="media_playback" />` inside
`<application>` in `AndroidManifest.xml`. Ensures audio focus operations are
properly attributed on Android 12+.

---

## Key Architecture Decisions & Rationale

### Why fetch hook instead of WebSocket hook (originally)
Spotify's initial state comes via REST `/state`; the fetch hook intercepts this
and removes ads before the player ever processes them. This alone blocks the
vast majority of ads.

### Why the safe WS hook was re-added
WS `replace_state` messages deliver state updates mid-session (e.g., between
tracks). Without intercepting these, ads inserted after the initial load slip
through. The safe hook fast-forwards audio to end when a WS ad state is
detected, without modifying the WS protocol layer.

### Why `pauseTimers()` is NOT called on lifecycle ON_PAUSE
`WebView.pauseTimers()` is a global call that stops ALL JavaScript timers in
every WebView in the process. Calling it while music is playing immediately
stops Spotify's keep-alive and state-machine heartbeats, killing audio within
seconds. Only `onPause()` (rendering throttle) is called, and only when music
is not actively playing.

### Why `queryIsLiked` returns `null` for absent/unknown
The `add-button` may not be rendered until a track is playing. Returning `null`
means "don't change the current icon" — avoids flickering between liked/unliked
on startup.

### Why passive token capture instead of active `_refreshToken()` at startup
At document-start there is no Spotify session yet — making a `get_access_token`
request at that point returns an HTML page or error. By cloning the response of
Spotify's own token call we get a valid token at the right time with zero extra
network requests.

---

## Known Issues / Investigated but Not Fixed

### `c2.android.aac.decoder` frame drops
```
Qin:0, Render:0, Drop:214-221 (climbing ~5 per second)
```
MediaCodec stats show AAC frames being decoded but dropped rather than rendered.
Benign if Spotify uses its own audio pipeline (which it does via WebAudio API).
No action taken.

### WebView SIGTRAP (historical — fixed)
The old `Object.defineProperty(ws, 'onmessage')` caused a `signal 5 SIGTRAP`
in `Chrome_IOThread` after ~30–50 min. Fixed by removing that property
override and using passive `addEventListener` instead. See section 5.

---

## Dependencies Added

```kotlin
// app/build.gradle.kts
implementation("androidx.webkit:webkit:1.11.0")   // WebViewCompat.addDocumentStartJavaScript
implementation("androidx.media:media:1.7.0")      // MediaSessionCompat, PlaybackStateCompat
implementation("androidx.browser:browser:1.8.0")  // CustomTabsIntent for external links
```

---

## Debugging Reference

| Logcat tag | What it shows |
|---|---|
| `SpotifyWV` | WebView page load, ad-skip events, JS bridge calls, network blocks |
| `CrashRecovery` | Crash handler events, restart scheduling, auto-play on recovery |
| `chromium` | Raw WebView console.log output (mirrors SpotifyWV for our JS) |

### Common log patterns
```
[SpotifyAdBlock] fetch + safe-WS hooks installed   → JS injected OK
[SpotifyAdBlock] access token captured             → token ready for _getStates
[SpotifyAdBlock] ad detected: spotify:ad:...       → ad found in state machine
[SpotifyAdBlock] WS replace_state contains ad      → ad delivered via WebSocket
ad-network request blocked: ...                    → CDN-level block
ad-skip-poll: fast-forwarded:bar-text:advertisement → DOM poll fallback worked
CrashRecovery: Crash-recovery handler installed    → handler active
CrashRecovery: Restart scheduled in 1500ms         → crash → restart in progress
CrashRecovery: Auto-play triggered after crash     → resume after crash
```

---

## Manifest State (as of last update)

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>

<application android:name=".SpotifyApp" ...>
    <attribution android:tag="media_playback"/>

    <activity android:name=".MainActivity" .../>

    <service android:name=".media.MediaPlaybackService"
        android:foregroundServiceType="mediaPlayback"
        android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.MEDIA_BUTTON"/>
        </intent-filter>
    </service>

    <receiver android:name="androidx.media.session.MediaButtonReceiver"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MEDIA_BUTTON"/>
        </intent-filter>
    </receiver>
</application>
```

