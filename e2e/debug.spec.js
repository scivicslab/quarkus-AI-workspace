const { test } = require('@playwright/test');
const fs = require('fs');
const { resolvePortalUrl } = require('./resolve-portal-url');

const PORTAL_URL = resolvePortalUrl();

test('dump page HTML', async ({ page }) => {
  await page.goto(PORTAL_URL);
  const html = await page.content();
  fs.writeFileSync('/tmp/playwright-page.html', html);
  console.log('HTML length:', html.length);
  console.log('Contains Build:', html.includes('Build'));
  console.log('Contains buildTool:', html.includes('buildTool'));
  console.log('Contains buildDir:', html.includes('buildDir'));
  console.log('Contains build-dir:', html.includes('build-dir'));

  // Find all tool-action elements
  const actions = await page.locator('.tool-action').allTextContents();
  console.log('tool-action texts:', JSON.stringify(actions));
});
