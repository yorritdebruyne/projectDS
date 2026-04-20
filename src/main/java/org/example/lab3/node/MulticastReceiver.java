package org.example.lab3.node;

import org.example.lab3.service.HashService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * MulticastReceiver listens for JOIN messages from OTHER new nodes.
 *
 * When a new node broadcasts its "name:ip" on the multicast group, every
 * existing node runs the logic below to decide whether the new node should
 * become their new prev or next neighbour.
 *
 * === Algorithm (from slides, step 5) ===
 *
 * Let newId    = hash of the new node's name
 * Let myId     = state.currentId   (my own ring position)
 * Let myPrevId = state.prevId
 * Let myNextId = state.nextId
 *
 * CASE A — new node falls BETWEEN me and my next (straight):
 *   myId < newId < myNextId
 *   → new node is my new next.
 *     I update my own nextId.
 *     I tell the new node: "your prev = me, your next = my old next."
 *
 * CASE B — new node falls BETWEEN my prev and me (straight):
 *   myPrevId < newId < myId
 *   → new node is my new prev.
 *     I update my own prevId.
 *     I tell the new node: "your next = me, your prev = my old prev."
 *
 * WRAP-AROUND CASES (ring is circular):
 *
 * CASE C — I am the LARGEST node (myNextId < myId, meaning next wraps to start):
 *   newId > myId  OR  newId < myNextId
 *   → new node falls after me in the ring.
 *     Same action as Case A.
 *
 * CASE D — I am the SMALLEST node (myPrevId > myId, meaning prev wraps to end):
 *   newId < myId  OR  newId > myPrevId
 *   → new node falls before me in the ring.
 *     Same action as Case B.
 *
 * If none of the cases match, the new node is not our neighbour — someone else
 * in the ring will handle it.
 */
@Service
@Profile("node")
public class MulticastReceiver {

    @Value("${multicast.group:230.0.0.0}")
    private String multicastGroup;

    @Value("${multicast.port:4446}")
    private int multicastPort;

    @Value("${node.name}")
    private String myName; // used to ignore our OWN multicast message

    @Value("${node.peer.port:8081}")
    private int peerPort;

    private final NodeState   state;
    private final HashService hashService;
    private final RestTemplate restTemplate = new RestTemplate();

    public MulticastReceiver(NodeState state, HashService hashService) {
        this.state       = state;
        this.hashService = hashService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        // Run in a background daemon thread so it does not block Spring startup
        Thread t = new Thread(this::listenLoop, "node-multicast-receiver");
        t.setDaemon(true);
        t.start();
    }

