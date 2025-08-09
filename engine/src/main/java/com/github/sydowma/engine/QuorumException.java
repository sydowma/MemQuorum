package com.github.sydowma.engine;

public class QuorumException extends Exception {
    public QuorumException(String message) {
        super(message);
    }
    
    public QuorumException(String message, Throwable cause) {
        super(message, cause);
    }
}