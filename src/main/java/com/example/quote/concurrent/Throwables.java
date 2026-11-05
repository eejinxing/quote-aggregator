package com.example.quote.concurrent;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Throwables {
    private Throwables() {
    }

    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
