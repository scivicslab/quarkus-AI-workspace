// Service Portal JavaScript

// Auto-refresh every 5 seconds
setInterval(async () => {
    const response = await fetch('/api/status');
    if (response.ok) {
        const data = await response.json();
        updateDashboard(data);
    }
}, 5000);

// Update dashboard with new data
function updateDashboard(data) {
    // TODO: Implement dynamic update without full page reload
    // For now, just reload the page
    location.reload();
}

// Management service actions
async function mgmtStart(name) {
    const progressDiv = document.getElementById(`progress-${name}`);
    if (progressDiv) {
        progressDiv.classList.add('active');
        progressDiv.innerHTML = 'Starting...';
    }

    const response = await fetch(`/api/mgmt/${name}/start`, { method: 'POST' });
    if (response.ok) {
        pollProgress(name);
    } else {
        alert('Failed to start service');
        if (progressDiv) {
            progressDiv.classList.remove('active');
        }
    }
}

async function mgmtStop(name) {
    const response = await fetch(`/api/mgmt/${name}/stop`, { method: 'POST' });
    if (response.ok) {
        setTimeout(() => location.reload(), 1000);
    } else {
        alert('Failed to stop service');
    }
}

// Poll progress for starting services
function pollProgress(serviceName) {
    const progressDiv = document.getElementById(`progress-${serviceName}`);
    const interval = setInterval(async () => {
        const response = await fetch(`/api/mgmt/${serviceName}/progress`);
        if (response.ok) {
            const data = await response.json();
            if (data.phase === 'COMPLETE') {
                clearInterval(interval);
                location.reload();
            } else if (data.messages) {
                progressDiv.innerHTML = data.messages.join('<br>');
            }
        }
    }, 500);
}

// Tool actions
async function launchTool(toolName) {
    const tile = document.getElementById(`tool-tile-${toolName}`);
    if (tile) {
        tile.style.opacity = '0.5';
        tile.style.pointerEvents = 'none';
    }

    const response = await fetch(`/api/tool/${toolName}/launch`, { method: 'POST' });
    if (response.ok) {
        setTimeout(() => location.reload(), 2000);
    } else {
        alert('Failed to launch tool');
        if (tile) {
            tile.style.opacity = '1';
            tile.style.pointerEvents = 'auto';
        }
    }
}

async function stopTool(toolName, port) {
    const response = await fetch(`/api/tool/${toolName}/${port}/stop`, { method: 'POST' });
    if (response.ok) {
        setTimeout(() => location.reload(), 1000);
    } else {
        alert('Failed to stop tool');
    }
}

// Memo update
document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.memo-input').forEach(input => {
        input.addEventListener('blur', async (e) => {
            const toolName = e.target.dataset.toolName;
            const port = e.target.dataset.port;
            const memo = e.target.value;

            const response = await fetch(`/api/tool/${toolName}/${port}/memo`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ memo })
            });

            if (!response.ok) {
                alert('Failed to update memo');
            }
        });
    });
});

// Container actions
async function containerStart(name, remote) {
    const response = await fetch(`/api/container/${name}/start`, { method: 'POST' });
    if (response.ok) {
        setTimeout(() => location.reload(), 2000);
    } else {
        alert('Failed to start container');
    }
}

async function containerStop(name, remote) {
    const response = await fetch(`/api/container/${name}/stop`, { method: 'POST' });
    if (response.ok) {
        setTimeout(() => location.reload(), 1000);
    } else {
        alert('Failed to stop container');
    }
}
