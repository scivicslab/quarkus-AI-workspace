package com.scivicslab.serviceportal.e2e;

class LocalToClaudeE2E {

    public static void main(String[] args) {
        try { new LocalToClaudeE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- LocalToClaudeE2E ---");
        int mockPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockPort);
        mockVllm.start();
        try {
            ClaudeToClaudeE2E.runAgentPair("openai-compat", "claude", mockPort);
        } finally {
            mockVllm.stop();
        }
        System.out.println("LocalToClaudeE2E: PASSED");
    }
}
