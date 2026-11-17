// @ts-check
const { test, expect } = require('@playwright/test');
const { resolvePortalUrl } = require('./resolve-portal-url');

/*
 * The AI-workspace dashboard and its API, against a throwaway AI-workspace (PORTAL_URL).
 *
 * The launch tests start and stop chat-ui-with-audit-trail-local-llm, so the throwaway AI-workspace's
 * works directory (its working directory) must hold a chat-ui-with-audit-trail.jar. A stand-in that only
 * opens the HTTP port it is given (-Dquarkus.http.port) is enough: these tests check what AI-workspace
 * does, not what chat-ui does.
 */
const PORTAL_URL = resolvePortalUrl();
const CHAT_UI = 'chat-ui-with-audit-trail-local-llm';

/** Polls fn until it returns a truthy value, or fails after timeoutMs. */
async function waitFor(fn, timeoutMs, what) {
  const until = Date.now() + timeoutMs;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > until) throw new Error('timed out waiting for ' + what);
    await new Promise(r => setTimeout(r, 500));
  }
}

test.describe('Dashboard page', () => {

  test('loads with the AI Workspace title', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await expect(page).toHaveTitle(/AI Workspace/);
  });

  test('shows the Instances and Catalog sections', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await expect(page.locator('#instances-heading')).toBeVisible();
    await expect(page.locator('#catalog-heading')).toBeVisible();
  });

  test('the catalog has the chat-ui, html-saurus and Turing workflow editor tiles', async ({ page }) => {
    await page.goto(PORTAL_URL);
    for (const name of [CHAT_UI, 'chat-ui-with-audit-trail-cloud-llm', 'chat-ui-with-audit-trail-no-web-tools',
                        'html-saurus', 'turing-workflow-editor']) {
      await expect(page.locator(`#tool-tile-${name}`)).toBeVisible();
    }
  });

  test('the retired quarkus-chat-ui tiles are gone', async ({ page }) => {
    await page.goto(PORTAL_URL);
    for (const name of ['quarkus-chat-ui', 'quarkus-chat-ui-3', 'quarkus-chat-ui3']) {
      await expect(page.locator(`#tool-tile-${name}`)).toHaveCount(0);
    }
  });

  test('each tool tile has an action list and a Run button', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tile = page.locator(`#tool-tile-${CHAT_UI}`);
    await expect(tile.locator(`#action-${CHAT_UI}`)).toBeVisible();
    await expect(tile.locator(`#btn-run-${CHAT_UI}`)).toBeVisible();
  });

  test('the GPU broker tile is view-only: no action list, no Run button', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tile = page.locator('#gpu-broker-tile');
    await expect(tile).toBeVisible();
    await expect(tile).toHaveAttribute('data-view-only', 'true');
    await expect(tile.locator('select.tool-action')).toHaveCount(0);
    await expect(tile.locator('button', { hasText: 'Run' })).toHaveCount(0);
  });
});

test.describe('API', () => {

  test('GET /api/status answers with the dashboard model', async ({ request }) => {
    const resp = await request.get(`${PORTAL_URL}/api/status`);
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('activeSessions');
    expect(body).toHaveProperty('launchTools');
  });

  test('GET /api/services answers with a list', async ({ request }) => {
    const resp = await request.get(`${PORTAL_URL}/api/services`);
    expect(resp.status()).toBe(200);
    expect(Array.isArray(await resp.json())).toBe(true);
  });

  test('GET /api/gpu-broker/queues says whether a broker is configured', async ({ request }) => {
    const resp = await request.get(`${PORTAL_URL}/api/gpu-broker/queues`);
    expect(resp.status()).toBe(200);
    expect(typeof (await resp.json()).configured).toBe('boolean');
  });

  test('launching a tool that is not in the catalog fails with an error', async ({ request }) => {
    const resp = await request.post(`${PORTAL_URL}/api/tool/no-such-tool/launch`, { data: {} });
    expect(resp.ok()).toBe(false);
    const body = await resp.json();
    expect(body.success).toBe(false);
    expect(body.error).toBeTruthy();
  });
});

test.describe('Launching and stopping a chat-ui', () => {

  test('a launched chat-ui is listed as running and as a peer, and leaves both lists when stopped', async ({ page, request }) => {
    const launch = await request.post(`${PORTAL_URL}/api/tool/${CHAT_UI}/launch`, { data: {} });
    expect(await launch.json()).toEqual({ success: true });

    // It becomes READY under its tool name.
    const entry = await waitFor(async () => {
      const list = await (await request.get(`${PORTAL_URL}/api/services?name=${CHAT_UI}&readyOnly=true`)).json();
      return list[0];
    }, 30000, `${CHAT_UI} to be READY`);
    const port = entry.port;

    try {
      // It is a peer: node name and actor port as AI-workspace launched it.
      const peers = await (await request.get(`${PORTAL_URL}/api/chat-ui/peers`)).json();
      expect(peers).toContainEqual({ name: `chat-ui-${port}`, host: '127.0.0.1', actorPort: port + 1000 });

      // The Instances table shows it.
      await page.goto(PORTAL_URL);
      await expect(page.locator(`tr[data-tool="${CHAT_UI}"][data-port="${port}"]`)).toBeVisible();
    } finally {
      const stop = await request.post(`${PORTAL_URL}/api/tool/${CHAT_UI}/${port}/stop`);
      expect(await stop.json()).toEqual({ success: true });
    }

    // Stopped: no longer READY, no longer a peer.
    await waitFor(async () => {
      const list = await (await request.get(`${PORTAL_URL}/api/services?name=${CHAT_UI}&readyOnly=true`)).json();
      return !list.some(s => s.port === port);
    }, 15000, `${CHAT_UI}:${port} to stop`);
    const after = await (await request.get(`${PORTAL_URL}/api/chat-ui/peers`)).json();
    expect(after.some(p => p.name === `chat-ui-${port}`)).toBe(false);
  });
});
