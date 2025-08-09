package com.github.sydowma.engine.raft;

import com.github.sydowma.memquorum.grpc.RaftServiceGrpc;
import com.github.sydowma.memquorum.grpc.RequestVoteRequest;
import com.github.sydowma.memquorum.grpc.RequestVoteResponse;
import com.github.sydowma.memquorum.grpc.AppendEntriesRequest;
import com.github.sydowma.memquorum.grpc.AppendEntriesResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * gRPC Raft service implementation
 */
public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(RaftServiceImpl.class);
    
    private final RaftNode raftNode;
    
    public RaftServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }
    
    @Override
    public void requestVote(RequestVoteRequest request, StreamObserver<RequestVoteResponse> responseObserver) {
        try {
            logger.debug("Received RequestVote from candidate {} for term {}", 
                request.getCandidateId(), request.getTerm());
            
            // Convert protobuf request to internal format
            com.github.sydowma.engine.raft.RequestVoteRequest internalRequest = 
                new com.github.sydowma.engine.raft.RequestVoteRequest(
                    request.getTerm(),
                    request.getCandidateId(),
                    request.getLastLogIndex(),
                    request.getLastLogTerm()
                );
            
            // Process request using Raft node
            com.github.sydowma.engine.raft.RequestVoteResponse internalResponse = 
                raftNode.handleRequestVote(internalRequest);
            
            // Convert back to protobuf format
            RequestVoteResponse response = RequestVoteResponse.newBuilder()
                .setTerm(internalResponse.getTerm())
                .setVoteGranted(internalResponse.isVoteGranted())
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            logger.debug("Responded to RequestVote from {} - vote granted: {}", 
                request.getCandidateId(), internalResponse.isVoteGranted());
                
        } catch (Exception e) {
            logger.error("Error processing RequestVote", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        try {
            logger.debug("Received AppendEntries from leader {} for term {} with {} entries", 
                request.getLeaderId(), request.getTerm(), request.getEntriesCount());
            
            // Convert protobuf entries to internal format
            List<com.github.sydowma.engine.raft.LogEntry> internalEntries = new ArrayList<>();
            for (com.github.sydowma.memquorum.grpc.LogEntry protoEntry : request.getEntriesList()) {
                com.github.sydowma.engine.raft.LogEntry internalEntry = 
                    new com.github.sydowma.engine.raft.LogEntry(
                        protoEntry.getTerm(),
                        protoEntry.getIndex(),
                        protoEntry.getData().toByteArray()
                    );
                internalEntries.add(internalEntry);
            }
            
            // Convert protobuf request to internal format
            com.github.sydowma.engine.raft.AppendEntriesRequest internalRequest = 
                new com.github.sydowma.engine.raft.AppendEntriesRequest(
                    request.getTerm(),
                    request.getLeaderId(),
                    request.getPrevLogIndex(),
                    request.getPrevLogTerm(),
                    internalEntries,
                    request.getLeaderCommit()
                );
            
            // Process request using Raft node
            com.github.sydowma.engine.raft.AppendEntriesResponse internalResponse = 
                raftNode.handleAppendEntries(internalRequest);
            
            // Convert back to protobuf format
            AppendEntriesResponse response = AppendEntriesResponse.newBuilder()
                .setTerm(internalResponse.term())
                .setSuccess(internalResponse.success())
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            logger.debug("Responded to AppendEntries from {} - success: {}", 
                request.getLeaderId(), internalResponse.success());
                
        } catch (Exception e) {
            logger.error("Error processing AppendEntries", e);
            responseObserver.onError(e);
        }
    }
}