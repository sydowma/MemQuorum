package com.github.sydowma.memquorum.raft;

import com.github.sydowma.memquorum.grpc.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Memory-based state machine implementation for Raft
 * Stores key-value pairs in memory and applies log entries
 */
public class MemoryStateMachine implements StateMachine {
    private static final Logger logger = LoggerFactory.getLogger(MemoryStateMachine.class);
    
    // In-memory storage
    private final ConcurrentMap<String, byte[]> storage = new ConcurrentHashMap<>();
    
    // Metrics
    private long appliedIndex = 0;
    private long totalOperations = 0;
    
    @Override
    public void apply(LogEntry logEntry) {
        try {
            logger.debug("Applying log entry at index: {}", logEntry.getIndex());
            
            // Deserialize the data back to Entry
            byte[] data = logEntry.getData();
            Entry entry = Entry.parseFrom(data);
            
            // Apply the operation
            String key = entry.getKey();
            byte[] value = entry.getValue().toByteArray();
            
            if (value.length == 0) {
                // Empty value means deletion
                storage.remove(key);
                logger.debug("Deleted key: {}", key);
            } else {
                // Store the value
                storage.put(key, value);
                logger.debug("Stored key: {} with {} bytes", key, value.length);
            }
            
            // Update metrics
            appliedIndex = logEntry.getIndex();
            totalOperations++;
            
            if (totalOperations % 1000 == 0) {
                logger.info("Applied {} operations, current storage size: {}", 
                    totalOperations, storage.size());
            }
            
        } catch (Exception e) {
            logger.error("Failed to apply log entry at index: {}", logEntry.getIndex(), e);
            throw new RuntimeException("State machine apply failed", e);
        }
    }
    
    @Override
    public long getLastAppliedIndex() {
        return appliedIndex;
    }
    
    /**
     * Query operation (not part of StateMachine interface)
     */
    public byte[] query(String key) {
        byte[] value = storage.get(key);
        logger.debug("Queried key: {}, found: {}", key, value != null);
        return value;
    }
    
    /**
     * Get all keys in the state machine
     */
    public java.util.Set<String> getAllKeys() {
        return storage.keySet();
    }
    
    /**
     * Get storage size
     */
    public int getStorageSize() {
        return storage.size();
    }
    
    /**
     * Get total operations count
     */
    public long getTotalOperations() {
        return totalOperations;
    }
    
    /**
     * Check if key exists
     */
    public boolean containsKey(String key) {
        return storage.containsKey(key);
    }
    
    /**
     * Get memory usage estimate
     */
    public long getEstimatedMemoryUsage() {
        long totalSize = 0;
        for (java.util.Map.Entry<String, byte[]> entry : storage.entrySet()) {
            totalSize += entry.getKey().length();
            totalSize += entry.getValue().length;
        }
        return totalSize;
    }
    
    /**
     * Clear all data (for testing or reset)
     */
    public void clear() {
        storage.clear();
        appliedIndex = 0;
        totalOperations = 0;
        logger.info("State machine cleared");
    }
    
    @Override
    public byte[] createSnapshot() {
        try {
            StateMachineSnapshot snapshot = new StateMachineSnapshot(
                new ConcurrentHashMap<>(storage), 
                appliedIndex, 
                totalOperations
            );
            
            // Simple serialization (in production, use proper serialization)
            StringBuilder sb = new StringBuilder();
            sb.append(appliedIndex).append("|").append(totalOperations).append("|");
            for (java.util.Map.Entry<String, byte[]> entry : storage.entrySet()) {
                sb.append(entry.getKey()).append(":").append(
                    java.util.Base64.getEncoder().encodeToString(entry.getValue())
                ).append(";");
            }
            
            return sb.toString().getBytes();
        } catch (Exception e) {
            logger.error("Failed to create snapshot", e);
            return new byte[0];
        }
    }
    
    @Override
    public void restoreFromSnapshot(byte[] snapshotData) {
        try {
            String snapshotStr = new String(snapshotData);
            String[] parts = snapshotStr.split("\\|", 3);
            
            if (parts.length >= 3) {
                appliedIndex = Long.parseLong(parts[0]);
                totalOperations = Long.parseLong(parts[1]);
                
                storage.clear();
                if (!parts[2].isEmpty()) {
                    String[] entries = parts[2].split(";");
                    for (String entry : entries) {
                        if (!entry.isEmpty()) {
                            String[] kv = entry.split(":", 2);
                            if (kv.length == 2) {
                                storage.put(kv[0], java.util.Base64.getDecoder().decode(kv[1]));
                            }
                        }
                    }
                }
            }
            
            logger.info("Restored state machine from snapshot - Index: {}, Operations: {}, Size: {}", 
                appliedIndex, totalOperations, storage.size());
                
        } catch (Exception e) {
            logger.error("Failed to restore from snapshot", e);
        }
    }
    
    /**
     * Create a structured snapshot (for internal use)
     */
    public StateMachineSnapshot createStructuredSnapshot() {
        return new StateMachineSnapshot(
            new ConcurrentHashMap<>(storage), 
            appliedIndex, 
            totalOperations
        );
    }
    
    /**
     * Restore from structured snapshot (for internal use)
     */
    public void restoreFromStructuredSnapshot(StateMachineSnapshot snapshot) {
        storage.clear();
        storage.putAll(snapshot.getStorage());
        appliedIndex = snapshot.getAppliedIndex();
        totalOperations = snapshot.getTotalOperations();
        
        logger.info("Restored state machine from structured snapshot - Index: {}, Operations: {}, Size: {}", 
            appliedIndex, totalOperations, storage.size());
    }
    
    /**
     * Get statistics
     */
    public StateMachineStats getStats() {
        return new StateMachineStats(
            appliedIndex,
            totalOperations,
            storage.size(),
            getEstimatedMemoryUsage()
        );
    }
    
    /**
     * Snapshot data class
     */
    public static class StateMachineSnapshot {
        private final ConcurrentMap<String, byte[]> storage;
        private final long appliedIndex;
        private final long totalOperations;
        
        public StateMachineSnapshot(ConcurrentMap<String, byte[]> storage, 
                                  long appliedIndex, long totalOperations) {
            this.storage = storage;
            this.appliedIndex = appliedIndex;
            this.totalOperations = totalOperations;
        }
        
        public ConcurrentMap<String, byte[]> getStorage() { return storage; }
        public long getAppliedIndex() { return appliedIndex; }
        public long getTotalOperations() { return totalOperations; }
    }
    
    /**
     * Statistics data class
     */
    public static class StateMachineStats {
        private final long appliedIndex;
        private final long totalOperations;
        private final int storageSize;
        private final long memoryUsage;
        
        public StateMachineStats(long appliedIndex, long totalOperations, 
                               int storageSize, long memoryUsage) {
            this.appliedIndex = appliedIndex;
            this.totalOperations = totalOperations;
            this.storageSize = storageSize;
            this.memoryUsage = memoryUsage;
        }
        
        public long getAppliedIndex() { return appliedIndex; }
        public long getTotalOperations() { return totalOperations; }
        public int getStorageSize() { return storageSize; }
        public long getMemoryUsage() { return memoryUsage; }
        
        @Override
        public String toString() {
            return String.format("StateMachineStats{appliedIndex=%d, totalOperations=%d, " +
                "storageSize=%d, memoryUsage=%d bytes}", 
                appliedIndex, totalOperations, storageSize, memoryUsage);
        }
    }
}