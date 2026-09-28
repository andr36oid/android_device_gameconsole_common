'use strict';
// Wi-Fi transfer page: sign in with the console's PIN, browse its storage, send files.
// Plain browser JavaScript, no libraries; the console serves it from the app's assets.

const $ = (id) => document.getElementById(id);
const CSRF = { 'X-Requested-With': 'WifiTransfer' };

const ICONS = {
  folder: 'M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
  file: 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z',
  game: 'M21.58 16.09l-1.09-7.66C20.21 6.46 18.52 5 16.53 5H7.47C5.48 5 3.79 6.46 3.51 8.43l-1.09 7.66C2.2 17.63 3.39 19 4.94 19c.68 0 1.32-.27 1.8-.75L9 16h6l2.25 2.25c.48.48 1.13.75 1.8.75 1.56 0 2.75-1.37 2.53-2.91zM11 11H9v2H8v-2H6v-1h2V8h1v2h2v1zm4-1c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm2 3c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z',
  disc: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z',
  archive: 'M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  rename: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  delete: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  ok: 'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
  fail: 'M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z',
};
const GAME_EXT = /\.(sfc|smc|nes|fds|gb|gbc|gba|nds|n64|z64|v64|md|gen|smd|sms|gg|pce|ngp|ngc|ws|wsc|a26|a78|lnx|32x|sg|col|vb|pbp|cso|chd|rvz|wbfs)$/i;
const DISC_EXT = /\.(iso|bin|cue|img|mdf|mds|ccd|gdi|m3u)$/i;
const ARCHIVE_EXT = /\.(zip|7z|rar|gz|xz|tar)$/i;

let roots = [];
let rootId = null;
let path = '';
let free = 0;
let total = 0;
let entries = [];

// ---------- helpers ----------

function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', ICONS[name]);
  svg.appendChild(p);
  return svg;
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function size(bytes) {
  if (bytes < 1024) return bytes + ' B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let u = -1;
  do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
  return (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + ' ' + units[u];
}

function duration(secs) {
  if (!isFinite(secs) || secs < 0) return '';
  if (secs < 60) return Math.max(1, Math.round(secs)) + ' s';
  if (secs < 3600) return Math.round(secs / 60) + ' min';
  return Math.floor(secs / 3600) + ' h ' + Math.round((secs % 3600) / 60) + ' min';
}

function join(a, b) {
  return a ? a + '/' + b : b;
}

function q(params) {
  return Object.keys(params).map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(params[k])).join('&');
}

let toastTimer = 0;
function toast(text) {
  const t = $('toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
}

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function api(method, url, body) {
  const opts = { method, credentials: 'same-origin', headers: {} };
  if (method !== 'GET') Object.assign(opts.headers, CSRF);
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    opts.body = body;
  }
  let res;
  try {
    res = await fetch(url, opts);
  } catch (e) {
    throw new ApiError(0, 'The console can’t be reached. Is Wi-Fi transfer still on?');
  }
  let data = {};
  try { data = await res.json(); } catch (e) { /* empty */ }
  if (res.status === 401 && !url.startsWith('/api/login')) {
    showLogin('The console started a new session. Enter the PIN shown on its screen.');
  }
  if (!res.ok) throw new ApiError(res.status, data.error || ('Error ' + res.status));
  return data;
}

// ---------- sign in ----------

function showLogin(message) {
  $('app').hidden = true;
  $('queue').hidden = true;
  $('login').hidden = false;
  $('login-error').textContent = message || '';
  $('pin').value = '';
  $('pin').focus();
}

async function login(pin) {
  $('login-error').textContent = '';
  try {
    await api('POST', '/api/login', 'pin=' + encodeURIComponent(pin));
    showApp();
  } catch (e) {
    $('login-error').textContent = e.message;
    $('pin').value = '';
    $('pin').focus();
  }
}

$('login-form').addEventListener('submit', (e) => {
  e.preventDefault();
  login($('pin').value);
});
$('pin').addEventListener('input', () => {
  const v = $('pin').value.replace(/\D/g, '');
  $('pin').value = v;
  if (v.length === 4) login(v);
});

// ---------- browsing ----------

async function showApp() {
  $('login').hidden = true;
  $('app').hidden = false;
  try {
    roots = (await api('GET', '/api/roots')).roots;
  } catch (e) {
    toast(e.message);
    return;
  }
  renderRoots();
  const fromHash = parseHash();
  if (fromHash && roots.some((r) => r.id === fromHash.root)) {
    open(fromHash.root, fromHash.path, false);
  } else if (roots.length) {
    open(roots[0].id, '', true);
  } else {
    $('list').replaceChildren();
    $('empty').hidden = false;
    $('empty').textContent = 'No storage found on the console. Is the SD card in?';
  }
}

