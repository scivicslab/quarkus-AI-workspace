package com.scivicslab.aiworkspace.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** The names of a branch entry's files (DashboardBuilder_261118_oo01). */
class BranchVariantTest {

    @Test
    void theDefaultBranchKeepsItsNames() {
        assertEquals("chat-ui-with-audit-trail.jar", BranchVariant.linkName("chat-ui-with-audit-trail.jar", null));
        assertEquals("chat-ui-with-audit-trail.jar", BranchVariant.linkName("chat-ui-with-audit-trail.jar", " "));
        assertEquals("chat-ui-with-audit-trail", BranchVariant.checkoutDir("chat-ui-with-audit-trail", null));
    }

    @Test
    void aBranchAddsAtAndItsShortName() {
        assertEquals("chat-ui-with-audit-trail@v5.x.jar", BranchVariant.linkName("chat-ui-with-audit-trail.jar", "v5.x"));
        assertEquals("quarkus-chat-ui@feat-workflow-as-queue-item.jar",
                BranchVariant.linkName("quarkus-chat-ui.jar", "feat/workflow-as-queue-item"));
        assertEquals("chat-ui-with-audit-trail@v5.x", BranchVariant.checkoutDir("chat-ui-with-audit-trail", "v5.x"));
    }

    @Test
    void theBuildOutputIsFoundByTheNameBeforeTheAt() {
        assertEquals("chat-ui-with-audit-trail", BranchVariant.baseOf("chat-ui-with-audit-trail@v5.x.jar"));
        assertEquals("chat-ui-with-audit-trail-plugin-shell", BranchVariant.baseOf("chat-ui-with-audit-trail-plugin-shell@v5.x.jar"));
        assertEquals("html-saurus", BranchVariant.baseOf("html-saurus.jar"));
    }

    @Test
    void aBranchNamedLikeAVersionStaysDistinctFromAVersionedFile() {
        // With "-" the link would read as the versioned file of version 2.x.
        assertEquals("chat-ui-with-audit-trail@2.x.jar", BranchVariant.linkName("chat-ui-with-audit-trail.jar", "2.x"));
    }

    @Test
    void theVersionedFileCarriesTheBuildTimeToTheSecondThenTheBranch() {
        String stamp = BranchVariant.stamp(LocalDateTime.of(2026, 11, 18, 23, 5, 12));
        assertEquals("261118230512", stamp);
        assertEquals("chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512.jar",
                BranchVariant.versionedName("chat-ui-with-audit-trail-3.0.0-SNAPSHOT.jar", stamp, null));
        assertEquals("chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512@v5.x.jar",
                BranchVariant.versionedName("chat-ui-with-audit-trail-3.0.0-SNAPSHOT.jar", stamp, "v5.x"));
    }

    @Test
    void tenAndTwelveDigitStampsAndTheBranchAreBothTakenOff() {
        assertEquals("3.0.0-SNAPSHOT", BranchVariant.withoutBranchAndStamp("3.0.0-SNAPSHOT-261118230512@v5.x"));
        assertEquals("3.0.0-SNAPSHOT", BranchVariant.withoutBranchAndStamp("3.0.0-SNAPSHOT-2611181458"));
        assertEquals("3.0.0-SNAPSHOT", BranchVariant.withoutBranchAndStamp("3.0.0-SNAPSHOT"));
        assertEquals("1.0-12345678901", BranchVariant.withoutBranchAndStamp("1.0-12345678901"), "eleven digits are not a stamp");
    }
}
