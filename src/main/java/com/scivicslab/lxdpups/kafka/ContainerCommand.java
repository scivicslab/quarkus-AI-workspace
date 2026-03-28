package com.scivicslab.lxdpups.kafka;

/**
 * Represents a command received from Kafka.
 * <p>
 * Commands are sent by processes running inside LXC containers to request
 * host-side operations (LXD container management, tool lifecycle) that cannot
 * be performed from inside a container due to nesting restrictions.
 * </p>
 *
 * <p>Container commands: {@code launch}, {@code stop}, {@code delete}</p>
 * <p>Service commands:   {@code service-start}, {@code service-stop}</p>
 * <p>Tool commands:      {@code tool-launch}, {@code tool-stop}, {@code tool-build}</p>
 *
 * <pre>
 * Container launch:    {"command":"launch",        "name":"my-box", "template":"lxd-pups/ai-tools", "remote":"local"}
 * Container stop:      {"command":"stop",          "name":"my-box"}
 * Service start:       {"command":"service-start", "name":"my-box", "unit":"llm-console-claude@15200", "remote":"local"}
 * Service stop:        {"command":"service-stop",  "name":"my-box", "unit":"llm-console-claude@15200"}
 * Tool launch:         {"command":"tool-launch",   "name":"llm-console-claude", "workDir":"/home/devteam/projects/foo"}
 * Tool stop:           {"command":"tool-stop",     "name":"llm-console-claude", "port":15200}
 * Tool build:          {"command":"tool-build",    "name":"llm-console-claude"}
 * </pre>
 */
public record ContainerCommand(
        String command,    // "launch" | "stop" | "delete" | "service-start" | "service-stop" | "tool-launch" | "tool-stop" | "tool-build"
        String name,       // container name (container/service commands) or tool name (tool commands)
        String template,   // image template alias (launch only)
        String remote,     // LXD remote (default: "local")
        String unit,       // systemd unit name (service-start / service-stop)
        String workDir,    // working directory override (tool-launch)
        Integer port       // port of the instance to stop (tool-stop)
) {}
