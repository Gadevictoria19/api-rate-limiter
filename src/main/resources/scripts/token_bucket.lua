-- token_bucket.lua
-- Executes atomically inside Redis
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local requested = 1

-- Get current state
local bucket = redis.call("HMGET", key, "tokens", "last_refill_ms")
local tokens = tonumber(bucket[1])
local last_refill_ms = tonumber(bucket[2])

-- Initialize if it doesn't exist
if not tokens then
    tokens = capacity
    last_refill_ms = now_ms
end

-- Refill tokens based on time passed
local time_passed_ms = math.max(0, now_ms - last_refill_ms)
local tokens_to_add = (time_passed_ms / 1000.0) * refill_rate
local new_tokens = math.min(capacity, tokens + tokens_to_add)

-- Check if request is allowed
local allowed = 0
if new_tokens >= requested then
    allowed = 1
    new_tokens = new_tokens - requested
end

-- Save new state
redis.call("HMSET", key, "tokens", new_tokens, "last_refill_ms", now_ms)

-- Set TTL so old keys expire and don't leak memory (2x the time to refill full bucket)
local ttl = math.ceil((capacity / refill_rate) * 2)
redis.call("EXPIRE", key, ttl)

-- Return result to Java: [1=allowed / 0=blocked, remaining_tokens]
return { allowed, math.floor(new_tokens) }
