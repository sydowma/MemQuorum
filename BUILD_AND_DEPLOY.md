# MemQuorum Build and Deployment Guide

## Overview

MemQuorum is a distributed memory service with the following features:
- **User-based sharding**: Automatic data distribution based on user ID hashing
- **Raft consensus**: High availability and data consistency across nodes
- **Nacos integration**: Service discovery and configuration management
- **gRPC API**: High-performance external interface
- **Kafka support**: Stream processing from Kafka topics
- **Multi-replica**: Configurable replication factor for fault tolerance

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   API Gateway   │    │   Kafka Topic   │    │   Client Apps   │
│                 │    │                 │    │                 │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          │ gRPC                 │ Message              │ gRPC
          │ user:123:key         │ user:456:data        │ user:789:read
          │                      │                      │
          ▼                      ▼                      ▼
┌─────────────────────────────────────────────────────────────┐
│                    MemQuorum Cluster                       │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │    Node 1   │  │    Node 2   │  │    Node 3   │        │
│  │ (Leader)    │  │ (Follower)  │  │ (Follower)  │        │
│  │ Shards:     │  │ Shards:     │  │ Shards:     │        │
│  │ 0,1,2,5,6,7 │  │ 1,2,3,6,7,8 │  │ 2,3,4,7,8,9 │        │
│  └─────────────┘  └─────────────┘  └─────────────┘        │
│           │               │               │                │
│           └───────────────┼───────────────┘                │
│                    Raft Consensus                          │
└─────────────────────────────────────────────────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │ Nacos Discovery │
                    │ & Configuration │
                    └─────────────────┘
```

## Prerequisites

1. **Java 17+**
2. **Maven 3.6+**
3. **Docker & Docker Compose** (for development)
4. **Nacos Server** (service discovery)
5. **Kafka** (optional, for stream processing)

## Build Instructions

### 1. Generate Protocol Buffer Classes

```bash
# Install protoc if not already installed
# On macOS: brew install protobuf
# On Ubuntu: apt-get install protobuf-compiler

# Generate Java classes from proto files
mvn clean compile
```

### 2. Build the Application

```bash
# Full build with tests
mvn clean package

# Quick build without tests
mvn clean package -DskipTests

# Build Docker image
docker build -t memquorum:latest .
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MEMQUORUM_NODE_ID` | hostname-port | Unique node identifier |
| `MEMQUORUM_NODE_PORT` | 8080 | gRPC server port |
| `MEMQUORUM_SHARDS_TOTAL` | 16 | Total number of shards |
| `MEMQUORUM_REPLICATION_FACTOR` | 3 | Number of replicas per shard |
| `NACOS_SERVER_ADDR` | nacos-server:8848 | Nacos server address |
| `MEMQUORUM_KAFKA_ENABLED` | false | Enable Kafka input |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka brokers |
| `MEMQUORUM_KAFKA_TOPIC` | memquorum-input | Kafka topic to consume |

### Application Properties

```properties
# Node Configuration
memquorum.node.port=8080
memquorum.node.id=node-1

# Sharding
memquorum.shards.total=16
memquorum.replication.factor=3

# Kafka
memquorum.kafka.enabled=true
memquorum.kafka.bootstrap-servers=localhost:9092
memquorum.kafka.topic=memquorum-input

# Nacos
nacos.server.addr=localhost:8848
```

## Deployment Options

### 1. Docker Compose (Development)

```yaml
version: '3.8'
services:
  nacos:
    image: nacos/nacos-server:latest
    environment:
      - MODE=standalone
    ports:
      - "8848:8848"
  
  kafka:
    image: confluentinc/cp-kafka:latest
    environment:
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"
    depends_on:
      - zookeeper
  
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"
  
  memquorum-1:
    image: memquorum:latest
    environment:
      - MEMQUORUM_NODE_ID=node-1
      - MEMQUORUM_NODE_PORT=8080
      - NACOS_SERVER_ADDR=nacos:8848
      - MEMQUORUM_KAFKA_ENABLED=true
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
    ports:
      - "8080:8080"
    depends_on:
      - nacos
      - kafka
  
  memquorum-2:
    image: memquorum:latest
    environment:
      - MEMQUORUM_NODE_ID=node-2
      - MEMQUORUM_NODE_PORT=8081
      - NACOS_SERVER_ADDR=nacos:8848
      - MEMQUORUM_KAFKA_ENABLED=true
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
    ports:
      - "8081:8081"
    depends_on:
      - nacos
      - kafka
  
  memquorum-3:
    image: memquorum:latest
    environment:
      - MEMQUORUM_NODE_ID=node-3
      - MEMQUORUM_NODE_PORT=8082
      - NACOS_SERVER_ADDR=nacos:8848
      - MEMQUORUM_KAFKA_ENABLED=true
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
    ports:
      - "8082:8082"
    depends_on:
      - nacos
      - kafka
