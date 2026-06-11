# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo (local dir still named `hm-dianping`) has been **transformed** from the 黑马点评 (a Yelp-like Redis exercise app) into a **multi-tenant AI customer-service SaaS platform**. The original dianping Redis/middleware infrastructure (auth interceptors, `CacheClient`, distributed locks, `RedisIdWorker`, Streams) is **retained and reused**; the business domain is being replaced with a production-grade AI customer service whose core selling point is **high-concurrency governance** (caching, rate limiting, load shedding, degradation/circuit-breaking, isolation) to tame a slow & expensive LLM backend.

- **Design blueprint**: `AI智能客服平台-改造设计方案.md` (repo root).
- **Cross-session entry point**: `项目进度.md` (repo root) — current milestone, next steps, env connection info, **changelog (append one entry per meaningful change; read this first when starting a session)**.
- **Status**: M0 (env) and **M1 (skeleton) complete** — framework upgraded to Spring Boot 3.4.5 / Java 21; multi-tenant model + tenant-aware auth + tenant/knowledge-base CRUD + a minimal Spring AI `/chat` to 百炼. Next is **M2** (knowledge-base ingestion pipeline).

> **Legacy note**: the original dianping business code (shop/blog/voucher/follow/user controllers & services) still exists and compiles, but is **inactive** — its tables are not in the new DB and its endpoints sit behind the new auth gate. Treat it as reference/infrastructure, not live functionality. The `frontend/` nginx SPA is likewise the old dianping UI, not yet adapted to the new APIs.

## Repository Layout

