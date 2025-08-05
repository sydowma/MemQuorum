package com.github.sydowma.memquorum.client;

import java.io.Serializable;

/**
 * 集群信息
 */
public class ClusterInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String leaderId;
    private final String currentNodeRole;
    private final int totalNodes;
    private final int healthyNodes;
    private final long currentTerm;
    private final long commitIndex;
    
    public ClusterInfo(String leaderId, String currentNodeRole, int totalNodes, int healthyNodes) {
        this(leaderId, currentNodeRole, totalNodes, healthyNodes, 0, 0);
    }
    
    public ClusterInfo(String leaderId, String currentNodeRole, int totalNodes, 
                      int healthyNodes, long currentTerm, long commitIndex) {
        this.leaderId = leaderId;
        this.currentNodeRole = currentNodeRole;
        this.totalNodes = totalNodes;
        this.healthyNodes = healthyNodes;
        this.currentTerm = currentTerm;
        this.commitIndex = commitIndex;
    }
    
    public String getLeaderId() { return leaderId; }
    public String getCurrentNodeRole() { return currentNodeRole; }
    public int getTotalNodes() { return totalNodes; }
    public int getHealthyNodes() { return healthyNodes; }
    public long getCurrentTerm() { return currentTerm; }
    public long getCommitIndex() { return commitIndex; }
    
    public boolean isLeaderAvailable() {
        return leaderId != null && !leaderId.isEmpty();
    }
    
    public boolean isClusterHealthy() {
        return healthyNodes > totalNodes / 2;
    }
    
    @Override
    public String toString() {
        return String.format("ClusterInfo{leaderId='%s', role='%s', nodes=%d/%d, " +
            "term=%d, commitIndex=%d, healthy=%s}", 
            leaderId, currentNodeRole, healthyNodes, totalNodes, 
            currentTerm, commitIndex, isClusterHealthy());
    }
}
