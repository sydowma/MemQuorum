package com.github.sydowma.api.service;

import com.github.sydowma.memquorum.grpc.MemQuorumServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class DynamicGrpcClientPool {
    private static final Logger logger = LoggerFactory.getLogger(DynamicGrpcClientPool.class);
    
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemQuorumServiceGrpc.MemQuorumServiceBlockingStub> stubs = new ConcurrentHashMap<>();
    
    /**
     * Get or create gRPC client stub for the specified engine node
     */
    public MemQuorumServiceGrpc.MemQuorumServiceBlockingStub getStub(EngineServiceDiscovery.EngineNodeInfo nodeInfo) {
        String nodeKey = nodeInfo.getNodeId();
        
        return stubs.computeIfAbsent(nodeKey, key -> {
            ManagedChannel channel = channels.computeIfAbsent(nodeKey, k -> {
                logger.info("Creating gRPC channel for node {} at {}:{}", 
                           nodeInfo.getNodeId(), nodeInfo.getHost(), nodeInfo.getPort());
                
                return ManagedChannelBuilder.forAddress(nodeInfo.getHost(), nodeInfo.getPort())
                        .usePlaintext()
                        .keepAliveTime(30, TimeUnit.SECONDS)
                        .keepAliveTimeout(10, TimeUnit.SECONDS)
                        .keepAliveWithoutCalls(true)
                        .maxInboundMessageSize(4 * 1024 * 1024) // 4MB
                        .build();
            });
            
            return MemQuorumServiceGrpc.newBlockingStub(channel);
        });
    }
    
    /**
     * Remove and shutdown channel for a specific node
     */
    public void removeNode(String nodeId) {
        logger.info("Removing gRPC client for node {}", nodeId);
        
        stubs.remove(nodeId);
        ManagedChannel channel = channels.remove(nodeId);
        
        if (channel != null) {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Check if channel is healthy
     */
    public boolean isChannelHealthy(String nodeId) {
        ManagedChannel channel = channels.get(nodeId);
        return channel != null && !channel.isShutdown() && !channel.isTerminated();
    }
    
    /**
     * Get connection status for debugging
     */
    public String getConnectionStatus(String nodeId) {
        ManagedChannel channel = channels.get(nodeId);
        if (channel == null) {
            return "NO_CONNECTION";
        }
        return channel.getState(false).toString();
    }
    
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down gRPC client pool with {} connections", channels.size());
        
        for (String nodeId : channels.keySet()) {
            removeNode(nodeId);
        }
        
        channels.clear();
        stubs.clear();
    }
    
    /**
     * Get pool statistics
     */
    public PoolStats getStats() {
        return new PoolStats(channels.size(), stubs.size());
    }
    
    public static class PoolStats {
        private final int activeChannels;
        private final int activeStubs;
        
        public PoolStats(int activeChannels, int activeStubs) {
            this.activeChannels = activeChannels;
            this.activeStubs = activeStubs;
        }
        
        public int getActiveChannels() { return activeChannels; }
        public int getActiveStubs() { return activeStubs; }
        
        @Override
        public String toString() {
            return String.format("PoolStats{channels=%d, stubs=%d}", activeChannels, activeStubs);
        }
    }
}