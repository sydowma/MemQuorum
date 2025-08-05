package com.github.sydowma.memquorum.raft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raft协议实现
 * 包含Leader选举、日志复制、状态一致性等核心功能
 */
public class RaftNode {
    private static final Logger logger = LoggerFactory.getLogger(RaftNode.class);
    
    // Raft状态
    private NodeState state = NodeState.FOLLOWER;
    private String currentLeader = null;
    private final AtomicLong currentTerm = new AtomicLong(0);
    private String votedFor = null;
    
    // 节点信息
    private final String nodeId;
    private final List<String> cluster = new CopyOnWriteArrayList<>();
    
    // 日志相关
    private final List<LogEntry> log = Collections.synchronizedList(new ArrayList<>());
    private long commitIndex = 0;
    private long lastApplied = 0;
    
    // Leader状态（仅Leader使用）
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();
    
    // 定时器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;
    
    // 选举超时配置（毫秒）
    private final int electionTimeoutMin = 150;
    private final int electionTimeoutMax = 300;
    private final int heartbeatInterval = 50;
    
    // RPC客户端（用于节点间通信）
    private final RaftRpcClient rpcClient;
    
    // 状态机（应用层）
    private final StateMachine stateMachine;
    
    public RaftNode(String nodeId, List<String> initialCluster, RaftRpcClient rpcClient, StateMachine stateMachine) {
        this.nodeId = nodeId;
        this.cluster.addAll(initialCluster);
        this.rpcClient = rpcClient;
        this.stateMachine = stateMachine;
        
        // 初始化索引
        initializeIndices();
        
        logger.info("Raft node {} initialized with cluster: {}", nodeId, cluster);
    }
    
    /**
     * 启动Raft节点
     */
    public void start() {
        logger.info("Starting Raft node {}", nodeId);
        startElectionTimer();
    }
    
    /**
     * 停止Raft节点
     */
    public void stop() {
        logger.info("Stopping Raft node {}", nodeId);
        
        if (electionTimer != null) {
            electionTimer.cancel(true);
        }
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(true);
        }
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 客户端请求：添加日志条目
     */
    public CompletableFuture<Boolean> appendEntry(byte[] data) {
        if (state != NodeState.LEADER) {
            return CompletableFuture.completedFuture(false);
        }
        
        LogEntry entry = new LogEntry(currentTerm.get(), log.size(), data);
        log.add(entry);
        
        logger.debug("Leader {} appended log entry at index {}", nodeId, entry.getIndex());
        
        // 并行发送到所有Followers
        return replicateLogEntry(entry);
    }
    
