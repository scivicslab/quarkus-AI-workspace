package com.scivicslab.serviceportal.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * E2E test for the "Download Latest" button (POST /api/tool/{name}/download).
 *
 * Opens the dashboard in a headless browser, clicks the "Download Latest" button
 * for html-saurus, waits for the status span to show success, then verifies
 * that the versioned jar and symlink were written to ~/works/.
 *
 * Requires network access to api.github.com.
 */
class DownloadLatestE2E {

    public static void main(String[] args) throws Exception {
        new DownloadLatestE2E().run();
    }

    private static final int PAGE_TIMEOUT_MS = 120_000;

    void run() throws Exception {
        System.out.println("[DownloadLatestE2E] start");

        int portalPort = E2EConfig.findFreePort();
        Map<String, String> env = Map.of(
            "TEST_JARS_DIR", E2EConfig.testJarsDir().toString()
        );
        ServicePortalProcess portal = ServicePortalProcess.start(E2EConfig.configYaml(), portalPort, env);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.navigate("http://localhost:" + portalPort + "/",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));

                // Wait for the Download Latest button to appear
                Locator btn = page.locator("#btn-download-html-saurus");
                btn.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));

                // Click and wait for the status span to show a result (non-empty text)
                btn.click();
                Locator status = page.locator("#download-status-html-saurus");
                page.waitForFunction(
                    "() => { const s = document.getElementById('download-status-html-saurus'); " +
                    "return s && s.textContent && s.textContent.trim().length > 0; }",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(PAGE_TIMEOUT_MS));

                String statusText = status.textContent();
                if (statusText == null || !statusText.startsWith("✓"))
                    throw new AssertionError(
                        "downloadLatest_buttonShowsSuccess: expected '✓ ...' but got: " + statusText);

                System.out.println("[DownloadLatestE2E] UI status: " + statusText);

                // Verify the symlink was created in ~/works/
                Path symlink = Path.of(System.getProperty("user.home"), "works", "html-saurus.jar");
                if (!Files.exists(symlink))
                    throw new AssertionError("downloadLatest_symlinkExists: html-saurus.jar not found in ~/works");
                if (!Files.isSymbolicLink(symlink))
                    throw new AssertionError("downloadLatest_symlinkIsLink: html-saurus.jar is not a symlink");

                Path realFile = symlink.toRealPath();
                long size = Files.size(realFile);
                if (size == 0)
                    throw new AssertionError("downloadLatest_fileNonEmpty: downloaded jar is 0 bytes: " + realFile);

                System.out.println("[DownloadLatestE2E] PASSED — " + realFile.getFileName() + " (" + size + " bytes)");
                page.close();
            } finally {
                browser.close();
            }
        } finally {
            portal.stop();
        }
    }
}
