package com.scivicslab.aiworkspace.e2e;

/**
 * Entry point for Service Portal E2E tests.
 *
 * <p>Runs all E2E scenarios in sequence. Any failure throws an exception
 * and exits with a non-zero code, making it usable from CI scripts.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Tool JARs deployed in ~/works (quarkus-chat-ui.jar, html-saurus.jar, turing-workflow-editor.jar)</li>
 *   <li>Playwright Chromium installed: {@code mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"}</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java -Dexec.mainClass=com.scivicslab.aiworkspace.e2e.AiWorkspaceE2ERunner \
 *       -Dexec.classpathScope=test
 * </pre>
 */
public class AiWorkspaceE2ERunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Service Portal E2E Tests ===");
        new ToolStartupE2E().run();
        Thread.sleep(3_000);
        new ChatUiProviderE2E().run();
        Thread.sleep(3_000);
        new ChatUiProviderUIE2E().run();
        Thread.sleep(3_000);
        new ToolWorksE2E().run();
        Thread.sleep(3_000);
        new DownloadLatestE2E().run();
        Thread.sleep(3_000);
        new ToolStartupSequenceE2E().run();
        System.out.println("=== All E2E tests PASSED ===");
    }
}
