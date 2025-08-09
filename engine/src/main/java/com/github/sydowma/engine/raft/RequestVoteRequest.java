package com.github.sydowma.engine.raft;

import java.io.Serializable;

/**
 * RequestVote RPC请求
 */
public class RequestVoteRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final long term;            // 候选人的term
    private final String candidateId;   // 候选人ID
    private final long lastLogIndex;    // 候选人最后一条日志的索引
    private final long lastLogTerm;     // 候选人最后一条日志的term
    
    public RequestVoteRequest(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
        this.term = term;
        this.candidateId = candidateId;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
    }
    
    public long getTerm() {
        return term;
    }
    
    public String getCandidateId() {
        return candidateId;
    }
    
    public long getLastLogIndex() {
        return lastLogIndex;
    }
    
    public long getLastLogTerm() {
        return lastLogTerm;
    }
    
    @Override
    public String toString() {
        return String.format("RequestVoteRequest{term=%d, candidateId='%s', lastLogIndex=%d, lastLogTerm=%d}", 
            term, candidateId, lastLogIndex, lastLogTerm);
    }
}