    private void listenLoop() {
        try (MulticastSocket socket = new MulticastSocket(multicastPort)) {

            InetAddress group = InetAddress.getByName(multicastGroup);
            socket.joinGroup(group);
            System.out.println("[MulticastReceiver] Listening for new nodes...");

            byte[] buf = new byte[256];

            while (true) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt); // blocks until a packet arrives

                String   msg   = new String(pkt.getData(), 0, pkt.getLength(),
                        StandardCharsets.UTF_8).trim();
                String[] parts = msg.split(":");
                if (parts.length != 2) continue; // malformed packet, skip

                String newName = parts[0];
                String newIp   = parts[1];

                // IMPORTANT: ignore our OWN multicast that we sent during bootstrap
                if (newName.equals(myName)) continue;

                // Only process if we are already initialised
                if (state.getCurrentId() == -1) continue;

                int newId    = hashService.hashToRing(newName);
                int myId     = state.getCurrentId();
                int myPrevId = state.getPrevId();
                int myNextId = state.getNextId();

                System.out.printf("[MulticastReceiver] New node: name=%s id=%d | me=%d prev=%d next=%d%n",
                        newName, newId, myId, myPrevId, myNextId);

                // --- Determine if the new node is our new neighbour ---

                boolean iAmAlone = (myPrevId == myId && myNextId == myId);

                if (iAmAlone) {
                    // Special case: we were the only node.
                    // The new node becomes both our prev and next.
                    int oldNext = myNextId; // = myId
                    state.setNextId(newId);
                    state.setPrevId(newId);
                    // Tell the new node: prev = me, next = me  (ring of two)
                    sendNeighbourUpdate(newIp, myId, myId);
                    System.out.println("[MulticastReceiver] Was alone, new node " + newId + " is now prev+next.");

                } else if (isBetweenNext(myId, newId, myNextId)) {
                    // Case A / C: new node goes between me and my next
                    int oldNext = myNextId;
                    state.setNextId(newId);
                    // Tell new node: your prev = me (myId), your next = my old next (oldNext)
                    sendNeighbourUpdate(newIp, myId, oldNext);
                    System.out.println("[MulticastReceiver] Case A: updated next to " + newId);

                } else if (isBetweenPrev(myPrevId, newId, myId)) {
                    // Case B / D: new node goes between my prev and me
                    int oldPrev = myPrevId;
                    state.setPrevId(newId);
                    // Tell new node: your prev = my old prev (oldPrev), your next = me (myId)
                    sendNeighbourUpdate(newIp, oldPrev, myId);
                    System.out.println("[MulticastReceiver] Case B: updated prev to " + newId);
                }
                // else: not our neighbour, do nothing
            }

        } catch (Exception e) {
            System.err.println("[MulticastReceiver] Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Returns true if newId falls between myId and myNextId on the ring,
     * handling the wrap-around case where myNextId < myId (we are the largest node).
     */
    private boolean isBetweenNext(int myId, int newId, int myNextId) {
        if (myId < myNextId) {
            // Normal (no wrap): just check myId < newId < myNextId
            return myId < newId && newId < myNextId;
        } else {
            // Wrap-around: we are the largest node.
            // New node is our next if it is bigger than us OR smaller than the current next.
            return newId > myId || newId < myNextId;
        }
    }

    /**
     * Returns true if newId falls between myPrevId and myId on the ring,
     * handling the wrap-around case where myPrevId > myId (we are the smallest node).
     */
    private boolean isBetweenPrev(int myPrevId, int newId, int myId) {
        if (myPrevId < myId) {
            // Normal (no wrap): just check myPrevId < newId < myId
            return myPrevId < newId && newId < myId;
        } else {
            // Wrap-around: we are the smallest node.
            // New node is our prev if it is smaller than us OR larger than the current prev.
            return newId < myId || newId > myPrevId;
        }
    }

    /**
     * Sends REST calls to the new node to tell it who its neighbours are.
     *
     * PUT http://{newIp}:{peerPort}/node/prev  body: { "id": prevId }
     * PUT http://{newIp}:{peerPort}/node/next  body: { "id": nextId }
     *
     * The new node's NodeController receives these and updates its NodeState.
     *
     * @param newIp  IP of the newly joined node
     * @param prevId The value the new node should store as its prevId
     * @param nextId The value the new node should store as its nextId
     */
    private void sendNeighbourUpdate(String newIp, int prevId, int nextId) {
        String base = "http://" + newIp + ":" + peerPort;
        try {
            restTemplate.put(base + "/node/prev", new NeighbourUpdateRequest(prevId));
            System.out.println("[MulticastReceiver] Sent prevId=" + prevId + " to " + newIp);
        } catch (Exception e) {
            System.err.println("[MulticastReceiver] Could not send prev to " + newIp + ": " + e.getMessage());
        }
        try {
            restTemplate.put(base + "/node/next", new NeighbourUpdateRequest(nextId));
            System.out.println("[MulticastReceiver] Sent nextId=" + nextId + " to " + newIp);
        } catch (Exception e) {
            System.err.println("[MulticastReceiver] Could not send next to " + newIp + ": " + e.getMessage());
        }
    }

    /** Simple request body for PUT /node/prev and PUT /node/next */
    public static class NeighbourUpdateRequest {
        public int id;
        public NeighbourUpdateRequest() {}
        public NeighbourUpdateRequest(int id) { this.id = id; }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
    }
}