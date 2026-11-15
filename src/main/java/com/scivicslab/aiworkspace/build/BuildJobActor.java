package com.scivicslab.aiworkspace.build;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One snapshot build: how far it has got, and what it has printed.
 *
 * <p>A plain object with no knowledge of threads. One actor owns it, so the fields below are
 * ordinary fields and the log is an ordinary {@link ArrayDeque}: the virtual thread running the
 * build and the HTTP request asking about it both reach this through the same actor, one at a
 * time ({@code ActorBasedState_260907_oo01}).
 */
public class BuildJobActor {

    /** How the build ended, or that it has not. */
    public enum State { RUNNING, SUCCESS, FAILED }

    /** How many lines of the build's output are kept. */
    private static final int MAX_LOG_LINES = 500;

    private final String id;
    private final String tool;

    private State state = State.RUNNING;
    private String step = "queued";
    private String resultFile;
    private String error;
    private final Deque<String> log = new ArrayDeque<>();
    /** Completed with the end state when the job ends, for whoever waits to act on it. */
    private final CompletableFuture<State> finished = new CompletableFuture<>();
    /** The job started after this one ended (Build and restart: the restart), or null. */
    private String nextJobId;
    /** Why no job was started after this one, when one was meant to be, or null. */
    private String nextSkipped;

    public BuildJobActor(String id, String tool) {
        this.id = id;
        this.tool = tool;
    }

    public String id()         { return id; }
    public String tool()       { return tool; }
    public State state()       { return state; }
    public String step()       { return step; }
    public String resultFile() { return resultFile; }
    public String error()      { return error; }
    public String nextJobId()  { return nextJobId; }
    public String nextSkipped(){ return nextSkipped; }

    /** @return completed with SUCCESS or FAILED when the job ends; never completed while it runs */
    public CompletableFuture<State> finished() { return finished; }

    /** Records the job that was started because this one ended, so a reader can follow it. */
    public void next(String jobId)           { this.nextJobId = jobId; }

    /** Records why no job was started after this one, though one was meant to be. */
    public void nextSkipped(String why)      { this.nextSkipped = why; }

    public void step(String step)             { this.step = step; }
    public void resultFile(String resultFile) { this.resultFile = resultFile; }

    /** Records that the build finished, with the jar it produced. */
    public void succeeded() {
        this.state = State.SUCCESS;
        finished.complete(State.SUCCESS);
    }

    /** Records that the build stopped, with why. */
    public void failed(String error) {
        this.state = State.FAILED;
        this.error = error;
        finished.complete(State.FAILED);
    }

    /** @return the last {@code n} log lines, oldest first. */
    public List<String> tail(int n) {
        int skip = Math.max(0, log.size() - n);
        return log.stream().skip(skip).toList();
    }

    /** Adds one line, dropping the oldest once the buffer is full. */
    public void append(String line) {
        log.addLast(line);
        while (log.size() > MAX_LOG_LINES) log.removeFirst();
    }
}
