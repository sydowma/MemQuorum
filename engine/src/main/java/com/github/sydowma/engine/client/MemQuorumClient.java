package com.github.sydowma.engine.client;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * MemQuorum分布式内存服务客户端SDK
 * 提供简单易用的API接口，支持自动服务发现、负载均衡、故障转移
 */
public class MemQuorumClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumClient.class);
    
    private static final String SERVICE_NAME = "memquorum-cluster";
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_RETRY_COUNT = 3;
    
    // 配置
    private final ClientConfig config;
    
    // 服务发现
    private final NamingService namingService;
    private final List<NodeConnection> availableNodes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    
    // 连接管理
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutor;
    
    // 状态
    private volatile boolean closed = false;
    
    public MemQuorumClient(ClientConfig config) throws Exception {
        this.config = config;
        
        // 初始化线程池
        this.executorService = Executors.newFixedThreadPool(config.getMaxConcurrentRequests());
        this.scheduledExecutor = Executors.newScheduledThreadPool(2);
        
        // 初始化Nacos命名服务
        Properties props = new Properties();
        props.setProperty("serverAddr", config.getNacosServerAddr());
        this.namingService = NamingFactory.createNamingService(props);
        
        // 初始化服务发现
        initServiceDiscovery();
        
        logger.info("MemQuorum client initialized with config: {}", config);
    }
    
    /**
     * 异步PUT操作
     */
    public CompletableFuture<Boolean> putAsync(String key, byte[] value) {
        return executeAsync(connection -> connection.put(key, value));
    }
    
    /**
     * 同步PUT操作
     */
    public boolean put(String key, byte[] value) {
        return putAsync(key, value).join();
    }
    
    /**
     * 同步PUT操作（字符串值）
     */
    public boolean put(String key, String value) {
        return put(key, value.getBytes());
    }
    
    /**
     * 异步GET操作
     */
    public CompletableFuture<byte[]> getAsync(String key) {
        return executeAsync(connection -> connection.get(key));
    }
    
    /**
     * 同步GET操作
     */
    public byte[] get(String key) {
        return getAsync(key).join();
    }
    
    /**
     * 同步GET操作（返回字符串）
     */
    public String getString(String key) {
        byte[] value = get(key);
        return value != null ? new String(value) : null;
    }
    
    /**
     * 异步DELETE操作
     */
    public CompletableFuture<Boolean> deleteAsync(String key) {
        return executeAsync(connection -> connection.delete(key));
    }
    
    /**
     * 同步DELETE操作
     */
    public boolean delete(String key) {
        return deleteAsync(key).join();
    }
    
    /**
     * 检查键是否存在
     */
    public CompletableFuture<Boolean> existsAsync(String key) {
        return executeAsync(connection -> connection.exists(key));
    }
    
    /**
     * 同步检查键是否存在
     */
    public boolean exists(String key) {
        return existsAsync(key).join();
    }
    
    /**
     * 批量PUT操作
     */
    public CompletableFuture<Map<String, Boolean>> batchPutAsync(Map<String, byte[]> data) {
        return executeAsync(connection -> connection.batchPut(data));
    }
    
    /**
     * 同步批量PUT操作
     */
    public Map<String, Boolean> batchPut(Map<String, byte[]> data) {
        return batchPutAsync(data).join();
    }
    
    /**
     * 批量GET操作
     */
    public CompletableFuture<Map<String, byte[]>> batchGetAsync(Set<String> keys) {
        return executeAsync(connection -> connection.batchGet(keys));
    }
    
    /**
     * 同步批量GET操作
     */
    public Map<String, byte[]> batchGet(Set<String> keys) {
        return batchGetAsync(keys).join();
    }
    
    /**
     * 获取所有键
     */
    public CompletableFuture<Set<String>> getAllKeysAsync() {
        return executeAsync(NodeConnection::getAllKeys);
    }
    
    /**
     * 同步获取所有键
     */
    public Set<String> getAllKeys() {
        return getAllKeysAsync().join();
    }
    
    /**
     * 获取数据大小
     */
    public CompletableFuture<Long> getSizeAsync() {
        return executeAsync(NodeConnection::getSize);
    }
    
    /**
     * 同步获取数据大小
     */
    public long getSize() {
        return getSizeAsync().join();
    }
    
    /**
     * 清空所有数据
     */
    public CompletableFuture<Boolean> clearAsync() {
        return executeAsync(NodeConnection::clear);
    }
    
    /**
     * 同步清空所有数据
     */
    public boolean clear() {
        return clearAsync().join();
    }
    
    /**
     * 获取集群信息
     */
    public CompletableFuture<ClusterInfo> getClusterInfoAsync() {
        return executeAsync(NodeConnection::getClusterInfo);
    }
    
    /**
     * 同步获取集群信息
     */
    public ClusterInfo getClusterInfo() {
        return getClusterInfoAsync().join();
    }
    
    /**
     * 执行异步操作，包含重试和故障转移逻辑
     */
    private <T> CompletableFuture<T> executeAsync(NodeOperation<T> operation) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Client is closed"));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            Exception lastException = null;
            
            for (int attempt = 0; attempt < config.getRetryCount(); attempt++) {
                NodeConnection connection = selectNode();
                if (connection == null) {
                    throw new RuntimeException("No available nodes");
                }
                
                try {
                    return operation.execute(connection);
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Operation failed on node {} (attempt {}): {}", 
                        connection.getNodeId(), attempt + 1, e.getMessage());
                    
                    // 标记节点不健康
                    connection.markUnhealthy();
                    
                    // 如果不是最后一次尝试，等待一段时间再重试
                    if (attempt < config.getRetryCount() - 1) {
                        try {
                            Thread.sleep(config.getRetryDelayMs() * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during retry", ie);
                        }
                    }
                }
            }
            
            throw new RuntimeException("All retry attempts failed", lastException);
        }, executorService);
    }
    
    /**
     * 选择一个健康的节点（轮询负载均衡）
     */
    private NodeConnection selectNode() {
        List<NodeConnection> healthyNodes = availableNodes.stream()
            .filter(NodeConnection::isHealthy)
            .collect(Collectors.toList());
        
        if (healthyNodes.isEmpty()) {
            return null;
        }
        
        int index = Math.abs(roundRobinIndex.getAndIncrement()) % healthyNodes.size();
        return healthyNodes.get(index);
    }
    
    /**
     * 初始化服务发现
     */
    private void initServiceDiscovery() throws Exception {
        // 订阅服务变化
        namingService.subscribe(SERVICE_NAME, new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (event instanceof NamingEvent) {
                    NamingEvent namingEvent = (NamingEvent) event;
                    updateAvailableNodes(namingEvent.getInstances());
                }
            }
        });
        
        // 立即获取一次服务列表
        List<Instance> instances = namingService.getAllInstances(SERVICE_NAME);
        updateAvailableNodes(instances);
        
        // 定期健康检查
        scheduledExecutor.scheduleAtFixedRate(this::performHealthCheck, 
            30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 更新可用节点列表
     */
    private void updateAvailableNodes(List<Instance> instances) {
        synchronized (availableNodes) {
            // 获取当前节点ID集合
            Set<String> currentNodeIds = availableNodes.stream()
                .map(NodeConnection::getNodeId)
                .collect(Collectors.toSet());
            
            // 获取新的节点ID集合
            Set<String> newNodeIds = instances.stream()
                .filter(Instance::isHealthy)
                .filter(Instance::isEnabled)
                .map(Instance::getInstanceId)
                .collect(Collectors.toSet());
            
            // 移除不再存在的节点
            availableNodes.removeIf(node -> {
                if (!newNodeIds.contains(node.getNodeId())) {
                    node.close();
                    logger.info("Removed node: {}", node.getNodeId());
                    return true;
                }
                return false;
            });
            
            // 添加新节点
            for (Instance instance : instances) {
                if (instance.isHealthy() && instance.isEnabled() && 
                    !currentNodeIds.contains(instance.getInstanceId())) {
                    
                    try {
                        NodeConnection connection = new NodeConnection(
                            instance.getInstanceId(),
                            instance.getIp() + ":" + instance.getPort(),
                            config
                        );
                        availableNodes.add(connection);
                        logger.info("Added node: {} at {}", 
                            instance.getInstanceId(), instance.getIp() + ":" + instance.getPort());
                    } catch (Exception e) {
                        logger.error("Failed to create connection to node {}: {}", 
                            instance.getInstanceId(), e.getMessage());
                    }
                }
            }
            
            logger.debug("Updated available nodes count: {}", availableNodes.size());
        }
    }
    
    /**
     * 执行健康检查
     */
    private void performHealthCheck() {
        for (NodeConnection connection : availableNodes) {
            CompletableFuture.runAsync(() -> {
                try {
                    connection.ping();
                    connection.markHealthy();
                } catch (Exception e) {
                    connection.markUnhealthy();
                    logger.debug("Health check failed for node {}: {}", 
                        connection.getNodeId(), e.getMessage());
                }
            }, executorService);
        }
    }
    
    /**
     * 获取客户端统计信息
     */
    public ClientStats getStats() {
        int totalNodes = availableNodes.size();
        int healthyNodes = (int) availableNodes.stream()
            .mapToInt(node -> node.isHealthy() ? 1 : 0)
            .sum();
        
        return new ClientStats(totalNodes, healthyNodes);
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        closed = true;
        logger.info("Closing MemQuorum client");
        
        // 关闭所有连接
        for (NodeConnection connection : availableNodes) {
            connection.close();
        }
        availableNodes.clear();
        
        // 关闭线程池
        executorService.shutdown();
        scheduledExecutor.shutdown();
        
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("MemQuorum client closed");
    }
    
    /**
     * 节点操作函数式接口
     */
    @FunctionalInterface
    private interface NodeOperation<T> {
        T execute(NodeConnection connection) throws Exception;
    }
}
