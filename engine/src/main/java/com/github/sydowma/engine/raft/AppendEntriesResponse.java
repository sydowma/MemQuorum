package com.github.sydowma.engine.raft;

import java.io.Serializable;

/**
 * AppendEntries RPC响应
 *
 * @param term    当前term，用于Leader更新自己
 * @param success 如果Follower包含prevLogIndex和prevLogTerm匹配的条目则为true
 */
public record AppendEntriesResponse(long term, boolean success) implements Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
        return String.format("AppendEntriesResponse{term=%d, success=%s}", term, success);
    }
}
