const { execSync } = require('child_process');

/**
 * Resolve the child portal URL by querying the LXC container IP.
 * Falls back to PORTAL_URL env var if set.
 */
function resolvePortalUrl() {
  if (process.env.PORTAL_URL) return process.env.PORTAL_URL;

  try {
    const ip = execSync(
      "lxc list AI-container-test --format=csv -c 4 | cut -d' ' -f1",
      { encoding: 'utf-8' }
    ).trim();
    if (ip) return `http://${ip}:16080`;
  } catch (_) {
    // lxc not available
  }
  return 'http://localhost:16080';
}

module.exports = { resolvePortalUrl };
