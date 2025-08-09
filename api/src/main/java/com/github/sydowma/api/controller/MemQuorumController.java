package com.github.sydowma.api.controller;

import com.github.sydowma.api.service.MemQuorumGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory")
public class MemQuorumController {
    private static final Logger logger = LoggerFactory.getLogger(MemQuorumController.class);
    
    @Autowired
    private MemQuorumGrpcClient grpcClient;
    
    @PostMapping("/users/{userId}/keys/{key}")
    public ResponseEntity<?> setValue(
            @PathVariable String userId,
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        try {
            String value = (String) body.get("value");
            if (value == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Value is required"));
            }
            
            String result = grpcClient.set(userId, key, value);
            if (result.equals("OK")) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Value set successfully"));
            } else {
                return ResponseEntity.internalServerError().body(Map.of("error", result));
            }
        } catch (Exception e) {
            logger.error("Error setting value for user {} key {}", userId, key, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
    
    @GetMapping("/users/{userId}/keys/{key}")
    public ResponseEntity<?> getValue(
            @PathVariable String userId,
            @PathVariable String key) {
        try {
            String value = grpcClient.get(userId, key);
            if (value != null) {
                return ResponseEntity.ok(Map.of("value", value, "found", true));
            } else {
                return ResponseEntity.ok(Map.of("found", false));
            }
        } catch (Exception e) {
            logger.error("Error getting value for user {} key {}", userId, key, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
    
    @DeleteMapping("/users/{userId}/keys/{key}")
    public ResponseEntity<?> deleteValue(
            @PathVariable String userId,
            @PathVariable String key) {
        try {
            boolean success = grpcClient.delete(userId, key);
            return ResponseEntity.ok(Map.of("success", success));
        } catch (Exception e) {
            logger.error("Error deleting value for user {} key {}", userId, key, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
    
    @GetMapping("/users/{userId}/keys")
    public ResponseEntity<?> listKeys(
            @PathVariable String userId,
            @RequestParam(required = false) String pattern) {
        try {
            String[] keys = grpcClient.list(userId, pattern);
            return ResponseEntity.ok(Map.of("keys", keys));
        } catch (Exception e) {
            logger.error("Error listing keys for user {}", userId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "MemQuorum API Gateway",
            "timestamp", System.currentTimeMillis()
        ));
    }
    
    @GetMapping("/cluster/status")
    public ResponseEntity<?> clusterStatus() {
        try {
            var clusterStatus = grpcClient.getClusterStatus();
            return ResponseEntity.ok(Map.of(
                "totalNodes", clusterStatus.getTotalNodes(),
                "activeConnections", clusterStatus.getActiveConnections(),
                "totalShards", clusterStatus.getTotalShards(),
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            logger.error("Error getting cluster status", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to get cluster status"));
        }
    }
}