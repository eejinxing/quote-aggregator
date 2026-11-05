package com.example.quote.downstream;

public class DownstreamException extends RuntimeException {
    public DownstreamException(String message) {
        super(message);
    }

    public DownstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
