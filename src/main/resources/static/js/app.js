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
                        delete row.dataset.priceToken;
                        break;
                    }
                }
            } catch (err) {
                cell.textContent = '–';
            }
            delete row.dataset.priceToken;
            resort();
            table.dispatchEvent(new CustomEvent('prices-changed'));
        }
        table.dispatchEvent(new CustomEvent('prices-changed'));
    }
    resort();
    load();
})();

// Confronta: piano con budget. Sceglie i cambi che avvicinano di più alla build
// (somma dell'"impatto" di ogni item) senza superare il budget.
(function () {
    const box = document.querySelector('[data-budget]');
    const table = document.querySelector('[data-suggestions]');
    if (!box || !table) return;
    const input = box.querySelector('[data-budget-input]');
    const unit = box.querySelector('[data-budget-unit]');
    const out = box.querySelector('[data-budget-result]');
    const divine = Number(box.dataset.divine) || 0;
    const t = (window.I18N && window.I18N.budget) || {};

    function fill(text, args) {
        return (text || '').replace(/\{(\d)\}/g, (m, i) => (args[Number(i)] === undefined ? '' : args[Number(i)]));
    }

    function price(chaos) {
        if (divine > 0 && chaos >= divine) return (chaos / divine).toFixed(1) + ' div';
        return (chaos < 10 ? chaos.toFixed(1) : Math.round(chaos)) + ' c';
    }

    // scelta migliore: con pochi item le proviamo tutte, altrimenti per valore/prezzo
    function choose(items, budget) {
        if (items.length <= 16) {
            let best = { value: 0, cost: 0, mask: 0 };
            for (let mask = 1; mask < (1 << items.length); mask++) {
                let cost = 0;
                let value = 0;
                for (let i = 0; i < items.length; i++) {
                    if (mask & (1 << i)) { cost += items[i].price; value += items[i].impact; }
                }
                if (cost <= budget && (value > best.value + 1e-9
                        || (Math.abs(value - best.value) < 1e-9 && cost < best.cost))) {
                    best = { value, cost, mask };
                }
            }
            return items.filter((it, i) => best.mask & (1 << i));
        }
        const sorted = items.slice().sort((a, b) => b.impact / b.price - a.impact / a.price);
        const picked = [];
        let left = budget;
        for (const it of sorted) {
            if (it.price <= left) { picked.push(it); left -= it.price; }
        }
        return picked;
    }

    const list = box.querySelector('[data-budget-list]');
    const apply = box.querySelector('[data-budget-apply]');

    function rowLabel(r) {
        const slot = r.cells[0].querySelector('span');
        const item = r.cells[2].querySelector('.unique-name, .rare-name');
        return (slot ? slot.textContent.trim() + ': ' : '') + (item ? item.textContent.trim() : '');
    }

    function setEnabled(on) {
        box.classList.toggle('is-disabled', !on);
        [input, unit, apply].forEach((el) => { if (el) el.disabled = !on; });
    }

    function update() {
        const rows = Array.from(table.querySelectorAll('tbody tr'));
        rows.forEach((r) => {
            r.classList.remove('in-plan');
            const b = r.querySelector('[data-plan-badge]');
            if (b) b.hidden = true;
        });
        list.innerHTML = '';
        list.hidden = true;

        const items = [];
        let totalImpact = 0;
        let unpriced = 0;
        let waiting = 0;
        rows.forEach((r) => {
            const impact = Number(r.dataset.impact) || 0;
            totalImpact += impact;
            const p = r.dataset.price;
            if (p === undefined || p === '') {
                if (r.dataset.priceToken) waiting++; else unpriced++;
                return;
            }
            const chaos = Number(p);
            if (chaos > 0 && impact > 0) items.push({ row: r, price: chaos, impact: impact });
        });

        // nessun item ha un prezzo (es. lega evento): il budget non può scegliere niente
        if (!items.length && !waiting) {
            setEnabled(false);
            out.textContent = t.noPrices || t.nothing || '';
            return;
        }
        setEnabled(true);

        const amount = parseFloat(String(input.value).replace(',', '.'));
        if (!(amount > 0)) {
            out.textContent = (t.help || '') + (waiting ? ' ' + (t.waiting || '') : '');
            return;
        }
        const budget = unit.value === 'div' ? amount * divine : amount;
        const picked = choose(items, budget);
        picked.forEach((it) => {
            it.row.classList.add('in-plan');
            const b = it.row.querySelector('[data-plan-badge]');
            if (b) b.hidden = false;
        });
        let text;
        if (!picked.length) {
            text = t.nothing || '';
        } else {
            const cost = picked.reduce((a, it) => a + it.price, 0);
            const value = picked.reduce((a, it) => a + it.impact, 0);
            const pct = totalImpact > 0 ? Math.round(value / totalImpact * 100) : 0;
            text = fill(t.result, [picked.length, price(cost), pct]);
            // elenco dei cambi scelti, dal più utile
            picked.slice().sort((a, b) => b.impact - a.impact).forEach((it) => {
                const li = document.createElement('li');
                const name = document.createElement('span');
                name.textContent = rowLabel(it.row);
                const cost1 = document.createElement('span');
                cost1.className = 'muted';
                cost1.textContent = price(it.price);
                li.append(name, cost1);
                list.appendChild(li);
            });
            list.hidden = false;
        }
        if (waiting) text += ' ' + (t.waiting || '');
        else if (unpriced) text += ' ' + fill(t.unpriced, [unpriced]);
        out.textContent = text;
    }

    try {
        const saved = localStorage.getItem('budget');
        const savedUnit = localStorage.getItem('budgetUnit');
        if (saved) input.value = saved;
        if (savedUnit && unit.querySelector('option[value="' + savedUnit + '"]')) unit.value = savedUnit;
    } catch (e) { /* storage non disponibile */ }

    function changed() {
        try {
            localStorage.setItem('budget', input.value);
            localStorage.setItem('budgetUnit', unit.value);
        } catch (e) { /* storage non disponibile */ }
        update();
    }
    input.addEventListener('input', changed);
    unit.addEventListener('change', changed);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); changed(); }
    });
    if (apply) {
        apply.addEventListener('click', () => {
            changed();
            // porta in vista il primo item scelto, se c'è
            const first = table.querySelector('tr.in-plan');
            if (first) first.scrollIntoView({ behavior: 'smooth', block: 'center' });
        });
    }
    table.addEventListener('prices-changed', update);
    update();
})();