    /**
     * 处理RequestVote RPC
     */
    public RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
        synchronized (this) {
            long term = request.getTerm();
            String candidateId = request.getCandidateId();
            long lastLogIndex = request.getLastLogIndex();
            long lastLogTerm = request.getLastLogTerm();
            
            // 如果请求的term更大，更新当前term并转为Follower
            if (term > currentTerm.get()) {
                currentTerm.set(term);
                votedFor = null;
                becomeFollower();
            }
            
            boolean voteGranted = false;
            
            // 投票条件：
            // 1. term >= currentTerm
            // 2. 还没有投票或已经投给了这个候选人
            // 3. 候选人的日志至少和自己一样新
            if (term >= currentTerm.get() && 
                (votedFor == null || votedFor.equals(candidateId)) &&
                isLogUpToDate(lastLogIndex, lastLogTerm)) {
                
                voteGranted = true;
                votedFor = candidateId;
                resetElectionTimer();
                
                logger.info("Node {} voted for {} in term {}", nodeId, candidateId, term);
            }
            
            return new RequestVoteResponse(currentTerm.get(), voteGranted);
        }
    }
    
    /**
     * 处理AppendEntries RPC
     */
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        synchronized (this) {
            long term = request.term();
            String leaderId = request.leaderId();
            long prevLogIndex = request.prevLogIndex();
            long prevLogTerm = request.prevLogTerm();
            List<LogEntry> entries = request.entries();
            long leaderCommit = request.leaderCommit();
            
            // 如果请求的term更大，更新term并转为Follower
            if (term > currentTerm.get()) {
                currentTerm.set(term);
                votedFor = null;
                becomeFollower();
            }
            
            // 拒绝来自较旧term的请求
            if (term < currentTerm.get()) {
                return new AppendEntriesResponse(currentTerm.get(), false);
            }
            
            // 重置选举定时器（收到有效的心跳）
            resetElectionTimer();
            currentLeader = leaderId;
            
            // 检查日志一致性
            if (prevLogIndex > 0 && 
                (log.size() <= prevLogIndex || 
                 log.get((int) prevLogIndex - 1).getTerm() != prevLogTerm)) {
                logger.debug("Log inconsistency at index {} for node {}", prevLogIndex, nodeId);
                return new AppendEntriesResponse(currentTerm.get(), false);
            }
            
            // 添加新条目
            if (!entries.isEmpty()) {
                // 删除冲突的条目
                int index = (int) prevLogIndex;
                for (LogEntry entry : entries) {
                    if (index < log.size()) {
                        if (log.get(index).getTerm() != entry.getTerm()) {
                            // 删除这个位置及之后的所有条目
                            log.subList(index, log.size()).clear();
                            break;
                        }
                    }
                    index++;
                }
                
                // 添加新条目
                index = (int) prevLogIndex;
                for (LogEntry entry : entries) {
                    if (index >= log.size()) {
                        log.add(entry);
                    }
                    index++;
                }
                
                logger.debug("Node {} appended {} entries", nodeId, entries.size());
            }
            
            // 更新commitIndex
            if (leaderCommit > commitIndex) {
                commitIndex = Math.min(leaderCommit, log.size());
                applyLogEntries();
            }
            
            return new AppendEntriesResponse(currentTerm.get(), true);
        }
    }
    
    /**
     * 开始选举
     */
    private void startElection() {
        synchronized (this) {
            if (state == NodeState.LEADER) {
                return;
            }
            
            state = NodeState.CANDIDATE;
            currentTerm.incrementAndGet();
            votedFor = nodeId;
            resetElectionTimer();
            
            logger.info("Node {} starting election for term {}", nodeId, currentTerm.get());
            
            // 获取最后一条日志的信息
            long lastLogIndex = log.size();
            long lastLogTerm = lastLogIndex > 0 ? log.get((int) lastLogIndex - 1).getTerm() : 0;
            
            AtomicInteger votes = new AtomicInteger(1); // 自己的票
            int majority = cluster.size() / 2 + 1;
            
            // 并行向所有其他节点请求投票
            List<CompletableFuture<Void>> voteFutures = new ArrayList<>();
            
            for (String peer : cluster) {
                if (!peer.equals(nodeId)) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            RequestVoteRequest request = new RequestVoteRequest(
                                currentTerm.get(), nodeId, lastLogIndex, lastLogTerm);
                            RequestVoteResponse response = rpcClient.requestVote(peer, request);
                            
                            if (response.getTerm() > currentTerm.get()) {
                                currentTerm.set(response.getTerm());
                                becomeFollower();
                                return;
                            }
                            
                            if (response.isVoteGranted() && state == NodeState.CANDIDATE) {
                                int currentVotes = votes.incrementAndGet();
                                logger.debug("Node {} received vote from {}, total votes: {}", 
                                    nodeId, peer, currentVotes);
                                
                                if (currentVotes >= majority) {
                                    becomeLeader();
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to request vote from {}: {}", peer, e.getMessage());
                        }
                    });
                    voteFutures.add(future);
                }
            }
            
            // 等待投票结果或超时
            CompletableFuture.allOf(voteFutures.toArray(new CompletableFuture[0]))
                .orTimeout(electionTimeoutMax, TimeUnit.MILLISECONDS)
                .whenComplete((result, throwable) -> {
                    if (throwable != null && !(throwable instanceof TimeoutException)) {
                        logger.warn("Election completed with error", throwable);
                    }
                });
        }
    }
    
    /**
     * 成为Leader
     */
    private void becomeLeader() {
        if (state != NodeState.CANDIDATE) {
            return;
        }
        
        state = NodeState.LEADER;
        currentLeader = nodeId;
        
        // 初始化Leader状态
        initializeLeaderState();
        
        // 开始发送心跳
        startHeartbeat();
        
        logger.info("Node {} became leader for term {}", nodeId, currentTerm.get());
    }
    
    /**
     * 成为Follower
     */
    private void becomeFollower() {
        state = NodeState.FOLLOWER;
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(true);
        }
        resetElectionTimer();
    }
    
    /**
     * 初始化Leader状态
     */
    private void initializeLeaderState() {
        long nextIdx = log.size() + 1;
        for (String peer : cluster) {
            if (!peer.equals(nodeId)) {
                nextIndex.put(peer, nextIdx);
                matchIndex.put(peer, 0L);
            }
        }
    }
    
    /**
     * 开始发送心跳
     */
    private void startHeartbeat() {
        heartbeatTimer = scheduler.scheduleAtFixedRate(
            this::sendHeartbeat, 0, heartbeatInterval, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        if (state != NodeState.LEADER) {
            return;
        }
        
        for (String peer : cluster) {
            if (!peer.equals(nodeId)) {
                CompletableFuture.runAsync(() -> sendAppendEntries(peer));
            }
        }
    }
    
    /**
     * 复制日志条目
     */
    private CompletableFuture<Boolean> replicateLogEntry(LogEntry entry) {
        AtomicInteger successCount = new AtomicInteger(1); // Leader自己
        int majority = cluster.size() / 2 + 1;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        
        List<CompletableFuture<Void>> replicationFutures = new ArrayList<>();
        
        for (String peer : cluster) {
            if (!peer.equals(nodeId)) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    boolean success = sendAppendEntries(peer);
                    if (success) {
                        int count = successCount.incrementAndGet();
                        if (count >= majority && !result.isDone()) {
                            // 达到多数派，提交日志
                            commitIndex = entry.getIndex();
                            applyLogEntries();
                            result.complete(true);
                        }
                    }
                });
                replicationFutures.add(future);
            }
        }
        
        // 设置超时
        scheduler.schedule(() -> {
            if (!result.isDone()) {
                result.complete(false);
            }
        }, 5, TimeUnit.SECONDS);
        
        return result;
    }
    
    /**
     * 发送AppendEntries到指定节点
     */
    private boolean sendAppendEntries(String peer) {
        try {
            long nextIdx = nextIndex.getOrDefault(peer, (long) log.size() + 1);
            long prevLogIndex = nextIdx - 1;
            long prevLogTerm = 0;
            
            if (prevLogIndex > 0 && prevLogIndex <= log.size()) {
                prevLogTerm = log.get((int) prevLogIndex - 1).getTerm();
            }
            
            List<LogEntry> entries = new ArrayList<>();
            if (nextIdx <= log.size()) {
                entries.addAll(log.subList((int) nextIdx - 1, log.size()));
            }
            
            AppendEntriesRequest request = new AppendEntriesRequest(
                currentTerm.get(), nodeId, prevLogIndex, prevLogTerm, 
                entries, commitIndex);
            
            AppendEntriesResponse response = rpcClient.appendEntries(peer, request);
            
            if (response.term() > currentTerm.get()) {
                currentTerm.set(response.term());
                becomeFollower();
                return false;
            }
            
            if (response.success()) {
                // 更新nextIndex和matchIndex
                if (!entries.isEmpty()) {
                    nextIndex.put(peer, nextIdx + entries.size());
                    matchIndex.put(peer, nextIdx + entries.size() - 1);
                }
                return true;
            } else {
                // 日志不一致，减少nextIndex重试
                nextIndex.put(peer, Math.max(1, nextIdx - 1));
                return false;
            }
            
        } catch (Exception e) {
            logger.warn("Failed to send AppendEntries to {}: {}", peer, e.getMessage());
            return false;
        }
    }
    
    /**
     * 应用已提交的日志条目到状态机
     */
    private void applyLogEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            if (lastApplied <= log.size()) {
                LogEntry entry = log.get((int) lastApplied - 1);
                stateMachine.apply(entry);
                logger.debug("Applied log entry {} to state machine", lastApplied);
            }
        }
    }
    
    /**
     * 检查候选人的日志是否至少和自己一样新
     */
    private boolean isLogUpToDate(long lastLogIndex, long lastLogTerm) {
        if (log.isEmpty()) {
            return true;
        }
        
        LogEntry lastEntry = log.get(log.size() - 1);
        
        // 比较term，term更大的更新
        if (lastLogTerm > lastEntry.getTerm()) {
            return true;
        }
        
        // term相同，比较index，index更大的更新
        if (lastLogTerm == lastEntry.getTerm()) {
            return lastLogIndex >= lastEntry.getIndex();
        }
        
        return false;
    }
    
    /**
     * 重置选举定时器
     */
    private void resetElectionTimer() {
        if (electionTimer != null) {
            electionTimer.cancel(true);
        }
        startElectionTimer();
    }
    
    /**
     * 启动选举定时器
     */
    private void startElectionTimer() {
        if (state == NodeState.LEADER) {
            return;
        }
        
        int timeout = ThreadLocalRandom.current().nextInt(electionTimeoutMin, electionTimeoutMax);
        electionTimer = scheduler.schedule(this::startElection, timeout, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 初始化索引
     */
    private void initializeIndices() {
        for (String peer : cluster) {
            if (!peer.equals(nodeId)) {
                nextIndex.put(peer, 1L);
                matchIndex.put(peer, 0L);
            }
        }
    }
    
    // Getters
    public NodeState getState() { return state; }
    public long getCurrentTerm() { return currentTerm.get(); }
    public String getCurrentLeader() { return currentLeader; }
    public String getNodeId() { return nodeId; }
    public long getCommitIndex() { return commitIndex; }
    public int getLogSize() { return log.size(); }
    
    /**
     * 节点状态枚举
     */
    public enum NodeState {
        FOLLOWER, CANDIDATE, LEADER
    }
}