```

### 2. Kubernetes (Production)

The `k8s/` directory contains Kubernetes manifests:

```bash
# Deploy to Kubernetes
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/statefulset.yaml
kubectl apply -f k8s/monitoring.yaml
```

## Usage Examples

### 1. gRPC Client (Java)

```java
// Create gRPC client
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("localhost", 8080)
    .usePlaintext()
    .build();

PartitionServiceBlockingStub stub = PartitionServiceGrpc.newBlockingStub(channel);

// Produce data with user-based sharding
Entry entry = Entry.newBuilder()
    .setKey("user:123:session")  // user:123 will be auto-sharded
    .setValue(ByteString.copyFromUtf8("session_data"))
    .setTimestamp(System.currentTimeMillis())
    .build();

ProduceRequest request = ProduceRequest.newBuilder()
    .setTopic("user-sessions")
    .setPartition(-1)  // Auto-shard based on key
    .setEntry(entry)
    .build();

ProduceResponse response = stub.produce(request);
System.out.println("Stored at offset: " + response.getOffset());

// Read data
ReadRequest readReq = ReadRequest.newBuilder()
    .setTopic("user-sessions")
    .setPartition(-1)  // Auto-route based on topic
    .setOffset(0)
    .setCount(10)
    .build();

ReadResponse readResp = stub.read(readReq);
for (Entry e : readResp.getEntriesList()) {
    System.out.println("Key: " + e.getKey() + ", Value: " + e.getValue().toStringUtf8());
}
```

### 2. Kafka Producer

```bash
# Produce messages to Kafka (will be auto-consumed by MemQuorum)
kafka-console-producer --bootstrap-server localhost:9092 \
  --topic memquorum-input \
  --property "parse.key=true" \
  --property "key.separator=:"

# Enter messages like:
user:123:login:{"timestamp":1234567890,"ip":"192.168.1.1"}
user:456:session:{"data":"session_info"}
```

### 3. REST API (via Gateway)

```javascript
// If you have an API Gateway in front
fetch('/api/memquorum/user/123/data', {
  method: 'POST',
  body: JSON.stringify({
    key: 'user:123:profile',
    value: { name: 'John', email: 'john@example.com' }
  })
});
```

## Key Design Features

### 1. User-based Sharding

- Keys with format `user:{userId}:{key}` are automatically sharded by userId
- Consistent hashing ensures even distribution
- Shard = `userId.hashCode() % totalShards`

### 2. High Availability

- Raft consensus ensures data consistency
- Configurable replication factor (default: 3)
- Automatic leader election and failover
- Only leader accepts writes, all replicas can serve reads

### 3. Service Discovery

- Automatic registration to Nacos on startup
- Publishes shard assignment information
- Health checking and automatic re-balancing
- Configuration management via Nacos

### 4. Stream Processing

- Kafka integration for real-time data ingestion
- Automatic routing based on message keys
- Only leader nodes process Kafka messages
- Guaranteed ordering per user/shard

## Monitoring

### Metrics Endpoints

- Health: `http://localhost:9090/actuator/health`
- Metrics: `http://localhost:9090/actuator/metrics`
- Info: `http://localhost:9090/actuator/info`

### Grafana Dashboard

Use the provided dashboard: `config/grafana/dashboards/memquorum-dashboard.json`

### Log Analysis

Logs include structured information:
```
2024-01-15 10:30:45 [raft-thread] INFO [node-1] c.g.s.m.raft.RaftNode - Became leader for term 5
2024-01-15 10:30:46 [grpc-thread] DEBUG [node-1] c.g.s.m.MemQuorumGrpcService - Produce request for user:123 -> shard 7
```

## Troubleshooting

### Common Issues

1. **Nodes not joining cluster**
   - Check Nacos connectivity
   - Verify network configuration
   - Check firewall settings

2. **Leadership issues**
   - Monitor Raft logs
   - Check node health
   - Verify time synchronization

3. **Sharding problems**
   - Check user ID extraction logic
   - Verify shard assignments in Nacos
   - Monitor load distribution

4. **Kafka integration issues**
   - Verify Kafka connectivity
   - Check topic creation
   - Monitor consumer lag

### Debug Commands

```bash
# Check cluster status via Nacos
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=memquorum-cluster

# View gRPC server reflection
grpcurl -plaintext localhost:8080 list

# Monitor Kafka consumer
kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group memquorum-group-node-1
```

## Performance Tuning

### JVM Options

```bash
export JAVA_OPTS="-Xmx2g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"
```

### Configuration Tuning

```properties
# Increase shards for better parallelism
memquorum.shards.total=64

# Adjust replication for your needs
memquorum.replication.factor=5

# Kafka consumer tuning
memquorum.kafka.max-poll-records=500
memquorum.kafka.session-timeout=30000
```

## Security Considerations

1. **TLS/SSL**: Enable TLS for gRPC and Kafka
2. **Authentication**: Add authentication mechanisms
3. **Authorization**: Implement per-user access controls
4. **Network**: Use VPCs and security groups
5. **Secrets**: Use external secret management

## Next Steps

After deployment, consider:
1. Setting up automated testing
2. Implementing backup/restore procedures
3. Adding more sophisticated monitoring
4. Performance benchmarking
5. Capacity planning
