package com.example.quote.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.concurrency.quote.call-timeout=300ms",
        "app.concurrency.quote.total-timeout=600ms",
        "app.simulated-downstream.price-latency=20ms",
        "app.simulated-downstream.stock-latency=20ms",
        "app.simulated-downstream.shipping-latency=20ms",
        "app.simulated-downstream.slow-latency=1s"
})
@AutoConfigureMockMvc
class QuoteApiTest {
    @Autowired
    MockMvc mvc;

    private ResultActions asyncGet(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult started = mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started));
    }

    private ResultActions asyncGet(String url) throws Exception {
        return asyncGet(get(url));
    }

    @Test
    void returnsAFullQuote() throws Exception {
        asyncGet("/api/quotes/ABC-123")
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.sku").value("ABC-123"))
                .andExpect(jsonPath("$.price").isNumber())
                .andExpect(jsonPath("$.stock").isNumber())
                .andExpect(jsonPath("$.shippingDays").isNumber())
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void degradesWhenAnOptionalDependencyFails() throws Exception {
        asyncGet("/api/quotes/NOSTOCK-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").isNumber())
                .andExpect(jsonPath("$.stock").doesNotExist())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradedFields[0]").value("stock"));
    }

    @Test
    void mapsAMandatoryDependencyFailureTo502() throws Exception {
        asyncGet("/api/quotes/FAIL-1")
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void mapsASlowDependencyTo504() throws Exception {
        asyncGet("/api/quotes/SLOW-1")
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsAMalformedSkuWith400() throws Exception {
        mvc.perform(get("/api/quotes/ab"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void echoesASafeIncomingRequestIdAndReplacesAnUnsafeOne() throws Exception {
        asyncGet(get("/api/quotes/ABC-123").header(RequestIdFilter.HEADER, "req-12345678"))
                .andExpect(header().string(RequestIdFilter.HEADER, "req-12345678"));

        String replaced = asyncGet(get("/api/quotes/ABC-123").header(RequestIdFilter.HEADER, "x"))
                .andReturn().getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(replaced).isNotEqualTo("x").hasSize(36);
    }

    @Test
    void exposesHealthAndBulkheadMetrics() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/bulkhead.available").param("tag", "name:price"))
                .andExpect(status().isOk());
    }
}
