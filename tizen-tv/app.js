(function () {
  'use strict';

  var currentScreen = 'home';
  var previousScreen = 'home';
  var focusedElement = null;
  var toastTimer = null;
  var toast = document.getElementById('toast');
  var homeScreen = document.getElementById('home-screen');
  var detailScreen = document.getElementById('detail-screen');
  var footerContext = document.getElementById('footer-context');

  var screens = {
    music: {
      title: 'Music', kicker: 'YOUR COLLECTION', subtitle: 'Albums, artists and every track in your library.', icon: '♫',
      kind: 'library', mediaKind: 'audio'
    },
    videos: {
      title: 'Videos', kicker: 'WATCH YOUR WAY', subtitle: 'Your local films, shows and clips.', icon: '▷',
      kind: 'library', mediaKind: 'video'
    },
    books: {
      title: 'Books', kicker: 'PICK UP WHERE YOU LEFT OFF', subtitle: 'A calm space for your reading library.', icon: '▤',
      kind: 'empty', label: 'NOT CONNECTED YET', heading: 'Your reading shelf is planned.',
      body: 'Book discovery and reading controls need a TV-specific implementation before they can be enabled.',
      button: 'Back to Home', target: 'home', symbol: '▤', variant: 'book'
    },
    comics: {
      title: 'Comics', kicker: 'STORIES IN EVERY FRAME', subtitle: 'Your graphic novels and visual stories.', icon: '▧',
      kind: 'empty', label: 'NOT CONNECTED YET', heading: 'Made for the big screen.',
      body: 'Comic archive indexing and reader navigation are not implemented for this TV target yet.',
      button: 'Back to Home', target: 'home', symbol: '▧', variant: 'comic'
    },
    photos: {
      title: 'Photos', kicker: 'YOUR MOMENTS', subtitle: 'Bring your favorite memories to the big screen.', icon: '▣',
      kind: 'empty', label: 'NOT CONNECTED YET', heading: 'Your gallery, reimagined.',
      body: 'Photo source enumeration and slideshow controls need to be connected to supported TV APIs.',
      button: 'Back to Home', target: 'home', symbol: '▣', variant: 'photo'
    },
    'now-playing': {
      title: 'Now Playing', kicker: 'YOUR CURRENT SESSION', subtitle: 'Playback status and controls for the active track.', icon: '◉',
      kind: 'empty', label: 'NOTHING PLAYING', heading: 'Ready when you are.',
      body: 'Open a track from Music or Videos to start playback. Supported formats depend on the TV media engine.',
      button: 'Set up media sources', target: 'sources', symbol: '◉'
    },
    queue: {
      title: 'Queue', kicker: 'UP NEXT', subtitle: 'Manage what plays next.', icon: '☷',
      kind: 'simple', heading: 'Your queue is empty.',
      body: 'Load media before adding tracks to the queue.', button: 'Open Music', target: 'music', symbol: '☷'
    },
    equalizer: {
      title: 'Equalizer', kicker: 'SHAPE YOUR SOUND', subtitle: 'Audio tuning for your listening setup.', icon: '≋',
      kind: 'simple', heading: "Audio processing isn't connected.",
      body: 'Equalizer bands and presets will appear only after the TV audio pipeline is verified.', button: 'View Settings', target: 'settings', symbol: '≋'
    },
    sources: {
      title: 'Media sources', kicker: 'FIND YOUR MEDIA', subtitle: 'Connect local media to Mortimer Player.', icon: '▣',
      kind: 'sources'
    },
    settings: {
      title: 'Settings', kicker: 'MAKE IT YOURS', subtitle: 'Playback and interface preferences.', icon: '⚙',
      kind: 'settings'
    }
  };

  function announce(message) {
    if (!toast) return;
    toast.textContent = message;
    toast.hidden = false;
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { toast.hidden = true; }, 3600);
  }

  function makeDetailMarkup(key) {
    var data = screens[key];
    var html = '<div class="screen-heading"><div><p class="kicker">' + data.kicker +
      '</p><h1>' + data.title + '<span class="title-period">.</span></h1><p class="screen-subtitle">' +
      data.subtitle + '</p></div><span class="screen-icon" aria-hidden="true">' + data.icon + '</span></div>';

    if (data.kind === 'library') {
      html += '<div class="library-browser"><div class="library-toolbar"><div><p class="list-eyebrow">' +
        (data.mediaKind === 'audio' ? 'LOCAL AUDIO' : 'LOCAL VIDEO') +
        '</p><span id="library-status" class="media-status" role="status">Checking media sources…</span></div><div class="library-toolbar-actions"><button class="action-primary" data-media-action="scan-library" data-media-kind="' +
        data.mediaKind + '">Scan library</button><button class="action-secondary" data-screen="sources">Browse folders</button></div></div><div class="media-list" id="library-list" aria-label="Media files"></div></div>';
    } else if (data.kind === 'empty') {
      html += '<div class="empty-panel"><div class="empty-art ' + (data.variant ? data.variant + '-art' : '') +
        '" aria-hidden="true"><span>' + data.symbol + '</span><i></i></div><div class="empty-copy"><p class="section-eyebrow">' +
        data.label + '</p><h2>' + data.heading + '</h2><p>' + data.body +
        '</p><button class="' + (data.target === 'home' ? 'action-secondary' : 'action-primary') +
        '" data-screen="' + data.target + '">' + data.button +
        (data.target === 'home' ? '' : ' <span class="action-arrow">›</span>') + '</button></div></div>';
    } else if (data.kind === 'simple') {
      html += '<div class="simple-state"><span class="state-icon" aria-hidden="true">' + data.symbol +
        '</span><h2>' + data.heading + '</h2><p>' + data.body + '</p><button class="action-secondary" data-screen="' +
        data.target + '">' + data.button + '</button></div>';
    } else if (data.kind === 'sources') {
      html += '<div class="source-browser"><div class="source-toolbar"><div><p class="list-eyebrow">MEDIA STORAGE</p><span class="media-status" id="source-status" role="status">Checking TV storage…</span></div><div class="source-toolbar-actions"><button class="action-secondary" id="directory-back" data-media-action="back-directory" hidden>‹ Back</button><button class="action-primary" id="refresh-sources" data-media-action="refresh-sources">Refresh devices</button></div></div><div class="source-location" id="source-location">Choose a connected USB drive or standard TV folder.</div><div class="source-grid" id="source-list"></div><div class="media-list source-contents" id="source-contents" hidden></div><p class="source-disclaimer">Files are discovered through the Samsung Tizen filesystem API. Playback depends on the TV media engine and the format of each file.</p></div>';
    } else if (data.kind === 'settings') {
      html += '<div class="settings-list"><div class="setting-row"><span class="setting-symbol">⌘</span><div><b>Remote navigation</b><small>Directional focus, OK and Back</small></div><span class="setting-status">Preview active</span></div><div class="setting-row"><span class="setting-symbol">◈</span><div><b>Appearance</b><small>Mortimer dark theme</small></div><span class="setting-status">Active</span></div><div class="setting-row"><span class="setting-symbol">♫</span><div><b>Audio and subtitles</b><small>Requires media engine integration</small></div><span class="setting-status pending">Planned</span></div><div class="setting-row"><span class="setting-symbol">▣</span><div><b>Library scanning</b><small>Requires TV content index integration</small></div><span class="setting-status pending">Planned</span></div></div>';
    }
    return html;
  }

  function visibleButtons() {
    var playerOverlay = document.getElementById('player-overlay');
    var playerOpen = playerOverlay && !playerOverlay.hidden && !playerOverlay.classList.contains('hidden');
    var root = playerOpen ? playerOverlay : document;
    var list = root.querySelectorAll('button:not([disabled])');
    var result = [];
    for (var i = 0; i < list.length; i++) {
      var button = list[i];
      if (button.offsetWidth > 0 && button.offsetHeight > 0 && !button.closest('[hidden]')) result.push(button);
    }
    return result;
  }

  function setFocus(element) {
    var all = document.querySelectorAll('button.focused');
    for (var i = 0; i < all.length; i++) all[i].classList.remove('focused');
    if (!element) return;
    focusedElement = element;
    element.classList.add('focused');
    try { element.focus({ preventScroll: true }); } catch (_) { try { element.focus(); } catch (__) {} }
  }

  function focusFirst() {
    var buttons = visibleButtons();
    if (!buttons.length) return;
    var overlay = document.getElementById('player-overlay');
    var playerOpen = overlay && !overlay.hidden && !overlay.classList.contains('hidden');
    var preferred = playerOpen ? document.getElementById('player-play-pause') :
      (currentScreen === 'home' ? document.querySelector('#home-screen [data-screen="music"]') :
      detailScreen.querySelector('button:not([disabled])'));
    setFocus(preferred || buttons[0]);
  }

  function moveFocus(direction) {
    var buttons = visibleButtons();
    if (!buttons.length) return;
    if (!focusedElement || buttons.indexOf(focusedElement) < 0) { focusFirst(); return; }
    var current = focusedElement.getBoundingClientRect();
    var cx = current.left + current.width / 2;
    var cy = current.top + current.height / 2;
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < buttons.length; i++) {
      var candidate = buttons[i];
      if (candidate === focusedElement) continue;
      var rect = candidate.getBoundingClientRect();
      var x = rect.left + rect.width / 2;
      var y = rect.top + rect.height / 2;
      var dx = x - cx;
      var dy = y - cy;
      var primary;
      var secondary;
      if (direction === 'right' && dx > 4) { primary = dx; secondary = Math.abs(dy); }
      else if (direction === 'left' && dx < -4) { primary = -dx; secondary = Math.abs(dy); }
      else if (direction === 'down' && dy > 4) { primary = dy; secondary = Math.abs(dx); }
      else if (direction === 'up' && dy < -4) { primary = -dy; secondary = Math.abs(dx); }
      else continue;
      var score = primary + secondary * 2.25;
      if (secondary > primary * 1.8) score += primary;
      if (score < bestScore) { bestScore = score; best = candidate; }
    }
    if (best) setFocus(best);
  }

  function showScreen(key, remember) {
    if (key !== 'home' && !screens[key]) return;
    if (remember !== false && key !== currentScreen) previousScreen = currentScreen;
    currentScreen = key;
    homeScreen.hidden = key !== 'home';
    homeScreen.classList.toggle('active', key === 'home');
    detailScreen.hidden = key === 'home';
    detailScreen.classList.toggle('active', key !== 'home');
    if (key !== 'home') detailScreen.innerHTML = makeDetailMarkup(key);
    var nav = document.querySelectorAll('.nav-item[data-screen]');
    for (var i = 0; i < nav.length; i++) nav[i].classList.toggle('active', nav[i].getAttribute('data-screen') === key);
    footerContext.textContent = key === 'home' ? 'A personal media space' : (screens[key].subtitle || screens[key].title);
    setFocus(null);
    focusFirst();
    if (window.MortimerTVMedia && typeof window.MortimerTVMedia.onScreen === 'function') {
      window.MortimerTVMedia.onScreen(key);
    }
  }

  window.addEventListener('mortimer:focus-refresh', function (event) {
    if (event && event.detail && event.detail.offsetWidth > 0 && !event.detail.closest('[hidden]')) {
      setFocus(event.detail);
    } else {
      focusedElement = null;
      focusFirst();
    }
  });

  function goBack() {
    if (currentScreen !== 'home') {
      var destination = previousScreen !== currentScreen && previousScreen !== 'home' ? previousScreen : 'home';
      showScreen(destination, false);
    } else {
      announce('You are at Home.');
    }
  }

  document.addEventListener('click', function (event) {
    var target = event.target;
    while (target && target !== document && !target.getAttribute('data-screen')) target = target.parentNode;
    if (!target || target === document) return;
    var key = target.getAttribute('data-screen');
    if (key) showScreen(key);
  });

  document.addEventListener('keydown', function (event) {
    var key = event.key;
    var code = event.keyCode || event.which;
    if (key === 'Escape' || key === 'Backspace' || code === 10009) {
      event.preventDefault();
      goBack();
      return;
    }
    if (key === 'ArrowRight' || key === 'Right') { event.preventDefault(); moveFocus('right'); return; }
    if (key === 'ArrowLeft' || key === 'Left') { event.preventDefault(); moveFocus('left'); return; }
    if (key === 'ArrowDown' || key === 'Down') { event.preventDefault(); moveFocus('down'); return; }
    if (key === 'ArrowUp' || key === 'Up') { event.preventDefault(); moveFocus('up'); return; }
    if (key === 'Enter' || key === ' ' || code === 13) {
      var active = document.activeElement;
      if (active && active.tagName === 'BUTTON') { event.preventDefault(); active.click(); }
      return;
    }
    if (key === 'MediaPlay' || key === 'MediaPause' || key === 'MediaPlayPause' || key === 'MediaStop' ||
        key === 'MediaFastForward' || key === 'MediaRewind' || code === 10252) {
      announce('Playback controls become available when the TV media engine is connected.');
    }
  });

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      var buttons = visibleButtons();
      if (buttons.indexOf(focusedElement) < 0) focusFirst();
    }
  });

  try {
    if (window.tizen && window.tizen.tvinputdevice && window.tizen.tvinputdevice.registerKeyBatch) {
      window.tizen.tvinputdevice.registerKeyBatch([
        'MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaStop', 'MediaFastForward', 'MediaRewind'
      ]);
    }
  } catch (_) {}

  showScreen('home', false);
})();