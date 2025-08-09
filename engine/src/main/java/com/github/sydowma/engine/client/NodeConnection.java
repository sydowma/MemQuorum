package com.github.sydowma.engine.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个节点的连接管理
 */
public class NodeConnection {
    private static final Logger logger = LoggerFactory.getLogger(NodeConnection.class);
    
    private final String nodeId;
    private final String address;
    private final ClientConfig config;
    private final ManagedChannel channel;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicLong lastErrorTime = new AtomicLong(0);
    
    // gRPC stub（这里需要使用实际的服务stub）
    // private final PartitionServiceGrpc.PartitionServiceBlockingStub blockingStub;
    
    public NodeConnection(String nodeId, String address, ClientConfig config) {
        this.nodeId = nodeId;
        this.address = address;
        this.config = config;
        
        // 创建gRPC channel
        this.channel = ManagedChannelBuilder.forTarget(address)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(config.getMaxMessageSize())
            .build();
        
        // 创建gRPC stub
        // this.blockingStub = PartitionServiceGrpc.newBlockingStub(channel)
        //     .withDeadlineAfter(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        
        logger.debug("Created connection to node {} at {}", nodeId, address);
    }
    
    /**
     * PUT操作
     */
    public boolean put(String key, byte[] value) throws Exception {
        try {
            // 使用gRPC调用实际的PUT操作
            // ProduceRequest request = ProduceRequest.newBuilder()
            //     .setTopic("default")
            //     .setPartition(0)
            //     .setEntry(Entry.newBuilder()
            //         .setKey(key)
            //         .setValue(ByteString.copyFrom(value))
            //         .setTimestamp(System.currentTimeMillis())
            //         .build())
            //     .build();
            // ProduceResponse response = blockingStub.produce(request);
            // return response.getOffset() >= 0;
            
            // 模拟实现
            Thread.sleep(1); // 模拟网络延迟
            return Math.random() > 0.05; // 95%成功率
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * GET操作
     */
    public byte[] get(String key) throws Exception {
        try {
            // 使用gRPC调用实际的GET操作
            // ReadRequest request = ReadRequest.newBuilder()
            //     .setTopic("default")
            //     .setPartition(0)
            //     .setOffset(0)
            //     .setCount(1)
            //     .build();
            // ReadResponse response = blockingStub.read(request);
            // if (!response.getEntriesList().isEmpty()) {
            //     Entry entry = response.getEntries(0);
            //     if (entry.getKey().equals(key)) {
            //         return entry.getValue().toByteArray();
            //     }
            // }
            // return null;
            
            // 模拟实现
            Thread.sleep(1); // 模拟网络延迟
            if (Math.random() > 0.3) { // 70%的概率有数据
                return ("value_for_" + key).getBytes();
            }
            return null;
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * DELETE操作
     */
    public boolean delete(String key) throws Exception {
        try {
            // 实际实现中，DELETE可能是PUT一个特殊的标记
            // 或者实现专门的DELETE RPC
            
            // 模拟实现
            Thread.sleep(1);
            return Math.random() > 0.05; // 95%成功率
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * EXISTS操作
     */
    public boolean exists(String key) throws Exception {
        try {
            // 模拟实现
            Thread.sleep(1);
            return Math.random() > 0.5; // 50%存在
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 批量PUT操作
     */
    public Map<String, Boolean> batchPut(Map<String, byte[]> data) throws Exception {
        try {
            // 模拟实现
            Thread.sleep(data.size()); // 模拟批量操作延迟
            Map<String, Boolean> results = new java.util.HashMap<>();
            for (String key : data.keySet()) {
                results.put(key, Math.random() > 0.05); // 95%成功率
            }
            return results;
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 批量GET操作
     */
    public Map<String, byte[]> batchGet(Set<String> keys) throws Exception {
        try {
            // 模拟实现
            Thread.sleep(keys.size()); // 模拟批量操作延迟
            Map<String, byte[]> results = new java.util.HashMap<>();
            for (String key : keys) {
                if (Math.random() > 0.3) { // 70%的概率有数据
                    results.put(key, ("value_for_" + key).getBytes());
                }
            }
            return results;
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 获取所有键
     */
    public Set<String> getAllKeys() throws Exception {
        try {
            // 模拟实现
            Thread.sleep(5);
            Set<String> keys = new java.util.HashSet<>();
            for (int i = 0; i < 10; i++) {
                keys.add("key_" + i);
            }
            return keys;
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 获取数据大小
     */
    public long getSize() throws Exception {
        try {
            // 模拟实现
            Thread.sleep(1);
            return (long) (Math.random() * 1000);
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 清空所有数据
     */
    public boolean clear() throws Exception {
        try {
            // 模拟实现
            Thread.sleep(10);
            return Math.random() > 0.05; // 95%成功率
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 获取集群信息
     */
    public ClusterInfo getClusterInfo() throws Exception {
        try {
            // 模拟实现
            Thread.sleep(2);
            return new ClusterInfo(nodeId, "LEADER", 3, 2);
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * PING操作（健康检查）
     */
    public void ping() throws Exception {
        try {
            // 简单的健康检查，可以是一个轻量级的RPC调用
            // 或者检查channel状态
            if (channel.isShutdown() || channel.isTerminated()) {
                throw new Exception("Channel is closed");
            }
            
            // 模拟ping
            Thread.sleep(1);
            if (Math.random() < 0.05) { // 5%的概率失败
                throw new Exception("Ping failed");
            }
            
        } catch (Exception e) {
            handleError(e);
            throw e;
        }
    }
    
    /**
     * 处理错误
     */
    private void handleError(Exception e) {
        lastErrorTime.set(System.currentTimeMillis());
        healthy.set(false);
        logger.warn("Error in node {} connection: {}", nodeId, e.getMessage());
    }
    
    /**
     * 标记为健康
     */
    public void markHealthy() {
        healthy.set(true);
    }
    
    /**
     * 标记为不健康
     */
    public void markUnhealthy() {
        healthy.set(false);
        lastErrorTime.set(System.currentTimeMillis());
    }
    
    /**
     * 检查是否健康
     */
    public boolean isHealthy() {
        // 如果标记为不健康，但距离上次错误已经超过一定时间，可以重新尝试
        if (!healthy.get()) {
            long timeSinceLastError = System.currentTimeMillis() - lastErrorTime.get();
            if (timeSinceLastError > 30000) { // 30秒后重新尝试
                healthy.set(true);
            }
        }
        return healthy.get();
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        logger.debug("Closing connection to node {}", nodeId);
        
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Getters
    public String getNodeId() { return nodeId; }
    public String getAddress() { return address; }
    public long getLastErrorTime() { return lastErrorTime.get(); }
}
