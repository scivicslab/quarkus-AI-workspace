package com.scivicslab.aiworkspace.build;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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

    public void step(String step)             { this.step = step; }
    public void resultFile(String resultFile) { this.resultFile = resultFile; }

    /** Records that the build finished, with the jar it produced. */
    public void succeeded() { this.state = State.SUCCESS; }

    /** Records that the build stopped, with why. */
    public void failed(String error) {
        this.state = State.FAILED;
        this.error = error;
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
