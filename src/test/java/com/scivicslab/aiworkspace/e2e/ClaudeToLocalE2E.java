package com.scivicslab.aiworkspace.e2e;

class ClaudeToLocalE2E {

    public static void main(String[] args) {
        try { new ClaudeToLocalE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- ClaudeToLocalE2E ---");
        int mockPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockPort);
        mockVllm.start();
        try {
            ClaudeToClaudeE2E.runAgentPair("claude", "openai-compat", mockPort);
        } finally {
            mockVllm.stop();
        }
        System.out.println("ClaudeToLocalE2E: PASSED");
    }
}
