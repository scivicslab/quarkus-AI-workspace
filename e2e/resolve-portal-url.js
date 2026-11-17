/**
 * The AI-workspace these e2e tests drive, from PORTAL_URL, e.g.
 *
 *   PORTAL_URL=http://localhost:18000 npx playwright test
 *
 * There is no default on purpose. The tests launch, stop and build tools, so they must run against a
 * throwaway AI-workspace started for them, never the one already serving on localhost:28000.
 * AI_WORKSPACE_URL is not read either: AI-workspace sets it for every tool it launches, so a test run
 * started from one of those tools would land on the running AI-workspace.
 */
function resolvePortalUrl() {
  const url = process.env.PORTAL_URL;
  if (!url) {
    throw new Error('Set PORTAL_URL to a throwaway AI-workspace, e.g. PORTAL_URL=http://localhost:18000');
  }
  return url.replace(/\/+$/, '');
}

module.exports = { resolvePortalUrl };
