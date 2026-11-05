package com.example.quote.concurrent;

public class BulkheadFullException extends RuntimeException {
    private final String bulkhead;

    public BulkheadFullException(String bulkhead) {
        super("Bulkhead '" + bulkhead + "' is full", null, false, false);
        this.bulkhead = bulkhead;
    }

    public BulkheadFullException(String bulkhead, Throwable cause) {
        super("Bulkhead '" + bulkhead + "' rejected the call: executor saturated", cause, false, false);
        this.bulkhead = bulkhead;
    }

    public String bulkhead() {
        return bulkhead;
    }
}
