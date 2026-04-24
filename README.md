# System Y - Naming Server + Discovery (Lab 3 & 4)

This project implements the **Naming Server** and **Node lifecycle** for System Y,
a distributed file system organized in a consistent-hashing ring topology.

---

## 1. Purpose

### Naming Server (Lab 3)
- Keeps track of all nodes in the System Y ring
- Maps node names and filenames to integer IDs in `[0, 32768]` using a hashing function
- Determines which node is responsible for storing a given file
- Exposes this functionality via a REST API

### Node Application (Lab 4)
- Each node is a separate running instance of the same JAR, activated via a Spring profile
- Nodes auto-discover the naming server and each other using **UDP multicast** on startup
- Each node maintains a pointer to its **previous** and **next** neighbour in the ring
- Nodes handle their own lifecycle: discovery, bootstrap, graceful shutdown, and failure recovery

---

## 2. Hashing

The naming server maps Java's `hashCode()` range (`[-2,147,483,647 , 2,147,483,647]`)
into the System Y range `[0, 32768]` as specified in the lab slides.

The same hashing logic is reused by both the naming server and each node,
ensuring all IDs fall within the same ring space.

---

## 3. Ring Ownership Algorithm

When a file owner is requested, the naming server uses the ring topology:

- Let **N** = all nodes whose ID is strictly smaller than the file's hash
- If **N is not empty** → the owner is the node with the **largest ID in N**
- If **N is empty** → the owner is the node with the **largest ID overall**

---

## 4. Node Lifecycle (Lab 4)

### Discovery & Bootstrap
When a node starts up it:
1. Calculates its own ring ID by hashing its name
2. Sends a **UDP multicast** message (`name:ip`) to the multicast group `230.0.0.0:4446`
3. The naming server receives the multicast, registers the node, and replies via **unicast** with the current node count
4. Existing nodes also receive the multicast and check whether the new node falls between them and their current neighbour — if so they send a REST update to the new node with its correct `prevId` and `nextId`
5. If the node count reply is `0` → the node is alone in the ring and sets `prev = next = self`
6. If the node count reply is `> 0` → the node waits for neighbour updates from existing nodes via `PUT /node/prev` and `PUT /node/next`

### Shutdown (graceful)
When a node stops with CTRL-C:
1. Tells its **previous** neighbour: "your new next is my current next"
2. Tells its **next** neighbour: "your new prev is my current prev"
3. Removes itself from the naming server via `DELETE /api/nodes/{name}`

### Failure (hard crash)
Each node periodically pings its neighbours every 5 seconds.
When a ping fails:
1. Asks the naming server for the dead node's neighbours via `GET /api/nodes/neighbours/{id}`
2. Updates the **previous** neighbour's next pointer to skip the dead node
3. Updates the **next** neighbour's prev pointer to skip the dead node
4. Removes the dead node from the naming server

---

## 5. REST API

### Naming Server endpoints (`port 8080`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/nodes` | Register a new node. Rejects duplicate names with 400. |
| `DELETE` | `/api/nodes/{name}` | Remove a node from the ring. |
| `GET` | `/api/files/owner?filename=` | Returns the node responsible for a file. 404 if no nodes exist. |
| `GET` | `/api/nodes/neighbours/{id}` | Returns the prev and next node ID for a given ring ID. Used during failure recovery. |
| `GET` | `/api/nodes/{id}` | Returns the NodeInfo (including IP) for a given ring ID. Used by nodes to look up neighbour IPs. |

### Node endpoints (`port 8081+`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `PUT` | `/node/prev` | Update this node's previous neighbour ID. |
| `PUT` | `/node/next` | Update this node's next neighbour ID. |
| `GET` | `/node/ping` | Heartbeat check. Returns `"pong"` if alive. |
| `GET` | `/node/state` | Returns the full current state of this node as JSON. Useful for debugging. |

## 6. Project Structure

```
src/main/java/org/example/lab3/
├── Lab3Application.java
│
├── controller/
│   └── NamingServerController.java     # REST API for the naming server
│
├── model/
│   ├── AddNodeRequest.java
│   ├── FileOwnerResponse.java
│   ├── NeighbourResponse.java          # prev/next IDs returned during failure recovery
│   └── NodeInfo.java
│
├── network/
│   └── MulticastListener.java          # UDP multicast listener (naming server side)
│
├── service/
│   ├── HashService.java                # Shared hashing logic (used by both profiles)
│   └── NodeRegistry.java              # Ring map, add/remove/find logic
│
├── storage/
│   └── NodeRegistryStorage.java       # Persists ring state to nodes.json
│
└── node/                              # Everything for the node profile (Lab 4)
    ├── NodeState.java                 # Runtime state: currentId, prevId, nextId, ip, name
    ├── BootstrapService.java          # Sends multicast on startup, handles naming server reply
    ├── MulticastReceiver.java         # Listens for other nodes joining, updates neighbours
    ├── NodeController.java            # REST endpoints other nodes call on us
    ├── NodeIpLookup.java              # Asks naming server for a node's IP by ring ID
    ├── ShutdownService.java           # Graceful shutdown via @PreDestroy
    ├── FailureHandler.java            # Repairs ring after a crash is detected
    └── PingScheduler.java             # Periodic heartbeat to neighbours
```

## 7. Running the Project

### Prerequisites
- Java 25 (loom build used in this project)
- Maven wrapper (`mvnw.cmd`) included

### Build
```powershell
.\mvnw.cmd clean package -DskipTests
```

### Run as Naming Server
```powershell
java -jar target\projectDS-0.0.1-SNAPSHOT.jar --spring.profiles.active=naming-server
```

### Run as Node
```powershell
java -jar target\projectDS-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=node `
  --node.name=nodeA `
  --node.ip=127.0.0.1 `
  --server.port=8081 `
  --namingserver.url=http://localhost:8080 `
  --node.peer.port=8081
```

Each additional node gets a different `--node.name`, `--node.ip`, `--server.port`, and `--node.peer.port`.

### Verify
```powershell
# Check a node's ring state
Invoke-RestMethod -Uri "http://localhost:8081/node/state"

# Check who owns a file
Invoke-RestMethod -Uri "http://localhost:8080/api/files/owner?filename=test.txt"
```

---

## 8. Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8080` / `8081` | HTTP port for this instance |
| `multicast.group` | `230.0.0.0` | UDP multicast group address |
| `multicast.port` | `4446` | UDP multicast port |
| `namingserver.url` | `http://localhost:8080` | Where the naming server is reachable |
| `node.name` | — | This node's name (used for hashing) |
| `node.ip` | — | This node's IP address (sent to neighbours) |
| `node.peer.port` | `8081` | Port other nodes use to reach us |
| `ping.interval.ms` | `5000` | How often to ping neighbours (ms) |

---

## 9. Persistence

The naming server writes its node map to `nodes.json` in the working directory.
This file is loaded on startup so the ring survives a naming server restart.

To start completely fresh, delete this file before starting:
```powershell
Remove-Item .\nodes.json
```
