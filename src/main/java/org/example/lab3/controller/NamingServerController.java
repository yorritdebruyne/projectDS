package org.example.lab3.controller;

import org.example.lab3.model.AddNodeRequest;
import org.example.lab3.model.FileOwnerResponse;
import org.example.lab3.model.NeighbourResponse;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.NodeRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("naming-server")
@RestController
@RequestMapping("/api")
public class NamingServerController {

    private final NodeRegistry registry;

    public NamingServerController(NodeRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/nodes")
    public ResponseEntity<NodeInfo> addNode(@RequestBody AddNodeRequest req) {
        NodeInfo node = registry.addNode(req.getName(), req.getIp());
        return ResponseEntity.ok(node);
    }

    @DeleteMapping("/nodes/{name}")
    public ResponseEntity<Void> removeNode(@PathVariable String name) {
        registry.removeNode(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/files/owner")
    public ResponseEntity<FileOwnerResponse> getFileOwner(@RequestParam String filename) {
        NodeInfo owner = registry.findOwnerForFile(filename);
        if (owner == null) {
            return ResponseEntity.notFound().build();
        }
        FileOwnerResponse resp = new FileOwnerResponse(owner.getId(), owner.getIp());
        return ResponseEntity.ok(resp);
    }

    /**
     * Returns the previous and next node of a given node ID.
     *
     * This is used during FAILURE RECOVERY:
     * A surviving node that detects a dead neighbour calls this to find out
     * who the dead node's other neighbour was, so it can stitch the ring back together.
     *
     * @param id The hash ID of the (possibly dead) node whose neighbours we want.
     * @return A NeighbourResponse containing prevId and nextId.
     */
    @GetMapping("/nodes/neighbours/{id}")
    public ResponseEntity<NeighbourResponse> getNeighbours(@PathVariable int id) {
        NeighbourResponse neighbours = registry.getNeighbours(id);
        if (neighbours == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(neighbours);
    }
}
