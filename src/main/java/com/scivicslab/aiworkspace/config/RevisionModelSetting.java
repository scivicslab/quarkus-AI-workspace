package com.scivicslab.aiworkspace.config;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

/**
 * The model html-saurus's Revise panel proposes and makes revisions with, chosen on the Settings screen
 * ({@code RevisionPanel_261120_oo01}). Unset, html-saurus uses the translation model. It is a setting of its own
 * because the two are chosen for different things: translation sends a whole page's paragraphs at once and wants a
 * fast model, a revision wants one that rewrites the text naturally.
 */
@ApplicationScoped
public class RevisionModelSetting extends ModelSetting {

    static final String KEY = "revision.model";

    public RevisionModelSetting() {
        this(defaultFile());
    }

    RevisionModelSetting(Path file) {
        super(file, KEY);
    }
}
