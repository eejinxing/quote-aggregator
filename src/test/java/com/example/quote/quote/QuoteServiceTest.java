package com.example.quote.quote;

import com.example.quote.TestFixtures;
import com.example.quote.concurrent.BulkheadFullException;
import com.example.quote.concurrent.BulkheadRegistry;
import com.example.quote.config.ConcurrencyProperties;
import com.example.quote.downstream.DownstreamException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class QuoteServiceTest {
    private final FakeGateway gateway = new FakeGateway();
    private final ExecutorService executor = Executors.newFixedThreadPool(16);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private BulkheadRegistry bulkheads;

    @AfterEach
    void tearDown() {
        CountDownLatch gate = gateway.priceGate;
        if (gate != null) {
            gate.countDown();
        }
        executor.shutdownNow();
    }

    private QuoteService service(int priceLimit, Duration callTimeout, Duration totalTimeout) {
        ConcurrencyProperties props = TestFixtures.props(priceLimit, 8, 8, callTimeout, totalTimeout);
        bulkheads = new BulkheadRegistry(props, metrics);
        return new QuoteService(gateway, executor, bulkheads, props, metrics);
    }

    @Test
    void callsTheDependenciesInParallel() throws Exception {
        gateway.delay = Duration.ofMillis(400);
        QuoteService service = service(8, Duration.ofSeconds(2), Duration.ofSeconds(3));

        long start = System.nanoTime();
        Quote quote = service.getQuote("SKU-1").get(3, SECONDS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(quote.degraded()).isFalse();
        assertThat(quote.price()).isEqualByComparingTo("9.99");
        assertThat(quote.stock()).isEqualTo(5);
        assertThat(quote.shippingDays()).isEqualTo(2);

        assertThat(elapsed).isLessThan(Duration.ofMillis(900));
    }

    @Test
    void degradesInsteadOfFailingWhenAnOptionalDependencyFails() throws Exception {
        gateway.stockFails = true;
        QuoteService service = service(8, Duration.ofSeconds(2), Duration.ofSeconds(3));

        Quote quote = service.getQuote("SKU-1").get(3, SECONDS);

        assertThat(quote.degraded()).isTrue();
        assertThat(quote.degradedFields()).containsExactly("stock");
        assertThat(quote.stock()).isNull();
        assertThat(quote.price()).isEqualByComparingTo("9.99");
        assertThat(quote.shippingDays()).isEqualTo(2);
        assertThat(metrics.get("quote.degraded").tag("field", "stock").counter().count()).isEqualTo(1.0);
    }

    @Test
    void failsWhenTheMandatoryDependencyFails() {
        gateway.priceFails = true;
        CompletableFuture<Quote> future = service(8, Duration.ofSeconds(2), Duration.ofSeconds(3)).getQuote("SKU-1");

        assertThatThrownBy(() -> future.get(3, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(DownstreamException.class);
    }

    @Test
    void aCallTimeoutFailsTheRequestFastButTheBulkheadStaysOccupiedUntilTheWorkerReallyFinishes() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gateway.priceGate = gate;
        QuoteService service = service(3, Duration.ofMillis(200), Duration.ofSeconds(2));

        CompletableFuture<Quote> future = service.getQuote("SKU-1");

        assertThatThrownBy(() -> future.get(3, SECONDS)).hasRootCauseInstanceOf(TimeoutException.class);
        assertThat(bulkheads.get("price").availablePermits()).isEqualTo(2);

        gate.countDown();
        await().atMost(2, SECONDS)
                .untilAsserted(() -> assertThat(bulkheads.get("price").availablePermits()).isEqualTo(3));
    }

    @Test
    void shedsLoadWithAnImmediateRejectionInsteadOfQueueing() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gateway.priceGate = gate;
        QuoteService service = service(2, Duration.ofSeconds(5), Duration.ofSeconds(6));

        CompletableFuture<Quote> first = service.getQuote("SKU-A");
        CompletableFuture<Quote> second = service.getQuote("SKU-B");
        CompletableFuture<Quote> shed = service.getQuote("SKU-C");

        assertThatThrownBy(() -> shed.get(2, SECONDS)).hasRootCauseInstanceOf(BulkheadFullException.class);
        assertThat(metrics.get("bulkhead.rejected").tag("name", "price").counter().count()).isEqualTo(1.0);

        gate.countDown();
        assertThat(first.get(2, SECONDS).price()).isEqualByComparingTo("9.99");
        assertThat(second.get(2, SECONDS).price()).isEqualByComparingTo("9.99");
    }

    @Test
    void concurrentRequestsForTheSameSkuShareOneLoad() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gateway.priceGate = gate;
        QuoteService service = service(8, Duration.ofSeconds(5), Duration.ofSeconds(6));

        List<CompletableFuture<Quote>> callers = IntStream.range(0, 20)
                .mapToObj(i -> service.getQuote("HOT-1"))
                .toList();
        gate.countDown();

        for (CompletableFuture<Quote> caller : callers) {
            assertThat(caller.get(3, SECONDS).sku()).isEqualTo("HOT-1");
        }
        assertThat(gateway.priceCalls.get()).isEqualTo(1);
    }

    @Test
    void sequentialRequestsAreNotCached() throws Exception {
        QuoteService service = service(8, Duration.ofSeconds(2), Duration.ofSeconds(3));

        service.getQuote("SKU-A").get(3, SECONDS);
        service.getQuote("SKU-A").get(3, SECONDS);

        assertThat(gateway.priceCalls.get()).isEqualTo(2);
    }
}
