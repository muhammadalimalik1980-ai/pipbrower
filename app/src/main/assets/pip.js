(() => {
  'use strict';
  if (window.__pip) return;
  const A = window.AndroidPip;
  const L = m => { try { A && A.log && A.log(m); } catch (e) {} };
  const sleep = t => new Promise(r => setTimeout(r, t));
  let userPaused = false, holdUntil = 0, sound = false;
  let cv = null, raf = 0, on = false;

  // make the page believe it is always visible/focused (sites pause video when "hidden")
  try {
    const def = (o, k, v) => Object.defineProperty(o, k, { configurable: true, get: () => v });
    def(document, 'hidden', false);
    def(document, 'visibilityState', 'visible');
    def(document, 'webkitHidden', false);
    def(document, 'webkitVisibilityState', 'visible');
    document.hasFocus = () => true;
  } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange'].forEach(t =>
    window.addEventListener(t, e => e.stopImmediatePropagation(), true));

  const area = v => {
    const r = v.getBoundingClientRect();
    const w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
    const h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
    return w * h;
  };
  let lastV = null;
  const best = () => {
    let top = null, score = 0;
    for (const v of document.querySelectorAll('video')) {
      const s = area(v) + (v.paused ? 0 : 1e7);
      if (s > score) { score = s; top = v; }
    }
    if (top) lastV = top;
    return top;
  };

  const SHORT = /youtube\.com\/shorts|tiktok\.com|instagram\.com\/(reels?|stories)|facebook\.com\/(reels?|stories)|snapchat\.com\/spotlight/;
  const isShort = v => SHORT.test(location.href) || !!(v && v.videoHeight > v.videoWidth);
  const click = sel => { const e = document.querySelector(sel); if (e) { e.click(); return true; } return false; };
  const ADAPT = [
    { re: /youtube\.com\/shorts/,
      next: () => click('#navigation-button-down button, #navigation-button-down'),
      prev: () => click('#navigation-button-up button, #navigation-button-up') },
    { re: /youtube\.com\/watch/,
      next: () => click('.ytp-next-button, button[aria-label="Next video"], button[aria-label="Next"]'),
      prev: () => click('.ytp-prev-button, button[aria-label="Previous video"], button[aria-label="Previous"]') },
    { re: /tiktok\.com/,
      next: () => click('[data-e2e="arrow-right"]'),
      prev: () => click('[data-e2e="arrow-left"]') },
  ];

  const scrollFeed = (v, dir) => {
    let el = v;
    while (el && el !== document.documentElement) {
      const cs = getComputedStyle(el);
      if (/(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight) break;
      el = el.parentElement;
    }
    el = (el && el !== document.documentElement) ? el : (document.scrollingElement || document.documentElement);
    el.scrollBy({ top: dir * (el.clientHeight || innerHeight), behavior: 'smooth' });
  };
  const pressKey = key => {
    const kc = key === 'ArrowDown' ? 40 : 38;
    const o = { key, code: key, keyCode: kc, which: kc, bubbles: true, cancelable: true };
    for (const t of [document.activeElement || document.body, document])
      for (const type of ['keydown', 'keyup']) t.dispatchEvent(new KeyboardEvent(type, o));
  };

  const snap = () => { const v = best(); return { v, src: v && v.currentSrc, href: location.href, t: v ? v.currentTime : 0 }; };
  const same = (a, b) => a.v === b.v && a.src === b.src && a.href === b.href;
  const changed = async (before, n) => {
    for (let i = 0; i < (n || 10); i++) {
      await sleep(100);
      const c = snap();
      let why = '';
      if (c.v !== before.v) why = 'element';
      else if (c.href !== before.href) why = 'url';
      else if (c.src !== before.src) why = 'src';
      else if (before.t > 1 && c.v && c.t < before.t - 0.5) why = 'restart';
      if (why) { L('changed: ' + why); return true; }
    }
    return false;
  };

  // only one video should play: pause the others
  const dedupe = keep => {
    document.querySelectorAll('video').forEach(x => { if (x !== keep && !x.paused && !x.muted && x.volume > 0) x.pause(); });
  };
  async function ensurePlay() {
    for (let i = 0; i < 12; i++) {
      await sleep(250);
      const v = best();
      if (!v) continue;
      dedupe(v);
      if (v.paused && !userPaused && !v.ended) v.play().catch(() => {});
    }
  }

  let busy = false;
  async function go(dir) {
    if (busy) return;
    busy = true; userPaused = false; holdUntil = Date.now() + 3000;
    let how = 'none', ok = false;
    try {
      const before = snap();
      const short = isShort(before.v);
      const a = ADAPT.find(x => x.re.test(location.href));
      if (a && (dir > 0 ? a.next() : a.prev())) { ok = await changed(before); how = 'adapter'; }
      if (!ok && short) {
        if (A && A.swipe) {
          A.swipe(dir > 0, 0); ok = await changed(before, 12); how = 'swipe';
          if (!ok) { A.swipe(dir > 0, 1); ok = await changed(before, 12); how = 'long-swipe'; }
          if (!ok) { A.swipe(dir > 0, 2); ok = await changed(before, 12); how = 'flick'; }
          if (!ok) { scrollFeed(before.v, dir); ok = await changed(before, 10); how = 'scroll'; }
          if (!ok) { pressKey(dir > 0 ? 'ArrowDown' : 'ArrowUp'); ok = await changed(before, 10); how = 'key'; }
        } else {
          scrollFeed(before.v, dir); ok = await changed(before); how = 'scroll';
          if (!ok) { pressKey(dir > 0 ? 'ArrowDown' : 'ArrowUp'); ok = await changed(before); how = 'key'; }
        }
      }
      if (!ok && !short) {
        // plain video pages: skip 10 s
        if (before.v) before.v.currentTime = Math.max(0, before.v.currentTime + dir * 10);
        how = 'seek';
      }
    } finally {
      L('go ' + dir + ' via ' + how + ' ok=' + ok);
      if (on) dbg('after-go');
      busy = false; report(true);
      if (ok) ensurePlay();
    }
  }

  const toggle = () => {
    const v = best() || lastV; if (!v) return;
    if (v.paused) { userPaused = false; v.play().catch(() => {}); }
    else { userPaused = true; v.pause(); }
  };
  const pause = () => { const v = best() || lastV; userPaused = true; if (v) v.pause(); };
  const hold = () => { holdUntil = Date.now() + 4000; };

  // ---------- PiP overlay: draws the active video full-window, page keeps running underneath ----------
  const dbg = tag => {
    try {
      const r = cv ? cv.getBoundingClientRect() : null;
      const vv = window.visualViewport;
      L(tag + ' inner=' + innerWidth + 'x' + innerHeight +
        (vv ? ' vv=' + Math.round(vv.width) + 'x' + Math.round(vv.height) + '@' + Math.round(vv.offsetLeft) + ',' + Math.round(vv.offsetTop) + ' s=' + vv.scale.toFixed(2) : '') +
        (r ? ' cv=' + Math.round(r.left) + ',' + Math.round(r.top) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) : ' cv=none') +
        ' scrollY=' + Math.round(window.scrollY));
    } catch (e) { L('dbg err ' + e); }
  };
  let lastT = '';
  function setPip(flag) {
    flag = !!flag;
    if (flag && on && cv && cv.isConnected) return;   // already showing
    on = flag;
    L('pip overlay ' + on);
    if (!on) { cancelAnimationFrame(raf); if (cv) cv.remove(); return; }
    if (!cv) {
      cv = document.createElement('canvas');
      cv.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;background:#000;z-index:2147483647;pointer-events:none';
    }
    document.documentElement.appendChild(cv);
    cancelAnimationFrame(raf);
    const draw = () => {
      if (!on) return;
      if (!cv.isConnected && document.documentElement) document.documentElement.appendChild(cv);
      const v = best() || lastV, w = innerWidth, h = innerHeight;
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
      // keep the picture layer exactly over what is visible (page zoom / visual viewport offset)
      const vv = window.visualViewport;
      if (vv) {
        const t = 'translate(' + vv.offsetLeft + 'px,' + vv.offsetTop + 'px) scale(' + (1 / (vv.scale || 1)) + ')';
        if (t !== lastT) { cv.style.transformOrigin = '0 0'; cv.style.transform = t; lastT = t; }
      }
      const c = cv.getContext('2d');
      c.fillStyle = '#000'; c.fillRect(0, 0, w, h);
      if (v && v.videoWidth) {
        const s = Math.min(w / v.videoWidth, h / v.videoHeight);
        const dw = v.videoWidth * s, dh = v.videoHeight * s;
        try { c.drawImage(v, (w - dw) / 2, (h - dh) / 2, dw, dh); } catch (e) {}
      }
      raf = requestAnimationFrame(draw);
    };
    draw();
    setTimeout(() => dbg('pip-on'), 700);
  }

  // if the site pauses the video on its own while we are in PiP / leaving the app, resume it
  document.addEventListener('play', () => { userPaused = false; }, true);
  document.addEventListener('pause', e => {
    const v = e.target;
    if (!(v instanceof HTMLMediaElement)) return;
    const active = on || Date.now() < holdUntil;
    L('pause ' + (v === lastV ? 'main' : 'other') + ' active=' + active + ' user=' + userPaused + ' ended=' + v.ended);
    if (!active || userPaused || v.ended) return;
    if (v !== (best() || lastV)) return;
    const now = Date.now();
    const rc = v.__rc || (v.__rc = { n: 0, t: now });
    if (now - rc.t > 6000) { rc.n = 0; rc.t = now; }
    if (rc.n >= 3) return;
    rc.n++;
    [150, 800].forEach(t => setTimeout(() => {
      if (!userPaused && v.paused && !v.ended && v === (best() || lastV)) { L('resume play'); v.play().catch(() => {}); }
    }, t));
  }, true);

  // ---------- report playing state to the app ----------
  let lastKey = '', rt = 0;
  function doReport(force) {
    if (!A) return;
    const v = best() || lastV;
    const playing = !!(v && !v.paused && !v.ended);
    const ratio = v && v.videoWidth ? v.videoWidth / v.videoHeight : 0;
    const key = playing + '|' + !!v + '|' + ratio.toFixed(2);
    if (!force && key === lastKey) return;
    lastKey = key;
    try { A.onState(playing, !!v, ratio); } catch (e) {}
  }
  function report(force) {
    if (force) { clearTimeout(rt); doReport(true); return; }
    clearTimeout(rt);
    rt = setTimeout(() => doReport(false), 500);
  }
  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'emptied'].forEach(e =>
    document.addEventListener(e, () => report(false), true));
  setInterval(() => report(false), 2000);
  report(true);

  // used when a page is moved into a floating window: continue from the same time
  const startAt = t => {
    if (!(t > 1)) return;
    let tries = 0;
    const id = setInterval(() => {
      const v = best() || lastV;
      if (v && v.readyState > 0 && isFinite(v.duration) && v.duration > t + 1) {
        try { v.currentTime = t; } catch (e) {}
        clearInterval(id);
      } else if (++tries > 40) clearInterval(id);
    }, 250);
  };
  const time = () => { const v = best() || lastV; return v ? v.currentTime : 0; };

  // floating windows start without any tap, so sites begin muted: force sound on (can be toggled)
  const unmute = () => {
    const v = best() || lastV;
    if (v && (v.muted || v.volume === 0)) { try { v.muted = false; v.volume = 1; } catch (e) {} }
  };
  const setSound = f => { sound = !!f; if (sound) unmute(); };
  const toggleSound = () => {
    sound = !sound;
    const v = best() || lastV;
    if (v) { try { v.muted = !sound; if (sound) v.volume = 1; } catch (e) {} }
    return sound;
  };
  setInterval(() => { if (sound) unmute(); }, 600);

  const info = () => {
    const v = best() || lastV;
    return v ? (Math.round(v.currentTime * 10) / 10) + '|' + (v.paused ? 1 : 0) : '0|1';
  };
  const resume = () => { userPaused = false; const v = best() || lastV; if (v && v.paused) v.play().catch(() => {}); };

  window.__pip = { go, toggle, pause, setPip, hold, startAt, time, info, resume, setSound, toggleSound, report: () => report(true) };
})();
