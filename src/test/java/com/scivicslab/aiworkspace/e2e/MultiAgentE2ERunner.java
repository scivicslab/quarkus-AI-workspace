package com.scivicslab.aiworkspace.e2e;

public class MultiAgentE2ERunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Multi-Agent E2E Tests ===");
        new GatewayAggregationE2E().run();
        Thread.sleep(3_000);
        new ClaudeToClaudeE2E().run();
        Thread.sleep(3_000);
        new LocalToLocalE2E().run();
        Thread.sleep(3_000);
        new LocalToClaudeE2E().run();
        Thread.sleep(3_000);
        new ClaudeToLocalE2E().run();
        System.out.println("=== All Multi-Agent E2E Tests PASSED ===");
    }
}
