package com.github.sydowma.engine.raft;

import com.github.sydowma.memquorum.grpc.RaftServiceGrpc;
import com.github.sydowma.memquorum.grpc.RequestVoteRequest;
import com.github.sydowma.memquorum.grpc.RequestVoteResponse;
import com.github.sydowma.memquorum.grpc.AppendEntriesRequest;
import com.github.sydowma.memquorum.grpc.AppendEntriesResponse;
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
    public com.github.sydowma.engine.raft.RequestVoteResponse requestVote(String target, com.github.sydowma.engine.raft.RequestVoteRequest request) throws Exception {
        ManagedChannel channel = getOrCreateChannel(target);
        if (channel == null) {
            throw new Exception("Cannot create channel to node: " + target);
        }
        
        try {
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel);
            
            // Convert from internal format to protobuf format
            RequestVoteRequest protoRequest = RequestVoteRequest.newBuilder()
                .setTerm(request.getTerm())
                .setCandidateId(request.getCandidateId())
                .setLastLogIndex(request.getLastLogIndex())
                .setLastLogTerm(request.getLastLogTerm())
                .build();
            
            RequestVoteResponse protoResponse = stub.requestVote(protoRequest);
            
            // Convert back to internal format
            return new com.github.sydowma.engine.raft.RequestVoteResponse(
                protoResponse.getTerm(), 
                protoResponse.getVoteGranted()
            );
            
        } catch (StatusRuntimeException e) {
            logger.warn("RequestVote failed to {}: {}", target, e.getStatus());
            throw new Exception("RequestVote RPC failed", e);
        }
    }
    
    @Override
    public com.github.sydowma.engine.raft.AppendEntriesResponse appendEntries(String target, com.github.sydowma.engine.raft.AppendEntriesRequest request) throws Exception {
        ManagedChannel channel = getOrCreateChannel(target);
        if (channel == null) {
            throw new Exception("Cannot create channel to node: " + target);
        }
        
        try {
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel);
            
            // Convert from internal format to protobuf format
            AppendEntriesRequest.Builder protoRequestBuilder = AppendEntriesRequest.newBuilder()
                .setTerm(request.term())
                .setLeaderId(request.leaderId())
                .setPrevLogIndex(request.prevLogIndex())
                .setPrevLogTerm(request.prevLogTerm())
                .setLeaderCommit(request.leaderCommit());
            
            // Convert entries if any
            for (com.github.sydowma.engine.raft.LogEntry entry : request.entries()) {
                com.github.sydowma.memquorum.grpc.LogEntry protoEntry = 
                    com.github.sydowma.memquorum.grpc.LogEntry.newBuilder()
                        .setTerm(entry.getTerm())
                        .setIndex(entry.getIndex())
                        .setData(com.google.protobuf.ByteString.copyFrom(entry.getData()))
                        .build();
                protoRequestBuilder.addEntries(protoEntry);
            }
            
            AppendEntriesResponse protoResponse = stub.appendEntries(protoRequestBuilder.build());
            
            // Convert back to internal format
            return new com.github.sydowma.engine.raft.AppendEntriesResponse(
                protoResponse.getTerm(), 
                protoResponse.getSuccess()
            );
            
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
    
}
