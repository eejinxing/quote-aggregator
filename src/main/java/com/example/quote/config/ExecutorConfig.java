package com.example.quote.config;

import com.example.quote.concurrent.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class ExecutorConfig {
    public static final String IO_EXECUTOR = "ioExecutor";

    @Bean(name = IO_EXECUTOR)
    ThreadPoolTaskExecutor ioExecutor(ConcurrencyProperties props) {
        ConcurrencyProperties.Pool pool = props.ioPool();

        int permits = props.totalBulkheadPermits();
        if (pool.maxSize() < permits) {
            throw new IllegalStateException(
                    "app.concurrency.io-pool.max-size (" + pool.maxSize() + ") must be >= the sum of all bulkhead "
                            + "limits (" + permits + "); otherwise a slow dependency can starve the others of threads");
        }
        if (pool.coreSize() > pool.maxSize()) {
            throw new IllegalStateException("io-pool.core-size must be <= io-pool.max-size");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("io-");
        executor.setCorePoolSize(pool.coreSize());
        executor.setMaxPoolSize(pool.maxSize());
        executor.setQueueCapacity(pool.queueCapacity());
        executor.setKeepAliveSeconds((int) pool.keepAlive().toSeconds());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) pool.shutdownWait().toSeconds());
        return executor;
    }
}
