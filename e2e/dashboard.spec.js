// @ts-check
const { test, expect } = require('@playwright/test');
const { resolvePortalUrl } = require('./resolve-portal-url');

const PORTAL_URL = resolvePortalUrl();

test.describe('Child Portal Dashboard', () => {

  test('page loads with correct title', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await expect(page).toHaveTitle(/AI Worker/);
  });

  test('shows Available Tools section', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await expect(page.locator('text=Available Tools')).toBeVisible();
  });

  test('shows all 5 tool tiles', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tiles = page.locator('.tool-tile');
    await expect(tiles).toHaveCount(5);
  });

  test('each tool tile has "+ New" link', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const newLinks = page.locator('.tool-action', { hasText: '+ New' });
    await expect(newLinks).toHaveCount(5);
  });

  test('Build link appears for tools with build-dir', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const buildLinks = page.locator('.tool-action', { hasText: 'Build' });
    // 4 tools have build-dir: llm-console-local, claude-code, codex, workflow-editor
    // docusaurus does NOT have build-dir
    await expect(buildLinks).toHaveCount(4);
  });

  test('Build link does NOT appear for docusaurus', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const docTile = page.locator('#tool-tile-docusaurus');
    await expect(docTile).toBeVisible();
    const buildInDoc = docTile.locator('.tool-action', { hasText: 'Build' });
    await expect(buildInDoc).toHaveCount(0);
  });

  test('Build link appears for llm-console-local', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tile = page.locator('#tool-tile-llm-console-local');
    await expect(tile).toBeVisible();
    const build = tile.locator('.tool-action', { hasText: 'Build' });
    await expect(build).toHaveCount(1);
  });

  test('Build link appears for claude-code', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tile = page.locator('#tool-tile-claude-code');
    const build = tile.locator('.tool-action', { hasText: 'Build' });
    await expect(build).toHaveCount(1);
  });

  test('Build link appears for codex', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tile = page.locator('#tool-tile-codex');
    const build = tile.locator('.tool-action', { hasText: 'Build' });
    await expect(build).toHaveCount(1);
  });

  test('Build link appears for workflow-editor', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const tile = page.locator('#tool-tile-workflow-editor');
    const build = tile.locator('.tool-action', { hasText: 'Build' });
    await expect(build).toHaveCount(1);
  });

  test('clicking Build shows confirm dialog', async ({ page }) => {
    await page.goto(PORTAL_URL);
    let dialogMessage = '';
    page.on('dialog', async (dialog) => {
      dialogMessage = dialog.message();
      await dialog.dismiss(); // cancel
    });
    const tile = page.locator('#tool-tile-llm-console-local');
    const buildLink = tile.locator('.tool-action', { hasText: 'Build' });
    await buildLink.click();
    expect(dialogMessage).toContain('Build');
    expect(dialogMessage).toContain('llm-console-local');
  });

  test('build API returns 202 for buildable tool', async ({ request }) => {
    const resp = await request.post(`${PORTAL_URL}/api/tools/llm-console-local/build`);
    // 202 Accepted or 409 Conflict (already building) are both valid
    expect([202, 409]).toContain(resp.status());
  });

  test('build API returns 400 for non-buildable tool', async ({ request }) => {
    const resp = await request.post(`${PORTAL_URL}/api/tools/docusaurus/build`);
    expect(resp.status()).toBe(400);
    const body = await resp.json();
    expect(body.error).toContain('does not support building');
  });

  test('build progress API returns valid JSON', async ({ request }) => {
    const resp = await request.get(`${PORTAL_URL}/api/tools/llm-console-local/build/progress`);
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('name');
    expect(body).toHaveProperty('phase');
    expect(body).toHaveProperty('messages');
    expect(body).toHaveProperty('done');
    expect(body).toHaveProperty('success');
  });

  test('management services section is visible', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await expect(page.locator('text=Management Services')).toBeVisible();
  });

  test('running services section exists', async ({ page }) => {
    await page.goto(PORTAL_URL);
    await expect(page.getByText('Running Services', { exact: true })).toBeVisible();
  });

  test('build progress log container exists for each buildable tool', async ({ page }) => {
    await page.goto(PORTAL_URL);
    for (const name of ['llm-console-local', 'claude-code', 'codex', 'workflow-editor']) {
      const logEl = page.locator(`#build-log-${name}`);
      await expect(logEl).toBeAttached();
    }
  });

  // ---- Docusaurus project picker ----

  test('docusaurus projects API returns list', async ({ request }) => {
    const resp = await request.get(`${PORTAL_URL}/api/tools/docusaurus/projects`);
    expect(resp.status()).toBe(200);
    const projects = await resp.json();
    expect(Array.isArray(projects)).toBe(true);
    expect(projects.length).toBeGreaterThan(0);
    // Each project should have name and path
    for (const p of projects) {
      expect(p).toHaveProperty('name');
      expect(p).toHaveProperty('path');
    }
  });

  test('clicking docusaurus + New shows project picker modal', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const docTile = page.locator('#tool-tile-docusaurus');
    const newLink = docTile.locator('.tool-action', { hasText: '+ New' });
    await newLink.click();
    // Modal should appear with project list
    const modal = page.locator('#docusaurus-modal');
    await expect(modal).toBeVisible();
    await expect(modal.locator('text=Select Docusaurus Project')).toBeVisible();
    // Should have at least one project item
    const items = modal.locator('div[onclick*="launchDocusaurus"]');
    expect(await items.count()).toBeGreaterThan(0);
  });

  test('docusaurus modal can be closed with Cancel', async ({ page }) => {
    await page.goto(PORTAL_URL);
    const docTile = page.locator('#tool-tile-docusaurus');
    await docTile.locator('.tool-action', { hasText: '+ New' }).click();
    const modal = page.locator('#docusaurus-modal');
    await expect(modal).toBeVisible();
    await modal.locator('button', { hasText: 'Cancel' }).click();
    await expect(modal).not.toBeVisible();
  });

  test('docusaurus launch API accepts workDir parameter', async ({ request }) => {
    const resp = await request.post(`${PORTAL_URL}/api/tools/docusaurus/launch`, {
      data: { workDir: '/home/devteam/works/doc_SCIVICS003' }
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.status).toBe('launched');
    expect(body.tool).toBe('docusaurus');
    expect(body.port).toBeGreaterThanOrEqual(16300);

    // Clean up: stop the launched instance
    await request.post(`${PORTAL_URL}/api/tools/docusaurus/stop?port=${body.port}`);
  });

  test('clicking project in modal launches docusaurus and shows in Running Services', async ({ page }) => {
    await page.goto(PORTAL_URL);

    // Open the docusaurus project picker modal
    const docTile = page.locator('#tool-tile-docusaurus');
    await docTile.locator('.tool-action', { hasText: '+ New' }).click();
    const modal = page.locator('#docusaurus-modal');
    await expect(modal).toBeVisible();

    // Click the first project in the list
    const firstProject = modal.locator('div[onclick*="launchDocusaurus"]').first();
    const projectName = await firstProject.locator('div').first().textContent();
    await firstProject.click();

    // Modal should disappear (removed on click)
    await expect(modal).not.toBeVisible();

    // Page reloads after launch — wait for it
    await page.waitForLoadState('networkidle');

    // After reload, the launched docusaurus should appear in Running Services
    const runningSection = page.locator('text=Running Services');
    await expect(runningSection).toBeVisible();

    // There should be at least one running docusaurus instance with a Stop button
    const stopBtn = page.locator('.btn-stop');
    const stopCount = await stopBtn.count();
    expect(stopCount).toBeGreaterThan(0);

    // Find docusaurus instance row — look for a link with port in 16300 range
    const docInstances = page.locator('a[href*=":163"]');
    expect(await docInstances.count()).toBeGreaterThan(0);

    // Clean up: stop all docusaurus instances via API
    for (const btn of await stopBtn.all()) {
      // Click stop button for each docusaurus instance
      const row = btn.locator('..');
      const hasDocPort = await row.locator('a[href*=":163"]').count();
      if (hasDocPort > 0) {
        await btn.click();
        // Wait briefly for the stop to process
        await page.waitForTimeout(500);
      }
    }
    // Reload and verify instances are gone or reduced
    await page.reload();
    await page.waitForLoadState('networkidle');
  });

  test('clicking project in modal triggers fetch to launch API', async ({ page }) => {
    await page.goto(PORTAL_URL);

    // Intercept the launch API call
    const launchPromise = page.waitForRequest(req =>
      req.url().includes('/api/tools/docusaurus/launch') && req.method() === 'POST'
    );

    // Open modal and click first project
    const docTile = page.locator('#tool-tile-docusaurus');
    await docTile.locator('.tool-action', { hasText: '+ New' }).click();
    const modal = page.locator('#docusaurus-modal');
    await expect(modal).toBeVisible();

    const firstProject = modal.locator('div[onclick*="launchDocusaurus"]').first();
    await firstProject.click();

    // Verify the fetch was made with correct payload
    const req = await launchPromise;
    const postData = JSON.parse(req.postData());
    expect(postData).toHaveProperty('workDir');
    expect(postData.workDir).toBeTruthy();
    expect(postData.workDir).toContain('/home/devteam/works/');
  });
});
