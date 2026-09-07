package com.scivicslab.aiworkspace.conversation;

/**
 * Holds what the last merge of the conversation logs did, and whether one is running now
 * ({@code ConversationLogSearchPlacement_260908_oo01}).
 *
 * <p>A plain POJO. The merge runs on its own thread and the screen is drawn on another, so this is
 * held in an actor and touched by one thread at a time ({@code ActorBasedState_260907_oo01}).</p>
 */
public class ConversationLogMergeActor {

    /**
     * What one merge did, as the screen shows it.
     *
     * @param when      when the merge finished, or {@code ""} when none has
     * @param sources   how many databases were read
     * @param merged    how many conversations were added
     * @param skipped   how many were already there
     * @param turns     how many turns were added
     * @param problems  what could not be read, one line each
     * @param error     what stopped the merge, or {@code ""} when nothing did
     */
    public record Outcome(String when, int sources, int merged, int skipped, int turns,
                          java.util.List<String> problems, String error) {

        /** The outcome before any merge has been run. */
        public static Outcome none() {
            return new Outcome("", 0, 0, 0, 0, java.util.List.of(), "");
        }

        /** @return whether a merge has finished at least once */
        public boolean ran() {
            return !when.isEmpty();
        }
    }

    private boolean running;
    private Outcome last = Outcome.none();

    /**
     * Marks a merge as started, unless one already is.
     *
     * @return true when the caller may start one, false when one is already running
     */
    public boolean begin() {
        if (running) {
            return false;
        }
        running = true;
        return true;
    }

    /**
     * Records what a merge did and marks it finished.
     *
     * @param outcome what it did
     */
    public void finish(Outcome outcome) {
        last = outcome;
        running = false;
    }

    /** @return whether a merge is running now */
    public boolean running() {
        return running;
    }

    /** @return what the last finished merge did */
    public Outcome last() {
        return last;
    }
}
