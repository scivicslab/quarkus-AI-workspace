package com.scivicslab.aiworkspace.rest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit test for {@code CompanionJars_260912_oo01}: Download Latest picks, for each link name, the
 * release asset named {@code <base>-<version>.jar}, so a body's base never takes a plugin's asset
 * and a missing companion is reported rather than silently skipped.
 */
class ReleaseAssetChoiceTest {

    private static final String RELEASE = """
            {"tag_name":"v2.0.0","assets":[
              {"name":"chat-ui-with-audit-trail-plugin-web-tools-2.0.0.jar","uploader":{"login":"x","id":1},
               "browser_download_url":"https://example/plugin-web-tools-2.0.0.jar"},
              {"name":"chat-ui-with-audit-trail-2.0.0.jar","uploader":{"login":"x","id":1},
               "browser_download_url":"https://example/body-2.0.0.jar"},
              {"name":"chat-ui-with-audit-trail-2.0.0-sources.jar","uploader":{"login":"x","id":1},
               "browser_download_url":"https://example/body-2.0.0-sources.jar"}
            ]}""";

    @Test
    void findJarAssets_listsEveryJarExceptSourcesAndJavadoc() {
        List<String[]> assets = ServiceResource.findJarAssets(RELEASE);
        assertEquals(2, assets.size());
        assertEquals("chat-ui-with-audit-trail-plugin-web-tools-2.0.0.jar", assets.get(0)[0]);
        assertEquals("https://example/body-2.0.0.jar", assets.get(1)[1]);
    }

    @Test
    void pickAsset_bodyBaseSkipsThePluginAssetListedBeforeIt() {
        List<String[]> assets = ServiceResource.findJarAssets(RELEASE);
        assertEquals("chat-ui-with-audit-trail-2.0.0.jar",
                ServiceResource.pickAsset(assets, "chat-ui-with-audit-trail.jar")[0]);
        assertEquals("chat-ui-with-audit-trail-plugin-web-tools-2.0.0.jar",
                ServiceResource.pickAsset(assets, "chat-ui-with-audit-trail-plugin-web-tools.jar")[0]);
    }

    @Test
    void pickAsset_missingCompanion_isNull() {
        List<String[]> assets = ServiceResource.findJarAssets(RELEASE);
        assertNull(ServiceResource.pickAsset(assets, "chat-ui-with-audit-trail-plugin-harness.jar"));
    }
}
