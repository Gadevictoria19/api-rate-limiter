package com.victoria.ratelimiter;

import com.victoria.ratelimiter.service.TokenBucketService;
import com.victoria.ratelimiter.service.TokenBucketService.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for TokenBucketService.
 *
 * Requires a running Redis instance (use docker-compose up redis -d before running).
 * Tests verify the atomic Lua script behaviour end-to-end.
 */
@SpringBootTest
class TokenBucketServiceTest {

    @Autowired
    private TokenBucketService tokenBucketService;

    @Autowired
    private JedisPool jedisPool;

    // Point tests at localhost Redis (started separately via docker-compose)
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("redis.host", () -> "localhost");
        registry.add("redis.port", () -> "6379");
        registry.add("rate-limiter.capacity", () -> "5");
        registry.add("rate-limiter.refill-rate", () -> "1");
    }

    @BeforeEach
    void clearRedis() {
        // Flush only rate limit keys before each test
        try (var jedis = jedisPool.getResource()) {
            jedis.keys("ratelimit:*").forEach(jedis::del);
        }
    }

    @Test
    @DisplayName("First request should always be allowed")
    void firstRequest_shouldBeAllowed() {
        RateLimitResult result = tokenBucketService.isAllowed("test-user-1");
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(4); // 5 - 1
    }

    @Test
    @DisplayName("Should block after bucket is exhausted")
    void shouldBlock_afterCapacityExhausted() {
        String user = "test-user-2";

        // Drain all 5 tokens
        for (int i = 0; i < 5; i++) {
            RateLimitResult r = tokenBucketService.isAllowed(user);
            assertThat(r.allowed()).isTrue();
        }

        // 6th request must be blocked
        RateLimitResult blocked = tokenBucketService.isAllowed(user);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remainingTokens()).isZero();
    }

    @Test
    @DisplayName("Different users should have independent buckets")
    void differentUsers_haveIndependentBuckets() {
        // Drain user A completely
        for (int i = 0; i < 5; i++) {
            tokenBucketService.isAllowed("user-A");
        }

        // User B should still be allowed
        RateLimitResult resultB = tokenBucketService.isAllowed("user-B");
        assertThat(resultB.allowed()).isTrue();

        // User A should be blocked
        RateLimitResult resultA = tokenBucketService.isAllowed("user-A");
        assertThat(resultA.allowed()).isFalse();
    }

    @Test
    @DisplayName("Tokens should refill after waiting")
    void tokens_shouldRefillOverTime() throws InterruptedException {
        String user = "test-user-3";

        // Drain bucket
        for (int i = 0; i < 5; i++) {
            tokenBucketService.isAllowed(user);
        }

        // Blocked immediately
        assertThat(tokenBucketService.isAllowed(user).allowed()).isFalse();

        // Wait 2 seconds — at 1 token/sec, should have 2 tokens back
        Thread.sleep(2000);

        RateLimitResult result = tokenBucketService.isAllowed(user);
        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isGreaterThanOrEqualTo(1);
    }
}
