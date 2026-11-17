const { test } = require('@playwright/test');
const fs = require('fs');
const { resolvePortalUrl } = require('./resolve-portal-url');

const PORTAL_URL = resolvePortalUrl();

// Not a check: writes the dashboard's HTML to /tmp/playwright-page.html and prints each catalog tile with
// the actions its list offers, for looking at what the page shows.
test('dump page HTML and the catalog tiles', async ({ page }) => {
  await page.goto(PORTAL_URL);
  const html = await page.content();
  fs.writeFileSync('/tmp/playwright-page.html', html);
  console.log('HTML length:', html.length);
  const tiles = await page.locator('.tool-tile').evaluateAll(els => els.map(el => ({
    id: el.id,
    actions: Array.from(el.querySelectorAll('select.tool-action option')).map(o => o.textContent.trim()),
  })));
  console.log('tiles:', JSON.stringify(tiles, null, 2));
});
