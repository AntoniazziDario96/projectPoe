// Tema chiaro/scuro
document.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-theme-toggle]');
    if (!btn) return;
    const next = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (err) { /* storage non disponibile */ }
});

// Lingua IT/EN: cambia lingua restando sulla stessa pagina (mantiene lega, build, filtri...)
document.addEventListener('click', (e) => {
    const link = e.target.closest('[data-lang-toggle]');
    if (!link) return;
    e.preventDefault();
    const url = new URL(location.href);
    url.searchParams.set('lang', link.dataset.langToggle);
    location.href = url.toString();
});

// Copia negli appunti (PoB, lista oggetti)
document.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-copy]');
    if (!btn) return;
    const target = document.getElementById(btn.dataset.copy);
    if (!target) return;
    const text = target.value !== undefined ? target.value : target.textContent;
    try {
        await navigator.clipboard.writeText(text);
    } catch (err) {
        target.select && target.select();
        document.execCommand('copy');
    }
    const old = btn.textContent;
    btn.textContent = (window.I18N && window.I18N.copied) || 'Copiato!';
    setTimeout(() => { btn.textContent = old; }, 1500);
});

// Filtri della tabella build (testo + ascendancy), lato client
(function () {
    const table = document.querySelector('[data-combo-table]');
    if (!table) return;
    const text = document.querySelector('[data-filter-text]');
    const asc = document.querySelector('[data-filter-asc]');
    const onlyDone = document.querySelector('[data-filter-done]');
    const counter = document.querySelector('[data-selected-count]');
    const submit = document.querySelector('[data-analyze-btn]');

    function apply() {
        const q = (text.value || '').toLowerCase();
        const a = asc.value;
        let visible = 0;
        table.querySelectorAll('tbody tr').forEach((tr) => {
            const ok = (!q || tr.dataset.search.includes(q))
                && (!a || tr.dataset.asc === a)
                && (!onlyDone.checked || tr.dataset.done === 'true');
            tr.hidden = !ok;
            if (ok) visible++;
        });
        const shown = document.querySelector('[data-visible-count]');
        if (shown) shown.textContent = visible;
    }

    function updateSelected() {
        const n = table.querySelectorAll('input[name="combo"]:checked').length;
        counter.textContent = n;
        submit.disabled = n === 0;
    }

    [text, asc, onlyDone].forEach((el) => el && el.addEventListener('input', apply));
    table.addEventListener('change', updateSelected);
    apply();
    updateSelected();
})();

// Avanzamento delle analisi in corso: aggiorna le barre e ricarica quando finiscono
(function () {
    const boxes = document.querySelectorAll('[data-analysis-progress]');
    const waiting = document.querySelector('[data-reload-when-idle]');
    if (!boxes.length && !waiting) return;

    async function poll() {
        let list = [];
        try {
            const res = await fetch('/api/analyses', { headers: { 'Accept': 'application/json' } });
            list = await res.json();
        } catch (e) {
            setTimeout(poll, 5000);
            return;
        }
        const byKey = new Map(list.map((a) => [a.ascendancy + '|' + a.skill, a]));
        let finished = false;
        boxes.forEach((box) => {
            const a = byKey.get(box.dataset.analysisProgress);
            if (!a) { finished = true; return; }
            const bar = box.querySelector('.progress > span');
            const label = box.querySelector('[data-progress-label]');
            if (bar) bar.style.width = a.percent + '%';
            if (label) label.textContent = a.message + (a.total ? ` (${a.progress}/${a.total})` : '');
        });
        if (finished || (waiting && list.length === 0)) {
            location.reload();
            return;
        }
        setTimeout(poll, 3000);
    }
    setTimeout(poll, 2000);
})();

// Ricarica automatica mentre i dati sono in caricamento
(function () {
    const el = document.querySelector('[data-auto-reload]');
    if (!el) return;
    setTimeout(() => location.reload(), Number(el.dataset.autoReload) || 4000);
})();

// Confronta: scelta della fonte (PoB / poe.ninja)
(function () {
    const form = document.querySelector('[data-source-form]');
    if (!form) return;
    function show() {
        const checked = form.querySelector('[data-source-tab]:checked');
        const source = checked ? checked.value : 'pob';
        form.querySelectorAll('[data-source-panel]').forEach((p) => {
            p.hidden = p.dataset.sourcePanel !== source;
        });
    }
    form.querySelectorAll('[data-source-tab]').forEach((r) => r.addEventListener('change', show));
    show();
})();

// Form che si inviano da soli quando cambia una scelta
document.querySelectorAll('[data-autosubmit]').forEach((form) => {
    form.addEventListener('change', () => form.submit());
});

// Conferma prima di azioni come "Elimina"
document.addEventListener('submit', (e) => {
    const msg = e.target.dataset && e.target.dataset.confirm;
    if (msg && !window.confirm(msg)) e.preventDefault();
});

// Confronta: prezzi dei gialli letti dal trade, uno alla volta, poi riordino per prezzo
(function () {
    const table = document.querySelector('[data-suggestions]');
    if (!table) return;
    const tbody = table.querySelector('tbody');
    const byPrice = table.dataset.sortByPrice === 'true';
    const texts = (window.I18N && window.I18N.price) || {};

    function resort() {
        if (!byPrice) return;
        const rows = Array.from(tbody.querySelectorAll('tr'));
        rows.sort((a, b) => {
            const pa = a.dataset.price === undefined || a.dataset.price === '' ? Infinity : Number(a.dataset.price);
            const pb = b.dataset.price === undefined || b.dataset.price === '' ? Infinity : Number(b.dataset.price);
            return pa - pb;
        });
        rows.forEach((r) => tbody.appendChild(r));
    }

    async function load() {
        const rows = Array.from(tbody.querySelectorAll('tr[data-price-token]'));
        for (const row of rows) {
            const cell = row.querySelector('[data-price-cell]');
            try {
                const res = await fetch('/api/compare/price/' + encodeURIComponent(row.dataset.priceToken),
                    { headers: { 'Accept': 'application/json' } });
                const data = await res.json();
                if (data.status === 'ok') {
                    row.dataset.price = data.chaos;
                    cell.textContent = data.text;
                } else {
                    cell.innerHTML = '';
                    const span = document.createElement('span');
                    span.className = 'muted small';
                    span.textContent = texts[data.status] || '–';
                    cell.appendChild(span);
                    if (data.status === 'league') {
                        // la lega non è sul trade: inutile cercare gli altri
                        rows.filter((r) => r !== row && r.dataset.priceToken).forEach((r) => {
                            const c = r.querySelector('[data-price-cell]');
                            c.innerHTML = '';
                            const s2 = document.createElement('span');
                            s2.className = 'muted small';
                            s2.textContent = texts.league || '–';
                            c.appendChild(s2);
                            delete r.dataset.priceToken;
                        });
                        break;
                    }
                }
            } catch (err) {
                cell.textContent = '–';
            }
            delete row.dataset.priceToken;
            resort();
        }
    }
    resort();
    load();
})();
