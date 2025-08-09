# MemQuorum - 分布式内存服务

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/sydowma/MemQuorum)
[![Java Version](https://img.shields.io/badge/java-21-blue)](https://openjdk.java.net/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

MemQuorum 是一个基于 Java 21 和 Raft 协议的高性能分布式内存服务，提供强一致性的键值存储能力，支持用户分片和多副本存储。

## 🚀 核心特性

- **强一致性**: 基于 Raft 协议实现的分布式共识算法
- **用户分片**: 基于用户ID的一致性哈希分片，支持水平扩展
- **多副本存储**: 可配置的副本因子，确保数据高可用性
- **服务发现**: 集成 Nacos 注册中心，支持动态服务发现和配置管理
- **高性能通信**: 基于 gRPC 的高效节点间通信和客户端接口
- **微服务架构**: API网关 + 引擎节点分离，支持独立扩缩容
- **可观测性**: 集成 Prometheus + Grafana 监控栈
- **云原生**: 支持 Docker 容器化和 Kubernetes 部署
- **流式输入**: 可选的 Kafka 集成，支持流式数据写入

## 🏗️ 架构概览

```
                           ┌─────────────────┐
                           │   API Gateway   │
                           │   (Port: 8080)  │
                           └─────────┬───────┘
                                     │ gRPC calls
                           ┌─────────┴───────┐
                           │                 │
    ┌─────────────────┐    │                 │    ┌─────────────────┐
    │   Engine Node 1 │◄───┼─────────────────┼───►│   Engine Node 3 │
    │   (Port: 9091)  │    │                 │    │   (Port: 9093)  │
    │   Shard 0,3,6.. │    │                 │    │   Shard 2,5,8.. │
    └─────────┬───────┘    │                 │    └─────────┬───────┘
              │            │                 │              │
              │            │                 │              │
              │     ┌─────────────────┐      │              │
              │     │   Engine Node 2 │      │              │
              │     │   (Port: 9092)  │      │              │
              │     │   Shard 1,4,7.. │      │              │
              │     └─────────┬───────┘      │              │
              │               │              │              │
              └───────────────┼──────────────┼──────────────┘
                              │    Raft      │
                              │  Consensus   │
                              │              │
               ┌──────────────┴──────────────┴──────────────┐
               │                                            │
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
    │      Nacos      │    │   Prometheus    │    │     Grafana     │
    │  (Port: 8848)   │    │   (Port: 9090)  │    │   (Port: 3000)  │
    │ Service Registry│    │   Monitoring    │    │   Dashboard     │
    └─────────────────┘    └─────────────────┘    └─────────────────┘
```

## TODO 

- SNAPSHOT
- restart & deploy testing

## 📦 快速开始

### 前置要求

- Java 21+
- Maven 3.8+
- Docker (可选)
- Kubernetes (可选)

### 本地开发

1. **克隆项目**
   ```bash
   git clone https://github.com/your-repo/memquorum.git
   cd memquorum
   ```

2. **编译项目**
   ```bash
   mvn clean package
   ```

3. **启动 Nacos**
   ```bash
   docker run -d --name nacos-server \
     -p 8848:8848 \
     -p 9848:9848 \
     -e MODE=standalone \
     nacos/nacos-server:v2.3.0
   ```

4. **启动 MemQuorum 节点**
   ```bash
   # 节点1
   java -jar target/memquorum-1.0-SNAPSHOT.jar \
     --server.port=8081 \
     --grpc.port=9091 \
     --node.id=node1
   
   # 节点2
   java -jar target/memquorum-1.0-SNAPSHOT.jar \
     --server.port=8082 \
     --grpc.port=9092 \
     --node.id=node2
   
   # 节点3
   java -jar target/memquorum-1.0-SNAPSHOT.jar \
     --server.port=8083 \
     --grpc.port=9093 \
     --node.id=node3
   ```

### Docker Compose 部署

```bash
# 启动完整集群 (API网关 + 3个引擎节点 + Nacos + 监控)
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看引擎节点日志
docker-compose logs -f engine-node1
docker-compose logs -f engine-node2
docker-compose logs -f engine-node3

# 查看API网关日志
docker-compose logs -f api-gateway

# 查看Nacos日志
docker-compose logs -f nacos-server

# 重新构建并启动
docker-compose up -d --build

# 停止服务
docker-compose down
```

### 访问服务

```bash
# API测试
curl -X POST "http://localhost:8080/api/v1/memory/set" \
  -H "Content-Type: application/json" \
  -d '{"userId": "test", "key": "hello", "value": "world"}'

# Nacos控制台 (服务注册发现)
open http://localhost:8848/nacos

# Grafana监控面板 (admin/admin123)
open http://localhost:3000

# Prometheus指标
open http://localhost:9090
```

### 服务端口说明

| 服务 | 端口 | 描述 |
|------|------|------|
| API Gateway | 8080 | HTTP REST API |
| Engine Node 1 | 9091 | gRPC服务 |
| Engine Node 2 | 9092 | gRPC服务 |  
| Engine Node 3 | 9093 | gRPC服务 |
| Nacos Console | 8848 | 服务注册发现 |
| Prometheus | 9090 | 监控指标收集 |
| Grafana | 3000 | 监控面板 |

### Kubernetes 部署

```bash
# 创建命名空间
kubectl apply -f k8s/namespace.yaml

# 部署配置
kubectl apply -f k8s/configmap.yaml

# 部署服务
kubectl apply -f k8s/statefulset.yaml

# 部署监控
kubectl apply -f k8s/monitoring.yaml

# 查看状态
kubectl get pods -n memquorum
```

## 💻 客户端使用

### Java 客户端 SDK

```java
// 创建客户端配置
ClientConfig config = ClientConfig.builder()
    .nacosServerAddr("127.0.0.1:8848")
    .connectionTimeout(5000)
    .requestTimeout(3000)
    .retryCount(3)
    .maxConcurrentRequests(100);

// 创建客户端
try (MemQuorumClient client = new MemQuorumClient(config)) {
    
    // 基本操作
    client.put("user:123", "John Doe");
    String value = client.getString("user:123");
    boolean exists = client.exists("user:123");
    client.delete("user:123");
    
    // 批量操作
    Map<String, byte[]> data = Map.of(
        "key1", "value1".getBytes(),
        "key2", "value2".getBytes()
    );
    client.batchPut(data);
    
    // 异步操作
    CompletableFuture<Boolean> future = client.putAsync("async_key", "async_value".getBytes());
    future.thenAccept(result -> System.out.println("Put result: " + result));
    
    // 集群信息
    ClusterInfo clusterInfo = client.getClusterInfo();
    System.out.println("Leader: " + clusterInfo.getLeaderId());
    System.out.println("Cluster healthy: " + clusterInfo.isClusterHealthy());
}
```

### REST API

```bash
# SET 操作 - 存储用户数据
curl -X POST "http://localhost:8080/api/v1/memory/set" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123", "key": "profile", "value": "John Doe"}'

# GET 操作 - 获取用户数据
curl -X POST "http://localhost:8080/api/v1/memory/get" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123", "key": "profile"}'

# DELETE 操作 - 删除用户数据
curl -X POST "http://localhost:8080/api/v1/memory/delete" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123", "key": "profile"}'

# LIST 操作 - 列出用户的所有键
curl -X POST "http://localhost:8080/api/v1/memory/list" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123", "pattern": "*"}'

# 健康检查
curl "http://localhost:8080/api/v1/memory/health"

# 获取分片信息
curl "http://localhost:8080/api/v1/memory/shards"
```

## 🔧 配置说明

### 引擎节点配置 (engine/application.properties)

```properties
# 应用名称
spring.application.name=memquorum-engine

# 节点配置
memquorum.node.port=9090
memquorum.node.id=${MEMQUORUM_NODE_ID:}
memquorum.startup.delay=${MEMQUORUM_STARTUP_DELAY:0}

# 分片配置
memquorum.shards.total=${MEMQUORUM_SHARDS_TOTAL:16}
memquorum.replication.factor=${MEMQUORUM_REPLICATION_FACTOR:3}

# Nacos配置
nacos.server.addr=${NACOS_SERVER_ADDR:nacos-server:8848}

# Kafka配置 (可选)
memquorum.kafka.enabled=${MEMQUORUM_KAFKA_ENABLED:false}
memquorum.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

# 监控配置
server.port=8081
management.server.port=8081
management.endpoints.web.exposure.include=health,info,metrics

# 日志配置
logging.level.com.github.sydowma.engine=INFO
logging.level.com.github.sydowma.engine.raft=DEBUG
```

### API网关配置 (api/application.properties)

```properties
# 应用名称
spring.application.name=memquorum-api

# 服务器配置
server.port=8080

# Nacos配置
nacos.server.addr=${NACOS_SERVER_ADDR:nacos-server:8848}

# 引擎集群配置
memquorum.engine.total-shards=${MEMQUORUM_ENGINE_TOTAL_SHARDS:16}
```

### 环境变量

| 变量名 | 描述 | 默认值 | 服务 |
|--------|------|--------|------|
| `MEMQUORUM_NODE_ID` | 引擎节点唯一标识 | 自动生成 | Engine |
| `MEMQUORUM_NODE_PORT` | 引擎节点gRPC端口 | 9090 | Engine |
| `MEMQUORUM_STARTUP_DELAY` | 启动延迟(秒) | 0 | Engine |
| `MEMQUORUM_SHARDS_TOTAL` | 总分片数 | 16 | Engine |
| `MEMQUORUM_REPLICATION_FACTOR` | 副本因子 | 3 | Engine |
| `NACOS_SERVER_ADDR` | Nacos服务地址 | nacos-server:8848 | Both |
| `MEMQUORUM_KAFKA_ENABLED` | 启用Kafka | false | Engine |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka地址 | localhost:9092 | Engine |
| `JAVA_OPTS` | JVM参数 | -Xmx512m -Xms256m | Both |

## 📊 监控与运维

### 健康检查

```bash
# API网关健康状态
curl http://localhost:8080/api/v1/memory/health

# 引擎节点健康状态
curl http://localhost:8081/actuator/health  # 引擎节点管理端口

# 集群分片状态
curl http://localhost:8080/api/v1/memory/shards

# Nacos控制台 
# 浏览器访问: http://localhost:8848/nacos
```

### 指标监控

访问 [Grafana Dashboard](http://localhost:3000) (admin/admin123) 查看监控面板：

- 集群状态监控
- 节点性能指标
- Raft 协议指标
- JVM 运行时指标

### 日志管理

```bash
# 查看应用日志
tail -f logs/application.log

# 查看 Raft 日志
tail -f logs/raft.log

# 查看性能日志
tail -f logs/performance.log
```

## 🔄 扩容与缩容

### 水平扩容

```bash
# Kubernetes 扩容
kubectl scale statefulset memquorum --replicas=5 -n memquorum

# Docker Compose 扩容
docker-compose up -d --scale memquorum-node=5
```

### 节点维护

```bash
# 安全停止节点
curl -X POST "http://localhost:8080/api/v1/admin/shutdown"

# 迁移 Leader
curl -X POST "http://localhost:8080/api/v1/admin/transfer-leadership"
```

## 🛠️ 故障排查

### 常见问题

1. **节点无法加入集群**
   - 检查 Nacos 连接状态
   - 验证网络连通性
   - 查看节点 ID 冲突

2. **频繁的 Leader 选举**
   - 检查网络延迟
   - 调整选举超时参数
   - 查看节点负载情况

3. **性能问题**
   - 监控 JVM 内存使用
   - 检查网络带宽
   - 分析慢查询日志

### 日志级别调整

```bash
# 动态调整日志级别
curl -X POST "http://localhost:8080/actuator/loggers/com.github.sydowma.memquorum" \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'
```

## 🤝 贡献指南

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

- [Raft 协议](https://raft.github.io/) - 分布式共识算法
- [Nacos](https://nacos.io/) - 服务发现和配置管理
- [gRPC](https://grpc.io/) - 高性能 RPC 框架
- [Spring Boot](https://spring.io/projects/spring-boot) - 应用框架

## 📞 联系我们

- 💬 Issues: [GitHub Issues](https://github.com/sydowma/MemQuorum/issues)
- 📖 文档: [Wiki](https://github.com/sydowma/MemQuorum/wiki)
- 🚀 项目主页: [MemQuorum](https://github.com/sydowma/MemQuorum)
