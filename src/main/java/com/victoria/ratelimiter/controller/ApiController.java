package com.victoria.ratelimiter.controller;

import com.victoria.ratelimiter.service.TokenBucketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * ApiController
 *
 * Demo endpoints protected by the rate limiting middleware.
 * The interceptor runs BEFORE these methods — if rate-limited,
 * these methods never execute.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired
    private TokenBucketService tokenBucketService;

    /**
     * GET /api/status
     * Simple health/demo endpoint. Try hammering this with curl to trigger 429s.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "message",   "Success",
                "timestamp", Instant.now().toString(),
                "service",   "api-rate-limiter"
        ));
    }

    /**
     * GET /api/data
     * Simulates a heavier data endpoint.
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> data(@RequestParam(defaultValue = "1") int page) {
        return ResponseEntity.ok(Map.of(
                "page",    page,
                "records", java.util.List.of("record_a", "record_b", "record_c"),
                "total",   3
        ));
    }

    /**
     * GET /api/config
     * Returns the current rate limiter configuration — useful for debugging.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(Map.of(
                "algorithm",   "Token Bucket",
                "capacity",    tokenBucketService.getCapacity(),
                "refill_rate", tokenBucketService.getRefillRate() + " tokens/sec",
                "backend",     "Redis (distributed, atomic Lua script)"
        ));
    }
}