function parseHash() {
  const h = location.hash.replace(/^#\/?/, '');
  if (!h || h.startsWith('pin=')) return null;
  const parts = h.split('/').map(decodeURIComponent);
  return { root: parts[0], path: parts.slice(1).filter(Boolean).join('/') };
}

function renderRoots() {
  const nav = $('roots');
  nav.replaceChildren();
  if (roots.length < 2) return;
  for (const r of roots) {
    const b = el('button', null, r.name);
    b.type = 'button';
    b.setAttribute('aria-current', r.id === rootId ? 'true' : 'false');
    b.addEventListener('click', () => open(r.id, '', true));
    nav.appendChild(b);
  }
}

function rootName(id) {
  const r = roots.find((x) => x.id === id);
  return r ? r.name : id;
}

async function open(id, p, push) {
  let data;
  try {
    data = await api('GET', '/api/list?' + q({ root: id, path: p }));
  } catch (e) {
    if (e.status !== 401) {
      toast(e.message);
      if (p) open(id, '', true);
    }
    return;
  }
  rootId = id;
  path = p;
  entries = data.entries;
  free = data.free;
  total = data.total;
  const hash = '#/' + [id].concat(p ? p.split('/') : []).map(encodeURIComponent).join('/');
  if (location.hash !== hash) {
    if (push) history.pushState(null, '', hash); else history.replaceState(null, '', hash);
  }
  renderRoots();
  renderCrumbs();
  renderList();
}

async function refresh(highlight) {
  if (rootId === null) return;
  try {
    const data = await api('GET', '/api/list?' + q({ root: rootId, path }));
    entries = data.entries;
    free = data.free;
    total = data.total;
    renderList(highlight);
  } catch (e) { /* shown elsewhere */ }
}

window.addEventListener('popstate', () => {
  const h = parseHash();
  if (h && !$('app').hidden) open(h.root, h.path, false);
});

function renderCrumbs() {
  const nav = $('crumbs');
  nav.replaceChildren();
  const parts = path ? path.split('/') : [];
  const add = (label, target) => {
    const b = el('button', null, label);
    b.type = 'button';
    b.addEventListener('click', () => open(rootId, target, true));
    nav.appendChild(b);
  };
  add(rootName(rootId), '');
  parts.forEach((part, i) => {
    nav.appendChild(el('span', 'sep', '›'));
    add(part, parts.slice(0, i + 1).join('/'));
  });

  const used = total > 0 ? (total - free) / total : 0;
  $('space-used').style.width = Math.round(used * 100) + '%';
  $('space-used').classList.toggle('full', used > 0.95);
  $('space-text').textContent = size(free) + ' free';

  const hint = $('hint');
  if (rootId === 'easyroms' && !path) {
    hint.textContent = 'Tip: open the folder for the game’s system (snes, gba, psx…) and send the games there, so the emulators find them.';
    hint.hidden = false;
  } else {
    hint.hidden = true;
  }
}

function fileIcon(name) {
  if (GAME_EXT.test(name)) return 'game';
  if (DISC_EXT.test(name)) return 'disc';
  if (ARCHIVE_EXT.test(name)) return 'archive';
  return 'file';
}

function renderList(highlight) {
  renderCrumbs();
  const list = $('list');
  list.replaceChildren();
  $('empty').hidden = entries.length > 0;
  $('empty').textContent = 'This folder is empty. Drop files here or use Send files.';
  const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
  for (const entry of entries) {
    const li = el('li', entry.dir ? 'dir' : 'file');
    if (highlight && highlight.has(entry.name)) li.classList.add('fresh');
    const ic = icon(entry.dir ? 'folder' : fileIcon(entry.name));
    ic.classList.add('icon');
    li.appendChild(ic);
    const info = el('div', 'info');
    info.appendChild(el('div', 'name', entry.name));
    const meta = el('div', 'meta');
    if (!entry.dir) meta.appendChild(el('span', null, size(entry.size)));
    if (entry.mtime > 0) {
      meta.appendChild(el('span', 'date', (entry.dir ? '' : ' · ') + dateFmt.format(new Date(entry.mtime))));
    }
    info.appendChild(meta);
    li.appendChild(info);

    const tools = el('div', 'tools');
    const target = join(path, entry.name);
    if (!entry.dir) {
      const a = el('a');
      a.href = '/api/download?' + q({ root: rootId, path: target });
      a.setAttribute('download', entry.name);
      a.title = 'Download';
      a.setAttribute('aria-label', 'Download ' + entry.name);
      a.appendChild(icon('download'));
      a.addEventListener('click', (e) => e.stopPropagation());
      tools.appendChild(a);
    }
    const ren = el('button');
    ren.type = 'button';
    ren.title = 'Rename';
    ren.setAttribute('aria-label', 'Rename ' + entry.name);
    ren.appendChild(icon('rename'));
    ren.addEventListener('click', (e) => { e.stopPropagation(); rename(entry); });
    tools.appendChild(ren);
    const del = el('button', 'delete');
    del.type = 'button';
    del.title = 'Delete';
    del.setAttribute('aria-label', 'Delete ' + entry.name);
    del.appendChild(icon('delete'));
    del.addEventListener('click', (e) => { e.stopPropagation(); remove(entry); });
    tools.appendChild(del);
    li.appendChild(tools);

    if (entry.dir) li.addEventListener('click', () => open(rootId, target, true));
    list.appendChild(li);
  }
}

// ---------- folder actions ----------

$('new-folder').addEventListener('click', async () => {
  const name = (prompt('Name of the new folder') || '').trim();
  if (!name) return;
  try {
    await api('POST', '/api/mkdir?' + q({ root: rootId, path: join(path, name) }));
    await refresh(new Set([name]));
  } catch (e) {
    alert(e.message);
  }
});

async function rename(entry) {
  const to = (prompt('New name', entry.name) || '').trim();
  if (!to || to === entry.name) return;
  try {
    await api('POST', '/api/rename?' + q({ root: rootId, path: join(path, entry.name), to }));
    await refresh(new Set([to]));
  } catch (e) {
    alert(e.message);
  }
}

async function remove(entry) {
  const what = entry.dir
    ? 'Delete the folder “' + entry.name + '” and everything in it?'
    : 'Delete “' + entry.name + '”?';
  if (!confirm(what + '\n\nThis can’t be undone.')) return;
  try {
    await api('POST', '/api/delete?' + q({ root: rootId, path: join(path, entry.name) }));
    await refresh();
    toast('Deleted ' + entry.name);
  } catch (e) {
    alert(e.message);
  }
}

// ---------- sending files ----------

const queue = [];
let running = false;
let current = null; // { item, xhr }
let samples = [];   // [time, bytes sent in total] for the speed

$('pick-files').addEventListener('change', (e) => {
  add(Array.from(e.target.files).map((f) => ({ file: f, name: f.name })));
  e.target.value = '';
});
$('pick-folder').addEventListener('change', (e) => {
  add(Array.from(e.target.files).map((f) => ({ file: f, name: f.webkitRelativePath || f.name })));
  e.target.value = '';
});
// Folder picking isn't there on most phones
if (!('webkitdirectory' in document.createElement('input')) || /Android|iPhone|iPad/.test(navigator.userAgent)) {
  $('pick-folder-label').hidden = true;
}

function add(files) {
  if (!files.length || rootId === null) return;
  const bytes = files.reduce((s, f) => s + f.file.size, 0);
  if (bytes > free && !confirm('These files need ' + size(bytes) + ' but only ' + size(free) +
      ' is free here. Send them anyway? The ones that don’t fit will fail.')) {
    return;
  }
  // Finished lists are cleared when a new batch starts
  if (!running) {
    for (let i = queue.length - 1; i >= 0; i--) {
      if (queue[i].state !== 'wait') queue.splice(i, 1);
    }
  }
  for (const f of files) {
    queue.push({
      file: f.file, name: f.name, root: rootId, dir: path, size: f.file.size,
      sent: 0, state: 'wait', error: '', overwrite: false,
    });
  }
  renderQueue();
  if (!running) run();
}

async function run() {
  running = true;
  samples = [];
  document.body.classList.add('queue-open');
  let item;
  while ((item = queue.find((i) => i.state === 'wait'))) {
    await send(item);
    renderQueue();
  }
  running = false;
  current = null;
  finished();
}

function send(item) {
  return new Promise((resolve) => {
    item.state = 'up';
    item.sent = 0;
    const xhr = new XMLHttpRequest();
    current = { item, xhr };
    xhr.open('PUT', '/api/upload?' + q({
      root: item.root, dir: item.dir, name: item.name, overwrite: item.overwrite ? 1 : 0,
    }));
    xhr.setRequestHeader('X-Requested-With', 'WifiTransfer');
    xhr.upload.onprogress = (e) => {
      item.sent = e.loaded;
      tick();
    };
    xhr.onload = () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch (e) { /* empty */ }
      if (xhr.status === 200) {
        item.state = 'ok';
        item.sent = item.size;
        if (item.root === rootId && item.dir === path) refreshSoon(item.name.split('/')[0]);
      } else if (xhr.status === 409 && data.error === 'exists') {
        item.state = 'exists';
      } else if (xhr.status === 401) {
        item.state = 'wait';
        cancelAll();
        showLogin('The console started a new session. Enter the PIN shown on its screen.');
      } else {
        item.state = 'failed';
        item.error = data.error || ('Error ' + xhr.status);
      }
      resolve();
    };
    xhr.onerror = () => {
      item.state = 'failed';
      item.error = 'Connection lost';
      resolve();
    };
    xhr.onabort = () => {
      item.state = 'cancelled';
      resolve();
    };
    xhr.send(item.file);
  });
}

