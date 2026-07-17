package com.tongji.llm.rag;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 匿名/登录用户共用的 RAG 成本保护。
 *
 * <p>速率限制和并发限制都以 Redis 为准，支持多实例部署；IP 只用于生成不可逆摘要，不写入 Key 明文。</p>
 */
@Service
@RequiredArgsConstructor
public class RagRateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RagRateLimitService.class);

    private final RedissonClient redisson;
    private final RagProperties properties;
    private final RagMetrics metrics;

    public Lease acquire(Long userId, String remoteAddress) {
        RagProperties.RateLimit config = properties.getRateLimit();
        String principalType = userId == null ? "anonymous" : "authenticated";
        if (!config.isEnabled()) {
            return Lease.noop(principalType);
        }

        String rawSubject = userId == null
                ? "ip:" + normalizeRemoteAddress(remoteAddress)
                : "user:" + userId;
        String subjectHash = sha256Prefix(rawSubject);
        int permitsPerMinute = userId == null
                ? config.getAnonymousPermitsPerMinute()
                : config.getAuthenticatedPermitsPerMinute();

        try {
            String rateKey = "rl:rag:v1:" + principalType + ":" + permitsPerMinute + ":" + subjectHash;
            RRateLimiter rateLimiter = redisson.getRateLimiter(rateKey);
            // Key 中包含配额值，修改配置后会自然使用新桶，不会继续沿用旧配额。
            rateLimiter.trySetRate(
                    RateType.OVERALL,
                    permitsPerMinute,
                    Duration.ofMinutes(1),
                    Duration.ofMinutes(2));
            if (!rateLimiter.tryAcquire()) {
                metrics.rateLimited("rate");
                throw RagRateLimitException.exceeded(
                        "RAG_RATE_LIMITED",
                        "提问过于频繁，请稍后再试。",
                        config.getRetryAfter().toSeconds());
            }

            int maxConcurrent = config.getMaxConcurrentPerSubject();
            String semaphoreKey = "concurrency:rag:v1:" + maxConcurrent + ":" + subjectHash;
            RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(semaphoreKey);
            semaphore.trySetPermits(maxConcurrent);
            // 匿名主体可能很多，空闲 Key 自动过期，避免 Redis 长期积累无用信号量。
            semaphore.expire(config.getConcurrencyLease().plusMinutes(1));
            String permitId = semaphore.tryAcquire(
                    0,
                    config.getConcurrencyLease().toSeconds(),
                    TimeUnit.SECONDS);
            if (permitId == null) {
                metrics.rateLimited("concurrency");
                throw RagRateLimitException.exceeded(
                        "RAG_CONCURRENCY_LIMITED",
                        "当前提问仍在处理中，请等待完成后再试。",
                        1);
            }
            return new Lease(principalType, semaphore, permitId);
        } catch (RagRateLimitException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 成本型匿名接口在 Redis 故障时受控拒绝，避免故障期间无限调用外部模型。
            metrics.error("rate_limit");
            log.error("RAG rate limit backend unavailable principalType={} errorType={}",
                    principalType, e.getClass().getSimpleName(), e);
            throw RagRateLimitException.backendUnavailable();
        }
    }

    private static String normalizeRemoteAddress(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
    }

    static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 一个 SSE 请求持有一个带租期的并发许可；完成、异常或取消时均可幂等释放。
     */
    public static final class Lease implements AutoCloseable {
        private final String principalType;
        private final RPermitExpirableSemaphore semaphore;
        private final String permitId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(String principalType, RPermitExpirableSemaphore semaphore, String permitId) {
            this.principalType = principalType;
            this.semaphore = semaphore;
            this.permitId = permitId;
        }

        private static Lease noop(String principalType) {
            return new Lease(principalType, null, null);
        }

        public String principalType() {
            return principalType;
        }

        @Override
        public void close() {
            if (semaphore != null && released.compareAndSet(false, true)) {
                try {
                    semaphore.tryRelease(permitId);
                } catch (Exception e) {
                    // 许可本身带租期，释放失败不会永久占用；这里只记录低敏错误供排查。
                    log.warn("RAG concurrency permit release failed errorType={}",
                            e.getClass().getSimpleName());
                }
            }
        }
    }
}
