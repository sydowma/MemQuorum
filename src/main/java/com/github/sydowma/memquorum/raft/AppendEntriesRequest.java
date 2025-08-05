package com.github.sydowma.memquorum.raft;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AppendEntries RPC请求
 *
 * @param term         Leader的term
 * @param leaderId     Leader的ID
 * @param prevLogIndex 新条目前一条日志的索引
 * @param prevLogTerm  新条目前一条日志的term
 * @param entries      要存储的日志条目（心跳时为空）
 * @param leaderCommit Leader的commitIndex
 */
public record AppendEntriesRequest(long term, String leaderId, long prevLogIndex, long prevLogTerm,
                                   List<LogEntry> entries, long leaderCommit) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public boolean isHeartbeat() {
        return entries == null || entries.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("AppendEntriesRequest{term=%d, leaderId='%s', prevLogIndex=%d, " +
                        "prevLogTerm=%d, entriesCount=%d, leaderCommit=%d}",
                term, leaderId, prevLogIndex, prevLogTerm,
                entries != null ? entries.size() : 0, leaderCommit);
    }
}
