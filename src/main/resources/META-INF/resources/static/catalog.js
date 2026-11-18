// The Catalog tab (DashboardBuilder_261118_oo01): shows, hides, orders and titles the catalog entries the
// Dashboard tab draws, and saves the result to the user's dashboard file through PUT /api/dashboard/layout.
(function () {
    const shownTools = document.getElementById('catalog-shown-tools');
    const shownServices = document.getElementById('catalog-shown-services');
    const hiddenBody = document.getElementById('catalog-hidden');
    const missingBody = document.getElementById('catalog-missing');
    const missingBlock = document.getElementById('catalog-missing-block');
    const status = document.getElementById('catalog-status');
    let shown = [], hidden = [], missing = [];

    function cell(text, mono) {
        const td = document.createElement('td');
        td.textContent = text;
        if (mono) td.className = 'inst-mono';
        return td;
    }

    function button(label, onClick) {
        const b = document.createElement('button');
        b.className = 'btn btn-small';
        b.textContent = label;
        b.addEventListener('click', onClick);
        return b;
    }

    /** The neighbour of shown[i] of the same kind, in direction step (-1 up, +1 down), or -1 when there is none. */
    function neighbour(i, step) {
        for (let k = i + step; k >= 0 && k < shown.length; k += step) {
            if (shown[k].link === shown[i].link) return k;
        }
        return -1;
    }

    function swap(i, k) {
        const t = shown[i]; shown[i] = shown[k]; shown[k] = t;
        draw();
    }

    function draw() {
        // One saved list; the Dashboard tab draws tools and services (link entries) in two sections, so each
        // table shows one kind and up/down moves an entry only among its own kind (DashboardBuilder_261118_oo01).
        shownTools.innerHTML = '';
        shownServices.innerHTML = '';
        shown.forEach(function (t, i) {
            const tr = document.createElement('tr');
            const order = document.createElement('td');
            order.appendChild(button('↑', function () { const k = neighbour(i, -1); if (k >= 0) swap(i, k); }));
            order.appendChild(button('↓', function () { const k = neighbour(i, +1); if (k >= 0) swap(i, k); }));
            tr.appendChild(order);
            const titleCell = document.createElement('td');
            const input = document.createElement('input');
            input.type = 'text';
            input.value = t.title;
            input.placeholder = t.defaultTitle;
            input.addEventListener('input', function () { t.title = input.value; });
            titleCell.appendChild(input);
            tr.appendChild(titleCell);
            tr.appendChild(cell(t.name, true));
            if (!t.link) {
                tr.appendChild(cell(t.branch || '(default)', true));
                tr.appendChild(cell(t.jar, true));
            }
            const act = document.createElement('td');
            act.appendChild(button('Hide', function () { hidden.push(shown.splice(i, 1)[0]); draw(); }));
            tr.appendChild(act);
            (t.link ? shownServices : shownTools).appendChild(tr);
        });
        hiddenBody.innerHTML = '';
        hidden.forEach(function (t, i) {
            const tr = document.createElement('tr');
            tr.appendChild(cell(t.name, true));
            tr.appendChild(cell(t.link ? 'Service' : 'Tool', false));
            tr.appendChild(cell(t.defaultTitle, false));
            // A link entry has no branch and no jar link (DashboardBuilder_261118_oo01).
            tr.appendChild(cell(t.link ? '' : (t.branch || '(default)'), true));
            tr.appendChild(cell(t.link ? '' : t.jar, true));
            const act = document.createElement('td');
            act.appendChild(button('Show', function () { shown.push(hidden.splice(i, 1)[0]); draw(); }));
            tr.appendChild(act);
            hiddenBody.appendChild(tr);
        });
        missingBody.innerHTML = '';
        missingBlock.style.display = missing.length ? '' : 'none';
        missing.forEach(function (t, i) {
            const tr = document.createElement('tr');
            tr.appendChild(cell(t.name, true));
            tr.appendChild(cell(t.title || '', false));
            const act = document.createElement('td');
            act.appendChild(button('Remove', function () { missing.splice(i, 1); draw(); }));
            tr.appendChild(act);
            missingBody.appendChild(tr);
        });
    }

    function take(layout) {
        shown = layout.shown;
        hidden = layout.hidden;
        missing = layout.missing;
        draw();
    }

    document.getElementById('catalog-save').addEventListener('click', async function () {
        // Tiles the catalog no longer has go back too, unless the user removed them: the file keeps them.
        const tiles = shown.map(function (t) { return { name: t.name, title: t.title.trim() }; })
            .concat(missing.map(function (t) { return { name: t.name, title: t.title || '' }; }));
        status.textContent = 'saving…';
        const r = await fetch('api/dashboard/layout', {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tiles: tiles })
        });
        const body = await r.json();
        if (r.ok) { take(body); status.textContent = 'saved'; }
        else { status.textContent = 'not saved: ' + (body.error || r.status); }
    });

    fetch('api/dashboard/layout').then(function (r) { return r.json(); }).then(take);
})();
