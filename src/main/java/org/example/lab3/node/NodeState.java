package org.example.lab3.node;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * NodeState holds all the runtime state for THIS node.
 *
 * Think of it as the node's "memory" — every other service in the node
 * reads or writes these fields. Keeping it in one place means there is
 * a single source of truth.
 *
 * Fields:
 *   currentId  — this node's position on the hash ring (hash of the name)
 *   prevId     — ring ID of the clockwise-previous neighbour
 *   nextId     — ring ID of the clockwise-next neighbour
 *   ip         — our own IP address (so other services don't need @Value everywhere)
 *   name       — our own node name
 *
 * When a node is ALONE in the ring:  prevId == currentId == nextId
 *
 * All getters/setters are synchronized because BootstrapService,
 * MulticastReceiver, and PingScheduler all run on different threads
 * and could touch this state at the same time.
 */
@Component
@Profile("node") // only created when running with --spring.profiles.active=node
public class NodeState {

    private int    currentId = -1; // -1 = "not yet initialised"
    private int    prevId    = -1;
    private int    nextId    = -1;
    private String ip;
    private String name;

    public synchronized int getCurrentId()          { return currentId; }
    public synchronized void setCurrentId(int id)   { this.currentId = id; }

    public synchronized int getPrevId()             { return prevId; }
    public synchronized void setPrevId(int id)      { this.prevId = id; }

    public synchronized int getNextId()             { return nextId; }
    public synchronized void setNextId(int id)      { this.nextId = id; }

    public synchronized String getIp()              { return ip; }
    public synchronized void setIp(String ip)       { this.ip = ip; }

    public synchronized String getName()            { return name; }
    public synchronized void setName(String name)   { this.name = name; }

    @Override
    public String toString() {
        return String.format("NodeState{id=%d, prev=%d, next=%d, ip=%s, name=%s}",
                currentId, prevId, nextId, ip, name);
    }
}