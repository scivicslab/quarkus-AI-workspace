// @ts-check
const { test, expect } = require('@playwright/test');
const { resolvePortalUrl } = require('./resolve-portal-url');

/*
 * The Catalog tab (DashboardBuilder_261118_oo01), against a throwaway AI-workspace (PORTAL_URL) whose dashboard file
 * may be overwritten: start it with -Dai-workspace.dashboard-file pointing at a scratch file.
 */
const PORTAL_URL = resolvePortalUrl();

test('the Catalog tab hides a tile, retitles another, saves, and the Dashboard tab follows', async ({ page }) => {
  await page.goto(PORTAL_URL + '/catalog');
  await expect(page.locator('.screen-tab-active')).toHaveText('Catalog');
  const shownRows = page.locator('#catalog-shown tr');
  await expect(shownRows.first()).toBeVisible();
  const before = await shownRows.count();
  expect(before).toBeGreaterThan(2);

  // Hide the first shown entry; it moves to "Not shown".
  const hiddenName = await shownRows.first().locator('td').nth(2).innerText();
  await shownRows.first().getByRole('button', { name: 'Hide' }).click();
  await expect(shownRows).toHaveCount(before - 1);
  await expect(page.locator('#catalog-hidden')).toContainText(hiddenName);

  // Move the (new) second entry up and give it a heading.
  const movedName = await shownRows.nth(1).locator('td').nth(2).innerText();
  await shownRows.nth(1).getByRole('button', { name: '↑' }).click();
  await expect(shownRows.first().locator('td').nth(2)).toHaveText(movedName);
  await shownRows.first().locator('input').fill('E2E heading');

  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('#catalog-status')).toHaveText('saved');

  // The Dashboard tab draws the saved tiles: the retitled one first, the hidden one gone.
  await page.goto(PORTAL_URL + '/');
  await expect(page.locator('#catalog-heading')).toHaveText('Tools — what can be started');
  const names = page.locator('.tool-tile-name');
  await expect(names.first()).toHaveText('E2E heading');
  await expect(page.locator('#tool-tile-' + hiddenName)).toHaveCount(0);

  // The Catalog tab shows the saved state after a reload.
  await page.goto(PORTAL_URL + '/catalog');
  await expect(page.locator('#catalog-shown tr').first().locator('input')).toHaveValue('E2E heading');
});
