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
  // Blockify also identifies ad media by the content IDs advertised in the
  // track manifest.  This catches ad URLs that do not contain :ad:.
  var _knownAdContentIds = [];
  var _lastPublishedContentIds = '';
  var _MAX_AD_CONTENT_IDS = 32;

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

  function _rememberAdContentId(value) {
    if (typeof value !== 'string' || !/^[a-zA-Z0-9_-]{8,128}$/.test(value)) return;
    var idx = _knownAdContentIds.indexOf(value);
    if (idx >= 0) _knownAdContentIds.splice(idx, 1);
    _knownAdContentIds.push(value);
    while (_knownAdContentIds.length > _MAX_AD_CONTENT_IDS) _knownAdContentIds.shift();
  }

  function _collectManifestIds(manifest) {
    if (!manifest || typeof manifest !== 'object') return;
    ['file_ids_mp3', 'file_ids_external', 'file_ids', 'alternatives'].forEach(function (groupName) {
      var group = manifest[groupName];
      if (!Array.isArray(group)) return;
      group.forEach(function (entry) {
        if (typeof entry === 'string') _rememberAdContentId(entry);
        else if (entry) {
          _rememberAdContentId(entry.file_id);
          _rememberAdContentId(entry.fileId);
          _rememberAdContentId(entry.id);
        }
      });
    });
  }

  function _trackIsExplicitAd(track) {
    if (!track) return false;
    var m = track.metadata || {};
    return [track.content_type, track.contentType, track.type, m.content_type,
      m.contentType, m.is_ad, m.isAd, track.is_ad, track.isAd].some(function (v) {
        return v === true || (typeof v === 'string' &&
          ['AD', 'ADVERTISEMENT', 'TRUE'].indexOf(v.toUpperCase()) >= 0);
      });
  }

  function _inspectStateMachine(sm) {
    if (!sm || typeof sm !== 'object') return;
    var groups = [sm.tracks, sm.track_list, sm.queue && sm.queue.tracks];
    groups.forEach(function (tracks) {
      if (!Array.isArray(tracks)) return;
      tracks.forEach(function (track) {
        if (_trackIsExplicitAd(track)) {
          _collectManifestIds(track.manifest);
          _rememberAdContentId(track.file_id);
          _rememberAdContentId(track.fileId);
        }
      });
    });
    _publishAdContentIds();
  }

  function _inspectPayload(payload) {
    if (!payload || typeof payload !== 'object') return;
    _inspectStateMachine(payload.state_machine || payload.stateMachine);
    if (Array.isArray(payload.payloads)) payload.payloads.forEach(function (p) {
      _inspectStateMachine(p && (p.state_machine || p.stateMachine));
    });
  }

  function _publishAdContentIds() {
    if (!document.body) return;
    var serialized = JSON.stringify(_knownAdContentIds);
    if (serialized === _lastPublishedContentIds) return;
    _lastPublishedContentIds = serialized;
    document.body.setAttribute('data-blockify-ad-content-ids', serialized);
  }

  function _isKnownAdContentUrl(url) {
    return _knownAdContentIds.some(function (id) { return url.indexOf(id) >= 0; });
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

    // Blockify's request filter redirects known ad media.  An empty response
    // makes the player fail/advance without touching Spotify's other traffic.
    if (_isKnownAdContentUrl(urlStr)) {
      console.log('[TuneveilAdBlock] known ad media blocked');
      return Promise.resolve(new Response('', { status: 200 }));
    }

    // Passively steal the access token from Spotify's own token request
    if (urlStr.includes('get_access_token')) {
      return _originalFetch.call(window, url, init).then(function (resp) {
        var clone = resp.clone();
        clone.json().then(function (j) {
          if (j && j['accessToken']) {
            _accessToken = j['accessToken'];
            console.log('[TuneveilAdBlock] access token captured');
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
        console.error('[TuneveilAdBlock] fetch intercept error: ' + e);
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
        _inspectPayload(data);
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
    // Track indices that were already shortened so we never re-process them.
    // _shortenAd only changes playback position, NOT the URI, so _isAd() would
    // still return true and the do-while loop would spin forever without this guard.
    var shortenedIdx = {};

    do {
      changed = false;
      for (var i = 0; i < states.length; i++) {
        var state = states[i];
        if (!_isAd(state, sm)) continue;
        if (shortenedIdx[i]) continue; // already shortened in a previous pass – skip

        var adTrack = tracks[state['track']];
        console.log('[TuneveilAdBlock] ad detected: ' + adTrack['metadata']['uri']);

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
              console.warn('[TuneveilAdBlock] _getStates failed: ' + e);
            }
          }
          // If still an ad after fetch attempt (or rate-limited), shorten it.
          // Do NOT set changed=true – that would re-trigger the loop for a state
          // whose URI still satisfies _isAd(), causing an infinite detect→shorten cycle.
          if (_isAd(next, sm)) {
            states[i] = _shortenAd(state, adTrack);
            shortenedIdx[i] = true;
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
          console.log('[TuneveilAdBlock] ad removed ✓');
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
    startIdx = (startIdx != null) ? startIdx : 0;
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
    if (_trackIsExplicitAd(t)) return true;
    return false;
  }

  // Fetch more states — rate limited + cached + NO auto-retry on error.
  // Callers must handle null/undefined return (means unavailable/rate-limited).
  async function _getStates(smId, stateId) {
    var cacheKey = smId + '|' + stateId;
    if (_getStatesCache[cacheKey]) {
      console.log('[TuneveilAdBlock] _getStates cache hit');
      return _getStatesCache[cacheKey];
    }

    if (!_deviceId || !_accessToken) {
      console.warn('[TuneveilAdBlock] _getStates skipped – no deviceId or token yet');
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
      console.warn('[TuneveilAdBlock] _getStates 429 – Spotify says retry after ' +
                   retryAfterSec + 's. Using shortenAd fallback until then.');
      return null;
    }
    if (r.status === 401) {
      await _refreshToken();
      return null; // caller will retry on next event with fresh token
    }
    if (r.status !== 200) {
      console.warn('[TuneveilAdBlock] _getStates HTTP ' + r.status);
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

  // ── Safe WebSocket ad observer ──────────────────────────────────────────
  // We replace the WebSocket constructor so we can add ONE passive listener to
  // every WS connection.  When Spotify pushes a replace_state payload that
  // contains an ad state we immediately fast-forward the audio element.
  //
  // Safety rules (prevents the Chrome_IOThread SIGTRAP crash from the old hook):
  //   ✓  We do NOT use Object.defineProperty on any WebSocket instance.
  //   ✓  We do NOT touch ws.onmessage at all.
  //   ✓  We do NOT modify Spotify's messages before it sees them.
  //   ✓  Our listener only reads; Spotify's own listeners receive the original event.
  (function() {
    var _NativeWS = window.WebSocket;

    function TuneveilWSWrapper(url, protocols) {
      var ws = protocols ? new _NativeWS(url, protocols) : new _NativeWS(url);

      ws.addEventListener('message', function(evt) {
        try {
          if (typeof evt.data !== 'string') return;
          var msg = JSON.parse(evt.data);
          _inspectPayload(msg);
          if (!msg || !Array.isArray(msg.payloads)) return;
          for (var i = 0; i < msg.payloads.length; i++) {
            var pl = msg.payloads[i];
            if (pl && pl.type === 'replace_state' && pl.state_machine) {
              if (_wsHasAdState(pl.state_machine)) {
                console.log('[TuneveilAdBlock] WS replace_state contains ad – fast-forwarding');
                _wsFastForwardAd(0);
              }
            }
          }
        } catch(e) {}
      });

      return ws; // returning ws (not this) makes "new TuneveilWSWrapper()" yield the native instance
    }

    // Copy static constants (CONNECTING=0, OPEN=1, …)
    try {
      Object.keys(_NativeWS).forEach(function(k) { TuneveilWSWrapper[k] = _NativeWS[k]; });
    } catch(e) {}
    TuneveilWSWrapper.prototype = _NativeWS.prototype;
    window.WebSocket = TuneveilWSWrapper;
  })();

  function _wsHasAdState(sm) {
    if (!sm || !sm.states || !sm.tracks) return false;
    for (var i = 0; i < sm.states.length; i++) {
      if (_isAd(sm.states[i], sm)) return true;
    }
    return false;
  }

  // Attempt to fast-forward all audio elements to their end so Spotify's player
  // fires the 'ended' event and advances to the next (non-ad) state.
  // retries: how many times we've already retried waiting for duration.
  function _wsFastForwardAd(retries) {
    setTimeout(function() {
      var audios = document.querySelectorAll('audio');
      var seeked = false;
      for (var i = 0; i < audios.length; i++) {
        var a = audios[i];
        if (a.duration && isFinite(a.duration) && a.duration > 0) {
          try {
            a.currentTime = a.duration - 0.1;
            if (a.paused) { try { a.play(); } catch(e2) {} }
            seeked = true;
          } catch(e) {}
        }
      }
      if (!seeked && retries < 10) {
        // Duration not yet known (ad still buffering) – retry
        _wsFastForwardAd(retries + 1);
      }
    }, retries === 0 ? 150 : 500);
  }

  // Page-world fallback used by current Blockify: Spotify sometimes exposes
  // the ad in the UI before its media state reaches the next REST response.
  // Mute only while an explicit ad marker is visible, then restore playback.
  function _spotifyShowsAd() {
    var panel = document.getElementById('Desktop_PanelContainer_Id');
    if (!panel) return false;
    if ((panel.textContent || '').indexOf('Your music will continue after the break') >= 0) return true;
    return !!panel.querySelector('[data-testid="ad-companion-card"],'
      + '[data-testid="ad-companion-card-tagline"],a[data-context-item-type="ad"]');
  }

  var _uiAdMuted = false;
  function _syncUiAdState() {
    var ad = _spotifyShowsAd();
    if (ad) {
      document.querySelectorAll('audio,video').forEach(function (m) {
        try { m.muted = true; m.volume = 0; } catch (_) {}
      });
      _uiAdMuted = true;
    } else if (_uiAdMuted) {
      document.querySelectorAll('audio,video').forEach(function (m) {
        try { m.muted = false; if (m.volume < 0.05) m.volume = 1; } catch (_) {}
      });
      _uiAdMuted = false;
    }
  }

  function _startBlockifyUiFilter() {
    if (!document.documentElement || window.__spotifyBlockifyUiFilter) return;
    window.__spotifyBlockifyUiFilter = true;
    var timer = null;
    var schedule = function () {
      if (timer !== null) return;
      timer = setTimeout(function () { timer = null; _syncUiAdState(); }, 250);
    };
    new MutationObserver(schedule).observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-context-item-type', 'data-testid'],
      childList: true,
      subtree: true
    });
    setInterval(_syncUiAdState, 1000);
    _publishAdContentIds();
    _syncUiAdState();
  }

  if (document.documentElement) _startBlockifyUiFilter();
  else document.addEventListener('DOMContentLoaded', _startBlockifyUiFilter, { once: true });

  console.log('[TuneveilAdBlock] fetch + safe-WS hooks installed');

})();

