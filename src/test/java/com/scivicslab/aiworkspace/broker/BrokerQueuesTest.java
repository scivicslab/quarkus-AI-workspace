package com.scivicslab.aiworkspace.broker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What the Catalog's GPU broker tile reads out of one answer of the broker's GET /queues. */
class BrokerQueuesTest {

    /** Two queues as the broker answers them (fields the tile does not show are left in). */
    private static final String QUEUES = """
            [{"name":"chat-GLM-5.3-Flash-Uncensored","activeSlots":1,"idleSlots":3,"totalSlots":4,
              "pendingJobs":2,"completedLastHour":0,"ready":true,"generated":{"replies":0},
              "endpoints":[{"address":"192.168.5.16:8000","health":"UP","probeOk":6,"probeTotal":6}]},
             {"name":"chat-google-gemma-4-26B-A4B-it","activeSlots":0,"idleSlots":64,"totalSlots":64,
              "pendingJobs":0,"ready":true,
              "endpoints":[{"address":"192.168.5.18:8000","health":"UP"},
                           {"address":"192.168.5.19:8000","health":"DOWN"}]}]
            """;

    @Test void each_queue_keeps_its_slots_pending_jobs_and_endpoint_health() {
        List<BrokerQueues.Queue> queues = BrokerQueues.parse(QUEUES);
        assertEquals(2, queues.size());

        BrokerQueues.Queue glm = queues.get(0);
        assertEquals("chat-GLM-5.3-Flash-Uncensored", glm.name());
        assertEquals(1, glm.activeSlots());
        assertEquals(4, glm.totalSlots());
        assertEquals(2, glm.pendingJobs());
        assertEquals(List.of(new BrokerQueues.Endpoint("192.168.5.16:8000", "UP")), glm.endpoints());

        BrokerQueues.Queue gemma = queues.get(1);
        assertEquals(1, gemma.endpointsUp(), "one of gemma's two endpoints is DOWN");
        assertEquals("DOWN", gemma.endpoints().get(1).health());
    }

    @Test void a_queue_with_missing_fields_shows_zero_and_no_endpoints() {
        BrokerQueues.Queue q = BrokerQueues.parse("[{\"name\":\"embedding-e5large\"}]").get(0);
        assertEquals(0, q.totalSlots());
        assertEquals(0, q.pendingJobs());
        assertEquals(List.of(), q.endpoints());
    }

    @Test void an_answer_that_is_not_a_list_of_queues_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> BrokerQueues.parse("<html>Bad Gateway</html>"));
        assertThrows(IllegalArgumentException.class, () -> BrokerQueues.parse("{\"error\":\"x\"}"));
    }
}
