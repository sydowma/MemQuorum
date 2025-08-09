package com.github.sydowma.engine;

import com.github.sydowma.memquorum.grpc.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main gRPC service for MemQuorum
 * Handles user requests with automatic sharding and routing
 */
public class MemQuorumGrpcService extends PartitionServiceGrpc.PartitionServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumGrpcService.class);
    
    private final MemQuorumService memQuorumService;
    
    public MemQuorumGrpcService(MemQuorumService memQuorumService) {
        this.memQuorumService = memQuorumService;
    }
    
    @Override
    public void produce(ProduceRequest request, StreamObserver<ProduceResponse> responseObserver) {
        try {
            logger.debug("Produce request for topic: {}, partition: {}", 
                request.getTopic(), request.getPartition());
            
            // Extract user ID from entry key for sharding
            String userId = extractUserIdFromKey(request.getEntry().getKey());
            int targetShard = memQuorumService.getShardForUser(userId);
            
            // Check if this node is responsible for the shard
            if (!memQuorumService.isResponsibleForShard(targetShard)) {
                // Route to correct node
                routeToCorrectNode(request, responseObserver, targetShard);
                return;
            }
            
            // Handle locally
            Partition partition = memQuorumService.getPartitionForShard(targetShard);
            if (partition == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Partition not found for shard: " + targetShard)
                    .asRuntimeException());
                return;
            }
            
            // Ensure only leader can accept writes
            if (!memQuorumService.isLeader()) {
                // Forward to leader through Raft
                boolean success = forwardToLeader(request);
                if (success) {
                    responseObserver.onNext(ProduceResponse.newBuilder().setOffset(-1).build());
                    responseObserver.onCompleted();
                } else {
                    responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("No leader available")
                        .asRuntimeException());
                }
                return;
            }
            
            // Append through Raft for consensus
            long offset = appendThroughRaft(request.getEntry());
            
            ProduceResponse response = ProduceResponse.newBuilder()
                .setOffset(offset)
                .build();
                
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            logger.debug("Produced entry at offset: {}", offset);
            
        } catch (Exception e) {
            logger.error("Failed to produce entry", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to produce: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
        try {
            logger.debug("Read request for topic: {}, partition: {}, offset: {}", 
                request.getTopic(), request.getPartition(), request.getOffset());
            
            // For reads, we can serve from any replica
            // Use the provided partition or calculate shard from topic
            int shard = request.getPartition();
            if (shard < 0) {
                // If no specific partition, use topic hash
                shard = Math.abs(request.getTopic().hashCode()) % memQuorumService.getTotalShards();
            }
            
            // Check if this node has the requested shard
            if (!memQuorumService.isResponsibleForShard(shard)) {
                routeReadToCorrectNode(request, responseObserver, shard);
                return;
            }
            
            Partition partition = memQuorumService.getPartitionForShard(shard);
            if (partition == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Partition not found for shard: " + shard)
                    .asRuntimeException());
                return;
            }
            
            // Read from local partition
            java.util.List<Entry> entries = partition.read(request.getOffset(), request.getCount());
            
            ReadResponse response = ReadResponse.newBuilder()
                .addAllEntries(entries)
                .build();
                
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            logger.debug("Read {} entries from offset: {}", entries.size(), request.getOffset());
            
        } catch (Exception e) {
            logger.error("Failed to read entries", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to read: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void sync(SyncRequest request, StreamObserver<SyncResponse> responseObserver) {
        try {
            logger.debug("Sync request for offset: {}", request.getOffset());
            
            // This is used for replication between nodes
            // Extract shard information from the entry
            String userId = extractUserIdFromKey(request.getEntry().getKey());
            int shard = memQuorumService.getShardForUser(userId);
            
            Partition partition = memQuorumService.getPartitionForShard(shard);
            if (partition == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Partition not found for shard: " + shard)
                    .asRuntimeException());
                return;
            }
            
            boolean success = partition.sync(request.getEntry(), request.getOffset());
            
            SyncResponse response = SyncResponse.newBuilder()
                .setSuccess(success)
                .build();
                
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            logger.debug("Sync completed with success: {}", success);
            
        } catch (Exception e) {
            logger.error("Failed to sync entry", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to sync: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void subscribe(SubscribeRequest request, StreamObserver<Entry> responseObserver) {
        try {
            logger.info("Subscribe request for topic: {}, group: {}, partition: {}", 
                request.getTopic(), request.getGroupId(), request.getPartition());
            
            // Implement streaming subscription
            // This would typically involve:
            // 1. Register the subscriber
            // 2. Stream new entries as they arrive
            // 3. Handle subscriber disconnection
            
            // For now, send a simple response and complete
            responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("Subscribe not yet implemented")
                .asRuntimeException());
                
        } catch (Exception e) {
            logger.error("Failed to handle subscribe", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to subscribe: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    /**
     * Extract user ID from entry key for sharding
     * Expected format: "user:{userId}:key" or just use the whole key
     */
    private String extractUserIdFromKey(String key) {
        if (key.startsWith("user:")) {
            String[] parts = key.split(":", 3);
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        // Fallback to using the whole key
        return key;
    }
    
    /**
     * Route request to the correct node responsible for the shard
     */
    private void routeToCorrectNode(ProduceRequest request, StreamObserver<ProduceResponse> responseObserver, int shard) {
        // Get nodes responsible for this shard
        java.util.Set<String> nodes = memQuorumService.getNodesForShard(shard);
        
        if (nodes.isEmpty()) {
            responseObserver.onError(Status.UNAVAILABLE
                .withDescription("No nodes available for shard: " + shard)
                .asRuntimeException());
            return;
        }
        
        // For now, just return an error asking client to retry
        // In production, you would implement actual forwarding
        responseObserver.onError(Status.FAILED_PRECONDITION
            .withDescription("Request should be sent to nodes: " + nodes)
            .asRuntimeException());
    }
    
    /**
     * Route read request to correct node
     */
    private void routeReadToCorrectNode(ReadRequest request, StreamObserver<ReadResponse> responseObserver, int shard) {
        java.util.Set<String> nodes = memQuorumService.getNodesForShard(shard);
        
        if (nodes.isEmpty()) {
            responseObserver.onError(Status.UNAVAILABLE
                .withDescription("No nodes available for shard: " + shard)
                .asRuntimeException());
            return;
        }
        
        // For reads, we can suggest any replica
        responseObserver.onError(Status.FAILED_PRECONDITION
            .withDescription("Data available on nodes: " + nodes)
            .asRuntimeException());
    }
    
    /**
     * Forward write request to Raft leader
     */
    private boolean forwardToLeader(ProduceRequest request) {
        try {
            // Convert request to bytes for Raft log
            byte[] data = request.getEntry().toByteArray();
            
            // Submit to Raft
            java.util.concurrent.CompletableFuture<Boolean> future = 
                memQuorumService.getRaftNode().appendEntry(data);
            
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            
        } catch (Exception e) {
            logger.error("Failed to forward to leader", e);
            return false;
        }
    }
    
    /**
     * Append entry through Raft consensus
     */
    private long appendThroughRaft(Entry entry) throws Exception {
        // Convert entry to bytes for Raft log
        byte[] data = entry.toByteArray();
        
        // Submit to Raft for consensus
        java.util.concurrent.CompletableFuture<Boolean> future = 
            memQuorumService.getRaftNode().appendEntry(data);
        
        boolean success = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        
        if (!success) {
            throw new RuntimeException("Failed to achieve consensus");
        }
        
        // Return the log index as offset
        return memQuorumService.getRaftNode().getLogSize() - 1;
    }
}
