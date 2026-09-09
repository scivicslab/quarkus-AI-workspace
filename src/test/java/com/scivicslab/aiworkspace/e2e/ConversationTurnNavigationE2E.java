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
 * E2E: reading a conversation from a search result on the Conversations screen.
 *
 * <p>The screen is three regions that scroll on their own inside a page that is exactly the window:
 * the results, the calls of the turn one of them belongs to, and the whole text of the call picked
 * out of those. What this drives is that the structure stays visible however much there is of it —
 * the failure it exists to prevent is the one the earlier design had, where opening a long turn
 * lengthened the document until the turn's own heading was off the top of the screen.</p>
 *
 * <p>It runs against a conversation database this test writes itself, so the shapes that matter are
 * present rather than hoped for: a turn of thirty calls with long text in each, a gap in the turn
 * numbering, and a second conversation the arrows must not cross into. The portal is pointed at it
 * with {@code AI_WORKSPACE_CONVERSATION_LOG_DB_PATH}.</p>
 *
 * <pre>
 *   conversation "Alpha"  turn1  30 calls, alternating llm and tool, ~4 kB each
 *                         turn3  1 call            (turn2 was never written)
 *                         turn4  2 calls
 *   conversation "Beta"   turn1  1 call
 * </pre>
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

    /** How many entries the long turn holds. Enough that listing it would once have overflowed. */
    private static final int LONG_TURN_ENTRIES = 30;
    /** Each entry splits into three directions, and the turn opens with the person's message. */
    private static final int LONG_TURN_MESSAGES = LONG_TURN_ENTRIES * 3 + 1;

    private static final String ALPHA_PROMPT = "what does the long turn do?";

    // A result is picked by words only that entry holds. Its label will not do: turn1/step1/llm is
    // the first entry of every conversation, so a label picks whichever the search listed first.
    private static final String ALPHA_FIRST_ENTRY = "the model call that opens Alpha";
    private static final String ALPHA_LAST_ENTRY_OF_TURN1 = "the last entry of the long turn";
    private static final String ALPHA_TURN3 = "the turn after the missing one";
    private static final String ALPHA_TURN4_TOOL = "the tool of the last turn of Alpha";
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
                aResultOpensItsWholeTurn(browser, base);
                nothingEverLengthensTheDocument(browser, base);
                theStructureStaysOnScreenAtTheEndOfALongTurn(browser, base);
                theMessageListIsTallEnoughToReadItsRows(browser, base);
                pickingAMessageShowsItsWholeText(browser, base);
                arrowsStepBetweenTurns(browser, base);
                arrowKeysStillWorkAfterClickingAnArrow(browser, base);
                upAndDownStepBetweenTheMessagesOfTheTurn(browser, base);
                arrowsStopAtTheEndsOfTheConversation(browser, base);
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

    /** Clicking a result lists every message of the turn, named by who sent it to whom. */
    private void aResultOpensItsWholeTurn(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_LAST_ENTRY_OF_TURN1);
            check(messages(page) == LONG_TURN_MESSAGES,
                    "every message of the turn is listed (" + messages(page) + ")");
            check(where(page).startsWith("turn1"), "the bar says which turn (" + where(page) + ")");
            check(where(page).contains(LONG_TURN_MESSAGES + " messages"),
                    "the bar says how many it holds (" + where(page) + ")");
            check(selectedDirection(page).equals("loop → tool"),
                    "the entry that matched is where it lands (" + selectedDirection(page) + ")");
            check(directions(page).contains("user → loop"),
                    "the turn opens with what the person said");
            check(directions(page).containsAll(java.util.List.of(
                            "user → loop", "loop → LLM", "LLM → loop", "loop → tool", "tool → loop")),
                    "every direction of the exchange is named (" + directions(page) + ")");
        });
    }

    /**
     * The page never becomes taller than the window.
     *
     * <p>This is the whole point of the layout. Everything the screen can show grows without a
     * limit — the results, the calls of a turn, the text of one call — and if any of it lengthens
     * the document then what says where you are scrolls away above.</p>
     */
    private void nothingEverLengthensTheDocument(Browser browser, String base) {
        withScreen(browser, base, page -> {
            check(fitsTheWindow(page), "the search results alone do not lengthen the page");

            selectResult(page, ALPHA_FIRST_ENTRY);
            check(fitsTheWindow(page),
                    "a turn of " + LONG_TURN_MESSAGES + " messages does not lengthen the page");

            lastMessage(page).click();
            settle(page);
            check(fitsTheWindow(page), "a message of several kilobytes does not lengthen the page");
        });
    }

    /** Reading the end of a long turn does not push the turn's own heading off the screen. */
    private void theStructureStaysOnScreenAtTheEndOfALongTurn(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_FIRST_ENTRY);
            lastMessage(page).click();
            settle(page);

            check(inTheWindow(page, ".conv-turn-where"),
                    "the turn is still named on screen");
            check(inTheWindow(page, ".conv-turn-forward"),
                    "the way out to the next turn is still on screen");
            check(inTheWindow(page, ".conv-msg.selected"),
                    "the message being read is still marked in the list");
            check(inTheWindow(page, ".conv-results"),
                    "the search results are still on screen");
        });
    }

    /**
     * The calls list is tall enough to read.
     *
     * <p>Measured against the list's own box, not against the window. Given room to shrink, the
     * list lost it to the text below and ended up 11 pixels tall against a row of 27 — less than
     * half of one call showing under the bar — and every check that asked "is it inside the window"
     * passed, because an 11-pixel box is.</p>
     */
    private void theMessageListIsTallEnoughToReadItsRows(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_TURN3);
            check(fullyVisibleRows(page) == messages(page),
                    "a short turn shows all of its messages whole (" + fullyVisibleRows(page)
                            + " of " + messages(page) + ")");

            selectResult(page, ALPHA_FIRST_ENTRY);
            int visible = fullyVisibleRows(page);
            check(visible >= 3,
                    "a turn of " + LONG_TURN_MESSAGES + " messages shows several whole ("
                            + visible + ")");
            check(visible < LONG_TURN_MESSAGES,
                    "and not all of them, which would leave no room for the text ("
                            + visible + ")");
            check(clippedAtTheTop(page) == 0,
                    "the first message under the bar is not cut in half ("
                            + clippedAtTheTop(page) + "px cut)");
        });
    }

    /** The list carries a summary; picking a message fetches the whole thing. */
    private void pickingAMessageShowsItsWholeText(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_FIRST_ENTRY);
            // The model's answer, not the turn's first message: the first is what the person
            // typed, which is a single line and proves nothing about summarising.
            Locator answer = page.locator(".conv-msg.from-llm").first();
            String summary = answer.locator(".conv-msg-summary").textContent().trim();
            answer.click();
            settle(page);
            String whole = page.locator("#conv-text").textContent();

            check(summary.length() <= 210,
                    "the list carries a summary, not the message (" + summary.length() + " chars)");
            check(whole.length() > 3000,
                    "the message opened below is the whole of it (" + whole.length() + " chars)");
            check(whole.replaceAll("\\s+", " ").startsWith(
                            summary.replace("…", "").strip().substring(0, 40)),
                    "and it is the same message the summary came from");
        });
    }

    /** The arrows move to the turn before and after, across the gap where turn2 is missing. */
    private void arrowsStepBetweenTurns(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_FIRST_ENTRY);
            forward(page).click();
            settle(page);
            check(where(page).startsWith("turn3"),
                    "forward from turn1 skips the turn2 that was never written, reaching "
                            + where(page));

            forward(page).click();
            settle(page);
            check(where(page).startsWith("turn4"), "forward again reaches turn4 (" + where(page) + ")");
            check(messages(page) == 7,
                    "turn4's two entries are listed as seven messages (" + messages(page) + ")");

            back(page).click();
            settle(page);
            check(where(page).startsWith("turn3"), "back reaches turn3 (" + where(page) + ")");
            back(page).click();
            settle(page);
            check(where(page).startsWith("turn1"), "back reaches turn1 (" + where(page) + ")");
        });
    }

    /**
     * The arrow keys keep working after an arrow has been clicked.
     *
     * <p>Redrawing the pane replaces the very button that was clicked, so whatever the browser was
     * focusing is gone — and a key handler bound to the pane hears nothing after that.</p>
     */
    private void arrowKeysStillWorkAfterClickingAnArrow(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_FIRST_ENTRY);
            forward(page).click();
            settle(page);
            check(where(page).startsWith("turn3"), "the click moved to turn3 (" + where(page) + ")");

            // No explicit focus() here: that is the point. After the click, pressing the key is
            // what a person does next.
            page.keyboard().press("ArrowRight");
            page.waitForTimeout(1200);
            check(where(page).startsWith("turn4"),
                    "the right arrow key still moves after a click (" + where(page) + ")");
        });
    }

    /** Up and down move between the messages of the turn that is open. */
    private void upAndDownStepBetweenTheMessagesOfTheTurn(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_FIRST_ENTRY);
            check(selectedDirection(page).equals("user → loop"),
                    "opens on the message the matched entry starts with ("
                            + selectedDirection(page) + ")");

            page.keyboard().press("ArrowDown");
            page.waitForTimeout(800);
            check(selectedDirection(page).equals("loop → LLM"),
                    "down moves to the next message (" + selectedDirection(page) + ")");

            page.keyboard().press("ArrowUp");
            page.waitForTimeout(800);
            check(selectedDirection(page).equals("user → loop"),
                    "up moves back (" + selectedDirection(page) + ")");

            page.keyboard().press("ArrowUp");
            page.waitForTimeout(500);
            check(selectedDirection(page).equals("user → loop"),
                    "up at the first message stays put (" + selectedDirection(page) + ")");
        });
    }

    /** Neither arrow leads out of the conversation the result belongs to. */
    private void arrowsStopAtTheEndsOfTheConversation(Browser browser, String base) {
        withScreen(browser, base, page -> {
            selectResult(page, ALPHA_FIRST_ENTRY);
            check(back(page).isDisabled(), "the first turn has no back arrow");

            selectResult(page, ALPHA_TURN4_TOOL);
            check(forward(page).isDisabled(),
                    "the last turn of the conversation has no forward arrow — "
                    + "the next row in the database belongs to another conversation");

            selectResult(page, BETA_TURN1);
            check(back(page).isDisabled() && forward(page).isDisabled(),
                    "the other conversation's only turn has neither arrow");
            check(where(page).contains("Beta"),
                    "and it is that conversation being read (" + where(page) + ")");
        });
    }

    // --- driving the screen -------------------------------------------------------------------

    /** Opens the Conversations screen with every fixture call matched, and runs one check block. */
    private void withScreen(Browser browser, String base, java.util.function.Consumer<Page> body) {
        Page page = browser.newPage(new Browser.NewPageOptions()
                .setViewportSize(1280, 800));
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

    /** Clicks the result whose snippet holds {@code marker}, and waits for its turn to be readable. */
    private void selectResult(Page page, String marker) {
        page.locator(".conv-hit").filter(
                new Locator.FilterOptions().setHasText(marker)).first().click();
        settle(page);
    }

    /**
     * Waits until the turn is listed and the call it opened on has arrived.
     *
     * <p>Waiting for the text, not just for the list, is what makes a measurement mean anything:
     * the text is fetched after the list is drawn, and it is the height of the text that decides
     * how the pane is divided. Measured while it still says "Loading…", every layout looks fine —
     * which is how a calls list squeezed to 11 pixels passed this test once.</p>
     */
    private void settle(Page page) {
        page.locator(".conv-msg").first().waitFor(
                new Locator.WaitForOptions().setTimeout(15_000));
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String text = page.locator("#conv-text").textContent();
            if (text != null && !text.isBlank() && !"Loading…".equals(text.strip())) {
                page.waitForTimeout(200);   // let the pane settle on the text it now holds
                return;
            }
            page.waitForTimeout(100);
        }
        throw new AssertionError("the call's text never arrived");
    }

    /** True when the document is no taller than the window it is being shown in. */
    private boolean fitsTheWindow(Page page) {
        Object over = page.evaluate(
                "() => document.documentElement.scrollHeight - window.innerHeight");
        double overflow = ((Number) over).doubleValue();
        if (overflow > 2) {
            System.out.println("    (document is " + overflow + "px taller than the window)");
        }
        return overflow <= 2;
    }

    /** True when the element is present and its box lies inside the window. */
    private boolean inTheWindow(Page page, String selector) {
        Locator el = page.locator(selector).first();
        if (el.count() == 0) {
            return false;
        }
        var box = el.boundingBox();
        if (box == null) {
            return false;
        }
        Object height = page.evaluate("() => window.innerHeight");
        double windowHeight = ((Number) height).doubleValue();
        return box.y >= 0 && box.y + box.height <= windowHeight + 1;
    }

    /** How many message rows lie wholly within the list's own box. */
    private int fullyVisibleRows(Page page) {
        Object n = page.evaluate("""
                () => {
                  const box = document.querySelector('.conv-messages').getBoundingClientRect();
                  return [...document.querySelectorAll('.conv-msg')].filter(el => {
                    const r = el.getBoundingClientRect();
                    return r.top >= box.top - 1 && r.bottom <= box.bottom + 1;
                  }).length;
                }
                """);
        return ((Number) n).intValue();
    }

    /** How many pixels of the first message row are hidden above the top of the list. */
    private int clippedAtTheTop(Page page) {
        Object px = page.evaluate("""
                () => {
                  const box = document.querySelector('.conv-messages').getBoundingClientRect();
                  const first = document.querySelector('.conv-msg').getBoundingClientRect();
                  return Math.round(Math.max(0, box.top - first.top));
                }
                """);
        return ((Number) px).intValue();
    }

    private Locator back(Page page) {
        return page.locator(".conv-turn-back");
    }

    private Locator forward(Page page) {
        return page.locator(".conv-turn-forward");
    }

    private String where(Page page) {
        return page.locator(".conv-turn-where").textContent().trim();
    }

    private int messages(Page page) {
        return page.locator(".conv-msg").count();
    }

    private Locator lastMessage(Page page) {
        return page.locator(".conv-msg").last();
    }

    private String selectedDirection(Page page) {
        return page.locator(".conv-msg.selected .conv-msg-dir").textContent().trim();
    }

    /** Every direction named in the list, in the order they were sent. */
    private java.util.List<String> directions(Page page) {
        return page.locator(".conv-msg-dir").allTextContents().stream()
                .map(String::trim).toList();
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

            // turn1: long enough that listing it would once have run off the bottom of the screen,
            // and each entry long enough that opening one would have done the same on its own.
            long id = 100;
            for (int i = 0; i < LONG_TURN_ENTRIES; i++) {
                int step = i / 2 + 1;
                boolean llm = i % 2 == 0;
                String said = i == 0 ? ALPHA_FIRST_ENTRY
                        : (i == LONG_TURN_ENTRIES - 1 ? ALPHA_LAST_ENTRY_OF_TURN1
                                                      : "entry " + (i + 1) + " of the long turn");
                insert(c, id++, 1, "turn1/step" + step + "/" + (llm ? "llm" : "tool"),
                        llm ? llmEntry(ALPHA_PROMPT, "haystack: " + said + "\n" + filler(4000))
                            : toolEntry("write", "haystack: " + said + "\n" + filler(4000)));
            }
            insert(c, 200, 1, "turn3/step1/llm",
                    llmEntry("the question of turn3", "haystack: " + ALPHA_TURN3));
            insert(c, 201, 1, "turn4/step1/llm",
                    llmEntry("the question of turn4", "haystack: the last turn of Alpha opens here"));
            insert(c, 202, 1, "turn4/step1/tool",
                    toolEntry("read", "haystack: " + ALPHA_TURN4_TOOL));
            insert(c, 300, 2, "turn1/step1/llm",
                    llmEntry("the question of Beta", "haystack: " + BETA_TURN1));
        }
        System.out.println("  fixture database → " + db.toAbsolutePath() + ".mv.db");
        return db.toAbsolutePath();
    }

    /** An entry for a model call, in the shape the conversation log writes one. */
    private static String llmEntry(String prompt, String answer) {
        String request = "{\"messages\":[{\"role\":\"system\",\"content\":\"be helpful\"},"
                + "{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}";
        return "REQUEST: " + request + "\nRESPONSE: " + answer + "\nUSAGE: {\"prompt_tokens\":12}";
    }

    /** An entry for a tool run, in the shape the conversation log writes one. */
    private static String toolEntry(String name, String observation) {
        return "TOOL: " + name + "\nINPUT: {\"path\":\"somewhere\"}\nOBSERVATION: " + observation;
    }

    /** Body text long enough that one message cannot be shown without a scroll of its own. */
    private static String filler(int chars) {
        StringBuilder sb = new StringBuilder(chars + 40);
        while (sb.length() < chars) {
            sb.append("the recorded exchange, line ").append(sb.length()).append('\n');
        }
        return sb.toString();
    }

    private static void insert(Connection c, long id, long sessionId, String label, String message)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO logs (id, session_id, timestamp, label, level, message) "
                + "VALUES (?, ?, ?, ?, 'INFO', ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, sessionId);
            // Ordered the same way the ids are, which is the order a merge writes them in.
            ps.setTimestamp(3, Timestamp.valueOf(
                    java.time.LocalDateTime.of(2026, 9, 9, 10, 0, 0).plusSeconds(id)));
            ps.setString(4, label);
            ps.setString(5, message);
            ps.executeUpdate();
        }
    }
}
