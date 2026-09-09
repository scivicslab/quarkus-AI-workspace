package com.scivicslab.aiworkspace.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E2E: moving from a search result to the turns and rows around it, on the Conversations screen.
 *
 * <p>Runs against a conversation database this test writes itself, not the merged one on disk: the
 * cases worth driving a browser for are a turn that spans several rows and a gap in the turn
 * numbering, and neither can be relied on to be in whatever has been recorded lately. The portal is
 * pointed at the fixture with {@code AI_WORKSPACE_CONVERSATION_LOG_DB_PATH}.</p>
 *
 * <p>The fixture, in the order the rows were written:</p>
 * <pre>
 *   conversation "Alpha"  turn1  llm / tool / llm      (three rows)
 *                         turn3  llm                   (turn2 was never written)
 *                         turn4  llm / tool            (two rows)
 *   conversation "Beta"   turn1  llm
 * </pre>
 *
 * <p>Every row holds the word {@code haystack}, so one search returns all of them and every turn is
 * reachable from a result.</p>
 *
 * <p>Run via {@link AiWorkspaceE2ERunner}, or on its own:</p>
 * <pre>
 *   mvn test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.scivicslab.aiworkspace.e2e.ConversationTurnNavigationE2E
 * </pre>
 */
public class ConversationTurnNavigationE2E {

    private static int passed = 0;
    private static int failed = 0;

    // A result is picked by words only that row holds. Its label will not do: turn1/step1/llm is
    // the first row of every conversation, so a label picks whichever one the search listed first.
    private static final String ALPHA_TURN1_LLM = "the model call of the first turn";
    private static final String ALPHA_TURN1_TOOL = "a tool the first turn ran";
    private static final String ALPHA_TURN4_LLM = "the last turn of Alpha";
    private static final String ALPHA_TURN4_TOOL = "and the tool it ran";
    private static final String BETA_TURN1 = "Beta, another conversation entirely";

