# MemQuorum Microservices Architecture

This project has been restructured into a microservices architecture with the following components:

## Architecture Overview

```
┌─────────────────┐    HTTP      ┌─────────────────┐    gRPC     ┌─────────────────┐
│   Client App    │ ──────────→  │   API Gateway   │ ─────────→  │  Engine Service │
│                 │              │     (Port 8080) │             │    (Port 9090)  │
└─────────────────┘              └─────────────────┘             └─────────────────┘
                                          │                                │
                                          │                                │
                                          ▼                                ▼
                                   REST API for                    Memory Storage +
                                   Key-Value Ops                  Raft Consensus +
                                                                 Nacos Registration
```

## Modules

### 1. API Module (`api/`)
- **Purpose**: HTTP API Gateway that receives REST requests and forwards them to the engine service via gRPC
- **Port**: 8080
- **Technology**: Spring Boot Web + gRPC Client
- **Endpoints**:
  - `POST /api/v1/memory/users/{userId}/keys/{key}` - Set value
  - `GET /api/v1/memory/users/{userId}/keys/{key}` - Get value  
  - `DELETE /api/v1/memory/users/{userId}/keys/{key}` - Delete value
  - `GET /api/v1/memory/users/{userId}/keys?pattern={pattern}` - List keys
  - `GET /api/v1/memory/health` - Health check

### 2. Engine Module (`engine/`)
- **Purpose**: Core memory service with Raft consensus and Nacos service discovery
- **Port**: 9090
- **Technology**: Spring Boot + gRPC Server + Raft + Nacos
- **Features**:
  - Distributed memory storage with user-based sharding
  - Raft consensus algorithm for high availability
  - Nacos service registration and discovery
  - gRPC API for internal communication
  - Kafka input handling (optional)

## Running the Services

### Prerequisites
- Java 21
- Maven 3.6+
- Docker & Docker Compose (recommended)
- Nacos server running (optional, defaults to localhost:8848)
- Kafka cluster (optional, for stream processing)

### Option 1: Docker Compose (Recommended)

Start the entire cluster:
```bash
docker-compose up -d
```

This will start:
- API Gateway on port 8080
- 3 Engine nodes on ports 9091, 9092, 9093  
- Nacos server on port 8848
- Prometheus monitoring on port 9090
- Grafana visualization on port 3000

Check cluster status:
```bash
curl http://localhost:8080/api/v1/memory/cluster/status
```

### Option 2: Manual Development Mode

Start Nacos server:
```bash
# Download and start Nacos server
wget https://github.com/alibaba/nacos/releases/download/2.3.0/nacos-server-2.3.0.tar.gz
tar -xzf nacos-server-2.3.0.tar.gz
cd nacos/bin
./startup.sh -m standalone
```

Start Engine Service:
```bash
cd engine
mvn spring-boot:run
```

Start API Gateway:  
```bash
cd api
mvn spring-boot:run
```

## Configuration

### Engine Service Configuration (`engine/src/main/resources/application.properties`)
```properties
# Service runs on port 9090 (gRPC)
server.port=9090
memquorum.node.port=9090

# Nacos registration
nacos.server.addr=localhost:8848

# Sharding configuration
memquorum.shards.total=16
memquorum.replication.factor=3

# Kafka (optional)
memquorum.kafka.enabled=false
memquorum.kafka.bootstrap-servers=localhost:9092
```

### API Gateway Configuration (`api/src/main/resources/application.properties`)
```properties  
# HTTP API runs on port 8080
server.port=8080

# Engine service connection
memquorum.engine.host=localhost
memquorum.engine.port=9090
```

## API Usage Examples

### Set a value
```bash
curl -X POST http://localhost:8080/api/v1/memory/users/user123/keys/mykey \
  -H "Content-Type: application/json" \
  -d '{"value": "myvalue"}'
```

### Get a value
```bash  
curl http://localhost:8080/api/v1/memory/users/user123/keys/mykey
```

### Delete a value
```bash
curl -X DELETE http://localhost:8080/api/v1/memory/users/user123/keys/mykey
```

### List keys
```bash
curl http://localhost:8080/api/v1/memory/users/user123/keys?pattern=my*
```

## Key Features

1. **User-based Sharding**: Data is automatically sharded based on userId hash for horizontal scaling
2. **High Availability**: Raft consensus ensures data consistency and fault tolerance
3. **Service Discovery**: Nacos integration for dynamic service registration and discovery
4. **Microservices**: Clean separation between API gateway and core engine
5. **gRPC Communication**: Efficient inter-service communication using Protocol Buffers
6. **REST API**: Simple HTTP interface for client applications
7. **Dynamic Routing**: API gateway automatically routes requests to correct engine nodes based on shard assignments
8. **Intelligent Load Balancing**: Requests are routed to nodes responsible for specific user data shards

## Service Discovery & Routing

### How API Gateway Finds Engine Nodes

The API gateway solves the routing problem through Nacos service discovery:

1. **Engine Registration**: Each engine node registers with Nacos including:
   - Node ID and address
   - Responsible shard numbers
   - Total shard configuration
   - Node role (LEADER/FOLLOWER)

2. **API Discovery**: The API gateway:
   - Subscribes to engine service changes in Nacos
   - Maintains in-memory mapping of shards to nodes
   - Updates routing table when nodes join/leave

3. **Request Routing**: For each request:
   - Calculate shard: `shard = userId.hashCode() % totalShards`
   - Find nodes responsible for that shard
   - Route gRPC call to appropriate engine node

```
User Request Flow:
Client → API Gateway → Calculate Shard → Find Target Node → gRPC Call → Engine Node
```

### Shard Assignment Example

With 3 engine nodes and 16 total shards:
- Engine Node 1: Shards [0, 3, 6, 9, 12, 15]
- Engine Node 2: Shards [1, 4, 7, 10, 13] 
- Engine Node 3: Shards [2, 5, 8, 11, 14]

Request for userId="user123":
- Shard = "user123".hashCode() % 16 = 7
- Route to Engine Node 2

## Development Notes

- The engine service includes placeholder implementations for key-value operations
- Full implementation would require extending the Partition class with proper key-value storage
- Raft consensus is integrated for distributed coordination
- The system supports multiple replicas per shard for fault tolerance

## Next Steps for Production

1. Implement proper key-value storage in Partition class
2. Add authentication and authorization
3. Implement proper error handling and retry logic
4. Add metrics and monitoring
5. Configure load balancing for API gateway
6. Set up proper Nacos cluster
7. Add comprehensive testing