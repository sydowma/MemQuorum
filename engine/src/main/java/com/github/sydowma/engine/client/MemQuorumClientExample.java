package com.github.sydowma.engine.client;

import com.github.sydowma.memquorum.grpc.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Example client demonstrating how to use MemQuorum service
 * 
 * This example shows:
 * - Connecting to MemQuorum cluster
 * - Storing data with user-based sharding
 * - Reading data back
 * - Proper resource cleanup
 */
public class MemQuorumClientExample {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumClientExample.class);
    
    private final ManagedChannel channel;
    private final PartitionServiceGrpc.PartitionServiceBlockingStub stub;
    
    public MemQuorumClientExample(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .maxInboundMessageSize(4 * 1024 * 1024) // 4MB
            .build();
            
        this.stub = PartitionServiceGrpc.newBlockingStub(channel);
        
        logger.info("Connected to MemQuorum at {}:{}", host, port);
    }
    
    /**
     * Store user data with automatic sharding
     */
    public void storeUserData(String userId, String key, String data) {
        try {
            // Create entry with user-based key for automatic sharding
            String fullKey = "user:" + userId + ":" + key;
            
            Entry entry = Entry.newBuilder()
                .setKey(fullKey)
                .setValue(ByteString.copyFromUtf8(data))
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            ProduceRequest request = ProduceRequest.newBuilder()
                .setTopic("user-data")
                .setPartition(-1)  // Auto-shard based on key
                .setEntry(entry)
                .build();
            
            ProduceResponse response = stub.produce(request);
            
            logger.info("Stored data for user {} at offset: {}", userId, response.getOffset());
            
        } catch (Exception e) {
            logger.error("Failed to store data for user: " + userId, e);
        }
    }
    
    /**
     * Read user data
     */
    public String readUserData(String userId, String key, int maxEntries) {
        try {
            ReadRequest request = ReadRequest.newBuilder()
                .setTopic("user-data")
                .setPartition(-1)  // Auto-route
                .setOffset(0)
                .setCount(maxEntries)
                .build();
            
            ReadResponse response = stub.read(request);
            
            String targetKey = "user:" + userId + ":" + key;
            
            for (Entry entry : response.getEntriesList()) {
                if (entry.getKey().equals(targetKey)) {
                    String data = entry.getValue().toStringUtf8();
                    logger.info("Found data for user {}: {}", userId, data);
                    return data;
                }
            }
            
            logger.info("No data found for user {} with key {}", userId, key);
            return null;
            
        } catch (Exception e) {
            logger.error("Failed to read data for user: " + userId, e);
            return null;
        }
    }
    
    /**
     * Demonstrate batch operations
     */
    public void batchOperations() {
        logger.info("Starting batch operations example...");
        
        // Store data for multiple users
        String[] users = {"alice", "bob", "charlie", "diana", "eve"};
        String[] dataTypes = {"profile", "session", "preferences"};
        
        for (String user : users) {
            for (String dataType : dataTypes) {
                String data = String.format("{\"%s_data\": \"value_for_%s\"}", dataType, user);
                storeUserData(user, dataType, data);
            }
        }
        
        // Read back some data
        for (String user : users) {
            String profileData = readUserData(user, "profile", 100);
            if (profileData != null) {
                logger.info("Retrieved profile for {}: {}", user, profileData);
            }
        }
        
        logger.info("Batch operations completed");
    }
    
    /**
     * Simulate real-time user activity
     */
    public void simulateUserActivity() {
        logger.info("Simulating user activity...");
        
        for (int i = 0; i < 50; i++) {
            String userId = "user" + (i % 10); // 10 different users
            String sessionId = "session_" + System.currentTimeMillis();
            
            // Store session data
            String sessionData = String.format("{\"session_id\": \"%s\", \"timestamp\": %d, \"activity\": \"action_%d\"}", 
                sessionId, System.currentTimeMillis(), i);
            
            storeUserData(userId, "current_session", sessionData);
            
            // Simulate some delay
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        logger.info("User activity simulation completed");
    }
    
    /**
     * Test error handling
     */
    public void testErrorHandling() {
        logger.info("Testing error handling...");
        
        try {
            // Try to read from a very high offset
            ReadRequest request = ReadRequest.newBuilder()
                .setTopic("nonexistent-topic")
                .setPartition(999)
                .setOffset(999999)
                .setCount(10)
                .build();
            
            ReadResponse response = stub.read(request);
            logger.info("Read response: {} entries", response.getEntriesCount());
            
        } catch (Exception e) {
            logger.info("Expected error caught: {}", e.getMessage());
        }
    }
    
    /**
     * Clean up resources
     */
    public void shutdown() {
        try {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
            logger.info("Client connection closed");
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        
        MemQuorumClientExample client = new MemQuorumClientExample(host, port);
        
        try {
            logger.info("=== MemQuorum Client Example ===");
            
            // Basic operations
            client.storeUserData("john", "profile", "{\"name\": \"John Doe\", \"email\": \"john@example.com\"}");
            client.storeUserData("jane", "profile", "{\"name\": \"Jane Smith\", \"email\": \"jane@example.com\"}");
            
            String johnProfile = client.readUserData("john", "profile", 10);
            String janeProfile = client.readUserData("jane", "profile", 10);
            
            // Batch operations
            client.batchOperations();
            
            // Real-time activity simulation
            client.simulateUserActivity();
            
            // Error handling
            client.testErrorHandling();
            
            logger.info("=== Example completed successfully ===");
            
        } catch (Exception e) {
            logger.error("Example failed", e);
        } finally {
            client.shutdown();
        }
    }
}

/**
 * Usage examples:
 * 
 * 1. Basic usage:
 *    java -cp target/classes com.github.sydowma.memquorum.client.MemQuorumClientExample
 * 
 * 2. Connect to specific host:
 *    java -cp target/classes com.github.sydowma.memquorum.client.MemQuorumClientExample memquorum-node1 8080
 * 
 * 3. Integration with load balancer:
 *    java -cp target/classes com.github.sydowma.memquorum.client.MemQuorumClientExample lb.example.com 80
 */