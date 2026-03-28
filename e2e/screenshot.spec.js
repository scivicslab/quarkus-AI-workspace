const { test } = require('@playwright/test');
const { resolvePortalUrl } = require('./resolve-portal-url');

const PORTAL_URL = resolvePortalUrl();

test('capture full dashboard screenshot', async ({ page }) => {
  await page.goto(PORTAL_URL);
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: '/tmp/dashboard-full.png', fullPage: true });
  console.log('Screenshot saved to /tmp/dashboard-full.png');
});
