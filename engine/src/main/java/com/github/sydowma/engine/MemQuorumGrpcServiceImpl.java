package com.github.sydowma.engine;

import com.github.sydowma.memquorum.grpc.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemQuorumGrpcServiceImpl extends MemQuorumServiceGrpc.MemQuorumServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumGrpcServiceImpl.class);
    
    private final MemQuorumService memQuorumService;
    
    public MemQuorumGrpcServiceImpl(MemQuorumService memQuorumService) {
        this.memQuorumService = memQuorumService;
    }
    
    @Override
    public void set(SetRequest request, StreamObserver<SetResponse> responseObserver) {
        try {
            String userId = request.getUserId();
            String key = request.getKey();
            String value = request.getValue();
            
            logger.debug("Setting value for user {} key {}", userId, key);
            
            memQuorumService.set(userId, key, value.getBytes());
            
            SetResponse response = SetResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Value set successfully")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error setting value for user {} key {}", request.getUserId(), request.getKey(), e);
            
            SetResponse response = SetResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Error: " + e.getMessage())
                    .build();
                    
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        try {
            String userId = request.getUserId();
            String key = request.getKey();
            
            logger.debug("Getting value for user {} key {}", userId, key);
            
            byte[] value = memQuorumService.get(userId, key);
            
            GetResponse.Builder responseBuilder = GetResponse.newBuilder();
            
            if (value != null) {
                responseBuilder.setFound(true).setValue(new String(value));
            } else {
                responseBuilder.setFound(false);
            }
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error getting value for user {} key {}", request.getUserId(), request.getKey(), e);
            
            GetResponse response = GetResponse.newBuilder()
                    .setFound(false)
                    .build();
                    
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        try {
            String userId = request.getUserId();
            String key = request.getKey();
            
            logger.debug("Deleting value for user {} key {}", userId, key);
            
            boolean success = memQuorumService.delete(userId, key);
            
            DeleteResponse response = DeleteResponse.newBuilder()
                    .setSuccess(success)
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error deleting value for user {} key {}", request.getUserId(), request.getKey(), e);
            
            DeleteResponse response = DeleteResponse.newBuilder()
                    .setSuccess(false)
                    .build();
                    
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void list(ListRequest request, StreamObserver<ListResponse> responseObserver) {
        try {
            String userId = request.getUserId();
            String pattern = request.getPattern();
            
            logger.debug("Listing keys for user {} with pattern {}", userId, pattern);
            
            String[] keys = memQuorumService.list(userId, pattern);
            
            ListResponse response = ListResponse.newBuilder()
                    .addAllKeys(java.util.Arrays.asList(keys))
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error listing keys for user {}", request.getUserId(), e);
            
            ListResponse response = ListResponse.newBuilder().build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}