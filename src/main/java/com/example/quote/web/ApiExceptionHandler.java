package com.example.quote.web;

import com.example.quote.concurrent.BulkheadFullException;
import com.example.quote.concurrent.Throwables;
import com.example.quote.downstream.DownstreamException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            CompletionException.class, ExecutionException.class, BulkheadFullException.class,
            RejectedExecutionException.class, TimeoutException.class, DownstreamException.class})
    ResponseEntity<ProblemDetail> handleAsyncFailure(Exception ex) {
        Throwable cause = Throwables.unwrap(ex);

        if (cause instanceof BulkheadFullException || cause instanceof RejectedExecutionException) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Overloaded",
                    "The service is shedding load, please retry shortly", "1");
        }
        if (cause instanceof TimeoutException) {
            return problem(HttpStatus.GATEWAY_TIMEOUT, "Upstream timeout",
                    "A dependency did not answer in time", null);
        }
        if (cause instanceof DownstreamException) {
            return problem(HttpStatus.BAD_GATEWAY, "Upstream failure",
                    "A dependency failed to answer", null);
        }
        log.error("Unexpected failure", cause);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error", null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail, null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail,
                                                         String retryAfterSeconds) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        return response.body(body);
    }
}
