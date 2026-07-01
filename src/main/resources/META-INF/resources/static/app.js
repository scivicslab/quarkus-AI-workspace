// AI Workspace dashboard — adaptive polling, tab UI, drag-to-reorder

(function () {
    const POLL_FAST = 2000;  // while any session is STARTING
    const POLL_SLOW = 5000;  // all sessions stable

    // ---------------------------------------------------------------
    // localStorage: team definitions and session assignments
    // ---------------------------------------------------------------

    const TEAMS_KEY = 'sessionTeams';
    const ACTIVE_TAB_KEY = 'activeSessionTab';

    function loadTeams() {
        try {
            const raw = localStorage.getItem(TEAMS_KEY);
            if (raw) {
                const data = JSON.parse(raw);
                if (Array.isArray(data.teams) && typeof data.assignments === 'object') return data;
            }
        } catch (_) {}
        return { teams: ['Default'], assignments: {} };
    }

    function saveTeams(data) {
        localStorage.setItem(TEAMS_KEY, JSON.stringify(data));
    }

    // Key for localStorage assignments: "toolName:port"
    function teamKey(toolName, port) {
        return toolName + ':' + port;
    }

    function extractPort(card) {
        const portEl = card.querySelector('.session-port');
        return portEl ? parseInt(portEl.textContent.replace(':', ''), 10) : 0;
    }

    function extractToolName(card) {
        return card.querySelector('.session-name')?.textContent?.trim() || '';
    }

    // ---------------------------------------------------------------
    // Tab UI
    // ---------------------------------------------------------------

    function buildTabs() {
        const tabBar = document.getElementById('session-tab-bar');
        const panesContainer = document.getElementById('session-tab-panes');
        if (!tabBar || !panesContainer) return;

        const { teams, assignments } = loadTeams();
        const cards = Array.from(panesContainer.querySelectorAll('.session-card'));

        tabBar.innerHTML = '';
        panesContainer.querySelectorAll('.tab-pane').forEach(p => p.remove());

        if (teams.length <= 1) {
            tabBar.style.display = 'none';
            cards.forEach(c => { c.style.display = ''; });
            attachDragHandlers(panesContainer);
            return;
        }

        tabBar.style.display = '';

        const savedTab = localStorage.getItem(ACTIVE_TAB_KEY);
        const activeTeam = teams.includes(savedTab) ? savedTab : teams[0];

        // Create panes
        const panes = {};
        teams.forEach(team => {
            const pane = document.createElement('div');
            pane.className = 'tab-pane' + (team === activeTeam ? ' active' : '');
            pane.dataset.group = team;
            panesContainer.appendChild(pane);
            panes[team] = pane;
        });

        // Assign cards to panes; track saved position for sorting
        cards.forEach(card => {
            const tn  = extractToolName(card);
            const p   = extractPort(card);
            const key = teamKey(tn, p);
            const team = (assignments[key] && assignments[key].team) || 'Default';
            const pos  = (assignments[key] && assignments[key].position != null) ? assignments[key].position : Infinity;
            card._sortPos = pos;
            const pane = panes[team] || panes[teams[0]];
            if (pane) pane.appendChild(card);
        });

        // Sort cards within each pane by saved position
        teams.forEach(team => {
            const pane = panes[team];
            if (!pane) return;
            Array.from(pane.querySelectorAll('.session-card'))
                .sort((a, b) => (a._sortPos || 0) - (b._sortPos || 0))
                .forEach(c => pane.appendChild(c));
        });

        // Build tab buttons
        teams.forEach(team => {
            const btn = document.createElement('button');
            btn.className = 'tab-btn' + (team === activeTeam ? ' active' : '');
            btn.textContent = team;
            btn.onclick = () => switchTab(team);
            tabBar.appendChild(btn);
        });

        // "+" button to add a new team
        const addBtn = document.createElement('button');
        addBtn.className = 'tab-btn';
        addBtn.textContent = '+';
        addBtn.title = 'Add team';
        addBtn.onclick = addTeam;
        tabBar.appendChild(addBtn);

        teams.forEach(team => attachDragHandlers(panes[team]));

        // Prune stale assignments (sessions that no longer exist in DOM)
        const activeKeys = new Set(cards.map(c => teamKey(extractToolName(c), extractPort(c))));
        const pruned = {};
        Object.keys(assignments).forEach(k => { if (activeKeys.has(k)) pruned[k] = assignments[k]; });
        saveTeams({ teams, assignments: pruned });
    }

    function addTeam() {
        const name = prompt('Team name:');
        if (!name || !name.trim()) return;
        const trimmed = name.trim();
        const data = loadTeams();
        if (data.teams.includes(trimmed)) return;
        data.teams.push(trimmed);
        saveTeams(data);
        buildTabs();
        switchTab(trimmed);
    }

    function switchTab(team) {
        localStorage.setItem(ACTIVE_TAB_KEY, team);
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.textContent === team);
        });
        document.querySelectorAll('#session-tab-panes .tab-pane').forEach(pane => {
            pane.classList.toggle('active', pane.dataset.group === team);
        });
    }

    // ---------------------------------------------------------------
    // Drag & drop reorder (within pane)
    // ---------------------------------------------------------------

    let _dragSrc = null;

    function attachDragHandlers(container) {
        if (!container) return;
        container.querySelectorAll('.session-card').forEach(card => {
            card.addEventListener('dragstart', onDragStart);
            card.addEventListener('dragend',   onDragEnd);
            card.addEventListener('dragover',  onDragOver);
            card.addEventListener('drop',      onDrop);
        });
    }

    function onDragStart(e) {
        _dragSrc = this;
        this.classList.add('dragging');
        e.dataTransfer.effectAllowed = 'move';
    }

    function onDragEnd() {
        this.classList.remove('dragging');
        document.querySelectorAll('.session-card').forEach(c => c.classList.remove('drag-over'));
        _dragSrc = null;
    }

    function onDragOver(e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
        document.querySelectorAll('.session-card').forEach(c => c.classList.remove('drag-over'));
        if (_dragSrc && this !== _dragSrc) this.classList.add('drag-over');
    }

    function onDrop(e) {
        e.preventDefault();
        if (!_dragSrc || _dragSrc === this) return;
        this.classList.remove('drag-over');

        const parent = this.parentNode;
        const cards  = Array.from(parent.querySelectorAll('.session-card'));
        const srcIdx = cards.indexOf(_dragSrc);
        const dstIdx = cards.indexOf(this);

        if (srcIdx < dstIdx) {
            parent.insertBefore(_dragSrc, this.nextSibling);
        } else {
            parent.insertBefore(_dragSrc, this);
        }

        // Persist new order
        const data = loadTeams();
        Array.from(parent.querySelectorAll('.session-card')).forEach((c, i) => {
            const tn  = extractToolName(c);
            const p   = extractPort(c);
            const key = teamKey(tn, p);
            if (!data.assignments[key]) data.assignments[key] = { team: 'Default' };
            data.assignments[key].position = i;
        });
        saveTeams(data);
    }

    // ---------------------------------------------------------------
    // ⋮ session menu (move to team)
    // ---------------------------------------------------------------

    window.openSessionMenu = function (e, toolName, port) {
        e.stopPropagation();
        const popup = document.getElementById('session-menu-popup');
        if (!popup) return;

        const { teams, assignments } = loadTeams();
        const key = teamKey(toolName, port);
        const currentTeam = (assignments[key] && assignments[key].team) || 'Default';

        popup.innerHTML = '';

        if (teams.length > 1) {
            const label = document.createElement('div');
            label.className = 'menu-item';
            label.style.cssText = 'font-size:11px;text-transform:uppercase;letter-spacing:.06em;pointer-events:none;opacity:.6';
            label.textContent = 'Move to team';
            popup.appendChild(label);

            teams.filter(t => t !== currentTeam).forEach(team => {
                const item = document.createElement('div');
                item.className = 'menu-item';
                item.textContent = team;
                item.onclick = () => { moveSessionToTeam(toolName, port, team); popup.style.display = 'none'; };
                popup.appendChild(item);
            });
        }

        popup.style.display = 'block';
        const rect = e.target.getBoundingClientRect();
        popup.style.top  = (rect.bottom + window.scrollY + 4) + 'px';
        popup.style.left = Math.min(rect.left, window.innerWidth - 180) + 'px';
    };

    function moveSessionToTeam(toolName, port, team) {
        const data = loadTeams();
        const key = teamKey(toolName, port);
        if (!data.assignments[key]) data.assignments[key] = {};
        data.assignments[key].team = team;
        data.assignments[key].position = Infinity;
        saveTeams(data);
        buildTabs();
        switchTab(team);
    }

    document.addEventListener('click', () => {
        const p = document.getElementById('session-menu-popup');
        if (p) p.style.display = 'none';
    });

    function hasStarting(model) {
        const all = (model.managementServices || []).concat(model.activeSessions || []);
        return all.some(s => s.state === 'STARTING');
    }

    // Key for DOM element IDs: "toolName-port"
    function sessionKey(toolName, port) {
        return toolName + '-' + port;
    }

    function updateBadge(el, state) {
        el.className = 'badge badge-' + state;
        el.textContent = state;
    }

    async function pollStatus() {
        try {
            const r = await fetch('api/status');
            if (!r.ok) return;
            const model = await r.json();
            applyModel(model);
            const interval = hasStarting(model) ? POLL_FAST : POLL_SLOW;
            setTimeout(pollStatus, interval);
        } catch (e) {
            setTimeout(pollStatus, POLL_SLOW);
        }
    }

    function applyModel(model) {
        const all = (model.managementServices || []).concat(model.activeSessions || []);
        all.forEach(svc => {
            const card = document.getElementById('session-' + sessionKey(svc.toolName, svc.port));
            if (!card) {
                // New session appeared — reload page to render new card
                location.reload();
                return;
            }
            // Update badge
            const badge = card.querySelector('.badge');
            if (badge) updateBadge(badge, svc.state);

            // Update link/name
            const nameEl = card.querySelector('.session-name');
            if (nameEl && svc.accessUrl && nameEl.tagName !== 'A') {
                const a = document.createElement('a');
                a.className = 'session-name';
                a.href = svc.accessUrl;
                a.target = '_blank';
                a.textContent = svc.toolName;
                nameEl.replaceWith(a);
            }

            // Update Start/Stop button for management services
            const actions = card.querySelector('.session-actions');
            if (actions) {
                const isStopped = svc.state === 'STOPPED' || svc.state === 'FAILED';
                const btn = actions.querySelector('button');
                const isStartBtn = btn && btn.classList.contains('btn-start');
                if (isStopped && !isStartBtn) {
                    actions.innerHTML = '<button class="btn btn-start" onclick="mgmtStart(\'' + svc.toolName + '\', ' + svc.port + ')">Start</button>';
                } else if (!isStopped && isStartBtn) {
                    actions.innerHTML = '<button class="btn btn-stop" onclick="mgmtStop(\'' + svc.toolName + '\', ' + svc.port + ')">Stop</button>';
                }
            }

            // Update progress log
            const logEl = card.querySelector('.session-log');
            if (svc.state === 'STARTING' && svc.progressLog && svc.progressLog.length > 0) {
                if (logEl) {
                    logEl.innerHTML = svc.progressLog.join('<br>');
                }
            } else if (logEl && svc.state === 'READY') {
                logEl.style.display = 'none';
            }
        });

        // Check for sessions that disappeared (stopped)
        document.querySelectorAll('.session-card').forEach(card => {
            const id = card.id.replace('session-', '');
            const stillExists = all.some(s => sessionKey(s.toolName, s.port) === id);
            if (!stillExists) {
                card.remove();
            }
        });
    }

    // ---------------------------------------------------------------
    // Management service actions
    // ---------------------------------------------------------------

    window.mgmtStart = async function (name, port) {
        await fetch('api/mgmt/' + name + '/start', { method: 'POST' });
        setTimeout(pollStatus, 300);
    };

    window.mgmtStop = async function (name, port) {
        await fetch('api/mgmt/' + name + '/stop?port=' + port, { method: 'POST' });
        setTimeout(pollStatus, 300);
    };

    // ---------------------------------------------------------------
    // MCP Gateway mode switching
    // ---------------------------------------------------------------

    window.useExternalGateway = async function () {
        const input = document.getElementById('param-mcp-gateway-external-url');
        const url = input ? input.value.trim() : '';
        if (!url) { alert('External URL is required'); return; }
        const r = await fetch('api/mcp-gateway/use-external', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url })
        });
        if (r.ok) {
            location.reload();
        } else {
            const body = await r.json().catch(() => ({}));
            alert('Failed to switch to external MCP Gateway: ' + (body.error || r.status));
        }
    };

    window.useInternalGateway = async function () {
        const r = await fetch('api/mcp-gateway/use-internal', { method: 'POST' });
        if (r.ok) {
            location.reload();
        } else {
            const body = await r.json().catch(() => ({}));
            alert('Failed to switch to internal MCP Gateway: ' + (body.error || r.status));
        }
    };

    // ---------------------------------------------------------------
    // Tool session actions
    // ---------------------------------------------------------------

    window.launchTool = async function (toolName) {
        const tile = document.getElementById('tool-tile-' + toolName);
        if (tile) {
            tile.style.opacity = '0.5';
            tile.style.pointerEvents = 'none';
        }

        const body = {};
        document.querySelectorAll('[id^="param-' + toolName + '-"]').forEach(el => {
            const key = el.id.replace('param-' + toolName + '-', '');
            const value = el.value.trim();
            if (value) body[key] = value;
        });

        const r = await fetch('api/tool/' + toolName + '/launch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        if (r.ok) {
            // Reload to show new session card, then resume polling
            location.reload();
        } else {
            alert('Failed to launch ' + toolName);
            if (tile) {
                tile.style.opacity = '1';
                tile.style.pointerEvents = 'auto';
            }
        }
    };

    window.stopSession = async function (toolName, port, btn) {
        if (btn) { btn.disabled = true; btn.textContent = 'Stopping…'; }
        const r = await fetch('api/tool/' + toolName + '/' + port + '/stop', { method: 'POST' });
        if (r.ok) {
            const card = document.getElementById('session-' + sessionKey(toolName, port));
            if (card) {
                card.classList.add('stopping');
                card.addEventListener('transitionend', () => card.remove(), { once: true });
            }
        } else {
            if (btn) { btn.disabled = false; btn.textContent = 'Stop'; }
            alert('Failed to stop session');
        }
    };

    // ---------------------------------------------------------------
    // Memo update (on blur)
    // ---------------------------------------------------------------

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.memo-input').forEach(input => {
            input.addEventListener('blur', async e => {
                const toolName = e.target.dataset.toolName;
                const port = e.target.dataset.port;
                await fetch('api/tool/' + toolName + '/' + port + '/memo', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ memo: e.target.value })
                });
            });
        });

        buildTabs();

        // Start polling
        setTimeout(pollStatus, POLL_SLOW);
    });

    // ---------------------------------------------------------------
    // Directory browser modal
    // ---------------------------------------------------------------

    let _dirTargetInputId = null;

    window.openDirBrowser = async function (inputId) {
        _dirTargetInputId = inputId;
        const input = document.getElementById(inputId);
        const startPath = (input && input.value.trim()) || '~';
        await loadDirListing(startPath);
        document.getElementById('dir-modal-overlay').classList.add('open');
    };

    window.closeDirBrowser = function () {
        document.getElementById('dir-modal-overlay').classList.remove('open');
        _dirTargetInputId = null;
    };

    window.confirmDirBrowser = function () {
        const pathEl = document.getElementById('dir-modal-path');
        if (_dirTargetInputId && pathEl) {
            const input = document.getElementById(_dirTargetInputId);
            if (input) input.value = pathEl.textContent;
        }
        closeDirBrowser();
    };

    async function loadDirListing(path) {
        const r = await fetch('api/dirs?path=' + encodeURIComponent(path));
        if (!r.ok) return;
        const data = await r.json();

        document.getElementById('dir-modal-path').textContent = data.path;

        const list = document.getElementById('dir-modal-list');
        list.innerHTML = '';

        // Parent directory entry
        if (data.parent) {
            const up = document.createElement('div');
            up.className = 'dir-item dir-item-up';
            up.textContent = '⬆ ..';
            up.onclick = () => loadDirListing(data.parent);
            list.appendChild(up);
        }

        // Subdirectories
        data.dirs.forEach(name => {
            const item = document.createElement('div');
            item.className = 'dir-item';
            item.textContent = '📁 ' + name;
            item.onclick = () => loadDirListing(data.path + '/' + name);
            list.appendChild(item);
        });

        if (!data.parent && data.dirs.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'dir-item';
            empty.style.color = 'var(--muted)';
            empty.textContent = '(empty)';
            list.appendChild(empty);
        }
    }

    // Close modal when clicking outside
    document.addEventListener('DOMContentLoaded', () => {
        document.getElementById('dir-modal-overlay').addEventListener('click', e => {
            if (e.target === e.currentTarget) closeDirBrowser();
        });
    });

    // ── Download latest jar from GitHub Releases ──────────────────
    window.downloadTool = async function(name) {
        const btn = document.getElementById('btn-download-' + name);
        const status = document.getElementById('download-status-' + name);
        if (!btn) return;
        btn.disabled = true;
        btn.textContent = 'Downloading…';
        status.textContent = '';
        status.style.color = '';
        try {
            const r = await fetch('api/tool/' + encodeURIComponent(name) + '/download', { method: 'POST' });
            const data = await r.json();
            if (data.success) {
                status.textContent = '✓ ' + (data.version || 'updated');
                status.style.color = 'var(--accent-green)';
            } else {
                status.textContent = '✗ ' + (data.error || 'failed');
                status.style.color = 'var(--accent-red)';
            }
        } catch (e) {
            status.textContent = '✗ ' + e.message;
            status.style.color = 'var(--accent-red)';
        } finally {
            btn.disabled = false;
            btn.textContent = 'Download Latest';
        }
    };

    // Build the tool from GitHub source (clone → mvn install → copy uber-jar to ~/works).
    // The build runs on the server as a background job; we poll for its state.
    window.buildSnapshot = async function(name) {
        const btn = document.getElementById('btn-build-' + name);
        const status = document.getElementById('download-status-' + name);
        if (!btn) return;
        btn.disabled = true;
        btn.textContent = 'Building…';
        status.textContent = '';
        status.style.color = '';
        try {
            const r = await fetch('api/tool/' + encodeURIComponent(name) + '/build-snapshot', { method: 'POST' });
            const data = await r.json();
            if (!r.ok || !data.jobId) {
                status.textContent = '✗ ' + (data.error || 'failed to start build');
                status.style.color = 'var(--accent-red)';
                return;
            }
            await pollBuild(name, data.jobId, btn, status);
        } catch (e) {
            status.textContent = '✗ ' + e.message;
            status.style.color = 'var(--accent-red)';
        } finally {
            btn.disabled = false;
            btn.textContent = 'Build Snapshot';
        }
    };

    async function pollBuild(name, jobId, btn, status) {
        const url = 'api/tool/' + encodeURIComponent(name) + '/build-status/' + encodeURIComponent(jobId);
        while (true) {
            await new Promise(res => setTimeout(res, 2000));
            let data;
            try {
                const r = await fetch(url);
                data = await r.json();
            } catch (e) {
                continue; // transient; keep polling
            }
            if (data.state === 'RUNNING') {
                status.textContent = '⏳ ' + (data.step || 'building…');
                status.style.color = 'var(--text-secondary)';
                status.title = data.log || '';
            } else if (data.state === 'SUCCESS') {
                status.textContent = '✓ built ' + (data.file || 'snapshot');
                status.style.color = 'var(--accent-green)';
                status.title = data.log || '';
                return;
            } else {
                status.textContent = '✗ ' + (data.error || 'build failed');
                status.style.color = 'var(--accent-red)';
                status.title = data.log || '';
                return;
            }
        }
    }

})();
