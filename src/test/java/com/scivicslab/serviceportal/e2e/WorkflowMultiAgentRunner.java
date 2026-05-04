package com.scivicslab.serviceportal.e2e;

public class WorkflowMultiAgentRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Workflow Multi-Agent E2E Tests ===");
        new WorkflowClaudeToClaudeE2E().run();
        Thread.sleep(3_000);
        new WorkflowLocalToLocalE2E().run();
        Thread.sleep(3_000);
        new WorkflowLocalToClaudeE2E().run();
        Thread.sleep(3_000);
        new WorkflowClaudeToLocalE2E().run();
        System.out.println("=== All Workflow Multi-Agent E2E Tests PASSED ===");
    }
}
