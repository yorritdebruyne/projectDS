package org.example.lab3.node;

import org.example.lab3.service.HashService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * BootstrapService handles the DISCOVERY and BOOTSTRAP phases.
 *
 * It runs once, automatically, after Spring has fully started up.
 *
 * === What it does step by step (matching the slides) ===
 *
 * 1. Calculate our hash ID from our node name using HashService.
 *    Store it in NodeState.currentId.
 *
 * 2. Send a UDP multicast message to the entire local network.
 *    Message format:  "name:ip"   e.g. "nodeA:192.168.0.5"
 *    Everyone on the multicast group receives this:
 *      - The naming server registers us and sends back the node count.
 *      - Other nodes decide if we are their new neighbour (handled in
 *        MulticastReceiver).
 *
 * 3. Wait for the naming server's unicast reply: just a number (the count
 *    of nodes that existed BEFORE we joined).
 *
 *    count == 0  →  we are alone; set prev = next = self.
 *    count > 0   →  there are other nodes; they will call PUT /node/prev
 *                   and PUT /node/next on us via REST to tell us our
 *                   neighbours. We just wait — NodeController handles those.
 */
@Service
@Profile("node")
public class BootstrapService {

    @Value("${multicast.group:230.0.0.0}")
    private String multicastGroup;

    @Value("${multicast.port:4446}")
    private int multicastPort;

    @Value("${node.name}")
    private String nodeName;

    @Value("${node.ip}")
    private String nodeIp;

    private final NodeState   state;
    private final HashService hashService; // reused from the naming server

    public BootstrapService(NodeState state, HashService hashService) {
        this.state       = state;
        this.hashService = hashService;
    }

    /**
     * Spring fires ApplicationReadyEvent once the whole context is up.
     * This is the safest place to start network activity — everything is wired.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {

        // --- Step 1: compute and store our own ring ID ---
        int myId = hashService.hashToRing(nodeName);
        state.setCurrentId(myId);
        state.setIp(nodeIp);
        state.setName(nodeName);

        // Default: point to ourselves until neighbours tell us otherwise
        state.setPrevId(myId);
        state.setNextId(myId);

        System.out.println("[Bootstrap] Starting. name=" + nodeName
                + "  id=" + myId + "  ip=" + nodeIp);

        try (MulticastSocket socket = new MulticastSocket(multicastPort)) {

            InetAddress group = InetAddress.getByName(multicastGroup);
            socket.joinGroup(group);

            // --- Step 2: broadcast our name and IP ---
            String  message = nodeName + ":" + nodeIp;
            byte[]  data    = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, multicastPort);
            socket.send(packet);
            System.out.println("[Bootstrap] Multicast sent: " + message);

            // --- Step 3: wait for the naming server's unicast reply (node count) ---
            // Timeout so we don't block forever if the server is down.
            socket.setSoTimeout(3000);
            byte[]        buf   = new byte[64];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);

            try {
                socket.receive(reply);

                String countStr      = new String(reply.getData(), 0, reply.getLength(),
                        StandardCharsets.UTF_8).trim();
                int    existingCount = Integer.parseInt(countStr);

                System.out.println("[Bootstrap] Naming server says: "
                        + existingCount + " node(s) existed before us.");

                if (existingCount < 1) {
                    // We are the only node — the ring is just us.
                    // prev and next already point to ourselves (set above).
                    System.out.println("[Bootstrap] Alone in the network. prev=next=self.");
                } else {
                    // Other nodes exist. Their MulticastReceivers will send us
                    // PUT /node/prev and PUT /node/next once they figure out
                    // that we are their new neighbour.
                    System.out.println("[Bootstrap] Waiting for neighbour updates from existing nodes...");
                }

            } catch (SocketTimeoutException e) {
                // Naming server did not reply in time.
                // Safe fallback: treat ourselves as alone.
                System.err.println("[Bootstrap] Naming server timed out — assuming alone.");
            }

        } catch (Exception e) {
            System.err.println("[Bootstrap] Error during bootstrap: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[Bootstrap] Done. State: " + state);
    }
}