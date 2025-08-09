package com.github.sydowma.engine;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.listener.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Nacos集成的节点管理器
 * 提供服务注册发现、配置管理、健康检查等功能
 */
@Component
public class NacosNodeManager {
    private static final Logger logger = LoggerFactory.getLogger(NacosNodeManager.class);
    
    private static final String SERVICE_NAME = "memquorum-cluster";
    private static final String CONFIG_DATA_ID = "memquorum-config";
    private static final String CONFIG_GROUP = "DEFAULT_GROUP";
    
    private NamingService namingService;
    private ConfigService configService;
    private final Map<String, NodeInfo> activeNodes = new ConcurrentHashMap<>();
    private final List<NodeChangeListener> nodeChangeListeners = new ArrayList<>();
    
    private String nodeId;
    private String nodeAddress;
    private NodeRole nodeRole = NodeRole.FOLLOWER;

    @Value("${NACOS_SERVER_ADDR:nacos-server:8848}")
    private String nacosServerAddr;

    public NacosNodeManager() {
        // 构造函数现在为空，初始化将在 @PostConstruct 方法中完成
    }
    
    @PostConstruct
    public void init() throws Exception {
        // 初始化Nacos命名服务
        Properties namingProps = new Properties();
        namingProps.setProperty("serverAddr", nacosServerAddr);
        this.namingService = NamingFactory.createNamingService(namingProps);
        
        // 初始化Nacos配置服务
        Properties configProps = new Properties();
        configProps.setProperty("serverAddr", nacosServerAddr);
        this.configService = ConfigFactory.createConfigService(configProps);
        
        // 监听节点变化
        subscribeToNodeChanges();
        
        // 监听配置变化
        subscribeToConfigChanges();
    }
    
    /**
     * 注册当前节点到Nacos
     */
    public void registerNode(String nodeId, String address, int port) throws Exception {
        registerNode(nodeId, address, port, null, 0, 0);
    }
    
    /**
     * 注册当前节点到Nacos，包含分片信息
     */
    public void registerNode(String nodeId, String address, int port, Set<Integer> shards, int totalShards, int replicationFactor) throws Exception {
        this.nodeId = nodeId;
        this.nodeAddress = address + ":" + port;
        
        Instance instance = new Instance();
        instance.setInstanceId(nodeId);
        instance.setIp(address);
        instance.setPort(port);
        instance.setServiceName(SERVICE_NAME);
        instance.setHealthy(true);
        instance.setEnabled(true);
        
        // 添加节点元数据
        Map<String, String> metadata = new HashMap<>();
        metadata.put("nodeId", nodeId);
        metadata.put("role", nodeRole.name());
        metadata.put("version", "1.0.0");
        metadata.put("startTime", String.valueOf(System.currentTimeMillis()));
        
        // 添加分片信息
        if (shards != null && !shards.isEmpty()) {
            metadata.put("shards", shards.stream()
                    .map(String::valueOf)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(""));
            metadata.put("totalShards", String.valueOf(totalShards));
            metadata.put("replicationFactor", String.valueOf(replicationFactor));
        }
        
        instance.setMetadata(metadata);
        
        namingService.registerInstance(SERVICE_NAME, instance);
        logger.info("Node {} registered to Nacos at {}:{} with shards: {}", nodeId, address, port, shards);
    }
    
