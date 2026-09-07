package com.scivicslab.aiworkspace.conversation;

import com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.logdb.LogMerger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings every tool instance's conversations into one database
 * ({@code ConversationLogSearchPlacement_260908_oo01}).
 *
 * <p>Each tool instance writes its own database file, and only this portal knows where they all
 * are and is running the whole time. So the merge is started from here rather than from a tool, and
 * the copying itself is {@link LogMerger}, the same code the {@code log-merge} subcommand runs.</p>
 *
 * <p>Calling {@link LogMerger} directly rather than starting the {@code turing-workflow} command:
 * that command has to be installed and found before a merge can run, and there is nothing this
 * needs from a terminal.</p>
 */
@ApplicationScoped
public class ConversationLogMerge {

    private static final Logger LOG = Logger.getLogger(ConversationLogMerge.class.getName());

    @Inject
    AiWorkspaceActorSystem actors;

    /** Where the merged database is written. The Conversations screen reads the same path. */
    @ConfigProperty(name = "ai-workspace.conversation-log.db-path",
            defaultValue = "${user.dir}/chat-ui-iolog-all")
    String targetPath;

    /** The directory walked for the tool instances' own databases. */
    @ConfigProperty(name = "ai-workspace.conversation-log.scan-dir",
            defaultValue = "${user.dir}")
    String scanDir;

    /**
     * Which files under the scan directory are conversation logs.
     *
     * <p>Without this the walk takes every {@code .mv.db} file it finds. The works directory holds
     * databases several other programs wrote — a vocabulary drill, an institution-name checker, a
     * cluster's own logs — and none of them are conversations.</p>
     */
    @ConfigProperty(name = "ai-workspace.conversation-log.name-prefixes",
            defaultValue = "chat-ui-iolog-,chatui3-iolog-")
    String namePrefixes;

    /**
     * Starts a merge on its own thread.
     *
     * @return true when this call started one, false when one was already running
     */
    public boolean start() {
        ActorRef<ConversationLogMergeActor> state = actors.conversationLogMerge();
        if (!Boolean.TRUE.equals(state.ask(ConversationLogMergeActor::begin).join())) {
            return false;
        }
        // A virtual thread rather than the request thread: the merge reads every conversation
        // database on disk, and whoever pressed the button is holding an HTTP request open.
        Thread.ofVirtual().name("conversation-log-merge").start(() -> {
            ConversationLogMergeActor.Outcome outcome = run();
            state.tell(a -> a.finish(outcome));
        });
        return true;
    }

    /** @return the directory that is walked, for the screen to show */
    public String scanDirectory() {
        return Path.of(scanDir).toAbsolutePath().toString();
    }

    /** @return the file-name prefixes a database must have to be merged, for the screen to show */
    public String prefixes() {
        return namePrefixes;
    }

    /** Reads every conversation database under the scan directory into the merged one. */
    private ConversationLogMergeActor.Outcome run() {
        List<Path> sources;
        try {
            sources = LogMerger.scan(Path.of(scanDir), prefixList());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not look for conversation databases", e);
            return new ConversationLogMergeActor.Outcome(now(), 0, 0, 0, 0, List.of(),
                    "Could not read " + scanDirectory() + ": " + e.getMessage());
        }
        try {
            LogMerger.Report report = LogMerger.merge(Path.of(targetPath), sources);
            LOG.info("Merged conversation logs: " + report.sessionsMerged() + " added, "
                    + report.sessionsSkipped() + " already there, from " + sources.size()
                    + " databases");
            return new ConversationLogMergeActor.Outcome(now(), sources.size(),
                    report.sessionsMerged(), report.sessionsSkipped(), report.logsMerged(),
                    report.problems(), "");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Merging the conversation logs failed", e);
            return new ConversationLogMergeActor.Outcome(now(), sources.size(), 0, 0, 0, List.of(),
                    String.valueOf(e.getMessage()));
        }
    }

    /** @return the configured prefixes, split on commas */
    private List<String> prefixList() {
        List<String> out = new ArrayList<>();
        for (String part : namePrefixes.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String now() {
        return Instant.now().toString().replace('T', ' ').substring(0, 19);
    }
}
