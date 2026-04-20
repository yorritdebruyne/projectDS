package org.example.lab3.network;

import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.NodeRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Listens on a UDP multicast group for bootstrap messages from new nodes.
 *
 * When a new node starts, it sends a UDP packet to the multicast address
 * containing "name:ip" (e.g. "nodeA:192.168.0.5").
 *
 * This component:
 *   1. Receives that packet.
 *   2. Registers the node in the NodeRegistry (which hashes the name).
 *   3. Sends back a unicast UDP reply to the sender containing the number of
 *      nodes that were in the network BEFORE this node joined.
 *      The new node uses this count to decide whether it is alone (count == 0)
 *      or whether it should wait for neighbour info (count > 0).
 */
@Profile("naming-server")
@Component
public class MulticastListener {

    // The multicast group address – all nodes and the naming server join this group.
    // Must be in the range 224.0.0.0 – 239.255.255.255.
    @Value("${multicast.group:230.0.0.0}")
    private String multicastGroup;

    // The UDP port everyone listens on for discovery messages.
    @Value("${multicast.port:4446}")
    private int multicastPort;

    private final NodeRegistry registry;

    public MulticastListener(NodeRegistry registry) {
        this.registry = registry;
    }

    /**
     * Spring fires ApplicationReadyEvent after the whole context is up.
     * We start the listener in a separate daemon thread so it does not block
     * the main application thread.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        Thread thread = new Thread(this::listenLoop, "multicast-listener");
        thread.setDaemon(true); // dies with the JVM – no clean-up needed
        thread.start();
    }

    /**
     * Infinite loop: block on MulticastSocket.receive(), handle each packet.
     */
    private void listenLoop() {
        try (MulticastSocket socket = new MulticastSocket(multicastPort)) {

            // Join the multicast group so the OS delivers packets to us.
            InetAddress group = InetAddress.getByName(multicastGroup);
            socket.joinGroup(group);

            System.out.println("[MulticastListener] Listening on " + multicastGroup + ":" + multicastPort);

            byte[] buf = new byte[256];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet); // blocks until a packet arrives

                // Decode the payload: "name:ip"
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                System.out.println("[MulticastListener] Received: " + message + " from " + packet.getAddress());

                String[] parts = message.split(":");
                if (parts.length != 2) continue; // malformed, skip

                String nodeName = parts[0];
                String nodeIp   = parts[1];

                // Count existing nodes BEFORE adding the new one.
                // This is the number the new node receives to decide if it is alone.
                int existingCount = registry.getNodeCount(); // New code added in NodeRegistry.java

                // Register the node (hashes name → id, stores in the ring map).
                try {
                    NodeInfo added = registry.addNode(nodeName, nodeIp);
                    System.out.println("[MulticastListener] Registered node: " + added.getId() + " / " + nodeIp);
                } catch (IllegalArgumentException e) {
                    // Node already exists – probably a duplicate multicast packet. Ignore.
                    System.out.println("[MulticastListener] Node already exists: " + nodeName);
                }

                // Send unicast reply back to the new node with the existing count.
                // The new node is listening for this on the same port (multicastPort).
                byte[] reply = String.valueOf(existingCount).getBytes(StandardCharsets.UTF_8);
                DatagramPacket response = new DatagramPacket(
                        reply, reply.length,
                        packet.getAddress(), // reply to the sender's IP
                        multicastPort        // the node listens for the reply on the same port
                );
                socket.send(response);
            }

        } catch (Exception e) {
            System.err.println("[MulticastListener] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}