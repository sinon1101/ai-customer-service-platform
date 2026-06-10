# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

hm-dianping (黑马点评) — a Yelp-like local life service platform built as a Redis practical exercise project. Features shop browsing, user blogs, voucher seckill (flash sales), follow/feed, and check-in signing. The project heavily demonstrates Redis usage patterns: caching, distributed locks, streams, geo queries, bitmaps, and sorted sets.

## Repository Layout

The repo holds both tiers, separated at the top level:

- **backend/** — the Spring Boot Maven project (`backend/pom.xml`, `backend/src/`). All `mvn` commands must be run from inside `backend/`; there is no parent/aggregator POM at the repo root.
- **frontend/** — a bundled `nginx-1.18.0` that serves the static SPA (`frontend/nginx-1.18.0/html/hmdp/`) and reverse-proxies `/api` to the backend. nginx must be started from its own directory (`frontend/nginx-1.18.0/`) because `nginx.conf` uses paths relative to that prefix.

## Build & Run

```bash
# All Maven commands run from the backend/ directory
cd backend

# Build (Java 8, Maven)
mvn clean package -DskipTests

# Run
mvn spring-boot:run
# or
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=HmDianPingApplicationTests
```

```bash
# Start the frontend (from its own directory so relative paths resolve)
cd frontend/nginx-1.18.0
start nginx           # Windows: launch
nginx -s stop         # stop
nginx -s reload       # reload conf
```

## Prerequisites

- Java 8
- MySQL 5.x on localhost:3306, database `hmdp` (schema in `backend/src/main/resources/db/hmdp.sql`)
- Redis on localhost:6379 (no password)
- Backend runs on port 8081 (Spring `application.yaml`). nginx listens on **8080** and proxies `/api/**` → upstream `backend` (`127.0.0.1:8081` and `127.0.0.1:8082`). To run a single backend instance, either start one on 8081 or update the `upstream backend` block in `nginx.conf`. **Open the app at http://localhost:8080.**
- Blog/shop image uploads are written to `frontend/nginx-1.18.0/html/hmdp/imgs/` so nginx can serve them. The target directory is hardcoded in `SystemConstants.IMAGE_UPLOAD_DIR` (currently an absolute Windows path) — update it if the repo moves.

## Architecture

Spring Boot 2.3.12 app with a standard layered structure under `com.hmdp`:

- **controller/** — REST endpoints. No auth required for `/user/code`, `/user/login`, `/blog/hot`, `/shop/**`, `/shop-type/**`, `/upload/**`, `/voucher/**`. Everything else requires a valid token.
- **service/impl/** — Business logic. Most services extend MyBatis-Plus `ServiceImpl`.
- **mapper/** — MyBatis-Plus mapper interfaces. Custom XML only for `VoucherMapper`.
- **entity/** — JPA/MyBatis-Plus entities mapped to DB tables.
- **dto/** — `Result` (unified API response), `UserDTO`, `LoginFormDTO`, `ScrollResult` (feed pagination).
- **config/** — `MvcConfig` (interceptor chain), `RedissonConfig` (single-node Redisson), `MybatisConfig`.
- **utils/** — Redis utilities and interceptors (see below).

## Key Redis Patterns

All Redis key prefixes and TTLs are centralized in `RedisConstants`.

- **Auth**: SMS code stored in Redis (`login:code:{phone}`), user session as Hash (`login:token:{token}`). Two-layer interceptor: `RefreshTokenIntercepter` (order 0, all paths, refreshes token TTL) → `LoginIntercepter` (order 1, protected paths, checks ThreadLocal). Token passed via `authorization` header.
- **Cache**: `CacheClient` is a reusable utility with two strategies — `queryWithPassThrough` (null-value caching to prevent cache penetration) and `queryWithLogicalExpire` (logical expiration with mutex lock to prevent cache breakdown). Shop queries currently use logical expiration.
- **Distributed Lock**: `SimpleRedisLock` (custom, Lua-based unlock in `LuaScripts/unlock.lua`) and Redisson (`RLock`) for voucher order deduplication.
- **Seckill**: Lua script (`LuaScripts/seckill.lua`) atomically checks stock + one-per-user, decrements stock, and publishes to Redis Stream `stream.orders`. `VoucherOrderServiceImpl` consumes from a blocking queue (stream consumer is commented out but present).
- **ID Generation**: `RedisIdWorker` — 64-bit IDs composed of timestamp (32 bits) + Redis INCR sequence per day.
- **Blog Likes**: ZSet (`blog:liked:{id}`) with timestamp scores for ordering. Top-5 query uses `ZRANGE`.
- **Feed**: Push model — on blog creation, pushes blog ID into each follower's ZSet (`feed:{userId}`). Scroll pagination via `reverseRangeByScoreWithScores`.
- **Follow**: Common follows computed via `SINTERSTORE` on Redis Sets (`follows:{userId}`).
- **Shop Geo**: GEO queries (`shop:geo:{typeId}`) for nearby shop search with distance.
- **User Sign-in**: Bitmaps (`sign:{userId}:yyyyMM`) for daily check-in and consecutive-day counting via BITFIELD.

## Important Notes

- `@EnableAspectJAutoProxy(exposeProxy = true)` is enabled — `AopContext.currentProxy()` is used in `VoucherOrderServiceImpl` to get the transactional proxy for self-invocation.
- The codebase contains many commented-out alternative implementations showing the evolution of each feature (session → Redis, simple lock → Redisson, blocking queue → Redis Stream). These are intentional learning artifacts.
- `UserHolder` is a ThreadLocal-based context holder for the current user — set by interceptors, cleared in `afterCompletion`.
- Shop update uses Cache-Aside pattern: update DB first, then delete cache (`ShopServiceImpl.update`).
