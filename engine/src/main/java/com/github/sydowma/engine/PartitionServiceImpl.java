package com.github.sydowma.engine;

import com.github.sydowma.memquorum.grpc.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy PartitionService implementation for backward compatibility
 * New requests should use MemQuorumGrpcService for better sharding support
 */
public class PartitionServiceImpl extends PartitionServiceGrpc.PartitionServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(PartitionServiceImpl.class);
    
    private final MemQuorumService memQuorumService;

    public PartitionServiceImpl(MemQuorumService memQuorumService) {
        this.memQuorumService = memQuorumService;
    }

    @Override
    public void produce(ProduceRequest request, StreamObserver<ProduceResponse> responseObserver) {
        try {
            logger.debug("Legacy produce request for topic: {}, partition: {}", 
                request.getTopic(), request.getPartition());
            
            // Use the specified partition or map to shard
            int shard = request.getPartition();
            if (shard < 0) {
                // Extract user ID from key for automatic sharding
                String key = request.getEntry().getKey();
                String userId = extractUserIdFromKey(key);
                shard = memQuorumService.getShardForUser(userId);
            }
            
            // Check if this node is responsible for the shard
            if (!memQuorumService.isResponsibleForShard(shard)) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Node not responsible for shard: " + shard + 
                        ". Responsible nodes: " + memQuorumService.getNodesForShard(shard))
                    .asRuntimeException());
                return;
            }
            
            Partition partition = memQuorumService.getPartitionForShard(shard);
            if (partition == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Partition not found for shard: " + shard)
                    .asRuntimeException());
                return;
            }
            
            long offset = partition.append(request.getEntry());
            responseObserver.onNext(ProduceResponse.newBuilder().setOffset(offset).build());
            responseObserver.onCompleted();
            
            logger.debug("Legacy produce completed with offset: {}", offset);
            
        } catch (QuorumException e) {
            logger.error("Quorum exception in legacy produce", e);
            responseObserver.onError(Status.UNAVAILABLE
                .withDescription("Quorum not achieved: " + e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            logger.error("Failed to produce in legacy service", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to produce: " + e.getMessage())
                .asRuntimeException());
        }
    }

    @Override
    public void read(ReadRequest request, StreamObserver<ReadResponse> responseObserver) {
        try {
            logger.debug("Legacy read request for topic: {}, partition: {}, offset: {}", 
                request.getTopic(), request.getPartition(), request.getOffset());
            
            int shard = request.getPartition();
            if (shard < 0) {
                // Use topic hash for shard assignment
                shard = Math.abs(request.getTopic().hashCode()) % memQuorumService.getTotalShards();
            }
            
            // Check if this node has the requested shard
            if (!memQuorumService.isResponsibleForShard(shard)) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Node not responsible for shard: " + shard + 
                        ". Responsible nodes: " + memQuorumService.getNodesForShard(shard))
                    .asRuntimeException());
                return;
            }
            
            Partition partition = memQuorumService.getPartitionForShard(shard);
            if (partition == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Partition not found for shard: " + shard)
                    .asRuntimeException());
                return;
            }
            
            java.util.List<Entry> entries = partition.read(request.getOffset(), request.getCount());
            responseObserver.onNext(ReadResponse.newBuilder().addAllEntries(entries).build());
            responseObserver.onCompleted();
            
            logger.debug("Legacy read completed with {} entries", entries.size());
            
        } catch (Exception e) {
            logger.error("Failed to read in legacy service", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to read: " + e.getMessage())
                .asRuntimeException());
        }
    }

    @Override
    public void sync(SyncRequest request, StreamObserver<SyncResponse> responseObserver) {
        try {
            logger.debug("Legacy sync request for offset: {}", request.getOffset());
            
            // Extract shard from entry key
            String key = request.getEntry().getKey();
            String userId = extractUserIdFromKey(key);
            int shard = memQuorumService.getShardForUser(userId);
            
            Partition partition = memQuorumService.getPartitionForShard(shard);
            if (partition == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Partition not found for shard: " + shard)
                    .asRuntimeException());
                return;
            }
            
            boolean success = partition.sync(request.getEntry(), request.getOffset());
            responseObserver.onNext(SyncResponse.newBuilder().setSuccess(success).build());
            responseObserver.onCompleted();
            
            logger.debug("Legacy sync completed with success: {}", success);
            
        } catch (Exception e) {
            logger.error("Failed to sync in legacy service", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to sync: " + e.getMessage())
                .asRuntimeException());
        }
    }
    
    @Override
    public void subscribe(SubscribeRequest request, StreamObserver<Entry> responseObserver) {
        logger.warn("Subscribe not implemented in legacy service");
        responseObserver.onError(Status.UNIMPLEMENTED
            .withDescription("Subscribe not implemented in legacy PartitionService")
            .asRuntimeException());
    }
    
    /**
     * Extract user ID from entry key for sharding
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
}