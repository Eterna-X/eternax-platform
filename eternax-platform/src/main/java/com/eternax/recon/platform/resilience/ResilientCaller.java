package com.eternax.recon.platform.resilience;

import com.eternax.recon.exception.DownstreamTimeoutException;
import com.eternax.recon.exception.DownstreamUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Every call to a downstream dependency (another service, the GL, object storage) goes through
 * this: per-attempt timeout, bounded retries with exponential backoff and jitter, and a circuit
 * breaker (LLD 14.4). One breaker and retry policy exists per dependency name.
 */
public class ResilientCaller {

    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final Duration attemptTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientCaller(Duration attemptTimeout, int maxAttempts) {
        this.attemptTimeout = attemptTimeout;
        this.breakers =
                CircuitBreakerRegistry.of(
                        CircuitBreakerConfig.custom()
                                .slidingWindowSize(20)
                                .minimumNumberOfCalls(10)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(Duration.ofSeconds(30))
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .build());
        this.retries =
                RetryRegistry.of(
                        RetryConfig.custom()
                                .maxAttempts(maxAttempts)
                                .intervalFunction(
                                        io.github.resilience4j.core.IntervalFunction
                                                .ofExponentialRandomBackoff(
                                                        Duration.ofMillis(100), 2.0, 0.5))
                                .ignoreExceptions(IllegalArgumentException.class)
                                .build());
    }

    public <T> T call(String dependency, Callable<T> call) {
        CircuitBreaker breaker = breakers.circuitBreaker(dependency);
        Retry retry = retries.retry(dependency);
        Callable<T> guarded =
                CircuitBreaker.decorateCallable(breaker, () -> attempt(dependency, call));
        Callable<T> withRetry = Retry.decorateCallable(retry, guarded);
        try {
            return withRetry.call();
        } catch (CallNotPermittedException e) {
            throw new DownstreamUnavailableException(dependency, e);
        } catch (DownstreamTimeoutException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DownstreamUnavailableException(dependency, e);
        }
    }

    private <T> T attempt(String dependency, Callable<T> call) throws Exception {
        Future<T> future = executor.submit(call);
        try {
            return future.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DownstreamTimeoutException(dependency, attemptTimeout, 1, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
