package com.github.sydowma.api.service;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EngineServiceDiscovery {
    private static final Logger logger = LoggerFactory.getLogger(EngineServiceDiscovery.class);
    
    private static final String ENGINE_SERVICE_NAME = "memquorum-cluster";
    
    @Value("${nacos.server.addr:localhost:8848}")
    private String nacosServerAddr;
    
    private NamingService namingService;
    private final Map<Integer, Set<EngineNodeInfo>> shardToNodesMap = new ConcurrentHashMap<>();
    private final Map<String, EngineNodeInfo> nodeInfoMap = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    
    @PostConstruct
    public void init() throws Exception {
        // Initialize Nacos naming service
        Properties props = new Properties();
        props.setProperty("serverAddr", nacosServerAddr);
        this.namingService = NamingFactory.createNamingService(props);
        
        // Subscribe to engine service changes
        subscribeToEngineServiceChanges();
        
        // Initial load of engine nodes
        refreshEngineNodes();
        
        initialized = true;
        logger.info("Engine service discovery initialized with {} nodes", nodeInfoMap.size());
    }
    
    @PreDestroy
    public void destroy() {
        if (namingService != null) {
            try {
                // Unsubscribe from service changes
                // namingService.unsubscribe() method is not available, so we just log
                logger.info("Engine service discovery shutting down");
            } catch (Exception e) {
                logger.warn("Error during service discovery shutdown", e);
            }
        }
    }
    
    /**
     * Get engine nodes responsible for a specific shard
     */
    public Set<EngineNodeInfo> getNodesForShard(int shard) {
        if (!initialized) {
            logger.warn("Service discovery not yet initialized");
            return Collections.emptySet();
        }
        
        return shardToNodesMap.getOrDefault(shard, Collections.emptySet());
    }
    
    /**
     * Get engine node responsible for a userId (based on consistent hashing)
     */
    public EngineNodeInfo getNodeForUser(String userId, int totalShards) {
        int shard = Math.abs(userId.hashCode()) % totalShards;
        Set<EngineNodeInfo> nodes = getNodesForShard(shard);
        
        if (nodes.isEmpty()) {
            logger.warn("No nodes found for shard {} (userId: {})", shard, userId);
            return null;
        }
        
        // Return the first available node (can be enhanced with load balancing)
        return nodes.iterator().next();
    }
    
    /**
     * Get all active engine nodes
     */
    public Collection<EngineNodeInfo> getAllEngineNodes() {
        return new ArrayList<>(nodeInfoMap.values());
    }
    
    /**
     * Check if any engine nodes are available
     */
    public boolean hasAvailableNodes() {
        return !nodeInfoMap.isEmpty();
    }
    
    /**
     * Subscribe to engine service changes
     */
    private void subscribeToEngineServiceChanges() throws Exception {
        namingService.subscribe(ENGINE_SERVICE_NAME, new EventListener() {
            @Override
            public void onEvent(Event event) {
                if (event instanceof NamingEvent) {
                    NamingEvent namingEvent = (NamingEvent) event;
                    logger.info("Engine service change event: {}", namingEvent.getServiceName());
                    
                    try {
                        refreshEngineNodes();
                    } catch (Exception e) {
                        logger.error("Failed to refresh engine nodes", e);
                    }
                }
            }
        });
    }
    
    /**
     * Refresh engine nodes from Nacos
     */
    private void refreshEngineNodes() throws Exception {
        List<Instance> instances = namingService.getAllInstances(ENGINE_SERVICE_NAME);
        
        // Clear existing mappings
        shardToNodesMap.clear();
        nodeInfoMap.clear();
        
        for (Instance instance : instances) {
            if (instance.isHealthy() && instance.isEnabled()) {
                EngineNodeInfo nodeInfo = createEngineNodeInfo(instance);
                nodeInfoMap.put(nodeInfo.getNodeId(), nodeInfo);
                
                // Map shards to nodes
                for (Integer shard : nodeInfo.getShards()) {
                    shardToNodesMap.computeIfAbsent(shard, k -> new HashSet<>()).add(nodeInfo);
                }
            }
        }
        
        logger.info("Refreshed engine nodes: {} active nodes, {} shard mappings", 
                   nodeInfoMap.size(), shardToNodesMap.size());
        
        // Log shard distribution for debugging
        if (logger.isDebugEnabled()) {
            for (Map.Entry<Integer, Set<EngineNodeInfo>> entry : shardToNodesMap.entrySet()) {
                logger.debug("Shard {}: nodes {}", entry.getKey(), 
                            entry.getValue().stream().map(EngineNodeInfo::getNodeId).toList());
            }
        }
    }
    
    /**
     * Create EngineNodeInfo from Nacos instance
     */
    private EngineNodeInfo createEngineNodeInfo(Instance instance) {
        Map<String, String> metadata = instance.getMetadata();
        
        // Parse shards from metadata
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
        
        int totalShards = Integer.parseInt(metadata.getOrDefault("totalShards", "16"));
        String role = metadata.getOrDefault("role", "FOLLOWER");
        
        return new EngineNodeInfo(
            instance.getInstanceId(),
            instance.getIp(),
            instance.getPort(),
            shards,
            totalShards,
            role
        );
    }
    
    /**
     * Engine node information
     */
    public static class EngineNodeInfo {
        private final String nodeId;
        private final String host;
        private final int port;
        private final Set<Integer> shards;
        private final int totalShards;
        private final String role;
        
        public EngineNodeInfo(String nodeId, String host, int port, Set<Integer> shards, 
                             int totalShards, String role) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.shards = new HashSet<>(shards);
            this.totalShards = totalShards;
            this.role = role;
        }
        
        public String getNodeId() { return nodeId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public Set<Integer> getShards() { return new HashSet<>(shards); }
        public int getTotalShards() { return totalShards; }
        public String getRole() { return role; }
        public String getAddress() { return host + ":" + port; }
        
        public boolean isResponsibleForShard(int shard) {
            return shards.contains(shard);
        }
        
        public boolean isLeader() {
            return "LEADER".equals(role);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EngineNodeInfo that = (EngineNodeInfo) o;
            return Objects.equals(nodeId, that.nodeId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(nodeId);
        }
        
        @Override
        public String toString() {
            return String.format("EngineNodeInfo{nodeId='%s', address='%s:%d', role='%s', shards=%s}", 
                               nodeId, host, port, role, shards);
        }
    }
}