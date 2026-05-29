// ─────────────────────────────────────────────────────────────────────────────
// Spotify Ad Blocker – injected at document-start before Spotify's own scripts.
//
// Strategy (same as the "Blockify" Chrome extension):
//   1. Replace window.fetch to intercept state-machine REST calls.
//   2. When Spotify's player fetches a state machine, walk every state and
//      replace ad states with the next non-ad state before playback starts.
//
// NOTE: WebSocket interception has been intentionally removed.
//   Replacing the native WebSocket constructor or patching ws.onmessage via
//   Object.defineProperty overrides a V8-backed native accessor which causes a
//   reproducible CHECK() assertion failure on Chrome_IOThread after ~30 minutes
//   (signal 5 SIGTRAP in libwebviewchromium.so). The fetch hook alone handles
//   the initial state fetch which covers the vast majority of ads.
//
// Rate-limiting / 429 protection:
//   - _manipulate() is guarded by a mutex – only one concurrent execution.
//   - _getStates() results are cached per (smId+stateId) key.
//   - _getStates() is rate-limited to one call per 15 seconds globally.
//   - On any HTTP error (including 429) _getStates() falls back to _shortenAd()
//     immediately instead of retrying.
// ─────────────────────────────────────────────────────────────────────────────

(function () {
  'use strict';

  // ── State ─────────────────────────────────────────────────────────────────
  var _originalFetch   = window.fetch;
  var _accessToken     = '';
  var _deviceId        = '';
  var _tamperedIds     = [];

  // ── Mutex: only one _manipulate() runs at a time ──────────────────────────
  var _manipulating    = false;
  var _manipulateQueue = [];   // pending {sm, startIdx, isWS, resolve} entries

  // ── _getStates rate limiter ───────────────────────────────────────────────
  var _lastGetStatesMs = 0;
  var _GET_STATES_MIN_INTERVAL_MS = 15000; // max 1 call per 15 s
  var _getStatesCache  = {};               // key → state_machine result

  /** Safely extract a URL string from whatever fetch() receives as first arg. */
  function _urlStr(input) {
    if (!input) return '';
    if (typeof input === 'string') return input;
    if (typeof input === 'object' && input.url) return input.url;
    return String(input);
  }

  async function _refreshToken() {
    try {
      var r = await _originalFetch.call(window,
        'https://open.spotify.com/get_access_token?reason=transport&productType=web_player',
        { credentials: 'same-origin' });
      var json = await r.json();
      if (json && json['accessToken']) _accessToken = json['accessToken'];
    } catch (_) {}
  }

  // ── Hook fetch ───────────────────────────────────────────────────────────
  window.fetch = function (url, init) {
    var urlStr = _urlStr(url);

    // Passively steal the access token from Spotify's own token request
    if (urlStr.includes('get_access_token')) {
      return _originalFetch.call(window, url, init).then(function (resp) {
        var clone = resp.clone();
        clone.json().then(function (j) {
          if (j && j['accessToken']) {
            _accessToken = j['accessToken'];
            console.log('[SpotifyAdBlock] access token captured');
          }
        }).catch(function () {});
        return resp;
      });
    }

    // Track device ID (needed for _getStates API)
    if (urlStr.endsWith('/devices') && init && init.body) {
      try {
        var req = JSON.parse(init.body);
        if (req.device && req.device.device_id) _deviceId = req.device.device_id;
      } catch (_) {}
    }

    // Intercept state-machine responses
    if (urlStr.includes('/state')) {
      return _originalFetch.call(window, url, init).then(function (resp) {
        return _patchFetchResponse(resp);
      }).catch(function (e) {
        console.error('[SpotifyAdBlock] fetch intercept error: ' + e);
        return _originalFetch.call(window, url, init);
      });
    }

    return _originalFetch.call(window, url, init);
  };

  function _patchFetchResponse(resp) {
    var _origJson = resp.json.bind(resp);
    resp.json = function () {
      return _origJson().then(async function (data) {
        var sm  = data['state_machine'];
        var ref = data['updated_state_ref'];
        if (sm && ref != null) {
          data['state_machine'] = await _manipulateSafe(sm, ref['state_index'], false);
        }
        return data;
      }).catch(function (e) {
        var s = String(e);
        if (s.includes('No token') || s.includes('Token expired')) _refreshToken();
        return Promise.reject(e);
      });
    };
    return resp;
  }

  // ── Mutex wrapper for _manipulate ────────────────────────────────────────
  function _manipulateSafe(sm, startIdx, isWS) {
    return new Promise(function (resolve) {
      _manipulateQueue.push({ sm: sm, startIdx: startIdx, isWS: isWS, resolve: resolve });
      _drainManipulateQueue();
    });
  }

  function _drainManipulateQueue() {
    if (_manipulating || _manipulateQueue.length === 0) return;
    _manipulating = true;
    var entry = _manipulateQueue.shift();
    _manipulate(entry.sm, entry.startIdx, entry.isWS).then(function (result) {
      entry.resolve(result);
      _manipulating = false;
      _drainManipulateQueue(); // process next queued item
    }).catch(function () {
      entry.resolve(entry.sm); // on error, return sm unchanged
      _manipulating = false;
      _drainManipulateQueue();
    });
  }

  // ── Core: manipulate the state machine ───────────────────────────────────
  async function _manipulate(sm, startIdx, isWS) {
    var states = sm['states'];
    var tracks = sm['tracks'];
    var changed = false;

    do {
      changed = false;
      for (var i = 0; i < states.length; i++) {
        var state = states[i];
        if (!_isAd(state, sm)) continue;

        var adTrack = tracks[state['track']];
        console.log('[SpotifyAdBlock] ad detected: ' + adTrack['metadata']['uri']);

        var next = _getNextNonAd(sm, adTrack, i);

        if (next && _isAd(next, sm)) {
          // Consecutive ads — try fetching more states, but respect rate limit.
          var now = Date.now();
          if (now - _lastGetStatesMs >= _GET_STATES_MIN_INTERVAL_MS && _deviceId) {
            try {
              _lastGetStatesMs = now;
              var futureSM = await _getStates(sm['state_machine_id'], next['state_id']);
              if (futureSM) {
                var lTrack = futureSM['tracks'][next['track']] ||
                             futureSM['tracks'][0]; // fallback
                var futureNext = _getNextNonAd(futureSM, lTrack);
                if (futureNext && !_isAd(futureNext, futureSM)) {
                  var nextId      = futureNext['state_id'];
                  futureNext['state_id']    = state['state_id'];
                  futureNext['transitions'] = {};
                  tracks.push(futureSM['tracks'][futureNext['track']]);
                  futureNext['track'] = tracks.length - 1;
                  if (i === startIdx && !isWS) {
                    futureNext['state_id']       = nextId;
                    sm['state_machine_id']        = futureSM['state_machine_id'];
                  }
                  next = futureNext;
                }
              }
            } catch (e) {
              console.warn('[SpotifyAdBlock] _getStates failed: ' + e);
            }
          }
          // If still an ad after fetch attempt (or rate-limited), shorten it
          if (_isAd(next, sm)) {
            state = _shortenAd(state, adTrack);
            states[i] = state;
            changed = true;
            continue;
          }
          changed = true;
        }

        if (next && !_isAd(next, sm)) {
          _tamperedIds.push(next['state_id']);
          states[i] = next;
          changed = true;
        }

        if (i === startIdx && !isWS && _tamperedIds.includes(state['state_id'])) {
          console.log('[SpotifyAdBlock] ad removed ✓');
          document.body && document.body.setAttribute('spotifyAdRemoved', Date.now());
        }
      }
    } while (changed);

    sm['states'] = states;
    sm['tracks'] = tracks;
    return sm;
  }

  function _shortenAd(state, track) {
    var dur = (track['metadata'] && track['metadata']['duration']) || 1;
    state['disallow_seeking']          = false;
    state['restrictions']              = {};
    state['initial_playback_position'] = dur;
    state['position_offset']           = dur;
    return state;
  }

  function _getNextNonAd(sm, sourceTrack, startIdx) {
    startIdx = startIdx || 2;
    var states = sm['states'];
    var tracks = sm['tracks'];
    var foundSource = false;
    var prev = null;

    for (var s = states[startIdx]; s; ) {
      if (s === prev) break;
      var t = tracks[s['track']];
      if (foundSource && !_isAd(s, sm)) return s;
      foundSource = foundSource ||
        (t && t['metadata'] && t['metadata']['uri'] === sourceTrack['metadata']['uri']);
      prev = s;
      var trans = s['transitions'] && s['transitions']['advance'];
      if (!trans) break;
      s = states[trans['state_index']];
    }
    return prev;
  }

  function _isAd(state, sm) {
    if (!state || !sm) return false;
    var t = sm['tracks'][state['track']];
    if (!t) return false;
    var uri = (t['metadata'] && t['metadata']['uri']) || '';
    if (uri.includes(':ad:')) return true;
    if (t['content_type'] === 'AD') return true;
    return false;
  }

  // Fetch more states — rate limited + cached + NO auto-retry on error.
  // Callers must handle null/undefined return (means unavailable/rate-limited).
  async function _getStates(smId, stateId) {
    var cacheKey = smId + '|' + stateId;
    if (_getStatesCache[cacheKey]) {
      console.log('[SpotifyAdBlock] _getStates cache hit');
      return _getStatesCache[cacheKey];
    }

    if (!_deviceId || !_accessToken) {
      console.warn('[SpotifyAdBlock] _getStates skipped – no deviceId or token yet');
      return null;
    }

    var url  = 'https://spclient.wg.spotify.com/track-playback/v1/devices/' + _deviceId + '/state';
    var body = {
      seq_num: Date.now(),
      state_ref: { state_machine_id: smId, state_id: stateId, paused: false },
      sub_state: { playback_speed: 1, position: 0, duration: 0, stream_time: 0,
                   media_type: 'AUDIO', bitrate: 160000 },
      previous_position: 0,
      debug_source: 'resume'
    };
    var headers = {
      'Authorization': 'Bearer ' + _accessToken,
      'Content-Type': 'application/json'
    };

    var r = await _originalFetch.call(window, url,
      { method: 'PUT', headers: headers, body: JSON.stringify(body) });

    // On 429 or any non-200, do NOT retry — just return null and let caller use fallback.
    if (r.status === 429) {
      // Honour Spotify's Retry-After header; default to 30 s if absent.
      var retryAfterSec = parseInt(r.headers.get('Retry-After') || '30', 10);
      if (isNaN(retryAfterSec) || retryAfterSec < 1) retryAfterSec = 30;
      // Push the next-allowed timestamp forward by the full retry-after window.
      _lastGetStatesMs = Date.now() + (retryAfterSec * 1000) - _GET_STATES_MIN_INTERVAL_MS;
      console.warn('[SpotifyAdBlock] _getStates 429 – Spotify says retry after ' +
                   retryAfterSec + 's. Using shortenAd fallback until then.');
      return null;
    }
    if (r.status === 401) {
      await _refreshToken();
      return null; // caller will retry on next event with fresh token
    }
    if (r.status !== 200) {
      console.warn('[SpotifyAdBlock] _getStates HTTP ' + r.status);
      return null;
    }

    var json = await r.json();
    var sm   = json['state_machine'];
    if (sm) {
      _getStatesCache[cacheKey] = sm;
      // Evict old cache entries (keep last 5)
      var keys = Object.keys(_getStatesCache);
      if (keys.length > 5) delete _getStatesCache[keys[0]];
    }
    return sm || null;
  }

  console.log('[SpotifyAdBlock] fetch hook installed (WebSocket hook disabled – crash prevention)');

})();

