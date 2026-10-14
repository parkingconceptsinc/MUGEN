(function () {
  'use strict';

  var audioExtensions = {
    mp3: true, m4a: true, mp4a: true, aac: true, wav: true, wave: true, flac: true,
    ogg: true, oga: true, opus: true, wma: true, aiff: true, aif: true, amr: true
  };
  var videoExtensions = {
    mp4: true, m4v: true, mkv: true, avi: true, mov: true, mpg: true, mpeg: true,
    ts: true, m2ts: true, webm: true, '3gp': true, vob: true
  };
  var state = {
    screen: 'home',
    sourceTitle: '',
    currentPath: null,
    pathStack: [],
    storages: [],
    itemMap: {},
    nextId: 1,
    currentItem: null,
    playing: false,
    focusBeforePlayer: null,
    progressTimer: null,
    scanToken: 0,
    sourceToken: 0
  };

  function byId(id) { return document.getElementById(id); }
  function fsApi() {
    return window.tizen && window.tizen.filesystem ? window.tizen.filesystem : null;
  }
  function apiAvailable() {
    return !!fsApi();
  }
  function toast(message) {
    var el = byId('toast');
    if (el) {
      el.textContent = message;
      el.hidden = false;
      if (state.toastTimer) window.clearTimeout(state.toastTimer);
      state.toastTimer = window.setTimeout(function () { el.hidden = true; }, 4200);
    }
  }
  function setText(id, value) {
    var el = byId(id);
    if (el) el.textContent = value;
  }
  function safeText(value) {
    return String(value == null ? '' : value);
  }
  function focusRefresh(preferred) {
    try {
      window.dispatchEvent(new CustomEvent('mortimer:focus-refresh', { detail: preferred || null }));
    } catch (_) {
      var event = document.createEvent('Event');
      event.initEvent('mortimer:focus-refresh', true, true);
      event.detail = preferred || null;
      window.dispatchEvent(event);
    }
  }
  function clear(element) {
    if (element) while (element.firstChild) element.removeChild(element.firstChild);
  }
  function element(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }
  function makeActionButton(label, action, className) {
    var button = element('button', className || 'action-secondary', label);
    button.type = 'button';
    button.setAttribute('data-media-action', action);
    return button;
  }
  function rootPath(path) {
    return safeText(path).replace(/\/+$/, '');
  }
  function joinPath(parent, name) {
    var base = rootPath(parent);
    return base ? base + '/' + name : name;
  }
  function extensionOf(name) {
    var value = safeText(name);
    var index = value.lastIndexOf('.');
    return index < 0 ? '' : value.slice(index + 1).toLowerCase();
  }
  function classify(name) {
    if (window.MortimerFormats && typeof window.MortimerFormats.classify === 'function') {
      return window.MortimerFormats.classify(name);
    }
    var ext = extensionOf(name);
    if (audioExtensions[ext]) return 'audio';
    if (videoExtensions[ext]) return 'video';
    return '';
  }
  function fileUri(path) {
    var api = fsApi();
    if (!api || typeof api.toURI !== 'function') return '';
    try { return api.toURI(path); } catch (_) { return ''; }
  }
  function listStorages() {
    return new Promise(function (resolve, reject) {
      var api = fsApi();
      if (!api || typeof api.listStorages !== 'function') {
        reject(new Error('Samsung Tizen filesystem APIs are not available in this browser.'));
        return;
      }
      try {
        api.listStorages(function (items) { resolve(items || []); }, function (error) {
          reject(new Error(error && error.message ? error.message : 'The TV could not list storage devices.'));
        });
      } catch (error) { reject(error); }
    });
  }
  function listDirectory(path) {
    return new Promise(function (resolve, reject) {
      var api = fsApi();
      if (!api || typeof api.listDirectory !== 'function') {
        reject(new Error('The Tizen filesystem API is not available.'));
        return;
      }
      try {
        api.listDirectory(path, function (names) { resolve(names || []); }, function (error) {
          reject(new Error(error && error.message ? error.message : 'This folder could not be opened.'));
        });
      } catch (error) { reject(error); }
    });
  }
  function isDirectory(path) {
    var api = fsApi();
    try { return !!(api && api.isDirectory && api.isDirectory(path)); } catch (_) { return false; }
  }
  function isRemovablePath(path, allowRoot) {
    var candidate = rootPath(path);
    return state.storages.some(function (storage) {
      if (!storage || storage.state !== 'MOUNTED' ||
          !(storage.type === 'EXTERNAL' || /^removable_/i.test(storageLabel(storage)))) return false;
      var root = rootPath(storageLabel(storage));
      return !!root && (candidate === root ? !!allowRoot : candidate.indexOf(root + '/') === 0);
    });
  }
  function makeManageButton(path, name, directory) {
    var button = element('button', 'media-manage-button', 'Manage');
    button.type = 'button';
    button.setAttribute('data-manage-path', path);
    button.setAttribute('data-manage-name', name);
    button.setAttribute('data-manage-directory', directory ? 'true' : 'false');
    button.setAttribute('aria-label', 'Manage ' + name);
    return button;
  }
  function fsMutation(path, operation, destination, name, directory) {
    return new Promise(function (resolve, reject) {
      var api = fsApi();
      if (!api || typeof api.resolve !== 'function') {
        reject(new Error('File management requires the Samsung Tizen filesystem API.'));
        return;
      }
      api.resolve(path, function (entry) {
        var success = function () { resolve(true); };
        var failure = function (error) { reject(new Error(error && error.message || 'The USB operation failed. Check drive permissions and free space.')); };
        try {
          if (operation === 'copy' || operation === 'move') {
            var method = operation === 'copy' ? entry.copyTo : entry.moveTo;
            if (typeof method !== 'function') {
              reject(new Error('This Tizen filesystem entry does not support ' + operation + '.'));
              return;
            }
            method.call(entry, destination, name, false, success, failure);
          } else if (operation === 'delete') {
            if (directory) {
              if (typeof api.deleteDirectory !== 'function') {
                reject(new Error('Folder deletion is not available in this Tizen runtime.'));
                return;
              }
              api.deleteDirectory(path, true, success, failure);
            } else {
              if (typeof api.deleteFile !== 'function') {
                reject(new Error('File deletion is not available in this Tizen runtime.'));
                return;
              }
              api.deleteFile(path, success, failure);
            }
          } else {
            reject(new Error('Unknown file operation.'));
          }
        } catch (error) { reject(error); }
      }, function (error) {
        reject(new Error(error && error.message || 'Could not access this USB item for writing.'));
      }, 'rw');
    });
  }
  function manageMedia(path, name, directory) {
    if (!isRemovablePath(path, false)) {
      toast('For safety, file changes are enabled only for items inside a mounted removable USB drive.');
      return;
    }
    var choice = window.prompt('Manage USB item:\n1 = Copy\n2 = Move\n3 = Delete\n\nEnter 1, 2 or 3.', '1');
    if (choice == null) return;
    choice = String(choice).trim();
    if (choice !== '1' && choice !== '2' && choice !== '3') {
      toast('No operation selected. Enter 1, 2 or 3.');
      return;
    }
    var operation = choice === '1' ? 'copy' : (choice === '2' ? 'move' : 'delete');
    var destination = '';
    if (operation === 'copy' || operation === 'move') {
      destination = window.prompt('Enter the destination folder path on a connected USB drive. The item name will be preserved.', state.currentPath || '');
      if (destination == null) return;
      destination = rootPath(destination.trim());
      if (!destination || !isRemovablePath(destination, true) || !isDirectory(destination)) {
        toast('Destination must be an existing folder on a mounted USB drive.');
        return;
      }
      if (directory && (destination === path || destination.indexOf(path + '/') === 0)) {
        toast('A folder cannot be copied or moved into itself or one of its subfolders.');
        return;
      }
    } else {
      var confirmed = window.confirm('Permanently delete ' + (directory ? 'folder and all its contents' : 'file') + '?\n\n' + name);
      if (!confirmed) return;
    }
    toast((operation === 'delete' ? 'Deleting ' : (operation === 'copy' ? 'Copying ' : 'Moving ')) + name + '…');
    fsMutation(path, operation, destination, name, directory).then(function () {
      toast(operation.charAt(0).toUpperCase() + operation.slice(1) + ' completed: ' + name);
      if (state.currentPath) renderSourceFiles(state.currentPath, state.sourceTitle);
      if (state.screen === 'music') scanLibrary('audio');
      else if (state.screen === 'videos') scanLibrary('video');
      else if (state.screen === 'library') scanLibrary('all');
      if (!state.currentPath) refreshSources();
    }).catch(function (error) {
      toast(error.message || 'USB operation failed.');
      showSourceStatus(error.message || 'USB operation failed.', true);
    });
  }

  function showSourceStatus(message, isError) {
    var status = byId('source-status');
    if (!status) return;
    status.textContent = message;
    status.className = 'media-status' + (isError ? ' is-error' : '');
  }
  function showLibraryStatus(message, isError) {
    var status = byId('library-status');
    if (!status) return;
    status.textContent = message;
    status.className = 'media-status' + (isError ? ' is-error' : '');
  }

  function storageLabel(storage) {
    return storage.label || storage.name || 'External storage';
  }
  function sourceButton(label, description, path, glyph) {
    var button = element('button', 'source-entry');
    button.type = 'button';
    button.setAttribute('data-open-path', path);
    button.setAttribute('data-open-title', label);
    button.appendChild(element('span', 'source-entry-icon', glyph || '▣'));
    var copy = element('span', 'source-entry-copy');
    copy.appendChild(element('b', '', label));
    copy.appendChild(element('small', '', description));
    button.appendChild(copy);
    button.appendChild(element('span', 'source-entry-arrow', '›'));
    return button;
  }
  function renderSourceList(storages) {
    var container = byId('source-list');
    if (!container) return;
    clear(container);
    container.hidden = false;
    var removable = storages.filter(function (storage) {
      return storage && storage.state === 'MOUNTED' &&
        (storage.type === 'EXTERNAL' || /^removable_/i.test(storageLabel(storage)));
    });
    if (removable.length) {
      container.appendChild(element('p', 'list-eyebrow', 'CONNECTED STORAGE'));
      removable.forEach(function (storage) {
        var label = storageLabel(storage);
        container.appendChild(sourceButton(label, 'USB device · mounted and ready to browse', label, '▣'));
      });
    } else {
      var empty = element('div', 'source-empty');
      empty.appendChild(element('span', 'empty-symbol', '▣'));
      empty.appendChild(element('b', '', 'No removable drive detected'));
      empty.appendChild(element('p', '', 'Connect a USB drive to the TV and refresh this list. The browser preview cannot detect physical TV storage.'));
      container.appendChild(empty);
    }
    container.appendChild(element('p', 'list-eyebrow standard-locations-label', 'STANDARD TV LOCATIONS'));
    container.appendChild(sourceButton('Music', 'Device music folder', 'music', '♫'));
    container.appendChild(sourceButton('Videos', 'Device video folder', 'videos', '▷'));
    container.appendChild(sourceButton('Downloads', 'Downloaded media and files', 'downloads', '↓'));
    container.appendChild(sourceButton('Documents', 'Documents and subtitles', 'documents', '▤'));
  }

  function refreshSources() {
    var requestToken = ++state.sourceToken;
    var sourceList = byId('source-list');
    var contents = byId('source-contents');
    var backButton = byId('directory-back');
    var refreshButton = byId('refresh-sources');
    if (sourceList) sourceList.hidden = false;
    if (contents) contents.hidden = true;
    if (backButton) backButton.hidden = true;
    if (refreshButton) refreshButton.disabled = false;
    state.currentPath = null;
    state.pathStack = [];
    showSourceStatus('Checking TV storage…', false);
    listStorages().then(function (storages) {
      if (requestToken !== state.sourceToken) return;
      state.storages = storages;
      renderSourceList(storages);
      showSourceStatus('Storage scan complete. Select a source to browse its folders and media.', false);
      focusRefresh(containerFirstButton(sourceList));
    }).catch(function (error) {
      if (requestToken !== state.sourceToken) return;
      state.storages = [];
      renderSourceList([]);
      showSourceStatus(error.message || 'TV storage is unavailable.', true);
      focusRefresh(containerFirstButton(sourceList));
    });
  }
  function containerFirstButton(container) {
    if (!container) return null;
    return container.querySelector('button');
  }

  function renderSourceFiles(path, title) {
    var list = byId('source-contents');
    var sourceList = byId('source-list');
    var backButton = byId('directory-back');
    if (!list) return;
    if (sourceList) sourceList.hidden = true;
    list.hidden = false;
    if (backButton) backButton.hidden = false;
    setText('source-location', title || path);
    clear(list);
    list.appendChild(element('p', 'list-eyebrow', path));
    var loading = element('div', 'media-empty', 'Reading folder contents…');
    list.appendChild(loading);
    showSourceStatus('Reading folder…', false);
    listDirectory(path).then(function (names) {
      if (state.currentPath !== path) return;
      clear(list);
      list.appendChild(element('p', 'list-eyebrow', title || path));
      var folders = [];
      var media = [];
      var maxVisibleEntries = 350;
      var clipped = names.length > maxVisibleEntries;
      names.slice(0, maxVisibleEntries).forEach(function (name) {
        if (!name || name === '.' || name === '..' || name.indexOf('/') >= 0) return;
        var fullPath = joinPath(path, name);
        if (isDirectory(fullPath)) {
          folders.push({ name: name, path: fullPath, kind: 'directory' });
        } else {
          var kind = classify(name);
          if (kind) media.push({ name: name, path: fullPath, uri: fileUri(fullPath), kind: kind });
        }
      });
      folders.sort(byName);
      media.sort(byName);
      if (!folders.length && !media.length) {
        list.appendChild(element('div', 'media-empty', 'No recognized media or document files were found in this folder.'));
      }
      folders.forEach(function (folder) {
        var row = element('div', 'media-row-container');
        var button = element('button', 'media-row folder-row');
        button.type = 'button';
        button.setAttribute('data-open-path', folder.path);
        button.setAttribute('data-open-title', folder.name);
        button.appendChild(element('span', 'media-row-icon', '▰'));
        var copy = element('span', 'media-row-copy');
        copy.appendChild(element('b', '', folder.name));
        copy.appendChild(element('small', '', 'Folder'));
        button.appendChild(copy);
        button.appendChild(element('span', 'media-row-arrow', '›'));
        row.appendChild(button);
        row.appendChild(makeManageButton(folder.path, folder.name, true));
        list.appendChild(row);
      });
      media.forEach(function (item) {
        appendMediaRow(list, item, false);
      });
      var summary = folders.length + ' folders · ' + media.length + ' recognized files. Actual playback and reading depend on the available engine.';
      if (clipped) summary += ' Showing the first ' + maxVisibleEntries + ' of ' + names.length + ' entries; open subfolders to continue.';
      showSourceStatus(summary, false);
      focusRefresh(containerFirstButton(list));
    }).catch(function (error) {
      if (state.currentPath !== path) return;
      clear(list);
      list.appendChild(element('div', 'media-empty is-error', error.message || 'Could not read this folder.'));
      showSourceStatus(error.message || 'Folder could not be read.', true);
      focusRefresh(containerFirstButton(backButton && !backButton.hidden ? backButton.parentNode : list));
    });
  }
  function byName(a, b) {
    return a.name.toLowerCase() < b.name.toLowerCase() ? -1 : (a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0);
  }
  function browseTo(path, label) {
    var safePath = rootPath(path);
    if (!safePath) return;
    state.currentPath = safePath;
    state.sourceTitle = label || safePath;
    state.pathStack.push({ path: safePath, label: state.sourceTitle });
    renderSourceFiles(safePath, state.sourceTitle);
  }
  function backDirectory() {
    if (state.pathStack.length > 1) {
      state.pathStack.pop();
      var previous = state.pathStack[state.pathStack.length - 1];
      state.currentPath = previous.path;
      state.sourceTitle = previous.label;
      renderSourceFiles(previous.path, previous.label);
      return true;
    }
    if (state.pathStack.length === 1) {
      state.pathStack = [];
      state.currentPath = null;
      refreshSources();
      return true;
    }
    return false;
  }

  function appendMediaRow(container, item, isScanResult) {
    var id = String(state.nextId++);
    state.itemMap[id] = item;
    var row = element('div', 'media-row-container');
    var button = element('button', 'media-row');
    button.type = 'button';
    button.setAttribute('data-media-file-id', id);
    var formats = window.MortimerFormats;
    var label = formats ? formats.label(item.kind) : (item.kind === 'audio' ? 'Audio' : 'Video');
    var glyph = formats ? formats.icon(item.kind) : (item.kind === 'audio' ? '♫' : '▷');
    button.appendChild(element('span', 'media-row-icon', glyph));
    var copy = element('span', 'media-row-copy');
    copy.appendChild(element('b', '', item.name));
    copy.appendChild(element('small', '', label + ' · ' + (isScanResult ? item.location || item.path : item.path)));
    button.appendChild(copy);
    button.appendChild(element('span', 'media-row-arrow', formats && formats.isMedia(item.kind) ? '▶' : '›'));
    row.appendChild(button);
    row.appendChild(makeManageButton(item.path, item.name, false));
    container.appendChild(row);
  }

  function renderLibrary(items, kind, message) {
    var list = byId('library-list');
    if (!list) return;
    clear(list);
    var filtered = items.filter(function (item) { return kind === 'all' || item.kind === kind; });
    if (!filtered.length) {
      var empty = element('div', 'media-empty');
      empty.appendChild(element('b', '', kind === 'audio' ? 'No music found yet' : (kind === 'video' ? 'No videos found yet' : 'No supported files found yet')));
      empty.appendChild(element('p', '', 'Connect a USB drive or open Media Sources to browse available folders. Only items the TV filesystem can read are listed.'));
      var browseButton = makeActionButton('Browse media sources', 'open-sources', 'action-primary');
      browseButton.setAttribute('data-screen', 'sources');
      browseButton.removeAttribute('data-media-action');
      empty.appendChild(browseButton);
      list.appendChild(empty);
      showLibraryStatus(message || 'No matching files found. You can browse a specific folder in Media Sources.', false);
      focusRefresh(list.querySelector('button'));
      return;
    }
    filtered.forEach(function (item) { appendMediaRow(list, item, true); });
    showLibraryStatus(message || (filtered.length + ' recognized files found. Codec and reader compatibility varies by format.'), false);
    focusRefresh(containerFirstButton(list));
  }

  async function scanLibrary(kind) {
    if (!apiAvailable()) {
      showLibraryStatus('This preview is running outside Samsung Tizen. USB scanning and playback require the TV runtime or emulator.', true);
      return;
    }
    var token = ++state.scanToken;
    var list = byId('library-list');
    if (list) clear(list);
    showLibraryStatus('Scanning mounted media folders…', false);
    var roots = kind === 'all' ? [
      {path:'music',label:'Music folder',depth:0},{path:'videos',label:'Videos folder',depth:0},
      {path:'documents',label:'Documents folder',depth:0},{path:'downloads',label:'Downloads folder',depth:0}
    ] : [{ path: kind === 'audio' ? 'music' : 'videos', label: kind === 'audio' ? 'Music folder' : 'Videos folder', depth: 0 }];
    try {
      var storages = await listStorages();
      state.storages = storages;
      storages.forEach(function (storage) {
        if (storage && storage.state === 'MOUNTED' &&
            (storage.type === 'EXTERNAL' || /^removable_/i.test(storageLabel(storage)))) {
          roots.push({ path: storageLabel(storage), label: storageLabel(storage), depth: 0 });
        }
      });
      var queue = roots.slice();
      var visited = {};
      var found = [];
      var foundPaths = {};
      var scanned = 0;
      var maxDirectories = 240;
      var maxResults = 1200;
      while (queue.length && scanned < maxDirectories && found.length < maxResults) {
        if (token !== state.scanToken) return;
        var entry = queue.shift();
        var key = rootPath(entry.path);
        if (!key || visited[key]) continue;
        visited[key] = true;
        var names;
        try { names = await listDirectory(key); }
        catch (_) { scanned++; continue; }
        scanned++;
        names.forEach(function (name) {
          if (!name || name === '.' || name === '..' || name.indexOf('/') >= 0) return;
          var path = joinPath(key, name);
          if (isDirectory(path)) {
            if (entry.depth < 5 && !visited[path] && queue.length < 1600) {
              queue.push({ path: path, label: entry.label, depth: entry.depth + 1 });
            }
          } else {
            var itemKind = classify(name);
            if ((kind === 'all' ? !!itemKind : itemKind === kind) && !foundPaths[path] && found.length < maxResults) {
              foundPaths[path] = true;
              found.push({ name: name, path: path, uri: fileUri(path), kind: itemKind, location: entry.label });
            }
          }
        });
        if (scanned % 8 === 0) showLibraryStatus('Scanning… ' + scanned + ' folders checked · ' + found.length + ' candidates.', false);
      }
      if (token !== state.scanToken) return;
      var capNotice = scanned >= maxDirectories || found.length >= maxResults;
      var suffix = ' Scanned ' + scanned + ' folders.';
      if (capNotice) suffix += ' The scan reached its safety limit; browse Media Sources for deeper folders.';
      renderLibrary(found, kind, found.length + ' candidate files found.' + suffix);
    } catch (error) {
      if (token !== state.scanToken) return;
      showLibraryStatus(error.message || 'The TV filesystem API could not scan media.', true);
      if (list) {
        clear(list);
        var empty = element('div', 'media-empty is-error', error.message || 'Media scan failed.');
        list.appendChild(empty);
      }
    }
  }

  function closeReader() {
    var overlay = byId('reader-overlay');
    var content = byId('reader-content');
    if (content) clear(content);
    if (overlay) { overlay.hidden = true; overlay.classList.add('hidden'); }
    focusRefresh();
  }
  function showReaderStatus(message, isError) {
    var status = byId('reader-status');
    if (!status) return;
    status.textContent = message;
    status.className = 'reader-status' + (isError ? ' is-error' : '');
  }
  function readTextFile(path) {
    return new Promise(function (resolve, reject) {
      var api = fsApi();
      if (!api || typeof api.resolve !== 'function') { reject(new Error('Text reading requires the Tizen filesystem API.')); return; }
      api.resolve(path, function (file) {
        if (!file || typeof file.openStream !== 'function') { reject(new Error('This TV filesystem entry cannot be opened as text.')); return; }
        file.openStream('r', function (stream) {
          try {
            var size = Math.min(Number(file.fileSize) || 65536, 250000);
            var text = stream.read(size);
            stream.close();
            resolve({text: text || '', truncated: (Number(file.fileSize) || 0) > size});
          } catch (error) {
            try { stream.close(); } catch (_) {}
            reject(error);
          }
        }, function (error) { reject(new Error(error && error.message || 'Could not read this text file.')); }, 'UTF-8');
      }, function (error) { reject(new Error(error && error.message || 'Could not resolve this file.')); }, 'r');
    });
  }
  function openDocument(item) {
    var formats = window.MortimerFormats;
    var kind = item.kind;
    var ext = formats ? formats.extension(item.name) : extensionOf(item.name);
    var stateInfo = formats ? formats.readerState(kind, ext) : {state:'recognized-only',message:'No reader is available for this format.'};
    var overlay = byId('reader-overlay');
    var content = byId('reader-content');
    if (!overlay || !content) { toast('Document reader UI is unavailable.'); return; }
    state.focusBeforePlayer = document.activeElement;
    clear(content);
    overlay.hidden = false;
    overlay.classList.remove('hidden');
    setText('reader-title', item.name);
    showReaderStatus((formats ? formats.label(kind) : kind) + ' · ' + stateInfo.message, false);
    if (!item.uri) {
      showReaderStatus('The TV did not return a readable URI for this file.', true);
      return;
    }
    if (kind === 'pdf') {
      var frame = element('iframe', 'reader-frame');
      frame.title = item.name;
      frame.src = item.uri;
      content.appendChild(frame);
      showReaderStatus('Opening PDF with the TV browser viewer. If it stays blank, this firmware does not expose a native PDF viewer.', false);
      focusRefresh(byId('reader-close'));
      return;
    }
    if (kind === 'image') {
      var image = element('img', 'reader-image');
      image.alt = item.name;
      image.src = item.uri;
      content.appendChild(image);
      showReaderStatus('Image decoding depends on the Samsung TV WebView.', false);
      return;
    }
    if (kind === 'text' && ['txt','md','markdown','log','csv','tsv','json','xml','yaml','yml','ini','srt','vtt'].indexOf(ext) >= 0) {
      readTextFile(item.path).then(function (result) {
        if (overlay.hidden) return;
        var pre = element('pre', 'reader-text', result.text);
        content.appendChild(pre);
        showReaderStatus('Text file opened.' + (result.truncated ? ' Preview limited to 250 KB to protect TV memory.' : ''), false);
      }).catch(function (error) { showReaderStatus(error.message || 'Could not read this text file.', true); });
      return;
    }
    showReaderStatus(stateInfo.message, true);
    var note = element('div', 'reader-unavailable');
    note.appendChild(element('h3', '', 'Reader engine required'));
    note.appendChild(element('p', '', stateInfo.message));
    note.appendChild(element('p', '', 'The file is recognized and remains visible in the library; MUGEN will not pretend it was opened successfully.'));
    content.appendChild(note);
  }

  function ensureWebApis() {
    if (window.webapis && window.webapis.avplay) return Promise.resolve(window.webapis);
    if (!window.tizen) return Promise.reject(new Error('AVPlay is available only inside a Samsung Tizen TV app or compatible emulator.'));
    return Promise.reject(new Error('Samsung WebAPI did not initialize. Check the TV WebAPI library and firmware compatibility.'));
  }

  function playerStatus(message, isError) {
    setText('player-status', message);
    var status = byId('player-status');
    if (status) status.className = 'player-status' + (isError ? ' is-error' : '');
  }
  function overlayVisible() {
    var overlay = byId('player-overlay');
    return !!(overlay && !overlay.hidden && !overlay.classList.contains('hidden'));
  }
  function showPlayerShell(item) {
    var overlay = byId('player-overlay');
    if (!overlay) return;
    state.focusBeforePlayer = document.activeElement;
    overlay.hidden = false;
    overlay.classList.remove('hidden');
    setText('player-title', item.name);
    setText('player-path', item.location || item.path || '');
    setText('audio-art-title', item.name);
    setText('player-kind', item.kind === 'audio' ? 'NOW PLAYING · AUDIO' : 'NOW PLAYING · VIDEO');
    var audioArt = byId('audio-player-art');
    var stage = byId('avplay-stage');
    if (audioArt) audioArt.hidden = item.kind !== 'audio';
    if (stage) stage.hidden = item.kind !== 'video';
    playerStatus('Preparing media…', false);
    var playButton = byId('player-play-pause');
    if (playButton) playButton.textContent = 'Ⅱ  Pause';
    setText('player-time-current', '00:00');
    setText('player-time-duration', '--:--');
    var seek = byId('playback-seek');
    if (seek) seek.value = '0';
    focusRefresh(byId('player-play-pause'));
  }
  function syncDisplayRect() {
    var avplay = window.webapis && window.webapis.avplay;
    var stage = byId('avplay-stage');
    if (!avplay || !stage || stage.hidden || !avplay.setDisplayRect) return;
    var rect = stage.getBoundingClientRect();
    var scaleX = 1920 / Math.max(1, window.innerWidth);
    var scaleY = 1080 / Math.max(1, window.innerHeight);
    try {
      avplay.setDisplayRect(
        Math.max(0, Math.round(rect.left * scaleX)),
        Math.max(0, Math.round(rect.top * scaleY)),
        Math.max(1, Math.round(rect.width * scaleX)),
        Math.max(1, Math.round(rect.height * scaleY))
      );
    } catch (_) {}
  }
  function formatTime(milliseconds) {
    var total = Math.max(0, Math.floor((milliseconds || 0) / 1000));
    var hours = Math.floor(total / 3600);
    var minutes = Math.floor((total % 3600) / 60);
    var seconds = total % 60;
    var core = (minutes < 10 ? '0' : '') + minutes + ':' + (seconds < 10 ? '0' : '') + seconds;
    return hours ? hours + ':' + core : core;
  }
  function updateProgress() {
    var avplay = window.webapis && window.webapis.avplay;
    if (!avplay || !state.currentItem) return;
    try {
      var current = avplay.getCurrentTime();
      var duration = avplay.getDuration();
      if (isFinite(current) && current >= 0) setText('player-time-current', formatTime(current));
      if (isFinite(duration) && duration > 0) {
        setText('player-time-duration', formatTime(duration));
        var seek = byId('playback-seek');
        if (seek) seek.value = String(Math.round(Math.min(1, current / duration) * 1000));
      }
    } catch (_) {}
  }
  function startProgressTimer() {
    if (state.progressTimer) window.clearInterval(state.progressTimer);
    state.progressTimer = window.setInterval(updateProgress, 1000);
  }
  function stopProgressTimer() {
    if (state.progressTimer) window.clearInterval(state.progressTimer);
    state.progressTimer = null;
  }
  function stopAvplay(close) {
    var avplay = window.webapis && window.webapis.avplay;
    if (!avplay) return;
    try { avplay.stop(); } catch (_) {}
    if (close !== false) {
      try { avplay.close(); } catch (_) {}
    }
  }
  function playItem(item) {
    if (!item || !item.uri) {
      toast('The TV did not return a playable path for this file.');
      return;
    }
    state.currentItem = item;
    state.playing = false;
    showPlayerShell(item);
    ensureWebApis().then(function (webapis) {
      var avplay = webapis.avplay;
      stopProgressTimer();
      stopAvplay(true);
      try {
        avplay.open(item.uri);
        avplay.setListener({
          onbufferingstart: function () { playerStatus('Buffering…', false); },
          onbufferingcomplete: function () { playerStatus('Playing', false); },
          onstreamcompleted: function () {
            state.playing = false;
            stopProgressTimer();
            stopAvplay(true);
            playerStatus('Playback finished.', false);
            var playButton = byId('player-play-pause');
            if (playButton) playButton.textContent = '▶  Play';
          },
          onerror: function (error) {
            state.playing = false;
            stopProgressTimer();
            playerStatus('Playback error: ' + safeText(error || 'unsupported or unreadable media'), true);
          },
          onerrormsg: function (message) {
            playerStatus('Playback error: ' + safeText(message), true);
          }
        });
        if (item.kind === 'video') syncDisplayRect();
        avplay.prepareAsync(function () {
          try {
            avplay.play();
            state.playing = true;
            playerStatus('Playing. If the TV rejects this file, try another format.', false);
            var playButton = byId('player-play-pause');
            if (playButton) playButton.textContent = 'Ⅱ  Pause';
            startProgressTimer();
            focusRefresh(byId('player-play-pause'));
          } catch (error) {
            playerStatus('The TV could not start playback: ' + safeText(error.message || error), true);
          }
        }, function (error) {
          playerStatus('The TV could not prepare this file. Its container or codec may not be supported.', true);
        });
      } catch (error) {
        playerStatus('Could not open this media: ' + safeText(error.message || error), true);
      }
    }).catch(function (error) {
      playerStatus(error.message || 'Samsung AVPlay is unavailable.', true);
    });
  }
  function togglePlayback() {
    var avplay = window.webapis && window.webapis.avplay;
    if (!avplay || !state.currentItem) {
      toast('Choose a file before starting playback.');
      return;
    }
    try {
      if (avplay.getState && avplay.getState() === 'PLAYING') {
        avplay.pause();
        state.playing = false;
        playerStatus('Paused', false);
        var button = byId('player-play-pause');
        if (button) button.textContent = '▶  Play';
      } else {
        avplay.play();
        state.playing = true;
        playerStatus('Playing', false);
        var playButton = byId('player-play-pause');
        if (playButton) playButton.textContent = 'Ⅱ  Pause';
        startProgressTimer();
      }
    } catch (error) {
      playerStatus('Playback control failed: ' + safeText(error.message || error), true);
    }
  }
  function seekBy(seconds) {
    var avplay = window.webapis && window.webapis.avplay;
    if (!avplay || !state.currentItem || !avplay.seekTo) return;
    try {
      var current = avplay.getCurrentTime();
      var duration = avplay.getDuration();
      var target = Math.max(0, Math.min(duration > 0 ? duration : current + seconds * 1000, current + seconds * 1000));
      avplay.seekTo(target, function () { updateProgress(); }, function () {});
    } catch (_) {}
  }
  function seekToPercent(percent) {
    var avplay = window.webapis && window.webapis.avplay;
    if (!avplay || !state.currentItem || !avplay.seekTo) return;
    try {
      var duration = avplay.getDuration();
      if (duration > 0) avplay.seekTo(Math.max(0, Math.min(duration, duration * percent)), function () { updateProgress(); }, function () {});
    } catch (_) {}
  }
  function closePlayer() {
    stopProgressTimer();
    stopAvplay(true);
    state.currentItem = null;
    state.playing = false;
    var overlay = byId('player-overlay');
    if (overlay) {
      overlay.classList.add('hidden');
      overlay.hidden = true;
    }
    playerStatus('Player closed', false);
    var restoreFocus = state.focusBeforePlayer;
    state.focusBeforePlayer = null;
    focusRefresh(restoreFocus && restoreFocus.offsetWidth > 0 ? restoreFocus : null);
  }
  function handleMediaKey(key, code) {
    if (!state.currentItem) return false;
    if (key === 'MediaPlay' || key === 'MediaPlayPause' || key === 'MediaPause' || code === 10252) {
      togglePlayback();
      return true;
    }
    if (key === 'MediaStop') { closePlayer(); return true; }
    if (key === 'MediaFastForward') { seekBy(30); return true; }
    if (key === 'MediaRewind') { seekBy(-10); return true; }
    return false;
  }

  document.addEventListener('click', function (event) {
    var target = event.target;
    while (target && target !== document && !target.getAttribute('data-media-action') &&
           !target.getAttribute('data-open-path') && !target.getAttribute('data-media-file-id') &&
           !target.getAttribute('data-manage-path')) {
      target = target.parentNode;
    }
    if (!target || target === document) return;
    var action = target.getAttribute('data-media-action');
    if (action === 'refresh-sources') { refreshSources(); return; }
    if (action === 'back-directory') { backDirectory(); return; }
    if (action === 'scan-library') { scanLibrary(target.getAttribute('data-media-kind') || 'audio'); return; }
    if (action === 'open-sources') {
      var navSources = document.querySelector('.nav-item[data-screen="sources"]');
      if (navSources) navSources.click();
      else toast('Open Media Sources from the navigation rail.');
      return;
    }
    if (action === 'player-close') { closePlayer(); return; }
    if (action === 'reader-close') { closeReader(); return; }
    if (action === 'player-toggle') { togglePlayback(); return; }
    if (action === 'player-stop') { closePlayer(); return; }
    if (action === 'player-backward') { seekBy(-10); return; }
    if (action === 'player-forward') { seekBy(30); return; }
    if (target.hasAttribute('data-manage-path')) {
      manageMedia(
        target.getAttribute('data-manage-path'),
        target.getAttribute('data-manage-name') || 'selected item',
        target.getAttribute('data-manage-directory') === 'true'
      );
      return;
    }
    if (target.hasAttribute('data-open-path')) {
      browseTo(target.getAttribute('data-open-path'), target.getAttribute('data-open-title'));
      return;
    }
    if (target.hasAttribute('data-media-file-id')) {
      var item = state.itemMap[target.getAttribute('data-media-file-id')];
      if (item) {
        var formats = window.MortimerFormats;
        if (formats && formats.isMedia(item.kind)) playItem(item);
        else openDocument(item);
      }
    }
  });

  document.addEventListener('change', function (event) {
    if (event.target && event.target.id === 'playback-seek') {
      seekToPercent(Number(event.target.value) / 1000);
    }
  });

  document.addEventListener('keydown', function (event) {
    var key = event.key;
    var code = event.keyCode || event.which;
    if (key === 'Escape' || key === 'Backspace' || code === 10009) {
      if (byId('reader-overlay') && !byId('reader-overlay').hidden) {
        event.preventDefault(); event.stopPropagation();
        if (event.stopImmediatePropagation) event.stopImmediatePropagation();
        closeReader(); return;
      }
      if (overlayVisible()) {
        event.preventDefault();
        event.stopPropagation();
        if (event.stopImmediatePropagation) event.stopImmediatePropagation();
        closePlayer();
        return;
      }
      if (state.screen === 'sources' && state.pathStack.length) {
        event.preventDefault();
        event.stopPropagation();
        if (event.stopImmediatePropagation) event.stopImmediatePropagation();
        backDirectory();
        return;
      }
      return;
    }
    if (!overlayVisible()) return;
    if (key === 'MediaPlay' || key === 'MediaPause' || key === 'MediaPlayPause' ||
        key === 'MediaStop' || key === 'MediaFastForward' || key === 'MediaRewind' || code === 10252) {
      if (handleMediaKey(key, code)) {
        event.preventDefault();
        event.stopPropagation();
        if (event.stopImmediatePropagation) event.stopImmediatePropagation();
      }
    }
  }, true);

  window.addEventListener('resize', function () {
    if (state.currentItem && state.currentItem.kind === 'video') syncDisplayRect();
  });

  window.MortimerTVMedia = {
    onScreen: function (key) {
      state.screen = key;
      if (key !== 'sources') state.sourceToken++;
      if (key !== 'music' && key !== 'videos') state.scanToken++;
      if (key === 'sources') refreshSources();
      else if (key === 'music') scanLibrary('audio');
      else if (key === 'videos') scanLibrary('video');
      else if (key === 'library') scanLibrary('all');
    },
    refreshSources: refreshSources,
    scanLibrary: scanLibrary,
    play: playItem,
    stop: closePlayer,
    togglePlayback: togglePlayback,
    handleMediaKey: handleMediaKey,
    overlayVisible: overlayVisible,
    openDocument: openDocument
  };
})();