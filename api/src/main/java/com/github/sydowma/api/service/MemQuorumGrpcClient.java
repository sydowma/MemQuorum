package com.github.sydowma.api.service;

import com.github.sydowma.memquorum.grpc.*;
import com.github.sydowma.memquorum.grpc.MemQuorumServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class MemQuorumGrpcClient {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumGrpcClient.class);
    
    @Autowired
    private EngineServiceDiscovery serviceDiscovery;
    
    @Autowired
    private DynamicGrpcClientPool clientPool;
    
    @Value("${memquorum.engine.total-shards:16}")
    private int totalShards;
    
    @PostConstruct
    public void init() {
        logger.info("Dynamic gRPC client initialized with total shards: {}", totalShards);
    }
    
    public String set(String userId, String key, String value) {
        try {
            EngineServiceDiscovery.EngineNodeInfo targetNode = getTargetNode(userId);
            if (targetNode == null) {
                throw new RuntimeException("No engine nodes available for user: " + userId);
            }
            
            MemQuorumServiceGrpc.MemQuorumServiceBlockingStub stub = clientPool.getStub(targetNode);
            
            SetRequest request = SetRequest.newBuilder()
                    .setUserId(userId)
                    .setKey(key)
                    .setValue(value)
                    .build();
            
            SetResponse response = stub.set(request);
            logger.debug("Set operation for user {} routed to node {}", userId, targetNode.getNodeId());
            return response.getSuccess() ? "OK" : "ERROR: " + response.getMessage();
            
        } catch (Exception e) {
            logger.error("Error calling set operation for user {}", userId, e);
            throw new RuntimeException("Failed to set value: " + e.getMessage(), e);
        }
    }
    
    public String get(String userId, String key) {
        try {
            EngineServiceDiscovery.EngineNodeInfo targetNode = getTargetNode(userId);
            if (targetNode == null) {
                throw new RuntimeException("No engine nodes available for user: " + userId);
            }
            
            MemQuorumServiceGrpc.MemQuorumServiceBlockingStub stub = clientPool.getStub(targetNode);
            
            GetRequest request = GetRequest.newBuilder()
                    .setUserId(userId)
                    .setKey(key)
                    .build();
            
            GetResponse response = stub.get(request);
            logger.debug("Get operation for user {} routed to node {}", userId, targetNode.getNodeId());
            return response.getFound() ? response.getValue() : null;
            
        } catch (Exception e) {
            logger.error("Error calling get operation for user {}", userId, e);
            throw new RuntimeException("Failed to get value: " + e.getMessage(), e);
        }
    }
    
    public boolean delete(String userId, String key) {
        try {
            EngineServiceDiscovery.EngineNodeInfo targetNode = getTargetNode(userId);
            if (targetNode == null) {
                throw new RuntimeException("No engine nodes available for user: " + userId);
            }
            
            MemQuorumServiceGrpc.MemQuorumServiceBlockingStub stub = clientPool.getStub(targetNode);
            
            DeleteRequest request = DeleteRequest.newBuilder()
                    .setUserId(userId)
                    .setKey(key)
                    .build();
            
            DeleteResponse response = stub.delete(request);
            logger.debug("Delete operation for user {} routed to node {}", userId, targetNode.getNodeId());
            return response.getSuccess();
            
        } catch (Exception e) {
            logger.error("Error calling delete operation for user {}", userId, e);
            throw new RuntimeException("Failed to delete value: " + e.getMessage(), e);
        }
    }
    
    public String[] list(String userId, String pattern) {
        try {
            EngineServiceDiscovery.EngineNodeInfo targetNode = getTargetNode(userId);
            if (targetNode == null) {
                throw new RuntimeException("No engine nodes available for user: " + userId);
            }
            
            MemQuorumServiceGrpc.MemQuorumServiceBlockingStub stub = clientPool.getStub(targetNode);
            
            ListRequest request = ListRequest.newBuilder()
                    .setUserId(userId)
                    .setPattern(pattern != null ? pattern : "*")
                    .build();
            
            ListResponse response = stub.list(request);
            logger.debug("List operation for user {} routed to node {}", userId, targetNode.getNodeId());
            return response.getKeysList().toArray(new String[0]);
            
        } catch (Exception e) {
            logger.error("Error calling list operation for user {}", userId, e);
            throw new RuntimeException("Failed to list keys: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get the target engine node for a specific user
     */
    private EngineServiceDiscovery.EngineNodeInfo getTargetNode(String userId) {
        if (!serviceDiscovery.hasAvailableNodes()) {
            logger.warn("No engine nodes available in service discovery");
            return null;
        }
        
        EngineServiceDiscovery.EngineNodeInfo targetNode = serviceDiscovery.getNodeForUser(userId, totalShards);
        
        if (targetNode == null) {
            logger.warn("No specific node found for user {}, will try first available node", userId);
            // Fallback to first available node
            var allNodes = serviceDiscovery.getAllEngineNodes();
            if (!allNodes.isEmpty()) {
                targetNode = allNodes.iterator().next();
                logger.info("Using fallback node {} for user {}", targetNode.getNodeId(), userId);
            }
        }
        
        return targetNode;
    }
    
    /**
     * Get cluster status for debugging
     */
    public ClusterStatus getClusterStatus() {
        var allNodes = serviceDiscovery.getAllEngineNodes();
        var poolStats = clientPool.getStats();
        
        return new ClusterStatus(allNodes.size(), poolStats.getActiveChannels(), totalShards);
    }
    
    public static class ClusterStatus {
        private final int totalNodes;
        private final int activeConnections;
        private final int totalShards;
        
        public ClusterStatus(int totalNodes, int activeConnections, int totalShards) {
            this.totalNodes = totalNodes;
            this.activeConnections = activeConnections;
            this.totalShards = totalShards;
        }
        
        public int getTotalNodes() { return totalNodes; }
        public int getActiveConnections() { return activeConnections; }
        public int getTotalShards() { return totalShards; }
        
        @Override
        public String toString() {
            return String.format("ClusterStatus{nodes=%d, connections=%d, shards=%d}", 
                               totalNodes, activeConnections, totalShards);
        }
    }
}