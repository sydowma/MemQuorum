# MemQuorum - 分布式内存服务

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/your-repo/memquorum)
[![Java Version](https://img.shields.io/badge/java-21-blue)](https://openjdk.java.net/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

MemQuorum 是一个基于 Java 21 和 Raft 协议的高性能分布式内存服务，提供强一致性的键值存储能力。

## 🚀 核心特性

- **强一致性**: 基于 Raft 协议实现的分布式共识
- **高可用性**: 支持多节点部署，自动故障转移
- **服务发现**: 集成 Nacos 注册中心，支持动态服务发现
- **高性能**: 基于 gRPC 的高效节点间通信
- **易用性**: 提供简洁的 Java 客户端 SDK
- **可观测性**: 完整的监控指标和分布式链路追踪
- **云原生**: 支持 Docker 容器化和 Kubernetes 部署

## 🏗️ 架构概览

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   MemQuorum     │    │   MemQuorum     │    │   MemQuorum     │
│   Node 1        │◄──►│   Node 2        │◄──►│   Node 3        │
│   (Leader)      │    │   (Follower)    │    │   (Follower)    │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                    ┌─────────────────┐
                    │  Nacos Registry │
                    │  Configuration  │
                    └─────────────────┘
```

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
# 启动完整集群
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f memquorum-node1
```

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
# PUT 操作
curl -X POST "http://localhost:8081/api/v1/kv/user:123" \
  -H "Content-Type: application/json" \
  -d '{"value": "John Doe"}'

# GET 操作
curl "http://localhost:8081/api/v1/kv/user:123"

# DELETE 操作
curl -X DELETE "http://localhost:8081/api/v1/kv/user:123"

# 集群状态
curl "http://localhost:8081/api/v1/cluster/status"
```

## 🔧 配置说明

### 应用配置 (application.properties)

```properties
# 服务器配置
server.port=8080
grpc.server.port=9090

# Nacos 配置
nacos.server.addr=127.0.0.1:8848
nacos.service.name=memquorum-cluster

# Raft 配置
raft.election-timeout-min=150
raft.election-timeout-max=300
raft.heartbeat-interval=50

# 性能调优
raft.async-replication=true
raft.batch-append=true
raft.max-log-entries-per-request=100
```

### 环境变量

| 变量名 | 描述 | 默认值 |
|--------|------|--------|
| `NODE_ID` | 节点唯一标识 | 自动生成 |
| `SERVER_PORT` | HTTP 服务端口 | 8080 |
| `GRPC_PORT` | gRPC 服务端口 | 9090 |
| `NACOS_SERVER_ADDR` | Nacos 服务地址 | 127.0.0.1:8848 |
| `CLUSTER_NODES` | 集群节点列表 | - |
| `JAVA_OPTS` | JVM 参数 | -Xmx1g -Xms512m |

## 📊 监控与运维

### 健康检查

```bash
# 应用健康状态
curl http://localhost:8080/actuator/health

# 就绪状态
curl http://localhost:8080/actuator/health/readiness

# 存活状态
curl http://localhost:8080/actuator/health/liveness
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

- 📧 Email: your-email@example.com
- 💬 Issues: [GitHub Issues](https://github.com/your-repo/memquorum/issues)
- 📖 文档: [Wiki](https://github.com/your-repo/memquorum/wiki)
