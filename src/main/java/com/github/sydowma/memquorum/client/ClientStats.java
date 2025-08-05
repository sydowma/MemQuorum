package com.github.sydowma.memquorum.client;

import java.io.Serializable;

/**
 * 客户端统计信息
 */
public class ClientStats implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final int totalNodes;
    private final int healthyNodes;
    private final long timestamp;
    
    public ClientStats(int totalNodes, int healthyNodes) {
        this.totalNodes = totalNodes;
        this.healthyNodes = healthyNodes;
        this.timestamp = System.currentTimeMillis();
    }
    
    public int getTotalNodes() { return totalNodes; }
    public int getHealthyNodes() { return healthyNodes; }
    public int getUnhealthyNodes() { return totalNodes - healthyNodes; }
    public long getTimestamp() { return timestamp; }
    
    public double getHealthyRatio() {
        return totalNodes > 0 ? (double) healthyNodes / totalNodes : 0.0;
    }
    
    public boolean isAllNodesHealthy() {
        return healthyNodes == totalNodes;
    }
    
    public boolean hasAnyHealthyNode() {
        return healthyNodes > 0;
    }
    
    @Override
    public String toString() {
        return String.format("ClientStats{nodes=%d/%d, healthyRatio=%.2f, timestamp=%d}", 
            healthyNodes, totalNodes, getHealthyRatio(), timestamp);
    }
}
