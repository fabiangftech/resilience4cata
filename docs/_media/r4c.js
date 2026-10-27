/*
 * resilience4cata.dev: theme behaviour for Docsify 4 (no build step).
 *
 * - header: language switcher (same page in the other language), theme toggle (system → light → dark),
 *   search box moved from the sidebar into the header, mobile menu
 * - GitHub alerts  > [!NOTE] / [!TIP] / [!IMPORTANT] / [!WARNING] / [!CAUTION] / [!MEASURED]
 * - <!-- compare:start --> … <!-- compare:end -->: "You write / The processor writes" panes
 * - <div class="r4c-facts"> + one-row table: the spec strip of policy pages
 * - Mermaid fences, themed from the CSS tokens, re-rendered when the theme changes
 * - breadcrumbs, policy icon in the title, lede, right-hand "On this page", prev/next, "Edit this page"
 * - landing pages (/en/, /es/): no sidebar, full width, interactive "compile" panel and chart
 */
(function () {
  'use strict';

  var REPO = 'fabiangftech/resilience4cata';
  var BRANCH = 'main';
  var POLICIES = ['retry', 'circuit-breaker', 'rate-limiter', 'timeout', 'bulkhead', 'fallback'];

  var I18N = {
    en: {
      note: 'Note', tip: 'Tip', important: 'Important', warning: 'Warning', caution: 'Caution', measured: 'Measured',
      youWrite: 'You write', processorWrites: 'The processor writes', abridged: 'generated, abridged', generated: 'generated',
      onThisPage: 'On this page', previous: 'Previous', next: 'Next', docs: 'Docs',
      edit: 'Edit this page on GitHub', otherLang: 'Leer en español',
      system: 'System', light: 'Light', dark: 'Dark', themeLabel: 'Color theme: ',
      nav: { docs: 'Docs', policies: 'Policies', performance: 'Performance', errors: 'Error codes' },
      menu: 'Navigation', skip: 'Skip to content', copy: 'Copy', copied: 'Copied'
    },
    es: {
      note: 'Nota', tip: 'Consejo', important: 'Importante', warning: 'Advertencia', caution: 'Precaución', measured: 'Medido',
      youWrite: 'Tú escribes', processorWrites: 'El procesador escribe', abridged: 'generado, abreviado', generated: 'generado',
      onThisPage: 'En esta página', previous: 'Anterior', next: 'Siguiente', docs: 'Docs',
      edit: 'Editar esta página en GitHub', otherLang: 'Read in English',
      system: 'Sistema', light: 'Claro', dark: 'Oscuro', themeLabel: 'Tema de color: ',
      nav: { docs: 'Documentación', policies: 'Políticas', performance: 'Rendimiento', errors: 'Códigos de error' },
      menu: 'Navegación', skip: 'Saltar al contenido', copy: 'Copiar', copied: 'Copiado'
    }
  };

  var ICONS = {
    'retry': '<path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4.5V8.5H16"/><circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none"/>',
    'circuit-breaker': '<path d="M2 16h4.5"/><circle cx="8" cy="16" r="1.6"/><path d="M9.2 15 L17 7.5"/><circle cx="16.5" cy="16" r="1.6"/><path d="M18 16h4"/>',
    'rate-limiter': '<path d="M4 4h16l-5.5 6h-5z"/><path d="M12 10v1.5"/><circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/><circle cx="12" cy="20" r="1.3" fill="currentColor" stroke="none"/>',
    'timeout': '<circle cx="12" cy="13.5" r="7"/><path d="M12 13.5V9.5"/><path d="M10 3h4"/><path d="M12 3v3.5"/><path d="M18.2 6.8l1.4-1.4"/>',
    'bulkhead': '<path d="M3 8h18l-1.8 8.4a2 2 0 0 1-2 1.6H6.8a2 2 0 0 1-2-1.6z"/><path d="M9 8v10"/><path d="M15 8v10"/><path d="M12 8V4.5"/>',
    'fallback': '<path d="M3 7h10"/><path d="M15.5 5.5l3 3"/><path d="M18.5 5.5l-3 3"/><path d="M7.5 7c0 6 3 10 8.5 10H21"/><path d="M18 14l3 3-3 3"/>'
  };
  var CALLOUT_ICONS = {
    note: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6"/><circle cx="12" cy="7.5" r="0.6" fill="currentColor"/>',
    tip: '<path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1.1 2h5c.1-.8.5-1.5 1.1-2A6 6 0 0 0 12 3z"/>',
    important: '<path d="M4 4h16v12H8l-4 4z"/><path d="M12 7v4"/><circle cx="12" cy="13.5" r="0.6" fill="currentColor"/>',
    warning: '<path d="M12 3 2 20h20z"/><path d="M12 10v4"/><circle cx="12" cy="17" r="0.6" fill="currentColor"/>',
    caution: '<path d="M8 3h8l5 5v8l-5 5H8l-5-5V8z"/><path d="M12 8v5"/><circle cx="12" cy="16" r="0.6" fill="currentColor"/>',
    measured: '<path d="M4 20h16"/><path d="M7 16v-5"/><path d="M12 16V6"/><path d="M17 16v-8"/>'
  };

  function svg(paths, cls) {
    return '<svg class="' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths + '</svg>';
  }

  var store = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { if (v) localStorage.setItem(k, v); else localStorage.removeItem(k); } catch (e) { /* private mode */ } }
  };
  var session = {
    get: function (k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { sessionStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  };

  function langOf(path) { return /^\/es(\/|$)/.test(path || '') ? 'es' : 'en'; }
  function isLanding(path) { return path === '/' || path === '/en/' || path === '/es/'; }
  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  var current = { path: '/', lang: 'en' };

  // ---------------------------------------------------------------- tabs: persist the choice across visits
  // docsify-tabs keeps the synced label in sessionStorage; copy it to localStorage so it survives the session.
  var TAB_KEY = 'docsify-tabs.sync';
  if (!session.get(TAB_KEY) && store.get('r4c-tabs')) session.set(TAB_KEY, store.get('r4c-tabs'));
  document.addEventListener('click', function (e) {
    var t = e.target.closest && e.target.closest('.docsify-tabs__tab, .r4c-install [role="tab"]');
    if (!t) return;
    setTimeout(function () { var v = session.get(TAB_KEY); if (v) store.set('r4c-tabs', v); }, 0);
  });
  function rememberTab(label) {
    var list = [];
    try { list = JSON.parse(session.get(TAB_KEY)) || []; } catch (e) { list = []; }
    list = [label].concat(list.filter(function (l) { return l !== label; }));
    session.set(TAB_KEY, JSON.stringify(list));
    store.set('r4c-tabs', JSON.stringify(list));
  }
  function preferredTab(labels) {
    var list = [];
    try { list = JSON.parse(session.get(TAB_KEY)) || []; } catch (e) { list = []; }
    for (var i = 0; i < list.length; i++) if (labels.indexOf(list[i]) >= 0) return list[i];
    return null;
  }

  // ---------------------------------------------------------------- theme
  var root = document.documentElement;
  var darkQuery = window.matchMedia ? matchMedia('(prefers-color-scheme: dark)') : null;
  function themeMode() { return root.getAttribute('data-theme') || 'system'; }
  function updateThemeButton() {
    var b = $('.r4c-theme');
    if (!b) return;
    var L = I18N[current.lang];
    b.querySelector('span').textContent = L[themeMode()];
    b.setAttribute('aria-label', L.themeLabel + L[themeMode()]);
  }
  function cycleTheme() {
    var next = { system: 'light', light: 'dark', dark: 'system' }[themeMode()];
    if (next === 'system') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', next);
    store.set('r4c-theme', next === 'system' ? null : next);
    updateThemeButton();
    renderMermaid();
  }
  if (darkQuery) {
    var onScheme = function () { if (themeMode() === 'system') renderMermaid(); };
    if (darkQuery.addEventListener) darkQuery.addEventListener('change', onScheme); else darkQuery.addListener(onScheme);
  }

  // ---------------------------------------------------------------- mermaid
  var mermaidSeq = 0;
  var mermaidRun = 0;
  function token(name) { return getComputedStyle(root).getPropertyValue('--' + name).trim(); }
  function mermaidBlock(code) {
    return '<div class="r4c-mermaid" data-src="' + encodeURIComponent(code) + '"><pre class="r4c-mermaid-src">' +
      escapeHtml(code) + '</pre></div>';
  }
  function withStateClasses(src) {
    // `:::closed`, `:::open`, `:::half` (or `class X closed`) get the state colours without a classDef in the page.
    var used = {};
    src.replace(/(?::::|\bclass\s+[^\n]*?\s)(closed|open|half|signal)\b/g, function (_, c) { used[c] = true; });
    var defs = [];
    Object.keys(used).forEach(function (c) {
      if (new RegExp('classDef\\s+' + c + '\\b').test(src)) return;
      var stroke = c === 'signal' ? token('link-line') : token(c);
      var fill = token(c + '-soft');
      defs.push('    classDef ' + c + ' fill:' + fill + ',stroke:' + stroke + ',color:' + token('ink'));
    });
    if (!defs.length) return src;
    var lines = src.split('\n');
    var i = 0;
    while (i < lines.length && (!lines[i].trim() || /^\s*%%/.test(lines[i]))) i++;
    lines.splice(i + 1, 0, defs.join('\n'));
    return lines.join('\n');
  }
  function mermaidConfig() {
    var t = {
      bg: token('bg'), surface: token('surface'), surface2: token('surface-2'), rule: token('rule'),
      ruleStrong: token('rule-strong'), ink: token('ink'), ink2: token('ink-2'), signal: token('signal'),
      signalSoft: token('signal-soft'), linkLine: token('link-line')
    };
    var dark = themeMode() === 'dark' || (themeMode() === 'system' && darkQuery && darkQuery.matches);
    return {
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'base',
      fontFamily: '"IBM Plex Sans", system-ui, sans-serif',
      flowchart: { curve: 'basis', htmlLabels: true, padding: 12 },
      themeVariables: {
        darkMode: dark,
        fontFamily: '"IBM Plex Sans", system-ui, sans-serif',
        fontSize: '14px',
        background: t.surface,
        primaryColor: t.surface2, primaryTextColor: t.ink, primaryBorderColor: t.ruleStrong,
        secondaryColor: t.signalSoft, secondaryTextColor: t.ink, secondaryBorderColor: t.linkLine,
        tertiaryColor: t.surface, tertiaryTextColor: t.ink, tertiaryBorderColor: t.rule,
        mainBkg: t.surface2, nodeBorder: t.ruleStrong, nodeTextColor: t.ink, textColor: t.ink, titleColor: t.ink,
        lineColor: t.ink2, edgeLabelBackground: t.surface, clusterBkg: t.bg, clusterBorder: t.rule,
        noteBkgColor: t.signalSoft, noteTextColor: t.ink, noteBorderColor: t.linkLine,
        labelBackgroundColor: t.surface, stateLabelColor: t.ink, stateBkg: t.surface2, transitionColor: t.ink2,
        transitionLabelColor: t.ink2, compositeBackground: t.bg, altBackground: t.bg, specialStateColor: t.ink2,
        innerEndBackground: t.ink,
        actorBkg: t.surface2, actorBorder: t.ruleStrong, actorTextColor: t.ink, actorLineColor: t.ruleStrong,
        signalColor: t.ink2, signalTextColor: t.ink, labelBoxBkgColor: t.surface2, labelBoxBorderColor: t.ruleStrong,
        labelTextColor: t.ink, loopTextColor: t.ink, activationBkgColor: t.signalSoft, activationBorderColor: t.linkLine,
        sequenceNumberColor: t.bg
      }
    };
  }
  function renderMermaid() {
    var els = $$('.r4c-mermaid');
    if (!els.length || !window.mermaid) return;
    var run = ++mermaidRun;
    var fonts = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
    fonts.then(function () {
      if (run !== mermaidRun) return;
      window.mermaid.initialize(mermaidConfig());
      var chain = Promise.resolve();
      els.forEach(function (el) {
        chain = chain.then(function () {
          if (run !== mermaidRun || !el.isConnected) return;
          var id = 'r4c-mmd-' + (++mermaidSeq);
          return window.mermaid.render(id, withStateClasses(decodeURIComponent(el.getAttribute('data-src'))))
            .then(function (out) {
              el.innerHTML = out.svg;
              el.classList.add('is-rendered');
              el.classList.remove('is-error');
            })
            .catch(function (err) {
              var stray = document.getElementById('d' + id);
              if (stray) stray.remove();
              el.classList.add('is-error');
              if (window.console) console.warn('Mermaid:', err);
            });
        });
      });
    });
  }

  // ---------------------------------------------------------------- page components
  function callouts(article, L) {
    $$('blockquote', article).forEach(function (bq) {
      var p = bq.firstElementChild;
      if (!p || p.tagName !== 'P') return;
      var m = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|MEASURED)\]/i.exec(p.textContent);
      if (!m) return;
      var first = p.firstChild;
      if (first && first.nodeType === 3) first.textContent = first.textContent.replace(/^\s*\[![A-Za-z]+\]\s*/, '');
      while (p.firstChild && (p.firstChild.nodeName === 'BR' || (p.firstChild.nodeType === 3 && !p.firstChild.textContent.trim()))) {
        p.removeChild(p.firstChild);
      }
      if (!p.childNodes.length) p.remove();
      var kind = m[1].toLowerCase();
      var box = document.createElement('div');
      box.className = 'r4c-callout r4c-callout--' + kind;
      box.setAttribute('role', 'note');
      box.innerHTML = svg(CALLOUT_ICONS[kind], 'r4c-callout__icon') +
        '<div class="r4c-callout__body"><p class="r4c-callout__title">' + L[kind] + '</p></div>';
      var body = box.lastChild;
      while (bq.firstChild) body.appendChild(bq.firstChild);
      bq.parentNode.replaceChild(box, bq);
    });
  }

  function compareBlocks(html, L) {
    return html.replace(/<!--\s*compare:start\s*-->([\s\S]*?)<!--\s*compare:end\s*-->/g, function (all, inner) {
      var pres = inner.match(/<pre[\s>][\s\S]*?<\/pre>/g) || [];
      if (pres.length < 2) return inner;
      var abridged = /not-compiled:[^>]*abridged/i.test(inner);
      return '<div class="r4c-compare">' +
        '<div class="r4c-compare__pane r4c-compare__src"><div class="r4c-compare__label"><b>' + L.youWrite + '</b></div>' + pres[0] + '</div>' +
        '<div class="r4c-compare__pane r4c-compare__gen"><div class="r4c-compare__label"><b>' + L.processorWrites + '</b><span>' +
        (abridged ? L.abridged : L.generated) + '</span></div>' + pres[1] + '</div>' +
        '</div>';
    });
  }

  function factsStrips(article) {
    $$('.r4c-facts', article).forEach(function (box) {
      var table = $('table', box);
      if (!table) return;
      var heads = $$('thead th', table);
      var rows = $$('tbody tr', table);
      if (rows.length !== 1) { box.classList.add('r4c-facts--table'); return; }
      var cells = $$('td', rows[0]);
      var dl = document.createElement('dl');
      dl.className = 'r4c-spec';
      heads.forEach(function (th, i) {
        var d = document.createElement('div');
        d.innerHTML = '<dt>' + th.innerHTML + '</dt><dd>' + (cells[i] ? cells[i].innerHTML : '') + '</dd>';
        dl.appendChild(d);
      });
      dl.style.setProperty('--cols', String(Math.min(heads.length, 4)));
      box.replaceChild(dl, table.closest('.r4c-table') || table);
    });
  }

  function wrapTables(article) {
    $$('table', article).forEach(function (t) {
      if (t.parentNode.classList.contains('r4c-table') || t.closest('.r4c-home')) return;
      var w = document.createElement('div');
      w.className = 'r4c-table';
      t.parentNode.insertBefore(w, t);
      w.appendChild(t);
    });
  }

  function sidebarLinks() {
    return $$('.sidebar-nav a').filter(function (a) {
      var h = a.getAttribute('href') || '';
      return /^\/(en|es)\//.test(h);
    });
  }
  function hrefPath(h) { return (h || '').replace(/\?.*$/, ''); }
  function samePath(a, b) {
    var n = function (p) { return p.replace(/\.md$/, '').replace(/\/README$/, '/').replace(/\/$/, '/'); };
    return n(a) === n(b);
  }
  function groupOf(a) {
    var li = a.closest('.sidebar-nav > ul > li');
    if (!li) return null;
    var s = li.firstElementChild;
    if (s && (s.tagName === 'STRONG' || s.tagName === 'P')) return s.textContent.trim();
    return null;
  }

  function titleBlock(article, path, L) {
    var h1 = $('h1', article);
    if (!h1) return;
    var policy = (/\/policies\/([a-z-]+)$/.exec(path) || [])[1];
    var wrap = document.createElement('div');
    wrap.className = 'r4c-title';
    h1.parentNode.insertBefore(wrap, h1);
    if (policy && ICONS[policy]) {
      var handle = document.createElement('span');
      handle.className = 'r4c-handle';
      handle.innerHTML = svg(ICONS[policy]);
      wrap.appendChild(handle);
    }
    wrap.appendChild(h1);
    var next = wrap.nextElementSibling;
    if (next && next.tagName === 'P' && !next.className) next.classList.add('r4c-lede');
    // breadcrumbs from the sidebar group
    var link = sidebarLinks().filter(function (a) { return samePath(hrefPath(a.getAttribute('href')), path); })[0];
    var group = link && groupOf(link);
    var crumbs = document.createElement('ol');
    crumbs.className = 'r4c-crumbs';
    crumbs.setAttribute('aria-label', 'Breadcrumb');
    crumbs.innerHTML = '<li><a href="/' + current.lang + '/">' + L.docs + '</a></li>' +
      (group ? '<li>' + escapeHtml(group) + '</li>' : '') +
      '<li aria-current="page">' + escapeHtml(h1.textContent.replace(/\s+/g, ' ').trim()) + '</li>';
    wrap.parentNode.insertBefore(crumbs, wrap);
  }

  var tocState = null;
  function buildToc(article, L) {
    var nav = $('.r4c-toc');
    if (!nav) {
      nav = document.createElement('nav');
      nav.className = 'r4c-toc';
      $('section.content').appendChild(nav);
    }
    var heads = $$('h2, h3', article).filter(function (h) {
      return h.id && !h.closest('.docsify-tabs, .r4c-callout, .r4c-home');
    });
    nav.setAttribute('aria-label', L.onThisPage);
    if (heads.length < 2) { nav.innerHTML = ''; nav.hidden = true; tocState = null; return; }
    nav.hidden = false;
    var html = '<p class="r4c-toc__title">' + L.onThisPage + '</p><ul>';
    heads.forEach(function (h) {
      var a = $('a.anchor', h);
      var href = a ? a.getAttribute('href') : '#';
      html += '<li class="' + (h.tagName === 'H3' ? 'sub' : '') + '"><a href="' + href + '" data-id="' + h.id + '">' +
        escapeHtml(h.textContent.trim()) + '</a></li>';
    });
    nav.innerHTML = html + '</ul>';
    tocState = { heads: heads, links: $$('a', nav) };
    spy();
  }
  function spy() {
    if (!tocState) return;
    var active = null;
    tocState.heads.forEach(function (h) { if (h.getBoundingClientRect().top < 160) active = h; });
    if (!active) active = tocState.heads[0];
    tocState.links.forEach(function (a) {
      if (a.getAttribute('data-id') === active.id) a.setAttribute('aria-current', 'true'); else a.removeAttribute('aria-current');
    });
  }
  var spyQueued = false;
  window.addEventListener('scroll', function () {
    if (spyQueued) return;
    spyQueued = true;
    requestAnimationFrame(function () { spyQueued = false; spy(); });
  }, { passive: true });

  function pageFooter(article, vm, L) {
    var path = vm.route.path;
    var links = sidebarLinks();
    var idx = -1;
    links.forEach(function (a, i) { if (idx < 0 && samePath(hrefPath(a.getAttribute('href')), path)) idx = i; });
    var out = document.createElement('footer');
    out.className = 'r4c-page-footer';
    var html = '';
    if (idx >= 0) {
      var prev = links[idx - 1], next = links[idx + 1];
      var cell = function (a, cls, label) {
        if (!a) return '<span></span>';
        return '<a class="' + cls + '" href="' + a.getAttribute('href') + '"><small>' + label +
          (groupOf(a) ? ' · ' + escapeHtml(groupOf(a)) : '') + '</small><b>' + escapeHtml(a.textContent.trim()) + '</b></a>';
      };
      html += '<nav class="r4c-pager" aria-label="Pages">' + cell(prev, 'prev', L.previous) + cell(next, 'next', L.next) + '</nav>';
    }
    var other = current.lang === 'en' ? 'es' : 'en';
    var file = (vm.route.file || '').replace(/^\//, '');
    html += '<div class="r4c-page-meta">' +
      '<a href="https://github.com/' + REPO + '/edit/' + BRANCH + '/docs/' + file + '">' +
      svg('<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>') + L.edit + '</a>' +
      '<a href="' + path.replace(/^\/(en|es)\//, '/' + other + '/') + '" lang="' + other + '">' + L.otherLang + '</a></div>';
    out.innerHTML = html;
    article.appendChild(out);
  }

  // ---------------------------------------------------------------- landing page behaviour
  function initLanding(article) {
    var box = $('.r4c-compile', article);
    if (box) {
      var gen = $('.r4c-pane-gen pre', box);
      var buttons = $$('.r4c-chain button', box);
      var smooth = !(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
      var activate = function (layer, scroll) {
        if (layer) box.setAttribute('data-active', layer); else box.removeAttribute('data-active');
        buttons.forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-layer') === layer)); });
        if (layer && scroll && gen) {
          var blk = $('.blk[data-layer="' + layer + '"]', gen);
          if (blk) gen.scrollTo({ top: blk.offsetTop - 12, behavior: smooth ? 'smooth' : 'auto' });
        }
      };
      $$('.hot', box).forEach(function (h) {
        var l = h.getAttribute('data-layer');
        h.addEventListener('mouseenter', function () { activate(l, true); });
        h.addEventListener('focus', function () { activate(l, true); });
        h.addEventListener('mouseleave', function () { activate(null); });
        h.addEventListener('blur', function () { activate(null); });
      });
      buttons.forEach(function (b) {
        b.addEventListener('click', function () {
          var l = b.getAttribute('data-layer');
          activate(box.getAttribute('data-active') === l ? null : l, true);
        });
      });
      $$('.blk', box).forEach(function (blk) {
        blk.addEventListener('mouseenter', function () { activate(blk.getAttribute('data-layer'), false); });
        blk.addEventListener('mouseleave', function () { activate(null); });
      });
    }
    var chart = $('.r4c-chart', article);
    if (chart) {
      var tip = $('.r4c-chart-tip', chart);
      $$('.bar', chart).forEach(function (bar) {
        var show = function () {
          var c = chart.getBoundingClientRect(), r = bar.getBoundingClientRect();
          tip.textContent = bar.getAttribute('data-tip');
          tip.hidden = false;
          tip.style.left = Math.max(0, Math.min(r.left - c.left, c.width - 270)) + 'px';
          tip.style.top = (r.top - c.top - 36) + 'px';
        };
        var hide = function () { tip.hidden = true; };
        bar.addEventListener('mouseenter', show); bar.addEventListener('focus', show);
        bar.addEventListener('mouseleave', hide); bar.addEventListener('blur', hide);
      });
    }
    $$('.r4c-install', article).forEach(function (group) {
      var tabs = $$('[role="tab"]', group);
      var select = function (label) {
        tabs.forEach(function (t) {
          var on = t.getAttribute('data-tab') === label;
          t.setAttribute('aria-selected', String(on));
          t.tabIndex = on ? 0 : -1;
          var panel = document.getElementById(t.getAttribute('aria-controls'));
          if (panel) panel.hidden = !on;
        });
      };
      var pref = preferredTab(tabs.map(function (t) { return t.getAttribute('data-tab'); }));
      if (pref) select(pref);
      tabs.forEach(function (t, i) {
        t.addEventListener('click', function () { select(t.getAttribute('data-tab')); rememberTab(t.getAttribute('data-tab')); });
        t.addEventListener('keydown', function (e) {
          var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
          if (!d) return;
          var n = tabs[(i + d + tabs.length) % tabs.length];
          n.click(); n.focus();
        });
      });
      var copy = $('.r4c-copy', group);
      if (copy) {
        copy.addEventListener('click', function () {
          var pre = $$('pre', group).filter(function (p) { return !p.closest('[hidden]'); })[0];
          var L = I18N[current.lang];
          var done = function (text) {
            copy.textContent = text;
            copy.setAttribute('data-done', '');
            setTimeout(function () { copy.textContent = L.copy; copy.removeAttribute('data-done'); }, 1600);
          };
          if (navigator.clipboard && pre) navigator.clipboard.writeText(pre.innerText).then(function () { done(L.copied); }, function () { done('…'); });
        });
      }
    });
  }

  // ---------------------------------------------------------------- header
  function updateHeader(path) {
    var L = I18N[current.lang];
    var other = current.lang === 'en' ? 'es' : 'en';
    root.lang = current.lang;
    $$('.r4c-lang a').forEach(function (a) {
      var l = a.getAttribute('data-lang');
      var target = path === '/' ? '/' + l + '/' : path.replace(/^\/(en|es)\//, '/' + l + '/');
      a.setAttribute('href', target);
      if (l === current.lang) a.setAttribute('aria-current', 'true'); else a.removeAttribute('aria-current');
    });
    var brand = $('.r4c-brand');
    if (brand) brand.setAttribute('href', '/' + current.lang + '/');
    var section = /\/policies\//.test(path) ? 'policies'
      : /\/(performance|benchmarks)$/.test(path) ? 'performance'
      : /\/errors$/.test(path) ? 'errors'
      : isLanding(path) ? '' : 'docs';
    var targets = { docs: 'start/installation', policies: 'policies/', performance: 'performance', errors: 'errors' };
    $$('.r4c-nav a').forEach(function (a) {
      var k = a.getAttribute('data-nav');
      a.textContent = L.nav[k];
      a.setAttribute('href', '/' + current.lang + '/' + targets[k]);
      if (k === section) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    var menu = $('.r4c-menu');
    if (menu) menu.setAttribute('aria-label', L.menu);
    var skip = $('.r4c-skip');
    if (skip) skip.textContent = L.skip;
    updateThemeButton();
    void other;
  }

  // Docsify links a folder's README.md as /x/README; the page's URL is /x/.
  function readmeLinks() {
    $$('a[href]').forEach(function (a) {
      var h = a.getAttribute('href');
      if (/^\/(en|es)\/(.*\/)?README(\?|$)/.test(h)) a.setAttribute('href', h.replace(/README(?=\?|$)/, ''));
    });
  }

  function closeMenu() {
    document.body.classList.remove('close');
    var m = $('.r4c-menu');
    if (m) m.setAttribute('aria-expanded', 'false');
  }

  // ---------------------------------------------------------------- docsify plugin
  function plugin(hook, vm) {
    hook.beforeEach(function (md) {
      var path = vm.route.path;
      current.path = path;
      current.lang = langOf(path);
      var body = document.body;
      body.classList.toggle('r4c-landing', isLanding(path));
      body.classList.toggle('r4c-doc', !isLanding(path));
      return md;
    });

    hook.afterEach(function (html, next) {
      next(compareBlocks(html, I18N[langOf(vm.route.path)]));
    });

    hook.doneEach(function () {
      var path = vm.route.path;
      readmeLinks();
      var L = I18N[current.lang];
      var article = $('.markdown-section');
      updateHeader(path);
      closeMenu();
      // clear a finished search
      var input = $('.search input');
      if (input && input.value) { input.value = ''; input.dispatchEvent(new Event('input')); }
      if (!article) return;
      if (isLanding(path)) {
        document.title = current.lang === 'es' ? 'resilience4cata: resiliencia, compilada' : 'resilience4cata: resilience, compiled';
        initLanding(article);
        buildToc(document.createElement('div'), L);
        if (path === '/') {
          var lang = store.get('r4c-lang') || navigator.language || 'en';
          location.replace('/' + (/^es\b/i.test(lang) ? 'es' : 'en') + '/');
        }
        return;
      }
      callouts(article, L);
      wrapTables(article);
      factsStrips(article);
      var notFound = !!$('.r4c-404', article);
      if (!notFound) titleBlock(article, path, L);
      // Same title as the pre-rendered page (site/…/SiteGenerator), not Docsify's sidebar label.
      var h1 = $('h1', article);
      if (h1 && !notFound) document.title = h1.textContent.replace(/\s+/g, ' ').trim() + ' · resilience4cata';
      buildToc(article, L);
      if (!notFound) pageFooter(article, vm, L);
      renderMermaid();
    });

    hook.mounted(function () {
      var slot = $('.r4c-search-slot');
      var search = $('.sidebar .search') || $('div.search');
      if (slot && search) slot.appendChild(search);
      var theme = $('.r4c-theme');
      if (theme) theme.addEventListener('click', cycleTheme);
      var menu = $('.r4c-menu');
      if (menu) {
        menu.addEventListener('click', function (e) {
          e.stopPropagation();
          var open = document.body.classList.toggle('close');
          menu.setAttribute('aria-expanded', String(open));
        });
      }
      document.addEventListener('click', function (e) {
        if (!document.body.classList.contains('close')) return;
        if (e.target.closest && e.target.closest('.sidebar, .r4c-menu')) return;
        closeMenu();
      });
      $$('.r4c-lang a').forEach(function (a) {
        a.addEventListener('click', function () { store.set('r4c-lang', a.getAttribute('data-lang')); });
      });
      var skip = $('.r4c-skip');
      if (skip) {
        skip.addEventListener('click', function (e) {
          e.preventDefault();
          var main = $('#main');
          if (main) { main.setAttribute('tabindex', '-1'); main.focus(); }
        });
      }
      document.addEventListener('keydown', function (e) {
        var input = $('.search input');
        if (!input) return;
        var tag = (document.activeElement && document.activeElement.tagName) || '';
        if (((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') || (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(tag))) {
          e.preventDefault();
          input.focus();
          input.select();
        } else if (e.key === 'Escape' && document.activeElement === input) {
          input.value = '';
          input.dispatchEvent(new Event('input'));
          input.blur();
        }
      });
    });
  }

  window.R4C = { mermaidBlock: mermaidBlock, renderMermaid: renderMermaid };
  window.$docsify = window.$docsify || {};
  window.$docsify.plugins = (window.$docsify.plugins || []).concat(plugin);
})();
