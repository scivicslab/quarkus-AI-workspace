package com.scivicslab.serviceportal.model;

import java.util.List;
import java.util.Map;

/**
 * Dashboard model.
 */
public record DashboardModel(
    boolean containerMode,                          // Container mode flag
    boolean hostMode,                               // Host mode flag
    String myIp,                                    // Own IP address
    List<HostService> managementServices,           // Management services
    List<ToolInstance> toolInstances,               // Running tool instances
    List<ToolDefinition> tools,                     // Available tools
    List<Container> containers,                     // Containers
    Map<String, ContainerProgress> containerProgress, // Container progress
    List<HostTool> hostTools,                       // Host tools
    String storageInfo                              // Storage information
) {}
