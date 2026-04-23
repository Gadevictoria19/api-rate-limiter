package com.victoria.ratelimiter.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class TokenBucketService {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketService.class);
    private static final String KEY_PREFIX = "ratelimit:";

    @Autowired
    private JedisPool jedisPool;

    @Value("${rate-limiter.capacity:10}")
    private int capacity;

    @Value("${rate-limiter.refill-rate:2}")
    private double refillRate; // tokens per second

    private String luaScript;

    @PostConstruct
    public void loadLuaScript() throws IOException {
        ClassPathResource resource = new ClassPathResource("scripts/token_bucket.lua");
        luaScript = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.info("Token Bucket Lua script loaded. Capacity={}, RefillRate={}/s", capacity, refillRate);
    }

    /**
     * Checks whether the given identifier (IP or API key) is within rate limits.
     *
     * @param identifier unique client identifier
     * @return RateLimitResult with allow/block decision and remaining tokens
     */
    public RateLimitResult isAllowed(String identifier) {
        String key = KEY_PREFIX + identifier;
        long nowMs = System.currentTimeMillis();

        try (Jedis jedis = jedisPool.getResource()) {
            // Execute Lua script atomically — no race conditions
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) jedis.eval(
                    luaScript,
                    List.of(key),
                    List.of(
                            String.valueOf(capacity),
                            String.valueOf(refillRate),
                            String.valueOf(nowMs)
                    )
            );

            boolean allowed = result.get(0) == 1L;
            int remaining  = result.get(1).intValue();

            log.debug("RateLimit check — id={} allowed={} remaining={}", identifier, allowed, remaining);
            return new RateLimitResult(allowed, remaining, capacity);

        } catch (Exception e) {
            // Fail open: if Redis is unavailable, allow the request
            // In production you may want to fail closed — adjust to your risk tolerance
            log.error("Redis error during rate limit check for [{}]: {}", identifier, e.getMessage());
            return new RateLimitResult(true, capacity, capacity);
        }
    }

    public int getCapacity()    { return capacity; }
    public double getRefillRate() { return refillRate; }

    // ── Inner result record ───────────────────────────────────────────────────

    public record RateLimitResult(boolean allowed, int remainingTokens, int capacity) {}
}
