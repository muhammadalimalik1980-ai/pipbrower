(() => {
  'use strict';
  if (window.__pip) return;
  const A = window.AndroidPip;
  let userPaused = false, holdUntil = 0;

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
  const sleep = t => new Promise(r => setTimeout(r, t));

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
  const changed = async before => {
    for (let i = 0; i < 10; i++) { await sleep(100); if (!same(before, snap())) return true; }
    return false;
  };

  let busy = false;
  async function go(dir) {
    if (busy) return; busy = true;
    try {
      const before = snap();
      const short = isShort(before.v);
      const a = ADAPT.find(x => x.re.test(location.href));
      let ok = false;
      if (a && (dir > 0 ? a.next() : a.prev())) ok = await changed(before);
      if (!ok && short) {
        scrollFeed(before.v, dir); ok = await changed(before);
        if (!ok) { pressKey(dir > 0 ? 'ArrowDown' : 'ArrowUp'); ok = await changed(before); }
      }
      if (!ok && !short && before.v) before.v.currentTime = Math.max(0, before.v.currentTime + dir * 10);
      if (ok) { const v = best(); if (v && v.paused) v.play().catch(() => {}); }
    } finally { busy = false; report(true); }
  }

  const toggle = () => {
    const v = best() || lastV; if (!v) return;
    if (v.paused) { userPaused = false; v.play().catch(() => {}); }
    else { userPaused = true; v.pause(); }
  };
  const pause = () => { const v = best() || lastV; userPaused = true; if (v) v.pause(); };
  const hold = () => { holdUntil = Date.now() + 4000; };

  // ---------- PiP overlay: draws the active video full-window, page keeps running underneath ----------
  let cv = null, raf = 0, on = false;
  function setPip(flag) {
    on = !!flag;
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

  // if the site pauses video while we are leaving the app / entering PiP, resume it
  document.addEventListener('play', () => { userPaused = false; }, true);
  document.addEventListener('pause', e => {
    const v = e.target;
    if (!(v instanceof HTMLMediaElement) || userPaused || v.ended) return;
    if (Date.now() > holdUntil) return;
    [100, 400, 1000].forEach(t => setTimeout(() => {
      if (!userPaused && v.paused && !v.ended) v.play().catch(() => {});
    }, t));
  }, true);

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