    /**
     * 获取所有活跃节点
     */
    public List<NodeInfo> getAllNodes() throws Exception {
        List<Instance> instances = namingService.getAllInstances(SERVICE_NAME);
        List<NodeInfo> nodes = new ArrayList<>();
        
        for (Instance instance : instances) {
            if (instance.isHealthy() && instance.isEnabled()) {
                Map<String, String> metadata = instance.getMetadata();
                
                // 解析分片信息
                Set<Integer> shards = new HashSet<>();
                String shardsStr = metadata.get("shards");
                if (shardsStr != null && !shardsStr.isEmpty()) {
                    String[] shardArray = shardsStr.split(",");
                    for (String shard : shardArray) {
                        try {
                            shards.add(Integer.parseInt(shard.trim()));
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid shard number: {}", shard);
                        }
                    }
                }
                
                int totalShards = Integer.parseInt(metadata.getOrDefault("totalShards", "0"));
                
                NodeInfo nodeInfo = new NodeInfo(
                    instance.getInstanceId(),
                    instance.getIp() + ":" + instance.getPort(),
                    NodeRole.valueOf(metadata.getOrDefault("role", "FOLLOWER")),
                    shards,
                    totalShards
                );
                nodes.add(nodeInfo);
            }
        }
        
        return nodes;
    }
    
    /**
     * 选举Leader节点
     */
    public String electLeader() throws Exception {
        List<NodeInfo> allNodes = getAllNodes();
        if (allNodes.isEmpty()) {
            return null;
        }
        
        // 简单的Leader选举：选择节点ID最小的作为Leader
        Optional<NodeInfo> leader = allNodes.stream()
            .min(Comparator.comparing(NodeInfo::getNodeId));
            
        if (leader.isPresent()) {
            String leaderId = leader.get().getNodeId();
            
            // 更新Leader角色
            if (leaderId.equals(this.nodeId)) {
                updateNodeRole(NodeRole.LEADER);
            } else {
                updateNodeRole(NodeRole.FOLLOWER);
            }
            
            return leaderId;
        }
        
        return null;
    }
    
    /**
     * 更新节点角色
     */
    public void updateNodeRole(NodeRole newRole) throws Exception {
        if (this.nodeRole != newRole) {
            this.nodeRole = newRole;
            
            // 更新Nacos中的节点元数据
            Instance instance = new Instance();
            instance.setInstanceId(nodeId);
            String[] addressParts = nodeAddress.split(":");
            instance.setIp(addressParts[0]);
            instance.setPort(Integer.parseInt(addressParts[1]));
            instance.setServiceName(SERVICE_NAME);
            
            Map<String, String> metadata = new HashMap<>();
            metadata.put("nodeId", nodeId);
            metadata.put("role", newRole.name());
            metadata.put("version", "1.0.0");
            metadata.put("lastUpdate", String.valueOf(System.currentTimeMillis()));
            instance.setMetadata(metadata);
            
            namingService.registerInstance(SERVICE_NAME, instance);
            logger.info("Node {} role updated to {}", nodeId, newRole);
        }
    }
    
