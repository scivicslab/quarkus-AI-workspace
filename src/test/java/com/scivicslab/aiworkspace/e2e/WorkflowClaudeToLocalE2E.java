package com.scivicslab.aiworkspace.e2e;

class WorkflowClaudeToLocalE2E {

    public static void main(String[] args) {
        try { new WorkflowClaudeToLocalE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- WorkflowClaudeToLocalE2E ---");
        int mockPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockPort);
        mockVllm.start();
        try {
            WorkflowClaudeToClaudeE2E.runWorkflowScenario("claude", "openai-compat", mockPort);
        } finally {
            mockVllm.stop();
        }
        System.out.println("WorkflowClaudeToLocalE2E: PASSED");
    }
}
