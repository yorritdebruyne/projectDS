package org.example.lab3.node;

import org.example.lab3.node.NodeController.NeighbourUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;

/**
 * ShutdownService handles a GRACEFUL shutdown of this node.
 *
 * Spring calls the shutdown() method automatically when the application
 * is stopping — triggered by CTRL-C, a SIGTERM signal, or
 * calling SpringApplication.exit().
 * The @PreDestroy annotation is what makes Spring call it automatically.
 *
 * === What we do (from the slides, Shutdown section) ===
 *
 * 1. Tell the PREVIOUS node: "your new next is my current next."
 *    PUT http://{prevIp}/node/next  { id: this.nextId }
 *
 * 2. Tell the NEXT node: "your new prev is my current prev."
 *    PUT http://{nextIp}/node/prev  { id: this.prevId }
 *
 * 3. Remove ourselves from the naming server's ring map.
 *    DELETE /api/nodes/{name}
 *
 * After these three steps the ring is intact again without us.
 *
 * Note: if we are the only node (prev == next == self), steps 1 and 2
 * are skipped because there are no neighbours to notify.
 */
@Service
@Profile("node")
public class ShutdownService {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    @Value("${node.peer.port:8081}")
    private int peerPort;

    private final NodeState    state;
    private final NodeIpLookup ipLookup;
    private final RestTemplate restTemplate = new RestTemplate();

    public ShutdownService(NodeState state, NodeIpLookup ipLookup) {
        this.state    = state;
        this.ipLookup = ipLookup;
    }

    /**
     * Called automatically by Spring on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        System.out.println("[Shutdown] Starting graceful shutdown. State: " + state);

        int myId   = state.getCurrentId();
        int prevId = state.getPrevId();
        int nextId = state.getNextId();

        // Only notify neighbours if we are NOT alone in the ring
        boolean alone = (prevId == myId && nextId == myId);

        if (!alone) {

            // --- Step 1: tell prev its new next is our current next ---
            String prevIp = ipLookup.getIpForId(prevId);
            if (prevIp != null) {
                try {
                    NeighbourUpdate req = new NeighbourUpdate();
                    req.setId(nextId); // prev.next = this.next
                    restTemplate.put("http://" + prevIp + ":" + peerPort + "/node/next", req);
                    System.out.println("[Shutdown] Told prev (" + prevId + ") new next = " + nextId);
                } catch (Exception e) {
                    System.err.println("[Shutdown] Could not reach prev: " + e.getMessage());
                }
            }

            // --- Step 2: tell next its new prev is our current prev ---
            String nextIp = ipLookup.getIpForId(nextId);
            if (nextIp != null) {
                try {
                    NeighbourUpdate req = new NeighbourUpdate();
                    req.setId(prevId); // next.prev = this.prev
                    restTemplate.put("http://" + nextIp + ":" + peerPort + "/node/prev", req);
                    System.out.println("[Shutdown] Told next (" + nextId + ") new prev = " + prevId);
                } catch (Exception e) {
                    System.err.println("[Shutdown] Could not reach next: " + e.getMessage());
                }
            }
        }

        // --- Step 3: remove ourselves from the naming server ---
        try {
            restTemplate.delete(namingServerUrl + "/api/nodes/" + state.getName());
            System.out.println("[Shutdown] Removed from naming server.");
        } catch (Exception e) {
            System.err.println("[Shutdown] Could not remove from naming server: " + e.getMessage());
        }

        System.out.println("[Shutdown] Done.");
    }
}