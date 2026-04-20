package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * NodeIpLookup answers the question: "Given a ring ID, what is that node's IP?"
 *
 * We ask the naming server via GET /api/nodes/{id}.
 * (You added this endpoint to NamingServerController in step 5 of the earlier work.)
 *
 * This is used by:
 *   - ShutdownService: to find the IP of prev and next so it can call them
 *   - FailureHandler:  to find the IPs of the neighbours of the dead node
 *   - PingScheduler:   to know which IP to ping
 */
@Service
@Profile("node")
public class NodeIpLookup {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Returns the IP address of the node with the given ring ID,
     * by asking the naming server. Returns null if not found.
     *
     * @param nodeId  The hash ring ID of the node we want to find.
     */
    public String getIpForId(int nodeId) {
        try {
            NodeInfo info = restTemplate.getForObject(
                    namingServerUrl + "/api/nodes/" + nodeId,
                    NodeInfo.class
            );
            return (info != null) ? info.getIp() : null;
        } catch (Exception e) {
            System.err.println("[NodeIpLookup] Could not find IP for id=" + nodeId
                    + ": " + e.getMessage());
            return null;
        }
    }
}