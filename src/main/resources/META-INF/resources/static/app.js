// Service Portal - adaptive polling, no full page reload

(function () {
    const POLL_FAST = 2000;  // while any session is STARTING
    const POLL_SLOW = 5000;  // all sessions stable

    function hasStarting(model) {
        const all = (model.managementServices || []).concat(model.activeSessions || []);
        return all.some(s => s.state === 'STARTING');
    }

    function sessionKey(toolName, port) {
        return toolName + '-' + port;
    }

    function updateBadge(el, state) {
        el.className = 'badge badge-' + state;
        el.textContent = state;
    }

    async function pollStatus() {
        try {
            const r = await fetch('/api/status');
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
        await fetch('/api/mgmt/' + name + '/start', { method: 'POST' });
        setTimeout(pollStatus, 300);
    };

    window.mgmtStop = async function (name, port) {
        await fetch('/api/mgmt/' + name + '/stop?port=' + port, { method: 'POST' });
        setTimeout(pollStatus, 300);
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

        const r = await fetch('/api/tool/' + toolName + '/launch', {
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

    window.stopSession = async function (toolName, port) {
        const r = await fetch('/api/tool/' + toolName + '/' + port + '/stop', { method: 'POST' });
        if (r.ok) {
            const card = document.getElementById('session-' + sessionKey(toolName, port));
            if (card) card.remove();
        } else {
            alert('Failed to stop session');
        }
    };

    window.detachSession = async function (toolName, port) {
        const r = await fetch('/api/tool/' + toolName + '/' + port + '/detach', { method: 'POST' });
        if (r.ok) {
            const card = document.getElementById('session-' + sessionKey(toolName, port));
            if (card) card.remove();
        } else {
            alert('Failed to detach session');
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
                await fetch('/api/tool/' + toolName + '/' + port + '/memo', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ memo: e.target.value })
                });
            });
        });

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
        const r = await fetch('/api/dirs?path=' + encodeURIComponent(path));
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

})();
