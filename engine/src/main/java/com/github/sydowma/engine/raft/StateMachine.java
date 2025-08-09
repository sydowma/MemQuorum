package com.github.sydowma.engine.raft;

/**
 * 状态机接口
 * 应用层需要实现这个接口来处理已提交的日志条目
 */
public interface StateMachine {
    
    /**
     * 应用日志条目到状态机
     * @param entry 已提交的日志条目
     */
    void apply(LogEntry entry);
    
    /**
     * 创建快照
     * @return 快照数据
     */
    byte[] createSnapshot();
    
    /**
     * 从快照恢复状态
     * @param snapshot 快照数据
     */
    void restoreFromSnapshot(byte[] snapshot);
    
    /**
     * 获取最后应用的日志索引
     * @return 最后应用的索引
     */
    long getLastAppliedIndex();
}