let refreshTimer = 0;
const freshNames = new Set();
function refreshSoon(name) {
  freshNames.add(name);
  if (refreshTimer) return;
  refreshTimer = setTimeout(() => {
    refreshTimer = 0;
    const names = new Set(freshNames);
    freshNames.clear();
    refresh(names);
  }, 1200);
}

function cancelAll() {
  for (const i of queue) if (i.state === 'wait') i.state = 'cancelled';
  if (current && current.item.state === 'up') current.xhr.abort();
}

$('queue-cancel').addEventListener('click', () => {
  if (confirm('Stop sending? Files already sent stay on the console.')) cancelAll();
});
$('queue-close').addEventListener('click', () => {
  $('queue').hidden = true;
  document.body.classList.remove('queue-open');
  queue.length = 0;
});
$('queue-retry').addEventListener('click', () => {
  for (const i of queue) if (i.state === 'failed' || i.state === 'cancelled') { i.state = 'wait'; i.error = ''; }
  renderQueue();
  if (!running) run();
});

function finished() {
  const exists = queue.filter((i) => i.state === 'exists');
  if (exists.length) {
    const names = exists.slice(0, 8).map((i) => '• ' + i.name).join('\n') +
      (exists.length > 8 ? '\n… and ' + (exists.length - 8) + ' more' : '');
    const one = exists.length === 1;
    if (confirm((one ? 'This file is' : exists.length + ' files are') +
        ' already on the console:\n\n' + names + '\n\nReplace ' + (one ? 'it' : 'them') + '?')) {
      for (const i of exists) { i.state = 'wait'; i.overwrite = true; }
      renderQueue();
      run();
      return;
    }
    for (const i of exists) { i.state = 'skipped'; }
  }
  renderQueue();
  const ok = queue.filter((i) => i.state === 'ok');
  if (ok.length) {
    const where = rootName(ok[0].root) + (ok[0].dir ? '/' + ok[0].dir : '');
    toast((ok.length === 1 ? '1 file' : ok.length + ' files') + ' sent to ' + where);
  }
  refresh();
}

