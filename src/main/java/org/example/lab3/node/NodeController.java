package org.example.lab3.node;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * NodeController exposes REST endpoints that OTHER nodes call to update
 * this node's state, or to check whether this node is alive.
 *
 * Endpoints:
 *
 *   PUT  /node/prev   — set our prevId
 *                       called by MulticastReceiver of an existing node when
 *                       it decides we are its new neighbour, OR by
 *                       ShutdownService/FailureHandler to stitch the ring
 *
 *   PUT  /node/next   — set our nextId (same idea)
 *
 *   GET  /node/ping   — heartbeat check; returns "pong" if we are alive
 *                       PingScheduler on neighbours calls this periodically
 *
 *   GET  /node/state  — debugging: returns our full NodeState as JSON
 *                       hit this in a browser to see what the node thinks
 */
@RestController
@RequestMapping("/node")
@Profile("node")
public class NodeController {

    private final NodeState state;

    public NodeController(NodeState state) {
        this.state = state;
    }

    /**
     * Updates this node's prevId.
     *
     * Example: when nodeB joins between nodeA and nodeC, nodeA's
     * MulticastReceiver calls PUT /node/prev on nodeB with id=nodeA.id,
     * so nodeB knows nodeA is behind it.
     */
    @PutMapping("/prev")
    public ResponseEntity<Void> updatePrev(@RequestBody NeighbourUpdate req) {
        System.out.println("[NodeController] Setting prevId = " + req.getId());
        state.setPrevId(req.getId());
        return ResponseEntity.ok().build();
    }

    /**
     * Updates this node's nextId.
     */
    @PutMapping("/next")
    public ResponseEntity<Void> updateNext(@RequestBody NeighbourUpdate req) {
        System.out.println("[NodeController] Setting nextId = " + req.getId());
        state.setNextId(req.getId());
        return ResponseEntity.ok().build();
    }

    /**
     * Simple ping / heartbeat.
     * Returns HTTP 200 "pong" if this node is alive.
     * If the node is dead, the HTTP call throws a connection exception on
     * the caller's side — that's what PingScheduler catches.
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }

    /**
     * Returns the full current state of this node as JSON.
     * Very useful during debugging — just open in a browser.
     * Example: http://192.168.0.5:8081/node/state
     */
    @GetMapping("/state")
    public ResponseEntity<NodeState> getState() {
        return ResponseEntity.ok(state);
    }

    // -------------------------------------------------------------------------
    // Inner class — request body for PUT /node/prev and PUT /node/next
    // -------------------------------------------------------------------------

    /**
     * Simple wrapper around a single integer ring ID.
     * Jackson deserialises {"id": 12345} into this automatically.
     */
    public static class NeighbourUpdate {
        private int id;
        public int  getId()        { return id; }
        public void setId(int id)  { this.id = id; }
    }
}