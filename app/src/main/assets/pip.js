(() => {
  'use strict';
  if (window.__pip) return;
  const A = window.AndroidPip;
  const L = m => { try { A && A.log && A.log(m); } catch (e) {} };
  const sleep = t => new Promise(r => setTimeout(r, t));
  let userPaused = false, holdUntil = 0;
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

  const snap = () => { const v = best(); return { v, src: v && v.currentSrc, href: location.href }; };
  const same = (a, b) => a.v === b.v && a.src === b.src && a.href === b.href;
  const changed = async (before, n) => {
    for (let i = 0; i < (n || 10); i++) { await sleep(100); if (!same(before, snap())) return true; }
    return false;
  };

  // only one video should play: pause the others
  const dedupe = keep => {
    document.querySelectorAll('video').forEach(x => { if (x !== keep && !x.paused) x.pause(); });
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
        if (A && A.swipe) { A.swipe(dir > 0); ok = await changed(before, 15); how = 'swipe'; }
        if (!ok) { scrollFeed(before.v, dir); ok = await changed(before); how = 'scroll'; }
        if (!ok) { pressKey(dir > 0 ? 'ArrowDown' : 'ArrowUp'); ok = await changed(before); how = 'key'; }
      }
      if (!ok && !short) {
        // plain video pages: skip 10 s
        if (before.v) before.v.currentTime = Math.max(0, before.v.currentTime + dir * 10);
        how = 'seek';
      }
    } finally {
      L('go ' + dir + ' via ' + how + ' ok=' + ok);
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
  function setPip(flag) {
    on = !!flag;
    L('pip overlay ' + on);
    if (!on) { cancelAnimationFrame(raf); if (cv) cv.remove(); return; }
    if (!cv) {
      cv = document.createElement('canvas');
      cv.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;background:#000;z-index:2147483647;pointer-events:none';
    }
    document.documentElement.appendChild(cv);
    const draw = () => {
      if (!on) return;
      const v = best() || lastV, w = innerWidth, h = innerHeight;
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
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
    [100, 400, 1000, 2000].forEach(t => setTimeout(() => {
      if (!userPaused && v.paused && !v.ended && v === (best() || lastV)) v.play().catch(() => {});
    }, t));
  }, true);
  setInterval(() => {
    if (on || Date.now() < holdUntil) { const k = best(); if (k) dedupe(k); }
  }, 800);

  // ---------- report playing state to the app ----------
  let lastKey = '';
  function report(force) {
    if (!A) return;
    const v = best() || lastV;
    const playing = !!(v && !v.paused && !v.ended);
    const ratio = v && v.videoWidth ? v.videoWidth / v.videoHeight : 0;
    const key = playing + '|' + !!v + '|' + ratio.toFixed(2);
    if (!force && key === lastKey) return;
    lastKey = key;
    try { A.onState(playing, !!v, ratio); } catch (e) {}
  }
  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'emptied'].forEach(e =>
    document.addEventListener(e, () => report(false), true));
  setInterval(() => report(false), 2000);
  report(true);

  window.__pip = { go, toggle, pause, setPip, hold };
})();
