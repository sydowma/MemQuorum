package com.github.sydowma.engine.raft;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Raft日志条目
 */
public class LogEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final long term;        // 创建这个条目时的term
    private final long index;       // 在日志中的位置
    private final byte[] data;      // 实际的数据
    private final long timestamp;   // 创建时间戳
    
    public LogEntry(long term, long index, byte[] data) {
        this.term = term;
        this.index = index;
        this.data = data != null ? data.clone() : new byte[0];
        this.timestamp = System.currentTimeMillis();
    }
    
    public long getTerm() {
        return term;
    }
    
    public long getIndex() {
        return index;
    }
    
    public byte[] getData() {
        return data.clone();
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        LogEntry logEntry = (LogEntry) obj;
        return term == logEntry.term &&
               index == logEntry.index &&
               Arrays.equals(data, logEntry.data);
    }
    
    @Override
    public int hashCode() {
        int result = Long.hashCode(term);
        result = 31 * result + Long.hashCode(index);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
    
    @Override
    public String toString() {
        return String.format("LogEntry{term=%d, index=%d, dataSize=%d, timestamp=%d}", 
            term, index, data.length, timestamp);
    }
}
