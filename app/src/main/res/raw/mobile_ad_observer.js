(function () {
  'use strict';

  if (location.hostname !== 'open.spotify.com' || window.__tuneveilMobileFilter) return;
  window.__tuneveilMobileFilter = true;

  // Android System WebView does not expose the Media Session API, so Spotify's
  // mobile bundle never registers its internal next/previous actions. Provide
  // the small surface Spotify needs before its player bundle starts.
  if (!('mediaSession' in navigator)) {
    Object.defineProperty(navigator, 'mediaSession', {
      configurable: false,
      enumerable: true,
      value: {
        metadata: null,
        playbackState: 'none',
        setActionHandler: function () {},
        setPositionState: function () {}
      }
    });
  }
  if (typeof window.MediaMetadata !== 'function') {
    window.MediaMetadata = function (metadata) {
      Object.assign(this, metadata || {});
    };
  }

  var mediaActionHandlers = Object.create(null);
  if (navigator.mediaSession && typeof navigator.mediaSession.setActionHandler === 'function') {
    var nativeSetActionHandler = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
    navigator.mediaSession.setActionHandler = function (action, handler) {
      if (action === 'nexttrack' || action === 'previoustrack') {
        if (typeof handler === 'function') mediaActionHandlers[action] = handler;
        // Spotify's free mobile UI later unregisters transport actions based on
        // client-side restriction flags. Keep the last real handler available
        // to Tuneveil's explicit native controls.
      }
      return nativeSetActionHandler(action, handler);
    };
  }

  window.__tuneveilRunMediaAction = function (action) {
    var handler = mediaActionHandlers[action];
    if (typeof handler !== 'function') return false;
    try {
      handler.call(navigator.mediaSession, { action: action });
      console.log('[TuneveilControls] invoked ' + action);
      return true;
    } catch (error) {
      console.warn('[TuneveilControls] ' + action + ' failed: ' + error);
      return false;
    }
  };

  var resumeAfterAd = false;
  try {
    resumeAfterAd = sessionStorage.getItem('tuneveil-resume-after-ad') === '1';
    sessionStorage.removeItem('tuneveil-resume-after-ad');
  } catch (_) {}

  function resumeMusic(attempt) {
    var button = document.querySelector('[data-testid="play-button"]') ||
      document.querySelector('[data-testid="control-button-playpause"]');
    var label = button && String(button.getAttribute('aria-label') || '').toLowerCase();
    if (button && label.indexOf('pause') >= 0) {
      console.log('[TuneveilMobileAds] music resumed after ad skip');
      return;
    }
    if (button && !button.disabled && label.indexOf('play') >= 0) {
      button.click();
    }
    if (attempt < 80) setTimeout(function () { resumeMusic(attempt + 1); }, 250);
  }

  // Called by the Android request interceptor when the player asks for a
  // confirmed ad-audio asset. Reloading abandons that ad state; after Spotify
  // restores its queue, resumeMusic advances playback without a silent gap.
  window.__tuneveilSkipBlockedAd = function () {
    if (window.__tuneveilAdReloadInFlight) return false;
    window.__tuneveilAdReloadInFlight = true;
    try { sessionStorage.setItem('tuneveil-resume-after-ad', '1'); } catch (_) {}
    console.log('[TuneveilMobileAds] blocked ad audio; skipping state');
    setTimeout(function () { location.reload(); }, 0);
    return true;
  };

  if (resumeAfterAd) {
    setTimeout(function () { resumeMusic(0); }, 250);
  }

  function trackForState(stateMachine, state) {
    if (!stateMachine || !state || !Array.isArray(stateMachine.tracks)) return null;
    return stateMachine.tracks[state.track] || null;
  }

  function isAdTrack(track) {
    if (!track) return false;
    var metadata = track.metadata || {};
    var uri = String(metadata.uri || track.uri || '');
    var contentType = String(track.content_type || metadata.content_type || '').toUpperCase();
    return uri.indexOf(':ad:') >= 0 || contentType === 'AD' ||
      track.is_ad === true || metadata.is_ad === true || metadata.is_ad === 'true';
  }

  function nextNonAdIndex(stateMachine, startIndex) {
    var states = stateMachine.states;
    var visited = {};
    var index = startIndex;

    while (Number.isInteger(index) && index >= 0 && index < states.length && !visited[index]) {
      visited[index] = true;
      var state = states[index];
      var advance = state && state.transitions && state.transitions.advance;
      index = advance && advance.state_index;
      if (Number.isInteger(index) && index >= 0 && index < states.length &&
          !isAdTrack(trackForState(stateMachine, states[index]))) {
        return index;
      }
    }

    for (var offset = 1; offset < states.length; offset += 1) {
      index = (startIndex + offset) % states.length;
      if (!isAdTrack(trackForState(stateMachine, states[index]))) return index;
    }
    return -1;
  }

  function activeState(payload) {
    if (!payload || typeof payload !== 'object') return null;
    var stateMachine = payload.state_machine;
    var stateRef = payload.updated_state_ref || payload.state_ref;
    if (!stateMachine || !stateRef || !Array.isArray(stateMachine.states)) return null;
    var index = stateRef.state_index;
    if (!Number.isInteger(index) || !stateMachine.states[index]) return null;
    return { stateMachine: stateMachine, stateRef: stateRef, index: index };
  }

  function removeActiveAd(payload) {
    var active = activeState(payload);
    if (!active) return false;

    var current = active.stateMachine.states[active.index];
    var currentTrack = trackForState(active.stateMachine, current);
    if (!isAdTrack(currentTrack)) return false;

    var replacementIndex = nextNonAdIndex(active.stateMachine, active.index);
    if (replacementIndex < 0) return false;

    var replacement = JSON.parse(JSON.stringify(active.stateMachine.states[replacementIndex]));
    if (current.state_id !== undefined) replacement.state_id = current.state_id;
    active.stateMachine.states[active.index] = replacement;
    if (active.stateRef.state_index !== undefined) active.stateRef.state_index = active.index;
    if (current.state_id !== undefined && active.stateRef.state_id !== undefined) {
      active.stateRef.state_id = current.state_id;
    }

    document.documentElement.setAttribute('data-tuneveil-ad-filtered', 'true');
    console.log('[TuneveilMobileAds] active ad removed');
    return true;
  }

  function filterPayload(payload) {
    var changed = removeActiveAd(payload);
    enableTransportControls(payload);
    if (payload && Array.isArray(payload.payloads)) {
      payload.payloads.forEach(function (nested) {
        if (filterPayload(nested)) changed = true;
      });
    }
    return changed;
  }

  function enableTransportControls(payload) {
    if (!payload || typeof payload !== 'object') return;
    var state = payload.state || payload.player_state || payload;
    if (state.disallows && typeof state.disallows === 'object') {
      delete state.disallows.skipping_next;
      delete state.disallows.skipping_prev;
    }
    if (state.restrictions && typeof state.restrictions === 'object') {
      delete state.restrictions.disallow_skipping_next_reasons;
      delete state.restrictions.disallow_skipping_prev_reasons;
    }
  }

  var originalFetch = window.fetch;
  window.fetch = new Proxy(originalFetch, {
    apply: function (target, receiver, args) {
      return Reflect.apply(target, receiver, args).then(function (response) {
        var input = args[0];
        var url = typeof input === 'string' ? input : input && input.url;
        if (!url || url.indexOf('/state') < 0) return response;

        var originalJson = response.json.bind(response);
        response.json = function () {
          return originalJson().then(function (payload) {
            filterPayload(payload);
            return payload;
          });
        };
        return response;
      });
    }
  });

  // Dealer messages cannot be replaced safely in Chromium. If one activates an
  // ad, reload once for that ad; the patched state fetch then substitutes music.
  window.WebSocket = new Proxy(window.WebSocket, {
    construct: function (target, args) {
      var socket = Reflect.construct(target, args);
      socket.addEventListener('message', function (event) {
        if (typeof event.data !== 'string') return;
        try {
          var payload = JSON.parse(event.data);
          var active = activeState(payload);
          if (!active && Array.isArray(payload.payloads)) {
            for (var i = 0; i < payload.payloads.length && !active; i += 1) {
              active = activeState(payload.payloads[i]);
            }
          }
          if (!active) return;
          var track = trackForState(active.stateMachine, active.stateMachine.states[active.index]);
          if (!isAdTrack(track)) return;

          var metadata = track.metadata || {};
          var adKey = String(metadata.uri || track.uri || active.index);
          var storageKey = 'tuneveil-skipped-' + adKey;
          if (sessionStorage.getItem(storageKey)) return;
          sessionStorage.setItem(storageKey, '1');
          console.log('[TuneveilMobileAds] dealer ad detected; refreshing state');
          setTimeout(function () { location.reload(); }, 100);
        } catch (_) {}
      });
      return socket;
    }
  });

  console.log('[TuneveilMobileAds] active filter installed');
})();
