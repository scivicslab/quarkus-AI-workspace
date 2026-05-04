package com.scivicslab.serviceportal.e2e;

class WorkflowLocalToLocalE2E {

    public static void main(String[] args) {
        try { new WorkflowLocalToLocalE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- WorkflowLocalToLocalE2E ---");
        int mockPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockPort);
        mockVllm.start();
        try {
            WorkflowClaudeToClaudeE2E.runWorkflowScenario("openai-compat", "openai-compat", mockPort);
        } finally {
            mockVllm.stop();
        }
        System.out.println("WorkflowLocalToLocalE2E: PASSED");
    }
}
