package com.github.sydowma.memquorum.client;

/**
 * MemQuorum客户端配置类
 */
public class ClientConfig {
    private String nacosServerAddr = "127.0.0.1:8848";
    private int connectionTimeoutMs = 5000;
    private int requestTimeoutMs = 5000;
    private int retryCount = 3;
    private int retryDelayMs = 100;
    private int maxConcurrentRequests = 100;
    private boolean enableCompression = true;
    private int maxMessageSize = 4 * 1024 * 1024; // 4MB
    
    public ClientConfig() {}
    
    public ClientConfig(String nacosServerAddr) {
        this.nacosServerAddr = nacosServerAddr;
    }
    
    // Builder模式
    public static ClientConfig builder() {
        return new ClientConfig();
    }
    
    public ClientConfig nacosServerAddr(String nacosServerAddr) {
        this.nacosServerAddr = nacosServerAddr;
        return this;
    }
    
    public ClientConfig connectionTimeout(int timeoutMs) {
        this.connectionTimeoutMs = timeoutMs;
        return this;
    }
    
    public ClientConfig requestTimeout(int timeoutMs) {
        this.requestTimeoutMs = timeoutMs;
        return this;
    }
    
    public ClientConfig retryCount(int count) {
        this.retryCount = count;
        return this;
    }
    
    public ClientConfig retryDelay(int delayMs) {
        this.retryDelayMs = delayMs;
        return this;
    }
    
    public ClientConfig maxConcurrentRequests(int max) {
        this.maxConcurrentRequests = max;
        return this;
    }
    
    public ClientConfig enableCompression(boolean enable) {
        this.enableCompression = enable;
        return this;
    }
    
    public ClientConfig maxMessageSize(int size) {
        this.maxMessageSize = size;
        return this;
    }
    
    // Getters
    public String getNacosServerAddr() { return nacosServerAddr; }
    public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public int getRetryCount() { return retryCount; }
    public int getRetryDelayMs() { return retryDelayMs; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public boolean isEnableCompression() { return enableCompression; }
    public int getMaxMessageSize() { return maxMessageSize; }
    
    @Override
    public String toString() {
        return String.format("ClientConfig{nacosServerAddr='%s', connectionTimeout=%d, " +
            "requestTimeout=%d, retryCount=%d, retryDelay=%d, maxConcurrentRequests=%d, " +
            "compression=%s, maxMessageSize=%d}", 
            nacosServerAddr, connectionTimeoutMs, requestTimeoutMs, retryCount, 
            retryDelayMs, maxConcurrentRequests, enableCompression, maxMessageSize);
    }
}
