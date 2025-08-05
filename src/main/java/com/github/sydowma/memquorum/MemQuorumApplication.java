package com.github.sydowma.memquorum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Main MemQuorum application
 * 
 * This service provides:
 * - Distributed memory storage with user-based sharding
 * - Raft consensus for high availability and data consistency
 * - Nacos service discovery and configuration management
 * - gRPC API for external access
 * - Kafka input handling for stream processing
 * 
 * Usage:
 * - For API services: Send requests to gRPC endpoints with user-based keys
 * - For Kafka: Publish messages with user IDs in keys for automatic sharding
 * - Keys format: "user:{userId}:{key}" or just "{userId}" for automatic routing
 */
@SpringBootApplication
public class MemQuorumApplication implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumApplication.class);
    
    @Autowired
    private MemQuorumService memQuorumService;

    public static void main(String[] args) {
        try {
            logger.info("Starting MemQuorum distributed memory service...");
            SpringApplication app = new SpringApplication(MemQuorumApplication.class);
            app.run(args);
        } catch (Exception e) {
            logger.error("Failed to start MemQuorum application", e);
            System.exit(1);
        }
    }
    
    @Override
    public void run(String... args) throws Exception {
        logger.info("MemQuorum application startup completed");
        logger.info("Service Info:");
        logger.info("  Node ID: {}", memQuorumService.getNodeId());
        logger.info("  Address: {}", memQuorumService.getNodeAddress());
        logger.info("  Total Shards: {}", memQuorumService.getTotalShards());
        logger.info("  Is Leader: {}", memQuorumService.isLeader());
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down MemQuorum application...");
            try {
                memQuorumService.stop();
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));
        
        // Keep the application running
        // The gRPC server and other services are running in their own threads
        logger.info("MemQuorum service is now ready to accept requests");
    }
    
    /**
     * Configuration for development/testing
     */
    @Bean
    public String applicationInfo() {
        return "MemQuorum Distributed Memory Service v1.0";
    }
}
