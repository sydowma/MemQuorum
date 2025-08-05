package com.github.sydowma.memquorum;

import com.github.sydowma.memquorum.grpc.Entry;
import com.google.protobuf.ByteString;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka input handler for consuming messages and feeding them into MemQuorum
 * Handles automatic sharding based on user ID extraction from message keys
 */
public class KafkaInputHandler {
    private static final Logger logger = LoggerFactory.getLogger(KafkaInputHandler.class);
    
    private final KafkaConsumer<String, byte[]> consumer;
    private final MemQuorumService memQuorumService;
    private final String topic;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public KafkaInputHandler(MemQuorumService memQuorumService, String kafkaBootstrap, String groupId, String topic) {
        this.memQuorumService = memQuorumService;
        this.topic = topic;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kafka-consumer-" + topic);
            t.setDaemon(true);
            return t;
        });
        
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        props.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        
        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        
        logger.info("Kafka input handler initialized for topic: {} with group: {}", topic, groupId);
    }

    public void startConsuming() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting Kafka consumer for topic: {}", topic);
            
            executor.submit(() -> {
                try {
                    while (running.get()) {
                        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                        
                        for (ConsumerRecord<String, byte[]> record : records) {
                            try {
                                processRecord(record);
                            } catch (Exception e) {
                                logger.error("Failed to process Kafka record: partition={}, offset={}, key={}", 
                                    record.partition(), record.offset(), record.key(), e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Kafka consumer error", e);
                } finally {
                    try {
                        consumer.close();
                    } catch (Exception e) {
                        logger.warn("Error closing Kafka consumer", e);
                    }
                    logger.info("Kafka consumer stopped for topic: {}", topic);
                }
            });
        } else {
            logger.warn("Kafka consumer is already running");
        }
    }
    
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping Kafka consumer for topic: {}", topic);
            
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Process a single Kafka record
     */
    private void processRecord(ConsumerRecord<String, byte[]> record) {
        logger.debug("Processing Kafka record: partition={}, offset={}, key={}", 
            record.partition(), record.offset(), record.key());
        
        // Extract user ID from key for sharding
        String key = record.key() != null ? record.key() : "unknown";
        String userId = extractUserIdFromKey(key);
        int shard = memQuorumService.getShardForUser(userId);
        
        // Check if this node is responsible for the shard
        if (!memQuorumService.isResponsibleForShard(shard)) {
            logger.debug("Skipping record for shard {} - not responsible. Key: {}", shard, key);
            return;
        }
        
        // Get the partition for this shard
        Partition partition = memQuorumService.getPartitionForShard(shard);
        if (partition == null) {
            logger.warn("No partition found for shard: {} (key: {})", shard, key);
            return;
        }
        
        // Create MemQuorum entry
        Entry.Builder entryBuilder = Entry.newBuilder()
            .setKey(key)
            .setValue(ByteString.copyFrom(record.value() != null ? record.value() : new byte[0]))
            .setTimestamp(record.timestamp());
        
        Entry entry = entryBuilder.build();
        
        try {
            // Only leader should handle writes
            if (memQuorumService.isLeader()) {
                // Append through Raft for consensus
                byte[] data = entry.toByteArray();
                memQuorumService.getRaftNode().appendEntry(data)
                    .thenAccept(success -> {
                        if (success) {
                            logger.debug("Successfully appended Kafka record through Raft: key={}, shard={}", 
                                key, shard);
                        } else {
                            logger.warn("Failed to append Kafka record through Raft: key={}, shard={}", 
                                key, shard);
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.error("Error appending Kafka record through Raft: key={}, shard={}", 
                            key, shard, throwable);
                        return null;
                    });
            } else {
                // Forward to leader or skip
                logger.debug("Not leader - skipping Kafka record: key={}, shard={}", key, shard);
            }
            
        } catch (Exception e) {
            logger.error("Failed to append Kafka record to MemQuorum: key={}, shard={}", 
                key, shard, e);
        }
    }
    
    /**
     * Extract user ID from Kafka message key for sharding
     * Expected formats:
     * - "user:{userId}:{key}" -> userId
     * - "{userId}" -> userId
     * - fallback to whole key
     */
    private String extractUserIdFromKey(String key) {
        if (key == null || key.isEmpty()) {
            return "default";
        }
        
        if (key.startsWith("user:")) {
            String[] parts = key.split(":", 3);
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                return parts[1];
            }
        }
        
        // Try to extract first part if contains colon
        int colonIndex = key.indexOf(':');
        if (colonIndex > 0) {
            return key.substring(0, colonIndex);
        }
        
        // Fallback to using the whole key
        return key;
    }
    
    /**
     * Get current consumer status
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get consumer metrics (if needed for monitoring)
     */
    public String getStatus() {
        return String.format("KafkaInputHandler{topic='%s', running=%s}", 
            topic, running.get());
    }
}
