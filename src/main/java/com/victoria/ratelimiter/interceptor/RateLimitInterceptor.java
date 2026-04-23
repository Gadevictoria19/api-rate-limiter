package com.victoria.ratelimiter.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.victoria.ratelimiter.service.TokenBucketService;
import com.victoria.ratelimiter.service.TokenBucketService.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * RateLimitInterceptor
 *
 * Intercepts every request before it reaches the controller.
 * Extracts the client identifier (API key header → fallback to IP),
 * checks the Token Bucket in Redis, and either:
 *   - Allows the request through (HTTP 200 from controller)
 *   - Blocks it immediately with HTTP 429 Too Many Requests
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TokenBucketService tokenBucketService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {

        String identifier = resolveIdentifier(request);
        RateLimitResult result = tokenBucketService.isAllowed(identifier);

        // Always set informational headers so clients know their quota status
        response.setHeader("X-RateLimit-Limit",     String.valueOf(result.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
        response.setHeader("X-RateLimit-Identifier", identifier);

        if (result.allowed()) {
            return true; // pass through to controller
        }

        // Block with HTTP 429
        log.warn("Rate limit exceeded for identifier: {}", identifier);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "1"); // suggest retry after 1 second

        String body = MAPPER.writeValueAsString(Map.of(
                "status",  429,
                "error",   "Too Many Requests",
                "message", "Rate limit exceeded. Your token bucket is empty. Please slow down.",
                "identifier", identifier
        ));
        response.getWriter().write(body);
        return false; // do NOT continue to controller
    }

    /**
     * Resolve client identity.
     * Priority: X-API-Key header → X-Forwarded-For (proxy) → remote IP
     */
    private String resolveIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "apikey:" + apiKey;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }

        return "ip:" + request.getRemoteAddr();
    }
}
