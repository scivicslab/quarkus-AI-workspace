package com.scivicslab.aiworkspace.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A catalog in which installing one entry would replace another's jar is refused (DashboardBuilder_261118_oo01). */
class CatalogRulesTest {

    private static List<ToolRegistryEntry> parse(String yaml) {
        return ToolRegistryLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theBundledCatalogKeepsTheRules() {
        assertTrue(ToolRegistryLoader.load().size() > 5, "the bundled catalog was read");
        assertEquals(List.of(), CatalogRules.violations(ToolRegistryLoader.load()));
    }

    @Test
    void entriesThatDifferOnlyInHowTheyStartMayShareALink() {
        assertEquals(List.of(), CatalogRules.violations(parse("""
                tools:
                  - {name: a, jar: chat.jar, github: o/chat, jvmArgs: ["-Dx=1"]}
                  - {name: b, jar: chat.jar, github: o/chat, jvmArgs: ["-Dx=2"]}
                """)));
    }

    @Test
    void aBranchEntryWithTheBranchSuffixOnItsLinksIsAccepted() {
        assertEquals(List.of(), CatalogRules.violations(parse("""
                tools:
                  - {name: a, jar: chat.jar, github: o/chat, companionJars: [chat-plugin.jar]}
                  - {name: a5, jar: chat@v5.x.jar, github: o/chat, branch: v5.x, companionJars: [chat-plugin@v5.x.jar]}
                """)));
    }

    @Test
    void aBranchEntryWithoutTheSuffixIsRefused() {
        List<String> broken = CatalogRules.violations(parse("""
                tools:
                  - {name: a5, jar: chat-5.jar, github: o/chat, branch: v5.x}
                """));
        assertEquals(1, broken.size());
        assertTrue(broken.get(0).contains("@v5.x.jar"), broken.get(0));
    }

    @Test
    void twoBranchesSharingALinkAreRefused() {
        List<String> broken = CatalogRules.violations(parse("""
                tools:
                  - {name: a, jar: chat.jar, github: o/chat, companionJars: [chat-plugin@v5.x.jar]}
                  - {name: a5, jar: chat@v5.x.jar, github: o/chat, branch: v5.x, companionJars: [chat-plugin@v5.x.jar]}
                """));
        assertTrue(broken.stream().anyMatch(b -> b.contains("share the link chat-plugin@v5.x.jar")), broken.toString());
    }

    @Test
    void theLoaderRefusesACatalogThatBreaksTheRules() {
        List<ToolRegistryEntry> bad = parse("""
                tools:
                  - {name: a, jar: chat.jar, github: o/chat}
                  - {name: a5, jar: chat.jar, github: o/chat, branch: v5.x}
                """);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ToolRegistryLoader.checked(bad));
        assertTrue(e.getMessage().contains("a5"), e.getMessage());
    }

    @Test
    void titleIsTheDefaultHeading_elseTheName() {
        List<ToolRegistryEntry> entries = parse("""
                tools:
                  - {name: a, jar: a.jar, title: "Tool A"}
                  - {name: b, jar: b.jar}
                """);
        assertEquals("Tool A", entries.get(0).defaultTitle());
        assertEquals("b", entries.get(1).defaultTitle());
    }
}
