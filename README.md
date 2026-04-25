# Cloud-Scale API Rate Limiter

Distributed API rate limiting middleware built with **Java (Spring Boot)**, **Redis**, and **Docker**.  
Implements the **Token Bucket algorithm** via an atomic **Lua script** executed inside Redis — eliminating race conditions across distributed server instances.

---

## Architecture

```
Client Request
      │
      ▼
┌─────────────────────┐
│  Spring Boot App    │  ← any number of instances behind a load balancer
│                     │
│  RateLimitInterceptor  ← middleware runs BEFORE controller
│       │             │
│       ▼             │
│  TokenBucketService │
│       │             │
└───────┼─────────────┘
        │  EVAL lua_script (atomic)
        ▼
┌─────────────────────┐
│       Redis         │  ← single source of truth for all instances
│                     │
│  ratelimit:user_123 │
│  { tokens: 7.4,     │
│    last_refill_ms } │
└─────────────────────┘
        │
        ▼
  allowed? → HTTP 200  /  blocked? → HTTP 429
```

### Why Lua in Redis?

Without atomicity, two concurrent requests can both read `tokens = 1`, both decrement to `0`, and both get allowed — a **race condition** that breaks the rate limit.

Redis executes Lua scripts **atomically**: the entire script runs as a single unit. No other command can run between the read and the write. This guarantees correctness even under burst traffic across multiple threads and server instances.

---

## Token Bucket Algorithm

Every client gets a "bucket":

| Parameter    | Default | Description                          |
|-------------|---------|--------------------------------------|
| `capacity`  | 10      | Maximum tokens (burst allowance)     |
| `refill_rate` | 2     | Tokens added per second              |

- Each request costs **1 token**
- If the bucket is empty → **HTTP 429 Too Many Requests**
- Tokens refill continuously — no cliff edge like Fixed Window

---

## Quickstart

### Prerequisites
- Docker + Docker Compose

### Run

```bash
git clone https://github.com/Gadevictoria19/api-rate-limiter
cd api-rate-limiter
docker-compose up --build
```

App starts on `http://localhost:8080`. Redis starts on `localhost:6379`.

### Endpoints

| Method | Path          | Description                        |
|--------|---------------|------------------------------------|
| GET    | /api/status   | Health check — try hammering this  |
| GET    | /api/data     | Demo data endpoint                 |
| GET    | /api/config   | View current rate limit config     |

### Test the Rate Limiter

**Hammer with curl (triggers 429s):**
```bash
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-API-Key: demo-user" \
    http://localhost:8080/api/status
done
```

**Expected output:**
```
200
200
200
...
429   ← bucket empty
429
429
```

**Check response headers:**
```bash
curl -v -H "X-API-Key: demo-user" http://localhost:8080/api/status 2>&1 | grep X-Rate
# X-RateLimit-Limit: 10
# X-RateLimit-Remaining: 9
```

### Configure Limits

Override via environment variables:
```bash
RATE_LIMIT_CAPACITY=100 RATE_LIMIT_REFILL=10 docker-compose up
```

Or edit `docker-compose.yml` directly.

---

## Project Structure

```
api-rate-limiter/
├── src/main/java/com/victoria/ratelimiter/
│   ├── RateLimiterApplication.java        # Entry point
│   ├── config/
│   │   ├── RedisConfig.java               # JedisPool setup
│   │   └── WebConfig.java                 # Interceptor registration
│   ├── interceptor/
│   │   └── RateLimitInterceptor.java      # Middleware — runs before every /api/** request
│   └── service/
│       └── TokenBucketService.java        # Loads + executes Lua script against Redis
├── src/main/resources/
│   ├── application.yml                    # All config in one place
│   └── scripts/token_bucket.lua          # Atomic Token Bucket logic
├── src/test/
│   └── TokenBucketServiceTest.java        # Integration tests
├── Dockerfile                             # Multi-stage build
├── docker-compose.yml                     # App + Redis together
└── README.md
```

---

## Key Design Decisions

**Fail open on Redis unavailability** — if Redis goes down, requests are allowed through rather than rejecting all traffic. Change `TokenBucketService` to fail closed if your use case demands it.

**IP → API Key priority** — identifier resolution prefers the `X-API-Key` header over IP address, allowing per-user limits even behind shared NAT.

**Connection pooling** — `JedisPool` with configurable pool size prevents connection exhaustion under load.

---

*Built by Gade Victoria — distributed systems project demonstrating Redis-backed rate limiting for Salesforce-scale API ecosystems.*
