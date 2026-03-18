package com.scivicslab.lxdpups.exec;

/**
 * Result of a process execution.
 */
public record CommandResult(int exitCode, String stdout, String stderr) {

    public boolean success() {
        return exitCode == 0;
    }
}
