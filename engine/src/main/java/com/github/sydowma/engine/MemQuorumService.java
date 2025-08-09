package com.github.sydowma.engine;

import com.github.sydowma.engine.raft.*;
import com.github.sydowma.memquorum.grpc.Entry;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main MemQuorum service that integrates all components:
 * - Raft consensus algorithm for high availability
 * - Nacos service discovery and configuration
 * - gRPC service for external APIs
 * - Kafka input handling
 * - User-based sharding
 */
@Service
public class MemQuorumService {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumService.class);
    
    @Autowired
    private NacosNodeManager nacosNodeManager;
    
    @Value("${memquorum.node.port:8080}")
    private int nodePort;
    
    @Value("${memquorum.node.id:}")
    private String nodeId;
    
    @Value("${memquorum.shards.total:16}")
    private int totalShards;
    
    @Value("${memquorum.replication.factor:3}")
    private int replicationFactor;
    
    @Value("${memquorum.kafka.enabled:false}")
    private boolean kafkaEnabled;
    
    @Value("${memquorum.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;
    
    @Value("${memquorum.kafka.topic:memquorum-input}")
    private String kafkaTopic;
    
    @Value("${memquorum.startup.delay:0}")
    private int startupDelaySeconds;
    
    // Core components
    private RaftNode raftNode;
    private Server grpcServer;
    private KafkaInputHandler kafkaInputHandler;
    private GrpcRaftRpcClient raftRpcClient;
    private MemoryStateMachine stateMachine;
    
    // Service state
    private String nodeAddress;
    private volatile boolean isLeader = false;
    private final Map<Integer, Set<String>> shardAssignments = new ConcurrentHashMap<>();
    private final Map<String, Partition> localPartitions = new ConcurrentHashMap<>();
    
    // Scheduled tasks
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    
    @PostConstruct
    public void start() {
        try {
            // Apply startup delay to stagger node initialization
            if (startupDelaySeconds > 0) {
                logger.info("Delaying startup by {} seconds to allow cluster formation", startupDelaySeconds);
                Thread.sleep(startupDelaySeconds * 1000L);
            }
            
            initializeNodeInfo();
            registerToNacos();  // Register first so other nodes can discover us
            initializeRaftComponents();
            startGrpcServer();
            initializeSharding();
            
            if (kafkaEnabled) {
                startKafkaHandler();
            }
            
            startHealthCheck();
            
            logger.info("MemQuorum service started successfully on {}", nodeAddress);
            
        } catch (Exception e) {
            logger.error("Failed to start MemQuorum service", e);
            throw new RuntimeException("Service startup failed", e);
        }
    }
    
    @PreDestroy
    public void stop() {
        logger.info("Stopping MemQuorum service...");
        
        try {
            // Stop Kafka handler
            if (kafkaInputHandler != null) {
                kafkaInputHandler.stop();
            }
            
            // Stop Raft node
            if (raftNode != null) {
                raftNode.stop();
            }
            
            // Stop gRPC server
            if (grpcServer != null) {
                grpcServer.shutdown();
                try {
                    if (!grpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                        grpcServer.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    grpcServer.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // Close RPC client
            if (raftRpcClient != null) {
                raftRpcClient.close();
            }
            
            // Deregister from Nacos
            nacosNodeManager.deregisterNode();
            
            // Shutdown scheduler
            scheduler.shutdown();
            
            logger.info("MemQuorum service stopped");
            
        } catch (Exception e) {
            logger.error("Error during service shutdown", e);
        }
    }
    
    /**
     * Initialize node information
     */
    private void initializeNodeInfo() throws Exception {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            nodeId = InetAddress.getLocalHost().getHostName() + "-" + nodePort;
        }
        
        String hostAddress = InetAddress.getLocalHost().getHostAddress();
        nodeAddress = hostAddress + ":" + nodePort;
        
        logger.info("Initializing node {} at {}", nodeId, nodeAddress);
    }
    
    /**
     * Initialize Raft components for consensus
     */
    private void initializeRaftComponents() throws Exception {
        // Create state machine
        stateMachine = new MemoryStateMachine();
        
        // Create RPC client
        raftRpcClient = new GrpcRaftRpcClient();
        
        // Get initial cluster members from Nacos
        List<String> initialCluster = getInitialCluster();
        
        // Create Raft node
        raftNode = new RaftNode(nodeId, initialCluster, raftRpcClient, stateMachine);
        
        // Start Raft node
        raftNode.start();
        
        // Schedule periodic cluster updates
        scheduler.scheduleAtFixedRate(this::updateClusterMembers, 30, 30, TimeUnit.SECONDS);
        
        logger.info("Raft components initialized with cluster: {}", initialCluster);
    }
    
    /**
     * Start gRPC server
     */
    private void startGrpcServer() throws Exception {
        MemQuorumGrpcServiceImpl memQuorumGrpcServiceImpl = new MemQuorumGrpcServiceImpl(this);
        
        grpcServer = ServerBuilder.forPort(nodePort)
            .addService(new PartitionServiceImpl(this))
            .addService(new MemQuorumGrpcService(this)) // Original partition service
            .addService(memQuorumGrpcServiceImpl) // New MemQuorum key-value service
            .addService(new com.github.sydowma.engine.raft.RaftServiceImpl(raftNode)) // Raft consensus service
            .build()
            .start();
        
        logger.info("gRPC server started on port {}", nodePort);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down gRPC server due to JVM shutdown");
            if (grpcServer != null) {
                grpcServer.shutdown();
            }
        }));
    }
    
    /**
     * Register to Nacos with shard information
     */
    private void registerToNacos() throws Exception {
        String hostAddress = nodeAddress.split(":")[0];
        
        // Initial registration without shard info
        nacosNodeManager.registerNode(nodeId, hostAddress, nodePort);
        
        // Add node change listener to handle cluster changes
        nacosNodeManager.addNodeChangeListener(new NacosNodeManager.NodeChangeListener() {
            @Override
            public void onNodesAdded(List<NacosNodeManager.NodeInfo> nodes) {
                logger.info("Nodes added: {}", nodes);
                updateClusterMembers();
                rebalanceShards();
            }
            
            @Override
            public void onNodesRemoved(Set<String> nodeIds) {
                logger.info("Nodes removed: {}", nodeIds);
                updateClusterMembers();
                rebalanceShards();
            }
        });
        
        logger.info("Registered to Nacos successfully");
    }
    
    /**
     * Update node registration with shard information
     */
    private void updateNodeRegistrationWithShards() throws Exception {
        String hostAddress = nodeAddress.split(":")[0];
        Set<Integer> myShards = getMyShards();
        nacosNodeManager.registerNode(nodeId, hostAddress, nodePort, myShards, totalShards, replicationFactor);
        logger.info("Updated node registration with shards: {}", myShards);
    }
    
    /**
     * Get shards this node is responsible for
     */
    public Set<Integer> getMyShards() {
        Set<Integer> myShards = new HashSet<>();
        for (Map.Entry<Integer, Set<String>> entry : shardAssignments.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                myShards.add(entry.getKey());
            }
        }
        return myShards;
    }
    
    /**
     * Initialize sharding configuration
     */
    private void initializeSharding() {
        // Calculate shard assignments based on consistent hashing
        rebalanceShards();
        
        logger.info("Sharding initialized with {} total shards", totalShards);
    }
    
    /**
     * Start Kafka input handler if enabled
     */
    private void startKafkaHandler() {
        try {
            kafkaInputHandler = new KafkaInputHandler(
                this, 
                kafkaBootstrapServers, 
                "memquorum-group-" + nodeId, 
                kafkaTopic
            );
            kafkaInputHandler.startConsuming();
            
            logger.info("Kafka input handler started for topic: {}", kafkaTopic);
            
        } catch (Exception e) {
            logger.error("Failed to start Kafka handler", e);
        }
    }
    
    /**
     * Start health check monitoring
     */
    private void startHealthCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performHealthCheck();
            } catch (Exception e) {
                logger.warn("Health check failed", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        logger.info("Health check monitoring started");
    }
    
    /**
     * Get initial cluster members from Nacos, with retry logic to wait for cluster formation
     */
    private List<String> getInitialCluster() {
        int maxRetries = 10;
        int retryInterval = 5000; // 5 seconds
        
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                List<NacosNodeManager.NodeInfo> allNodes = nacosNodeManager.getAllNodes();
                List<String> cluster = new ArrayList<>();
                
                for (NacosNodeManager.NodeInfo node : allNodes) {
                    cluster.add(node.getNodeId());
                    // Register node address with RPC client
                    raftRpcClient.registerNode(node.getNodeId(), node.getAddress());
                }
                
                // Always include current node
                if (!cluster.contains(nodeId)) {
                    cluster.add(nodeId);
                }
                raftRpcClient.registerNode(nodeId, nodeAddress);
                
                // Wait for at least 2 nodes for meaningful cluster
                if (cluster.size() >= 2 || retry == maxRetries - 1) {
                    logger.info("Found {} nodes in cluster after {} retries", cluster.size(), retry + 1);
                    return cluster;
                }
                
                logger.info("Only found {} nodes, waiting for more... (retry {}/{})", 
                    cluster.size(), retry + 1, maxRetries);
                Thread.sleep(retryInterval);
                
            } catch (Exception e) {
                logger.warn("Failed to get cluster from Nacos on retry {}/{}: {}", 
                    retry + 1, maxRetries, e.getMessage());
                if (retry < maxRetries - 1) {
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        logger.warn("Failed to get initial cluster from Nacos, using single node");
        return Collections.singletonList(nodeId);
    }
    
    /**
     * Update cluster members from Nacos
     */
    private void updateClusterMembers() {
        try {
            List<NacosNodeManager.NodeInfo> allNodes = nacosNodeManager.getAllNodes();
            
            for (NacosNodeManager.NodeInfo node : allNodes) {
                raftRpcClient.registerNode(node.getNodeId(), node.getAddress());
            }
            
            // Update leadership status
            isLeader = (raftNode.getState() == RaftNode.NodeState.LEADER);
            
            logger.debug("Updated cluster members: {} nodes", allNodes.size());
            
        } catch (Exception e) {
            logger.warn("Failed to update cluster members", e);
        }
    }
    
    /**
     * Rebalance shards across cluster nodes
     */
    private void rebalanceShards() {
        try {
            List<NacosNodeManager.NodeInfo> allNodes = nacosNodeManager.getAllNodes();
            
            if (allNodes.isEmpty()) {
                return;
            }
            
            // Clear current assignments
            shardAssignments.clear();
            
            // Simple consistent hashing for shard assignment
            for (int shard = 0; shard < totalShards; shard++) {
                Set<String> replicas = new HashSet<>();
                
                // Primary replica
                int primaryIndex = shard % allNodes.size();
                replicas.add(allNodes.get(primaryIndex).getNodeId());
                
                // Additional replicas for fault tolerance
                for (int i = 1; i < Math.min(replicationFactor, allNodes.size()); i++) {
                    int replicaIndex = (primaryIndex + i) % allNodes.size();
                    replicas.add(allNodes.get(replicaIndex).getNodeId());
                }
                
                shardAssignments.put(shard, replicas);
                
                // Create local partition if this node is responsible
                if (replicas.contains(nodeId)) {
                    String partitionKey = "shard-" + shard;
                    if (!localPartitions.containsKey(partitionKey)) {
                        List<String> replicaAddresses = new ArrayList<>();
                        for (String replicaNodeId : replicas) {
                            if (!replicaNodeId.equals(nodeId)) {
                                // Find address for replica node
                                for (NacosNodeManager.NodeInfo node : allNodes) {
                                    if (node.getNodeId().equals(replicaNodeId)) {
                                        replicaAddresses.add(node.getAddress());
                                        break;
                                    }
                                }
                            }
                        }
                        
                        Partition partition = new Partition(replicaAddresses, 
                            (replicationFactor + 1) / 2); // Quorum size
                        localPartitions.put(partitionKey, partition);
                    }
                }
            }
            
            // Publish shard configuration to Nacos
            publishShardConfiguration();
            
            // Update node registration with new shard assignments
            try {
                updateNodeRegistrationWithShards();
            } catch (Exception e) {
                logger.warn("Failed to update node registration with shards", e);
            }
            
            logger.info("Rebalanced {} shards across {} nodes", totalShards, allNodes.size());
            
        } catch (Exception e) {
            logger.error("Failed to rebalance shards", e);
        }
    }
    
    /**
     * Publish shard configuration to Nacos
     */
    private void publishShardConfiguration() {
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("totalShards", totalShards);
            config.put("replicationFactor", replicationFactor);
            config.put("shardAssignments", shardAssignments);
            config.put("timestamp", System.currentTimeMillis());
            
            // Convert to JSON string (simplified)
            String configJson = convertToJson(config);
            nacosNodeManager.publishConfig(configJson);
            
            logger.debug("Published shard configuration to Nacos");
            
        } catch (Exception e) {
            logger.warn("Failed to publish shard configuration", e);
        }
    }
    
    /**
     * Get shard for user ID using consistent hashing
     */
    public int getShardForUser(String userId) {
        return Math.abs(userId.hashCode()) % totalShards;
    }
    
    /**
     * Get nodes responsible for a shard
     */
    public Set<String> getNodesForShard(int shard) {
        return shardAssignments.getOrDefault(shard, Collections.emptySet());
    }
    
    /**
     * Get local partition for shard
     */
    public Partition getPartitionForShard(int shard) {
        return localPartitions.get("shard-" + shard);
    }
    
    /**
     * Check if this node is responsible for a shard
     */
    public boolean isResponsibleForShard(int shard) {
        Set<String> nodes = getNodesForShard(shard);
        return nodes.contains(nodeId);
    }
    
    /**
     * Perform health check
     */
    private void performHealthCheck() {
        // Check Raft status
        if (raftNode != null) {
            logger.debug("Raft status - State: {}, Term: {}, Leader: {}", 
                raftNode.getState(), raftNode.getCurrentTerm(), raftNode.getCurrentLeader());
        }
        
        // Check local partitions health
        logger.debug("Local partitions: {}", localPartitions.size());
        
        // Update node role in Nacos based on Raft state
        try {
            NacosNodeManager.NodeRole role = NacosNodeManager.NodeRole.FOLLOWER;
            if (raftNode != null) {
                switch (raftNode.getState()) {
                    case LEADER:
                        role = NacosNodeManager.NodeRole.LEADER;
                        isLeader = true;
                        break;
                    case CANDIDATE:
                        role = NacosNodeManager.NodeRole.CANDIDATE;
                        isLeader = false;
                        break;
                    default:
                        role = NacosNodeManager.NodeRole.FOLLOWER;
                        isLeader = false;
                        break;
                }
            }
            nacosNodeManager.updateNodeRole(role);
            
        } catch (Exception e) {
            logger.debug("Failed to update node role", e);
        }
    }
    
    /**
     * Simple JSON conversion (replace with proper JSON library in production)
     */
    private String convertToJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof String) {
                json.append("\"").append(entry.getValue()).append("\"");
            } else {
                json.append(entry.getValue().toString());
            }
            first = false;
        }
        json.append("}");
        return json.toString();
    }
    
    // Key-value storage operations
    public void set(String userId, String key, byte[] value) throws Exception {
        int shard = getShardForUser(userId);
        if (!isResponsibleForShard(shard)) {
            throw new RuntimeException("Not responsible for shard " + shard + " for user " + userId);
        }
        
        Partition partition = getPartitionForShard(shard);
        if (partition == null) {
            throw new RuntimeException("Partition not found for shard " + shard);
        }
        
        // Use the existing partition append mechanism
        Entry entry = Entry.newBuilder()
                .setKey("user:" + userId + ":" + key)
                .setValue(ByteString.copyFrom(value))
                .setTimestamp(System.currentTimeMillis())
                .build();
        partition.append(entry);
        
        logger.debug("Set key {} for user {} in shard {}", key, userId, shard);
    }
    
    public byte[] get(String userId, String key) throws Exception {
        int shard = getShardForUser(userId);
        if (!isResponsibleForShard(shard)) {
            throw new RuntimeException("Not responsible for shard " + shard + " for user " + userId);
        }
        
        Partition partition = getPartitionForShard(shard);
        if (partition == null) {
            return null;
        }
        
        // This is a simplified implementation - in reality, you'd need to 
        // implement proper key-value lookups in the partition
        logger.debug("Get key {} for user {} in shard {}", key, userId, shard);
        return null; // TODO: Implement proper key lookup
    }
    
    public boolean delete(String userId, String key) throws Exception {
        int shard = getShardForUser(userId);
        if (!isResponsibleForShard(shard)) {
            throw new RuntimeException("Not responsible for shard " + shard + " for user " + userId);
        }
        
        Partition partition = getPartitionForShard(shard);
        if (partition == null) {
            return false;
        }
        
        // This is a simplified implementation
        logger.debug("Delete key {} for user {} in shard {}", key, userId, shard);
        return true; // TODO: Implement proper key deletion
    }
    
    public String[] list(String userId, String pattern) throws Exception {
        int shard = getShardForUser(userId);
        if (!isResponsibleForShard(shard)) {
            throw new RuntimeException("Not responsible for shard " + shard + " for user " + userId);
        }
        
        Partition partition = getPartitionForShard(shard);
        if (partition == null) {
            return new String[0];
        }
        
        // This is a simplified implementation
        logger.debug("List keys for user {} with pattern {} in shard {}", userId, pattern, shard);
        return new String[0]; // TODO: Implement proper key listing
    }
    
    // Getters for other components
    public String getNodeId() { return nodeId; }
    public String getNodeAddress() { return nodeAddress; }
    public boolean isLeader() { return isLeader; }
    public RaftNode getRaftNode() { return raftNode; }
    public int getTotalShards() { return totalShards; }
}