    /**
     * 监听节点变化
     */
    private void subscribeToNodeChanges() throws Exception {
        namingService.subscribe(SERVICE_NAME, new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (event instanceof NamingEvent) {
                    NamingEvent namingEvent = (NamingEvent) event;
                    logger.info("Node change event: {}", namingEvent.getServiceName());
                    
                    List<Instance> instances = namingEvent.getInstances();
                    Map<String, NodeInfo> newNodes = new ConcurrentHashMap<>();
                    
                    for (Instance instance : instances) {
                        if (instance.isHealthy() && instance.isEnabled()) {
                            NodeInfo nodeInfo = new NodeInfo(
                                instance.getInstanceId(),
                                instance.getIp() + ":" + instance.getPort(),
                                NodeRole.valueOf(instance.getMetadata().getOrDefault("role", "FOLLOWER"))
                            );
                            newNodes.put(nodeInfo.getNodeId(), nodeInfo);
                        }
                    }
                    
                    // 检测节点变化
                    Set<String> removed = new HashSet<>(activeNodes.keySet());
                    removed.removeAll(newNodes.keySet());
                    
                    Set<String> added = new HashSet<>(newNodes.keySet());
                    added.removeAll(activeNodes.keySet());
                    
                    // 更新活跃节点列表
                    activeNodes.clear();
                    activeNodes.putAll(newNodes);
                    
                    // 通知监听器
                    for (NodeChangeListener listener : nodeChangeListeners) {
                        if (!added.isEmpty()) {
                            listener.onNodesAdded(added.stream()
                                .map(newNodes::get)
                                .toList());
                        }
                        if (!removed.isEmpty()) {
                            listener.onNodesRemoved(removed);
                        }
                    }
                    
                    // 如果Leader节点离线，触发重新选举
                    boolean leaderOffline = removed.stream()
                        .anyMatch(nodeId -> {
                            NodeInfo node = activeNodes.get(nodeId);
                            return node != null && node.getRole() == NodeRole.LEADER;
                        });
                        
                    if (leaderOffline) {
                        logger.warn("Leader node offline, triggering re-election");
                        try {
                            electLeader();
                        } catch (Exception e) {
                            logger.error("Failed to elect new leader", e);
                        }
                    }
                }
            }
        });
    }
    
    /**
     * 监听配置变化
     */
    private void subscribeToConfigChanges() throws Exception {
        configService.addListener(CONFIG_DATA_ID, CONFIG_GROUP, new Listener() {
            @Override
            public Executor getExecutor() {
                return Executors.newSingleThreadExecutor();
            }
            
            @Override
            public void receiveConfigInfo(String configInfo) {
                logger.info("Configuration updated: {}", configInfo);
                // 处理配置更新逻辑
                handleConfigUpdate(configInfo);
            }
        });
    }
    
    /**
     * 处理配置更新
     */
    private void handleConfigUpdate(String configInfo) {
        // 解析配置并应用到系统中
        // 例如：更新分区配置、复制因子等
        logger.info("Applying configuration update: {}", configInfo);
    }
    
    /**
     * 发布配置
     */
    public void publishConfig(String config) throws Exception {
        configService.publishConfig(CONFIG_DATA_ID, CONFIG_GROUP, config);
        logger.info("Configuration published: {}", config);
    }
    
    /**
     * 获取配置
     */
    public String getConfig() throws Exception {
        return configService.getConfig(CONFIG_DATA_ID, CONFIG_GROUP, 5000);
    }
    
    /**
     * 添加节点变化监听器
     */
    public void addNodeChangeListener(NodeChangeListener listener) {
        nodeChangeListeners.add(listener);
    }
    
    /**
     * 注销节点
     */
    public void deregisterNode() throws Exception {
        if (nodeId != null && nodeAddress != null) {
            String[] addressParts = nodeAddress.split(":");
            namingService.deregisterInstance(SERVICE_NAME, 
                addressParts[0], 
                Integer.parseInt(addressParts[1]));
            logger.info("Node {} deregistered from Nacos", nodeId);
        }
    }
    
    // 内部类和接口
    public static class NodeInfo {
        private final String nodeId;
        private final String address;
        private final NodeRole role;
        private final Set<Integer> shards;
        private final int totalShards;
        
        public NodeInfo(String nodeId, String address, NodeRole role) {
            this(nodeId, address, role, new HashSet<>(), 0);
        }
        
        public NodeInfo(String nodeId, String address, NodeRole role, Set<Integer> shards, int totalShards) {
            this.nodeId = nodeId;
            this.address = address;
            this.role = role;
            this.shards = new HashSet<>(shards);
            this.totalShards = totalShards;
        }
        
        public String getNodeId() { return nodeId; }
        public String getAddress() { return address; }
        public NodeRole getRole() { return role; }
        public Set<Integer> getShards() { return new HashSet<>(shards); }
        public int getTotalShards() { return totalShards; }
        
        public boolean isResponsibleForShard(int shard) {
            return shards.contains(shard);
        }
        
        @Override
        public String toString() {
            return String.format("NodeInfo{nodeId='%s', address='%s', role=%s, shards=%s}", 
                nodeId, address, role, shards);
        }
    }
    
    public enum NodeRole {
        LEADER, FOLLOWER, CANDIDATE
    }
    
    public interface NodeChangeListener {
        void onNodesAdded(List<NodeInfo> nodes);
        void onNodesRemoved(Set<String> nodeIds);
    }
}
