package com.github.sydowma.memquorum;

public class QuorumException extends Exception {
    public QuorumException(String message) {
        super(message);
    }
    
    public QuorumException(String message, Throwable cause) {
        super(message, cause);
    }
}