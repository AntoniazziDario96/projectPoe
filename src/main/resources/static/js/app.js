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
