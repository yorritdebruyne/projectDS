package org.example.lab3.model;

public class FileOwnerResponse {
    private int NodeId;
    private String ip;

    public FileOwnerResponse(int nodeId, String ip) {
        NodeId = nodeId;
        this.ip = ip;
    }

    public int getNodeId() {return NodeId;}
    public String getIp() {return ip;}
}
