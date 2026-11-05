package com.example.quote.web;

import com.example.quote.quote.Quote;
import com.example.quote.quote.QuoteService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/quotes")
@Validated
public class QuoteController {
    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/{sku}")
    public CompletableFuture<Quote> quote(
            @PathVariable
            @Pattern(regexp = "[A-Za-z0-9-]{3,32}", message = "sku must be 3-32 characters: letters, digits or '-'")
            String sku) {
        return quotes.getQuote(sku);
    }
}
