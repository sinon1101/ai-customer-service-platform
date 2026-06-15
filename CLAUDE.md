# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo (local dir still named `hm-dianping`) has been **transformed** from the 黑马点评 (a Yelp-like Redis exercise app) into a **multi-tenant AI customer-service SaaS platform**. The original dianping Redis/middleware infrastructure (auth interceptors, `CacheClient`, distributed locks, `RedisIdWorker`, Streams) is **retained and reused**; the business domain is being replaced with a production-grade AI customer service whose core selling point is **high-concurrency governance** (caching, rate limiting, load shedding, degradation/circuit-breaking, isolation) to tame a slow & expensive LLM backend.

- **Design blueprint**: `AI智能客服平台-改造设计方案.md` (repo root).
- **Cross-session entry point**: `项目进度.md` (repo root) — current milestone, next steps, env connection info, **changelog (append one entry per meaningful change; read this first when starting a session)**.
- **Status**: M0 (env), **M1 (skeleton), M2 (knowledge-base ingestion pipeline), M3 (RAG Q&A), and M4 (semantic cache) complete** — Spring Boot 3.4.5 / Java 21; multi-tenant model + tenant-aware auth + tenant/knowledge-base CRUD; an async ingestion pipeline (upload → RocketMQ → chunk + embed → RediSearch vector index); RAG Q&A (vector recall filtered by `tenantId`/`kbId` → prompt + citations → LLM → non-stream `/chat` & SSE `/chat/stream`, plus Redis-backed multi-turn context); and a **semantic cache** short-circuit in front of recall (separate cache vector index, 0.92 hit threshold, cache trio: null-value/mutex/random-TTL; first-turn only). Next is **M5** (high-concurrency governance: rate limiting, load shedding, degradation/circuit-breaking, isolation).

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
- **controller/** — new platform endpoints: `AuthController` (`/auth/register|login|me|logout`), `TenantController` (`/tenant/current`), `KnowledgeBaseController` (`/kb` CRUD + `/kb/{kbId}/documents` upload/list), `ChatController` (`/chat` non-stream RAG + `/chat/stream` SSE; captures `tenantId` from `UserContext` on the request thread before the async stream runs). Plus legacy dianping controllers (inactive).
- **service/impl/** — business logic; most extend MyBatis-Plus `ServiceImpl`. New: `AuthServiceImpl`, `TenantServiceImpl`, `SysUserServiceImpl`, `KnowledgeBaseServiceImpl`, `KbDocumentServiceImpl`, `ChatServiceImpl`.
- **entity/** — new tenant-scoped entities `Tenant`, `SysUser`, `KnowledgeBase`, `KbDocument` (each carries `tenant_id` for logical isolation).
- **mq/** — RocketMQ pipeline (M2): `DocIngestMessage` (payload `{tenantId,kbId,docId}`), `DocIngestConsumer` (self-managed `DefaultMQPushConsumer`, the chunk→embed→write-vector worker).
- **cache/** — `SemanticCache` (M4): the semantic-cache component (lookup/save against the cache vector index + the cache trio: mutex `tryLock`/`unlock`/`awaitUnlock`, random-TTL save, null-value short-TTL).
- **dto/** — `Result` (unified response), `LoginUser` (auth payload), `RegisterFormDTO`, `AuthLoginFormDTO`, `KnowledgeBaseFormDTO`, `DocUploadDTO`, `ChatRequestDTO` (`message`/`kbId`/`conversationId`), `ChatResponseDTO` (`conversationId`/`answer`/`sources`/`cached`).
- **constant/** — `IngestConstants` (RocketMQ topic/group, RediSearch index/prefix), `ChatConstants` (RAG topK/similarity threshold, multi-turn window/TTL, history key suffix, `NOT_FOUND_MARKER`), `CacheConstants` (M4: cache index/prefix, 0.92 hit threshold, answer/null TTLs + jitter, mutex lock key/TTL/wait).
- **config/** — `MvcConfig` (interceptor chain), `RedissonConfig` (single-node Redisson → 6381), `RocketMQConfig` (native `DefaultMQProducer` bean), `VectorStoreConfig` (dedicated `JedisPooled` + **two** Spring AI `RedisVectorStore` beans: `vectorStore` = KB index `aics-kb-index` with `tenantId`/`kbId` TAG + `docId`/`docName` TEXT; `semanticCacheStore` = cache index `aics-cache-index` with `tenantId`/`kbId` TAG + `answer`/`sources`/`answered` TEXT), `MybatisConfig` (pagination), `WebExceptionAdvice`.

### Multi-tenancy (logical isolation)
One deployment serves N tenants. Every business table carries `tenant_id`; login resolves the tenant from a globally-unique `username` and stores `tenantId` in the token Hash. Service layer **always filters by `UserContext.getTenantId()`** — cross-tenant access simply finds nothing (see `KnowledgeBaseServiceImpl.getOwned`). Redis keys use the tenant prefix helper `RedisConstants.tenantKey(tenantId, suffix)` → `t:{tenantId}:{suffix}`.

### AI (Spring AI + 百炼)
`spring-ai-alibaba-starter-dashscope:1.0.0.2` (built on Spring AI 1.0 GA). `ChatServiceImpl` injects Spring AI `ChatClient` (`prompt().system(...).messages(history).user(msg).call()/.stream()`). Chat model `qwen-plus`; embedding via `text-embedding-v3` (1024-dim), configured under `spring.ai.dashscope.embedding`.

### RAG Q&A engine (M3)
`ChatServiceImpl` is the RAG pipeline; both entry points take `tenantId` as an explicit arg (the controller reads `UserContext` on the request thread — the streaming `Flux` runs after the interceptor has cleared the ThreadLocal).
- **Recall**: `retrieve()` builds a `FilterExpressionBuilder` filter on `tenantId` (+ optional `kbId`) and calls `vectorStore.similaritySearch(SearchRequest)` (topK 4, similarityThreshold 0.3). Metadata values are stored as strings, so filter values use `String.valueOf`. **Requires the `tenantId`/`kbId` TAG fields declared in `VectorStoreConfig`** — RediSearch can't filter undeclared fields.
- **Prompt + citations**: retrieved chunks become a system prompt ("answer only from the material; if absent, say so — don't fabricate"); the same chunks are returned as `sources` (docName + score + truncated snippet).
- **Two entry points**: `POST /chat` returns `{conversationId, answer, sources}`; `POST /chat/stream` is SSE (`text/event-stream`) emitting a `meta` event (conversationId + sources), then per-token `message` events, then `done`.
- **Multi-turn context**: a Redis List at `RedisConstants.tenantKey(tenantId, "chat:hist:" + conversationId)`; each turn appends user+assistant JSON, trims to `HISTORY_WINDOW` (10), and sets `HISTORY_TTL` (30 min). Loaded back into `UserMessage`/`AssistantMessage` for the next prompt. First-turn `conversationId` is minted by `RedisIdWorker`. Implemented directly on Redis (not Spring AI `ChatMemory`) to keep the "Redis-bounded context window" selling point explicit.

### Semantic cache (M4)
A cache short-circuit **in front of** RAG recall (`ChatServiceImpl` + `cache/SemanticCache`): saves a slow/expensive LLM call when a similar question was answered before. **Only for first-turn questions** (`loadHistory` empty) — with conversation history the answer depends on context, so keying by question alone would be wrong.
- **Separate cache vector index**: `semanticCacheStore` bean (index `aics-cache-index`, prefix `aics:cache:`, reusing the same `JedisPooled`), kept apart from the KB index so cache entries can carry their own TTL. Two same-type `RedisVectorStore` beans are distinguished by `@Resource` field name (`vectorStore` vs `semanticCacheStore`).
- **Hit threshold 0.92** (vs recall's 0.3): the cache must be far stricter or it returns a wrong (merely-similar) answer. Paraphrases still hit (semantic, not literal).
- **Cache trio**: **penetration** — not-found answers are also cached but with a short TTL (`NULL_ANSWER_TTL_SECONDS` 120s); `answered` is judged by whether the answer text contains `ChatConstants.NOT_FOUND_MARKER` (NOT by `docs` being non-empty — the loose 0.3 recall returns chunks even for off-topic questions). **Breakdown** — on miss, `tryLock` (Redis `setIfAbsent`, key = tenant+kbId+question md5) lets one thread call the LLM and backfill; waiters `awaitUnlock` (cheap poll of the lock key, no re-embedding) then re-read the cache. `LOCK_WAIT_MAX_MILLIS` (8s) must exceed one LLM call. **Avalanche** — answered-entry TTL = `ANSWER_TTL_SECONDS` (2h) + random `[0, ANSWER_TTL_JITTER_SECONDS)` (30min) jitter.
- **TTL mechanism**: `RedisVectorStore` has no TTL, so after `add()` we `EXPIRE` the underlying key `aics:cache:{docId}`; RediSearch lazily drops expired keys from the index.
- **Return-field gotcha**: undeclared metadata is stored in the JSON but **not returned** by RediSearch search — so `answer`/`sources`/`answered` must be declared as TEXT metadata fields (else a hit comes back with an empty answer).
- **Streaming**: on a `/chat/stream` cache hit the cached answer is replayed token-by-token (per Unicode code point) with a `meta` event carrying `cached:true`; on a miss the lock holder backfills the cache in the `done` step and releases the lock via `doFinally`.

### Knowledge-base ingestion pipeline (M2)
Async pipeline so the slow/expensive embedding call never blocks the user request:
- `POST /kb/{kbId}/documents` validates KB ownership → persists `kb_document` (status `PENDING`) → sends a RocketMQ message (`KB_DOC_INGEST` topic) → returns `docId` immediately.
- `DocIngestConsumer` consumes: `PROCESSING` → `TokenTextSplitter` chunk → build Spring AI `Document` (metadata `tenantId`/`kbId`/`docId`/`docName`) → `vectorStore.add()` (embeds via 百炼 + writes RediSearch) → `COMPLETED` + chunk_count, `knowledge_base.doc_count++`. On failure: `FAILED` + `error_msg`, returns `RECONSUME_LATER` for RocketMQ retry/DLQ.
- **RocketMQ** uses the **native `rocketmq-client` 5.x** with hand-written producer/consumer beans, deliberately *not* the official `rocketmq-spring-boot-starter` (javax / autoconfig friction on Boot 3).
- **Vector store** uses Spring AI's `RedisVectorStore` (RediSearch) on a **dedicated `JedisPooled`** — the cache/auth path stays on Lettuce; the two Redis clients coexist. All vectors share one index (`aics-kb-index`); tenant isolation is via Document metadata, to be filtered at recall time in M3 (M2 only writes).
- Frontend polls `GET /kb/{kbId}/documents` for status progression.

## Key Redis Patterns (retained dianping infrastructure)

Key prefixes and TTLs are centralized in `RedisConstants`.

- **Auth (multi-tenant)**: user session as Hash `login:token:{token}` holding `LoginUser` incl. `tenantId`/`role`. Two-layer interceptor in `com.hmdp.auth`. Token via `authorization` header.
- **Cache**: `CacheClient` — `queryWithPassThrough` (null-value caching vs penetration) and `queryWithLogicalExpire` (logical expiry + mutex vs breakdown). Its cache-trio ideas were carried into the M4 **semantic cache** (`cache/SemanticCache`), which applies the same penetration/breakdown/avalanche playbook over a vector index instead of an exact key.
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
