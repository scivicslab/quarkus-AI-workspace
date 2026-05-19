package com.scivicslab.aiworkspace.e2e;

class WorkflowLocalToClaudeE2E {

    public static void main(String[] args) {
        try { new WorkflowLocalToClaudeE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- WorkflowLocalToClaudeE2E ---");
        int mockPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockPort);
        mockVllm.start();
        try {
            WorkflowClaudeToClaudeE2E.runWorkflowScenario("openai-compat", "claude", mockPort);
        } finally {
            mockVllm.stop();
        }
        System.out.println("WorkflowLocalToClaudeE2E: PASSED");
    }
}
