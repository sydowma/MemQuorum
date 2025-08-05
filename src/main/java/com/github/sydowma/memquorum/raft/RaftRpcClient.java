package com.github.sydowma.memquorum.raft;

/**
 * Raft RPC客户端接口
 * 用于节点间通信
 */
public interface RaftRpcClient {
    
    /**
     * 发送RequestVote RPC
     * @param target 目标节点ID
     * @param request 请求
     * @return 响应
     * @throws Exception 通信异常
     */
    RequestVoteResponse requestVote(String target, RequestVoteRequest request) throws Exception;
    
    /**
     * 发送AppendEntries RPC
     * @param target 目标节点ID
     * @param request 请求
     * @return 响应
     * @throws Exception 通信异常
     */
    AppendEntriesResponse appendEntries(String target, AppendEntriesRequest request) throws Exception;
    
    /**
     * 检查连接状态
     * @param target 目标节点ID
     * @return 是否可达
     */
    boolean isReachable(String target);
    
    /**
     * 关闭客户端
     */
    void close();
}
