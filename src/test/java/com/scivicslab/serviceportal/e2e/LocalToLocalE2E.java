package com.scivicslab.serviceportal.e2e;

class LocalToLocalE2E {

    public static void main(String[] args) {
        try { new LocalToLocalE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- LocalToLocalE2E ---");
        int mockPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockPort);
        mockVllm.start();
        try {
            ClaudeToClaudeE2E.runAgentPair("openai-compat", "openai-compat", mockPort);
        } finally {
            mockVllm.stop();
        }
        System.out.println("LocalToLocalE2E: PASSED");
    }
}
