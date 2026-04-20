package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * PingScheduler periodically checks whether our neighbours are still alive.
 *
 * Every N milliseconds (configured by ping.interval.ms) it calls
 * GET /node/ping on both the previous and next neighbour.
 *
 * If the call SUCCEEDS (HTTP 200 "pong") → neighbour is alive, do nothing.
 * If the call FAILS (exception) → neighbour has crashed, hand off to FailureHandler.
 *
 * @EnableScheduling activates Spring's scheduling mechanism for this service.
 * Without it, @Scheduled methods are simply ignored.
 */
@Service
@Profile("node")
@EnableScheduling
public class PingScheduler {

    @Value("${node.peer.port:8081}")
    private int peerPort;

    private final NodeState      state;
    private final FailureHandler failureHandler;
    private final NodeIpLookup   ipLookup;
    private final RestTemplate   restTemplate = new RestTemplate();

    public PingScheduler(NodeState state, FailureHandler failureHandler, NodeIpLookup ipLookup) {
        this.state          = state;
        this.failureHandler = failureHandler;
        this.ipLookup       = ipLookup;
    }

    /**
     * Runs every N milliseconds after the previous execution finishes.
     * fixedRateString reads from application.properties so you can tune it
     * without recompiling.
     *
     * We ping BOTH neighbours every tick. If either is dead, FailureHandler
     * repairs the ring.
     */
    @Scheduled(fixedRateString = "${ping.interval.ms:5000}")
    public void pingNeighbours() {
        // Don't start pinging before bootstrap has completed
        if (state.getCurrentId() == -1) return;

        pingOne(state.getPrevId(), "prev");
        pingOne(state.getNextId(), "next");
    }

    /**
     * Pings a single neighbour.
     *
     * @param neighbourId  Ring ID of the neighbour to ping.
     * @param label        "prev" or "next" (just for readable log output).
     */
    private void pingOne(int neighbourId, String label) {
        // Don't ping ourselves — happens when we are alone in the ring
        if (neighbourId == state.getCurrentId()) return;

        String ip = ipLookup.getIpForId(neighbourId);
        if (ip == null) {
            // Can't find the IP — the node may have already been removed from the naming server
            return;
        }

        try {
            restTemplate.getForObject(
                    "http://" + ip + ":" + peerPort + "/node/ping",
                    String.class
            );
            // Reached here → neighbour replied with "pong", all good
        } catch (Exception e) {
            // Any exception means the neighbour is unreachable → treat as dead
            System.err.println("[PingScheduler] " + label + " node (id=" + neighbourId
                    + ", ip=" + ip + ") is unreachable! Starting failure recovery.");

            // We pass the node's name as null here because PingScheduler only
            // knows the ID, not the name. FailureHandler will skip the DELETE
            // on the naming server in that case. To fix this properly, store
            // a id→name map in NodeState during bootstrap.
            failureHandler.handleFailure(neighbourId, null);
        }
    }
}