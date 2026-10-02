package com.achintha.productservice.client;

import com.achintha.productservice.client.UserServiceClient.SecurityStateResponse;
import com.achintha.productservice.client.UserServiceClient.ServiceTokenRequest;
import com.achintha.productservice.client.UserServiceClient.ServiceTokenResponse;
import com.achintha.productservice.exception.ApiException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.NotFoundException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resilient access to user-service: every call runs as Retry(CircuitBreaker(TimeLimiter(feign call))), so each
 * attempt has its own timeout and is counted by the breaker (same pattern as order-service's product-service calls).
 * Settings come from the {@code userService} instances under {@code resilience4j.*} in application.yml.
 *
 * <p>A 404 passes through as {@link NotFoundException}; anything else (down, timeout, 5xx, breaker open) ends in a
 * 503 {@code SERVICE_UNAVAILABLE}, never in fabricated data.
 */
@Component
public class UserServiceGateway {

    public static final String RESILIENCE_INSTANCE = "userService";

    private final UserServiceClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    // TimeLimiter needs a future to time out; virtual threads keep blocking Feign calls cheap
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public UserServiceGateway(UserServiceClient client, CircuitBreakerRegistry circuitBreakerRegistry,
                              RetryRegistry retryRegistry, TimeLimiterRegistry timeLimiterRegistry) {
        this.client = client;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(RESILIENCE_INSTANCE);
    }

    public ServiceTokenResponse serviceToken(String clientId, String clientSecret) {
        return call("obtain a service token",
                () -> client.serviceToken(new ServiceTokenRequest(clientId, clientSecret)));
    }

    /**
     * @param tokenSupplier gives the current service token; asked again on every attempt, so a retry after a 401
     *                      (expired token, invalidated by the caller's {@code onUnauthorized}) uses a fresh one
     */
    public SecurityStateResponse securityState(UUID userId, Supplier<String> tokenSupplier, Runnable onUnauthorized) {
        return call("read the security state of a user", () -> {
            try {
                return client.securityState(userId, "Bearer " + tokenSupplier.get());
            } catch (FeignException.NotFound e) {
                throw new NotFoundException("User not found");
            } catch (FeignException.Unauthorized e) {
                onUnauthorized.run();
                throw e;
            }
        });
    }

    private <T> T call(String action, Supplier<T> feignCall) {
        Callable<T> timed = TimeLimiter.decorateFutureSupplier(timeLimiter,
                () -> CompletableFuture.supplyAsync(feignCall, executor));
        Callable<T> guarded = CircuitBreaker.decorateCallable(circuitBreaker, timed);
        Callable<T> retried = Retry.decorateCallable(retry, guarded);
        try {
            return retried.call();
        } catch (Exception e) {
            throw fallback(action, unwrap(e));
        }
    }

    private static RuntimeException fallback(String action, Throwable failure) {
        if (failure instanceof NotFoundException notFound) {
            return notFound;
        }
        String reason = switch (failure) {
            case CallNotPermittedException ignored -> "circuit breaker is open";
            case TimeoutException ignored -> "request timed out";
            default -> "request failed";
        };
        return new UserServiceUnavailableException(
                "User service is unavailable (" + reason + "): could not " + action + ". Try again later.", failure);
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 503: user-service could not answer. */
    public static class UserServiceUnavailableException extends ApiException {

        public UserServiceUnavailableException(String message, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, message);
            initCause(cause);
        }
    }
}
