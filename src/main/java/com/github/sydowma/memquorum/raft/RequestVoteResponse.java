package com.github.sydowma.memquorum.raft;

import java.io.Serializable;

/**
 * RequestVote RPC响应
 */
public class RequestVoteResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final long term;            // 当前term，用于候选人更新自己
    private final boolean voteGranted;  // 是否投票给候选人
    
    public RequestVoteResponse(long term, boolean voteGranted) {
        this.term = term;
        this.voteGranted = voteGranted;
    }
    
    public long getTerm() {
        return term;
    }
    
    public boolean isVoteGranted() {
        return voteGranted;
    }
    
    @Override
    public String toString() {
        return String.format("RequestVoteResponse{term=%d, voteGranted=%s}", term, voteGranted);
    }
}