function tick() {
  const now = performance.now();
  const sent = queue.reduce((s, i) => s + (i.state === 'ok' ? i.size : i.state === 'up' ? i.sent : 0), 0);
  samples.push([now, sent]);
  while (samples.length > 2 && now - samples[0][0] > 4000) samples.shift();
  renderQueue();
}

function speed() {
  if (samples.length < 2) return 0;
  const [t0, b0] = samples[0];
  const [t1, b1] = samples[samples.length - 1];
  return t1 > t0 ? (b1 - b0) * 1000 / (t1 - t0) : 0;
}

let lastQueueRender = 0;
function renderQueue() {
  const now = performance.now();
  // Progress events come fast; the list is redrawn at most a few times a second
  if (running && current && current.item.state === 'up' && now - lastQueueRender < 200) {
    updateBar();
    return;
  }
  lastQueueRender = now;
  const box = $('queue');
  if (!queue.length) { box.hidden = true; return; }
  box.hidden = false;
  updateBar();

  const list = $('queue-list');
  list.replaceChildren();
  // Running and failed ones first, then waiting, then done
  const order = { up: 0, failed: 1, exists: 2, wait: 3, cancelled: 4, skipped: 5, ok: 6 };
  const shown = queue.slice().sort((a, b) => order[a.state] - order[b.state]).slice(0, 200);
  for (const i of shown) {
    const li = el('li', i.state);
    if (i.state === 'ok') li.appendChild(icon('ok'));
    else if (i.state === 'failed') li.appendChild(icon('fail'));
    else li.appendChild(icon(fileIcon(i.name)));
    li.appendChild(el('span', 'qname', i.name));
    let state = size(i.size);
    if (i.state === 'up') state = Math.floor(i.size ? i.sent * 100 / i.size : 100) + '%  ·  ' + size(i.size);
    else if (i.state === 'failed') state = i.error;
    else if (i.state === 'exists') state = 'Already there';
    else if (i.state === 'cancelled') state = 'Not sent';
    else if (i.state === 'skipped') state = 'Kept the one on the console';
    li.appendChild(el('span', 'qstate', state));
    list.appendChild(li);
  }
}