- **backend/** — the Spring Boot Maven project (`backend/pom.xml`, `backend/src/`). All `mvn` commands run from inside `backend/`; there is no parent/aggregator POM at the repo root.
- **deploy/** — local dev middleware orchestration (`deploy/docker-compose.yml`): MySQL 8 + Redis Stack + RocketMQ. DB init scripts in `deploy/mysql/init/`.
- **frontend/** — bundled `nginx-1.18.0` serving the legacy dianping SPA (not used by the new platform yet).

## Build & Run

```bash
# All Maven commands run from the backend/ directory
cd backend

# Build (Java 21, Maven)
mvn clean package -DskipTests

# Run — IMPORTANT: launch via PowerShell, not bash, so the JVM inherits the
# user-scoped API-KEY env var (a hyphenated env var bash won't re-export).
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

```bash
# Start middleware (from deploy/)
cd deploy
docker compose up -d           # all middleware
docker compose up -d mysql redis   # just DB + Redis (enough for most backend work)
```

> The `deploy/mysql/init/*.sql` scripts only auto-run on **first** MySQL init (empty volume). The volume already exists, so apply schema changes manually:
> `docker exec -i aics-mysql mysql -uroot -p123456 ai_customer_service < deploy/mysql/init/01-schema.sql`

## Prerequisites

- **Java 21** (default JDK on this machine; Maven runs on it). Spring Boot **3.4.5**.
- **Docker** middleware (see `项目进度.md` for the full port table):
  - MySQL 8 on `127.0.0.1:3308`, db `ai_customer_service`, root/`123456` (container `aics-mysql`).
  - Redis Stack on `127.0.0.1:6381` (RediSearch for future vectors; RedisInsight at `:8001`, container `aics-redis`).
  - RocketMQ NameServer `9876` / Broker `10911` / Dashboard `http://localhost:8089`.
- **百炼 (DashScope) API Key** in the system env var `API-KEY`; backend reads it via `spring.ai.dashscope.api-key: ${API-KEY}`. Chat model: `qwen-plus`.
- Backend runs on port **8081**.
- Local demo accounts (in DB): `acme_admin` / `globex_admin`, password `123456` (tenants `acme` / `globex`).

## Architecture

Spring Boot 3.4.5 / Java 21, layered under `com.hmdp` (jakarta.* namespace, MyBatis-Plus boot3 starter):

- **auth/** — multi-tenant auth (the M1 rewrite). `UserContext` (ThreadLocal carrying `LoginUser` incl. `tenantId`, with `getTenantId()`), `RefreshTokenInterceptor` (order 0, all paths — loads token Hash into context, refreshes TTL), `LoginInterceptor` (order 1, protected paths — 401 if no context). Wired in `config/MvcConfig`; public paths: `/auth/login`, `/auth/register`.
- **controller/** — new platform endpoints: `AuthController` (`/auth/register|login|me|logout`), `TenantController` (`/tenant/current`), `KnowledgeBaseController` (`/kb` CRUD), `ChatController` (`/chat`). Plus legacy dianping controllers (inactive).
- **service/impl/** — business logic; most extend MyBatis-Plus `ServiceImpl`. New: `AuthServiceImpl`, `TenantServiceImpl`, `SysUserServiceImpl`, `KnowledgeBaseServiceImpl`, `ChatServiceImpl`.
- **entity/** — new tenant-scoped entities `Tenant`, `SysUser`, `KnowledgeBase` (each carries `tenant_id` for logical isolation).
- **dto/** — `Result` (unified response), `LoginUser` (auth payload), `RegisterFormDTO`, `AuthLoginFormDTO`, `KnowledgeBaseFormDTO`, `ChatRequestDTO`.
- **config/** — `MvcConfig` (interceptor chain), `RedissonConfig` (single-node Redisson → 6381), `MybatisConfig` (pagination), `WebExceptionAdvice`.

### Multi-tenancy (logical isolation)
One deployment serves N tenants. Every business table carries `tenant_id`; login resolves the tenant from a globally-unique `username` and stores `tenantId` in the token Hash. Service layer **always filters by `UserContext.getTenantId()`** — cross-tenant access simply finds nothing (see `KnowledgeBaseServiceImpl.getOwned`). Redis keys use the tenant prefix helper `RedisConstants.tenantKey(tenantId, suffix)` → `t:{tenantId}:{suffix}`.

### AI (Spring AI + 百炼)
`spring-ai-alibaba-starter-dashscope:1.0.0.2` (built on Spring AI 1.0 GA). `ChatServiceImpl` injects Spring AI `ChatClient` and calls `prompt().user(msg).call().content()`. Swapping models / adding RAG later won't change this call site.

## Key Redis Patterns (retained dianping infrastructure)

Key prefixes and TTLs are centralized in `RedisConstants`.

- **Auth (multi-tenant)**: user session as Hash `login:token:{token}` holding `LoginUser` incl. `tenantId`/`role`. Two-layer interceptor in `com.hmdp.auth`. Token via `authorization` header.
- **Cache**: `CacheClient` — `queryWithPassThrough` (null-value caching vs penetration) and `queryWithLogicalExpire` (logical expiry + mutex vs breakdown). To evolve into the **semantic cache** (M4).
- **Distributed Lock**: `SimpleRedisLock` (Lua unlock in `LuaScripts/unlock.lua`) and Redisson `RLock` — to be reused for **agent ticket grabbing** (M6).
- **ID Generation**: `RedisIdWorker` — timestamp + per-day INCR sequence (session/ticket IDs).
- **Redis Stream**: dianping seckill stream experience → migrates to RocketMQ for the async pipeline (M2) and ticket message flow (M6).
- Remaining ZSet/GEO/Bitmap patterns (blog likes, feed, geo, sign-in) live in the inactive legacy code as reference.

## Important Notes

- **Launch via PowerShell** for local runs — the `API-KEY` env var is user-scoped and hyphenated; bash won't reliably pass it to the JVM.
- `@EnableAspectJAutoProxy(exposeProxy = true)` is enabled (`AopContext.currentProxy()` in legacy `VoucherOrderServiceImpl`).
- Spring Boot 3 prohibits circular references by default — self-injected proxies (e.g. legacy `BlogServiceImpl`) use `@Lazy` to break the cycle.
- MyBatis-Plus 3.5.x `count()` returns `Long` (not `int`); pagination needs the separate `mybatis-plus-jsqlparser` module.
- After any meaningful change, append a `项目进度.md` changelog entry and commit (the repo is hosted privately on GitHub: `sinon1101/ai-customer-service-platform`).
