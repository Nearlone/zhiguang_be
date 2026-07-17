package com.tongji.llm.rag;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRateLimitServiceTest {

    private RagProperties properties;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void bypassesRedisWhenRateLimitIsDisabled() {
        properties.getRateLimit().setEnabled(false);
        AtomicInteger redisCalls = new AtomicInteger();
        RedissonClient redisson = redissonProxy(null, null, redisCalls, false);

        try (RagRateLimitService.Lease lease = service(redisson).acquire(null, "127.0.0.1")) {
            assertThat(lease.principalType()).isEqualTo("anonymous");
        }

        assertThat(redisCalls).hasValue(0);
    }

    @Test
    void rejectsRequestsThatExceedMinuteQuota() {
        RRateLimiter rateLimiter = rateLimiterProxy(false);
        RedissonClient redisson = redissonProxy(
                rateLimiter, semaphoreProxy("unused", new AtomicInteger()), new AtomicInteger(), false);

        assertThatThrownBy(() -> service(redisson).acquire(null, "127.0.0.1"))
                .isInstanceOfSatisfying(RagRateLimitException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(429);
                    assertThat(error.getCode()).isEqualTo("RAG_RATE_LIMITED");
                });
        assertThat(meterRegistry.get("rag.rate.limited")
                .tag("reason", "rate").counter().count()).isEqualTo(1.0);
    }

    @Test
    void releasesConcurrentPermitExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        RedissonClient redisson = redissonProxy(
                rateLimiterProxy(true),
                semaphoreProxy("permit-1", releases),
                new AtomicInteger(),
                false);

        RagRateLimitService.Lease lease = service(redisson).acquire(42L, "127.0.0.1");
        assertThat(lease.principalType()).isEqualTo("authenticated");
        lease.close();
        lease.close();

        assertThat(releases).hasValue(1);
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        RedissonClient redisson = redissonProxy(null, null, new AtomicInteger(), true);

        assertThatThrownBy(() -> service(redisson).acquire(null, "127.0.0.1"))
                .isInstanceOfSatisfying(RagRateLimitException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getMessage()).doesNotContain("redis");
                });
    }

    @Test
    void hashesSubjectsWithoutKeepingRawAddress() {
        String hash = RagRateLimitService.sha256Prefix("ip:192.0.2.10");

        assertThat(hash).hasSize(24).doesNotContain("192.0.2.10");
    }

    private RagRateLimitService service(RedissonClient redisson) {
        return new RagRateLimitService(redisson, properties, new RagMetrics(meterRegistry));
    }

    private static RedissonClient redissonProxy(RRateLimiter rateLimiter,
                                                RPermitExpirableSemaphore semaphore,
                                                AtomicInteger calls,
                                                boolean fail) {
        return proxy(RedissonClient.class, (method, returnType, args) -> {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("redis down");
            }
            return switch (method) {
                case "getRateLimiter" -> rateLimiter;
                case "getPermitExpirableSemaphore" -> semaphore;
                default -> defaultValue(returnType);
            };
        });
    }

    private static RRateLimiter rateLimiterProxy(boolean allowed) {
        return proxy(RRateLimiter.class, (method, returnType, args) -> switch (method) {
            case "trySetRate" -> true;
            case "tryAcquire" -> allowed;
            default -> defaultValue(returnType);
        });
    }

    private static RPermitExpirableSemaphore semaphoreProxy(String permitId, AtomicInteger releases) {
        return proxy(RPermitExpirableSemaphore.class, (method, returnType, args) -> switch (method) {
            case "trySetPermits" -> true;
            case "tryAcquire" -> permitId;
            case "tryRelease" -> {
                releases.incrementAndGet();
                yield true;
            }
            default -> defaultValue(returnType);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, args) -> invocation.call(
                        method.getName(), method.getReturnType(), args));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Class<?> returnType, Object[] args);
    }
}