function updateBar() {
  const all = queue.filter((i) => i.state !== 'skipped' && i.state !== 'cancelled');
  const bytes = all.reduce((s, i) => s + i.size, 0);
  const sent = all.reduce((s, i) => s + (i.state === 'ok' ? i.size : i.state === 'up' ? i.sent : 0), 0);
  const done = queue.filter((i) => i.state === 'ok').length;
  const failed = queue.filter((i) => i.state === 'failed').length;
  const cancelled = queue.filter((i) => i.state === 'cancelled').length;
  const pct = bytes ? sent * 100 / bytes : (running ? 0 : 100);
  $('queue-bar').style.width = pct.toFixed(1) + '%';
  $('queue-bar').classList.toggle('done', !running && !failed);

  if (running) {
    const s = speed();
    const left = s > 0 ? duration((bytes - sent) / s) + ' left' : '';
    const n = done + 1;
    $('queue-title').textContent = 'Sending ' + Math.min(n, all.length) + ' of ' + all.length + '…';
    $('queue-sub').textContent = Math.floor(pct) + '%  ·  ' + size(sent) + ' of ' + size(bytes) +
      (s > 0 ? '  ·  ' + size(s) + '/s  ·  ' + left : '');
  } else {
    $('queue-title').textContent = failed
      ? done + ' sent, ' + failed + ' failed'
      : (done === 1 ? '1 file sent' : done + ' files sent');
    $('queue-sub').textContent = size(queue.filter((i) => i.state === 'ok').reduce((s, i) => s + i.size, 0)) +
      (cancelled ? '  ·  ' + cancelled + ' not sent' : '');
  }
  $('queue-cancel').hidden = !running;
  $('queue-close').hidden = running;
  $('queue-retry').hidden = running || !(failed || cancelled);
}

window.addEventListener('beforeunload', (e) => {
  if (running) {
    e.preventDefault();
    e.returnValue = '';
  }
});

// ---------- drag and drop, folders included ----------

let dragDepth = 0;
function hasFiles(e) {
  return e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
}
document.addEventListener('dragenter', (e) => {
  if (!hasFiles(e) || $('app').hidden) return;
  e.preventDefault();
  dragDepth++;
  $('drop-where').textContent = rootName(rootId) + (path ? '/' + path : '');
  $('drop').hidden = false;
});
document.addEventListener('dragover', (e) => {
  if (hasFiles(e)) e.preventDefault();
});
document.addEventListener('dragleave', () => {
  if (--dragDepth <= 0) { dragDepth = 0; $('drop').hidden = true; }
});
document.addEventListener('drop', async (e) => {
  if (!hasFiles(e)) return;
  e.preventDefault();
  dragDepth = 0;
  $('drop').hidden = true;
  if ($('app').hidden) return;
  const items = Array.from(e.dataTransfer.items || []);
  const entriesDropped = items.map((i) => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
  if (entriesDropped.length) {
    const files = [];
    for (const entry of entriesDropped) await walk(entry, '', files);
    add(files);
  } else {
    add(Array.from(e.dataTransfer.files).map((f) => ({ file: f, name: f.name })));
  }
});

async function walk(entry, prefix, out) {
  if (entry.isFile) {
    const file = await new Promise((res, rej) => entry.file(res, rej)).catch(() => null);
    if (file) out.push({ file, name: prefix + file.name });
    return;
  }
  if (!entry.isDirectory) return;
  const reader = entry.createReader();
  for (;;) {
    // readEntries returns the folder in chunks
    const chunk = await new Promise((res) => reader.readEntries(res, () => res([])));
    if (!chunk.length) break;
    for (const child of chunk) await walk(child, prefix + entry.name + '/', out);
  }
}

// ---------- start ----------

// The QR code on the console carries the PIN after the #, so scanning signs in
async function pinFromHash() {
  const m = location.hash.match(/pin=(\d{4})/);
  if (!m) return false;
  history.replaceState(null, '', location.pathname);
  $('login').hidden = false;
  await login(m[1]);
  return true;
}
window.addEventListener('hashchange', pinFromHash);

(async function start() {
  if (await pinFromHash()) return;
  try {
    const s = await api('GET', '/api/session');
    if (s.signedIn) { showApp(); return; }
  } catch (e) { /* show the sign in */ }
  showLogin();
})();
