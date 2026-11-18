// @ts-check
const { test, expect } = require('@playwright/test');
const { resolvePortalUrl } = require('./resolve-portal-url');

/*
 * A link entry (DashboardBuilder_261118_oo01): the GPU broker is shown and opened, never started, stopped or built.
 * Run against a throwaway AI-workspace started with -Dgpu.broker.url and a scratch -Dai-workspace.dashboard-file.
 */
const PORTAL_URL = resolvePortalUrl();
const BROKER_URL = process.env.BROKER_URL;

test('the GPU broker tile shows and opens the broker and offers nothing that starts or builds', async ({ page }) => {
  await page.goto(PORTAL_URL + '/');
  // Services sit in a section of their own after the tools, not among them.
  await expect(page.locator('#services-heading')).toHaveText('Services — opened, not started');
  const tile = page.locator('#services-grid #tool-tile-gpu-broker');
  await expect(tile).toBeVisible();
  await expect(page.locator('.tool-grid').first().locator('#tool-tile-gpu-broker')).toHaveCount(0);
  const toolsBox = await page.locator('#catalog-heading').boundingBox();
  const servicesBox = await page.locator('#services-heading').boundingBox();
  expect(servicesBox.y).toBeGreaterThan(toolsBox.y);
  await expect(tile.locator('.tool-tile-name')).toHaveText('GPU broker (W206 cluster)');
  await expect(tile).toHaveAttribute('data-view-only', 'true');
  const open = tile.getByRole('link', { name: 'Open' });
  await expect(open).toHaveAttribute('href', BROKER_URL + '/');
  await expect(open).toHaveAttribute('target', '_blank');
  await expect(tile).toContainText(BROKER_URL);
  for (const name of ['Start', 'Stop', 'Run', 'Configure & Start…']) {
    await expect(tile.getByRole('button', { name })).toHaveCount(0);
  }
  await expect(tile.locator('select')).toHaveCount(0);
  // The queues view reads the broker through this portal and says what it found, or that it could not read it.
  await expect(tile.locator('#gpu-broker-status')).not.toHaveText('reading…', { timeout: 15000 });
});

test('the Catalog tab shows, retitles and hides the GPU broker like any entry', async ({ page }) => {
  await page.goto(PORTAL_URL + '/catalog');
  const row = page.locator('#catalog-shown-services tr', { hasText: 'gpu-broker' });
  await expect(row).toHaveCount(1);
  await expect(page.locator('#catalog-shown-tools tr', { hasText: 'gpu-broker' })).toHaveCount(0);
  await row.locator('input').fill('Broker');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('#catalog-status')).toHaveText('saved');
  await page.goto(PORTAL_URL + '/');
  await expect(page.locator('#tool-tile-gpu-broker .tool-tile-name')).toHaveText('Broker');

  await page.goto(PORTAL_URL + '/catalog');
  await page.locator('#catalog-shown-services tr', { hasText: 'gpu-broker' }).getByRole('button', { name: 'Hide' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('#catalog-status')).toHaveText('saved');
  await expect(page.locator('#catalog-hidden')).toContainText('gpu-broker');
  await page.goto(PORTAL_URL + '/');
  await expect(page.locator('#tool-tile-gpu-broker')).toHaveCount(0);
  await expect(page.locator('#services-heading')).toHaveCount(0);
});
