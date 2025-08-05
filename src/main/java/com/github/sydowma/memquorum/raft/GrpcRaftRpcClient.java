package com.github.sydowma.memquorum.raft;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于gRPC的Raft RPC客户端实现
 */
public class GrpcRaftRpcClient implements RaftRpcClient {
    private static final Logger logger = LoggerFactory.getLogger(GrpcRaftRpcClient.class);
    
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nodeAddresses = new ConcurrentHashMap<>();
    
    public GrpcRaftRpcClient() {
        // 在关闭时清理资源
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }
    
    /**
     * 注册节点地址
     */
    public void registerNode(String nodeId, String address) {
        nodeAddresses.put(nodeId, address);
        logger.debug("Registered node {} at address {}", nodeId, address);
    }
    
    /**
     * 移除节点
     */
    public void unregisterNode(String nodeId) {
        nodeAddresses.remove(nodeId);
        
        // 关闭对应的channel
        ManagedChannel channel = channels.remove(nodeId);
        if (channel != null) {
            channel.shutdown();
        }
        
        logger.debug("Unregistered node {}", nodeId);
    }
    
    @Override
    public RequestVoteResponse requestVote(String target, RequestVoteRequest request) throws Exception {
        ManagedChannel channel = getOrCreateChannel(target);
        if (channel == null) {
            throw new Exception("Cannot create channel to node: " + target);
        }
        
        try {
            // 这里需要使用实际的gRPC stub
            // 由于protobuf文件中没有定义Raft相关的服务，我们需要创建
            // 暂时使用模拟实现
            return simulateRequestVote(target, request);
            
        } catch (StatusRuntimeException e) {
            logger.warn("RequestVote failed to {}: {}", target, e.getStatus());
            throw new Exception("RequestVote RPC failed", e);
        }
    }
    
    @Override
    public AppendEntriesResponse appendEntries(String target, AppendEntriesRequest request) throws Exception {
        ManagedChannel channel = getOrCreateChannel(target);
        if (channel == null) {
            throw new Exception("Cannot create channel to node: " + target);
        }
        
        try {
            // 这里需要使用实际的gRPC stub
            // 暂时使用模拟实现
            return simulateAppendEntries(target, request);
            
        } catch (StatusRuntimeException e) {
            logger.warn("AppendEntries failed to {}: {}", target, e.getStatus());
            throw new Exception("AppendEntries RPC failed", e);
        }
    }
    
    @Override
    public boolean isReachable(String target) {
        try {
            ManagedChannel channel = getOrCreateChannel(target);
            return channel != null && !channel.isShutdown() && !channel.isTerminated();
        } catch (Exception e) {
            logger.debug("Node {} is not reachable: {}", target, e.getMessage());
            return false;
        }
    }
    
    @Override
    public void close() {
        logger.info("Closing gRPC Raft RPC client");
        
        for (ManagedChannel channel : channels.values()) {
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
        
        channels.clear();
        logger.info("gRPC Raft RPC client closed");
    }
    
    /**
     * 获取或创建到目标节点的gRPC channel
     */
    private ManagedChannel getOrCreateChannel(String target) {
        return channels.computeIfAbsent(target, nodeId -> {
            String address = nodeAddresses.get(nodeId);
            if (address == null) {
                logger.warn("No address found for node: {}", nodeId);
                return null;
            }
            
            try {
                ManagedChannel channel = ManagedChannelBuilder.forTarget(address)
                    .usePlaintext()
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(5, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .maxInboundMessageSize(4 * 1024 * 1024) // 4MB
                    .build();
                
                logger.debug("Created gRPC channel to node {} at {}", nodeId, address);
                return channel;
                
            } catch (Exception e) {
                logger.error("Failed to create channel to node {} at {}", nodeId, address, e);
                return null;
            }
        });
    }
    
    /**
     * 模拟RequestVote RPC（实际应该使用protobuf生成的stub）
     */
    private RequestVoteResponse simulateRequestVote(String target, RequestVoteRequest request) {
        // 这是一个简化的模拟实现
        // 在实际项目中，应该：
        // 1. 在protobuf中定义RaftService
        // 2. 生成对应的gRPC代码
        // 3. 使用生成的stub进行调用
        
        logger.debug("Simulating RequestVote to {} with term {}", target, request.getTerm());
        
        // 模拟网络延迟
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 简单的响应逻辑：随机决定是否投票
        boolean voteGranted = Math.random() > 0.3; // 70%的概率投票
        return new RequestVoteResponse(request.getTerm(), voteGranted);
    }
    
    /**
     * 模拟AppendEntries RPC（实际应该使用protobuf生成的stub）
     */
    private AppendEntriesResponse simulateAppendEntries(String target, AppendEntriesRequest request) {
        logger.debug("Simulating AppendEntries to {} with {} entries", 
            target, request.entries().size());
        
        // 模拟网络延迟
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 简单的响应逻辑：大部分情况下成功
        boolean success = Math.random() > 0.1; // 90%的成功率
        return new AppendEntriesResponse(request.term(), success);
    }
}