    public static void main(String[] args) throws Exception {
        new ConversationTurnNavigationE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    void run() throws Exception {
        System.out.println("--- ConversationTurnNavigationE2E ---");
        Path fixture = writeFixtureDatabase();
        int port = E2EConfig.findFreePort();
        AiWorkspaceProcess portal = AiWorkspaceProcess.start(
                E2EConfig.configYaml(), port,
                Map.of("AI_WORKSPACE_CONVERSATION_LOG_DB_PATH", fixture.toString()));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                String base = "http://localhost:" + port;
                readsAWholeTurn(browser, base);
                arrowsStepBetweenTurns(browser, base);
                arrowKeysStillWorkAfterClickingAnArrow(browser, base);
                rowModeStepsInsideTheTurn(browser, base);
                arrowsStopAtTheEndsOfTheConversation(browser, base);
                theBarDoesNotMoveWhenTheModeIsSwitched(browser, base);
                twoOpenReadersDoNotMoveEachOther(browser, base);
            } finally {
                browser.close();
                portal.stop();
            }
        }
        if (failed > 0) {
            throw new AssertionError("ConversationTurnNavigationE2E: " + failed + " check(s) failed");
        }
        System.out.println("ConversationTurnNavigationE2E: PASSED");
    }

    // --- the checks ---------------------------------------------------------------------------

    /** Opening a result shows the whole turn it belongs to, one labelled block per row. */
    private void readsAWholeTurn(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator reader = openResultFor(page, ALPHA_TURN1_TOOL);
            check(bodies(reader) == 3,
                    "the turn's three rows are all shown (" + bodies(reader) + ")");
            check(labels(reader) == 3,
                    "every row is labelled (" + labels(reader) + ")");
            check(where(reader).equals("turn1"), "the reader says which turn (" + where(reader) + ")");
            check(forward(reader).textContent().contains("turn3"),
                    "the forward arrow names where it leads (" + forward(reader).textContent().trim() + ")");
            check(reader.locator(".conv-reader-row-label").first().textContent()
                            .contains("turn1/step1/llm"),
                    "the rows are in the order they were written");
        });
    }

    /** The arrows move to the turn before and after, across the gap where turn2 is missing. */
    private void arrowsStepBetweenTurns(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator reader = openResultFor(page, ALPHA_TURN1_LLM);
            forward(reader).click();
            settle(page, reader);
            check(where(reader).equals("turn3"),
                    "forward from turn1 skips the turn2 that was never written, reaching "
                            + where(reader));

            forward(reader).click();
            settle(page, reader);
            check(where(reader).equals("turn4"), "forward again reaches turn4 (" + where(reader) + ")");
            check(bodies(reader) == 2, "turn4's two rows are shown (" + bodies(reader) + ")");

            back(reader).click();
            settle(page, reader);
            check(where(reader).equals("turn3"), "back reaches turn3 (" + where(reader) + ")");
            back(reader).click();
            settle(page, reader);
            check(where(reader).equals("turn1"), "back reaches turn1 (" + where(reader) + ")");
        });
    }

    /**
     * The arrow keys keep working after an arrow has been clicked.
     *
     * <p>Redrawing the reader replaces the very button that was clicked, so whatever the browser
     * was focusing is gone from the page — and a key handler bound to the reader hears nothing.</p>
     */
    private void arrowKeysStillWorkAfterClickingAnArrow(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator reader = openResultFor(page, ALPHA_TURN1_LLM);
            forward(reader).click();
            settle(page, reader);
            check(where(reader).equals("turn3"), "the click moved to turn3 (" + where(reader) + ")");

            // No explicit focus() here: that is the point. After the click, pressing the key is
            // what a person does next.
            page.keyboard().press("ArrowRight");
            page.waitForTimeout(1200);
            check(where(reader).equals("turn4"),
                    "the right arrow key still moves after a click (" + where(reader) + ")");
        });
    }

    /** Row mode shows one row and steps inside the turn; turn mode brings the whole turn back. */
    private void rowModeStepsInsideTheTurn(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator reader = openResultFor(page, ALPHA_TURN1_LLM);
            reader.locator(".conv-reader-mode-row").click();
            settle(page, reader);
            check(bodies(reader) == 1, "row mode shows one row (" + bodies(reader) + ")");
            check(where(reader).contains("turn1/step1/llm"),
                    "row mode names the row (" + where(reader) + ")");

            forward(reader).click();
            settle(page, reader);
            check(where(reader).contains("turn1/step1/tool"),
                    "forward steps to the next row of the same turn (" + where(reader) + ")");

            reader.locator(".conv-reader-mode-turn").click();
            settle(page, reader);
            check(bodies(reader) == 3,
                    "turn mode shows the whole turn again (" + bodies(reader) + ")");
        });
    }

    /** Neither arrow leads out of the conversation the result belongs to. */
    private void arrowsStopAtTheEndsOfTheConversation(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator first = openResultFor(page, ALPHA_TURN1_LLM);
            check(back(first).isDisabled(), "the first turn has no back arrow");

            Locator last = openResultFor(page, ALPHA_TURN4_TOOL);
            check(forward(last).isDisabled(),
                    "the last turn of the conversation has no forward arrow — "
                    + "the next row in the database belongs to another conversation");

            Locator other = openResultFor(page, BETA_TURN1);
            check(back(other).isDisabled(),
                    "the other conversation's only turn has no back arrow");
        });
    }

    /**
     * The bar does not rearrange itself when the mode is switched.
     *
     * <p>Turn mode names an arrow's destination {@code turn4}; row mode names it
     * {@code turn1/step2/llm}. Laid out as a flex row, that alone moved every control to the right
     * of it, and marking the current mode by disabling its button changed a third thing. The bar is
     * a grid of fixed cells so that pressing Turn or Row changes what the controls say and nothing
     * about where they are.</p>
     */
    private void theBarDoesNotMoveWhenTheModeIsSwitched(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator reader = openResultFor(page, ALPHA_TURN1_LLM);
            var backBefore = back(reader).boundingBox();
            var forwardBefore = forward(reader).boundingBox();
            var turnBefore = reader.locator(".conv-reader-mode-turn").boundingBox();
            var rowBefore = reader.locator(".conv-reader-mode-row").boundingBox();

            reader.locator(".conv-reader-mode-row").click();
            settle(page, reader);

            check(sameBox(backBefore, back(reader).boundingBox()),
                    "the back arrow stayed where it was");
            check(sameBox(forwardBefore, forward(reader).boundingBox()),
                    "the forward arrow stayed where it was");
            check(sameBox(turnBefore, reader.locator(".conv-reader-mode-turn").boundingBox()),
                    "the Turn button stayed where it was");
            check(sameBox(rowBefore, reader.locator(".conv-reader-mode-row").boundingBox()),
                    "the Row button stayed where it was");
            check(reader.locator(".conv-reader-mode-row").isEnabled()
                            && reader.locator(".conv-reader-mode-turn").isEnabled(),
                    "both mode buttons stay pressable");
        });
    }

    /** Two boxes are the same place and size, to within a pixel of rounding. */
    private static boolean sameBox(com.microsoft.playwright.options.BoundingBox a,
                                   com.microsoft.playwright.options.BoundingBox b) {
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(a.x - b.x) < 1.5 && Math.abs(a.y - b.y) < 1.5
                && Math.abs(a.width - b.width) < 1.5 && Math.abs(a.height - b.height) < 1.5;
    }

    /** Each open reader moves on its own. */
    private void twoOpenReadersDoNotMoveEachOther(Browser browser, String base) {
        withSearch(browser, base, page -> {
            Locator one = openResultFor(page, ALPHA_TURN1_LLM);
            Locator two = openResultFor(page, ALPHA_TURN4_LLM);
            String twoBefore = where(two);

            back(two).click();
            settle(page, two);
            check(where(one).equals("turn1"),
                    "moving one reader left the other where it was (" + where(one) + ")");
            check(!where(two).equals(twoBefore),
                    "the reader that was clicked did move (" + twoBefore + " -> " + where(two) + ")");
        });
    }

    // --- driving the screen -------------------------------------------------------------------

    /** Opens the Conversations screen with every fixture row matched, and runs one check block. */
    private void withSearch(Browser browser, String base, java.util.function.Consumer<Page> body) {
        Page page = browser.newPage();
        List<String> errors = new ArrayList<>();
        page.onPageError(errors::add);
        page.onConsoleMessage(m -> {
            if ("error".equals(m.type())) {
                errors.add("console: " + m.text());
            }
        });
        try {
            page.navigate(base + "/conversations?q=haystack",
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            body.accept(page);
            check(errors.isEmpty(), "no JavaScript errors: " + errors);
        } finally {
            page.close();
        }
    }

    /**
     * Opens the reader of the result whose head or snippet holds {@code marker}.
     *
     * @return that result's reader, already showing something
     */
    private Locator openResultFor(Page page, String marker) {
        Locator hit = page.locator(".conv-hit").filter(
                new Locator.FilterOptions().setHasText(marker)).first();
        Locator toggle = hit.locator(".conv-hit-toggle");
        if (!"Hide".equals(toggle.textContent().trim())) {
            toggle.click();
        }
        Locator reader = hit.locator(".conv-reader");
        reader.locator(".conv-reader-body").first().waitFor(
                new Locator.WaitForOptions().setTimeout(15_000));
        return reader;
    }

    /** Waits for a redraw to land. The reader replaces its content, so nothing else is stable. */
    private void settle(Page page, Locator reader) {
        page.waitForTimeout(1000);
        reader.locator(".conv-reader-body").first().waitFor(
                new Locator.WaitForOptions().setTimeout(15_000));
    }

    private Locator back(Locator reader) {
        return reader.locator(".conv-reader-back");
    }

    private Locator forward(Locator reader) {
        return reader.locator(".conv-reader-forward");
    }

    private String where(Locator reader) {
        return reader.locator(".conv-reader-where").textContent().trim();
    }

    private int bodies(Locator reader) {
        return reader.locator(".conv-reader-body").count();
    }

    private int labels(Locator reader) {
        return reader.locator(".conv-reader-row-label").count();
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            passed++;
            System.out.println("  PASS: " + message);
        } else {
            failed++;
            System.out.println("  FAIL: " + message);
        }
    }

    // --- the fixture --------------------------------------------------------------------------

    /** @return the database path, with no {@code .mv.db} extension, as the portal expects it */
    private static Path writeFixtureDatabase() throws Exception {
        Path dir = Files.createTempDirectory("conv-nav-e2e-");
        Path db = dir.resolve("fixture-log");
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sessions (id BIGINT PRIMARY KEY, workflow_name VARCHAR, "
                    + "command_line VARCHAR, source_db VARCHAR)");
            s.execute("CREATE TABLE logs (id BIGINT PRIMARY KEY, session_id BIGINT, "
                    + "timestamp TIMESTAMP, node_id VARCHAR, label VARCHAR, action_name VARCHAR, "
                    + "level VARCHAR, message VARCHAR, exit_code INT, duration_ms BIGINT)");
            s.execute("INSERT INTO sessions VALUES "
                    + "(1, 'Alpha', '', 'chat-ui-iolog-28013.mv.db'),"
                    + "(2, 'Beta', '', 'chat-ui-iolog-28014.mv.db')");
            insert(c, 10, 1, "turn1/step1/llm", "haystack: the model call of the first turn");
            insert(c, 11, 1, "turn1/step1/tool", "haystack: a tool the first turn ran");
            insert(c, 12, 1, "turn1/step2/llm", "haystack: the model call after that tool");
            insert(c, 13, 1, "turn3/step1/llm", "haystack: turn2 was never written");
            insert(c, 14, 1, "turn4/step1/llm", "haystack: the last turn of Alpha");
            insert(c, 15, 1, "turn4/step1/tool", "haystack: and the tool it ran");
            insert(c, 20, 2, "turn1/step1/llm", "haystack: Beta, another conversation entirely");
        }
        System.out.println("  fixture database → " + db.toAbsolutePath() + ".mv.db");
        return db.toAbsolutePath();
    }

    private static void insert(Connection c, long id, long sessionId, String label, String message)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO logs (id, session_id, timestamp, label, level, message) "
                + "VALUES (?, ?, ?, ?, 'INFO', ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, sessionId);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-09 10:00:" + String.format("%02d", id)));
            ps.setString(4, label);
            ps.setString(5, message);
            ps.executeUpdate();
        }
    }
}
