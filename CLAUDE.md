# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo (local dir still named `hm-dianping`) has been **transformed** from the 黑马点评 (a Yelp-like Redis exercise app) into a **multi-tenant AI customer-service SaaS platform**. The original dianping Redis/middleware infrastructure (auth interceptors, `CacheClient`, distributed locks, `RedisIdWorker`, Streams) is **retained and reused**; the business domain is being replaced with a production-grade AI customer service whose core selling point is **high-concurrency governance** (caching, rate limiting, load shedding, degradation/circuit-breaking, isolation) to tame a slow & expensive LLM backend.

- **Design blueprint**: `AI智能客服平台-改造设计方案.md` (repo root).
- **Cross-session entry point**: `项目进度.md` (repo root) — current milestone, next steps, env connection info, **changelog (append one entry per meaningful change; read this first when starting a session)**.
- **Status**: M0 (env), **M1 (skeleton), M2 (knowledge-base ingestion pipeline), M3 (RAG Q&A), M4 (semantic cache), M5 (high-concurrency governance), M6 (human agent), and M7 (stats dashboard + load test) complete** — Spring Boot 3.4.5 / Java 21; multi-tenant model + tenant-aware auth + tenant/knowledge-base CRUD; an async ingestion pipeline (upload → RocketMQ → chunk + embed → RediSearch vector index); RAG Q&A (vector recall filtered by `tenantId`/`kbId` → prompt + citations → LLM → non-stream `/chat` & SSE `/chat/stream`, plus Redis-backed multi-turn context); a **semantic cache** short-circuit in front of recall (separate cache vector index, 0.92 hit threshold, cache trio: null-value/mutex/random-TTL; first-turn only); a **high-concurrency governance** layer wrapping the LLM call (Redis+Lua token-bucket rate limiting per tenant+user, per-tenant semaphore bulkhead isolation, a hand-rolled Redis circuit breaker, and static-FAQ degradation fallback); a **human-agent** layer (transfer-to-human ticket → waiting pool → multi-agent ticket grabbing via Redisson `RLock` + DB conditional-update double-guard → agent↔visitor realtime chat over WebSocket fanned out cross-instance by Redis Pub/Sub); and a **stats dashboard** (per-tenant cumulative metrics in a Redis Hash via `HINCRBY` — chat volume / cache-hit rate / degrade rate / rate-limited / **real LLM token usage** read from Spring AI `ChatResponse.getMetadata().getUsage()` — plus agent efficiency aggregated from the `ticket` table by SQL, exposed at `GET /dashboard/overview`) with a `loadtest/` harness (JMeter `/chat` throughput plan + hand-rolled PowerShell assertion scripts). **All backend milestones (M0–M7) are done; the only remaining workflow is the frontend SPA.**

> **Legacy note**: the original dianping business code (shop/blog/voucher/follow/user controllers & services) still exists and compiles, but is **inactive** — its tables are not in the new DB and its endpoints sit behind the new auth gate. Treat it as reference/infrastructure, not live functionality. The `frontend/` nginx SPA is likewise the old dianping UI, not yet adapted to the new APIs.

## Repository Layout

- **backend/** — the Spring Boot Maven project (`backend/pom.xml`, `backend/src/`). All `mvn` commands run from inside `backend/`; there is no parent/aggregator POM at the repo root.
- **deploy/** — local dev middleware orchestration (`deploy/docker-compose.yml`): MySQL 8 + Redis Stack + RocketMQ. DB init scripts in `deploy/mysql/init/`.
- **frontend/** — bundled `nginx-1.18.0` serving the legacy dianping SPA (not used by the new platform yet).
- **loadtest/** — M7 load-test harness: JMeter `chat-load.jmx` (`/chat` throughput) + hand-rolled PowerShell assertion scripts (`ticket-grab`/`circuit-breaker`/`rate-limit-isolation` + `_common.ps1`). `.ps1` files are **UTF-8 with BOM** (PowerShell 5.1 otherwise decodes them as ANSI/GBK and mangles the Chinese).

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
- **controller/** — new platform endpoints: `AuthController` (`/auth/register|login|me|logout`), `TenantController` (`/tenant/current`), `KnowledgeBaseController` (`/kb` CRUD + `/kb/{kbId}/documents` upload/list), `ChatController` (`/chat` non-stream RAG + `/chat/stream` SSE; captures `tenantId` from `UserContext` on the request thread before the async stream runs; M5 calls `RateLimiter` at entry), `GovernanceController` (M5, ADMIN: `GET /governance/status`, `POST /governance/fault?on=`), `TicketController` (M6: `POST /ticket/transfer`, `GET /ticket/pending`, `POST /ticket/{id}/claim`, `POST /ticket/{id}/close`, `GET /ticket/mine`, `GET /ticket/{id}`), `DashboardController` (M7, ADMIN: `GET /dashboard/overview` — cumulative chat/token/agent metrics + live governance gauges). Plus legacy dianping controllers (inactive).
- **service/impl/** — business logic; most extend MyBatis-Plus `ServiceImpl`. New: `AuthServiceImpl`, `TenantServiceImpl`, `SysUserServiceImpl`, `KnowledgeBaseServiceImpl`, `KbDocumentServiceImpl`, `ChatServiceImpl`, `TicketServiceImpl` (M6 ticket grab), `ChatMessageServiceImpl` (M6 agent-chat persistence).
- **entity/** — new tenant-scoped entities `Tenant`, `SysUser`, `KnowledgeBase`, `KbDocument`, `Ticket` (M6, id from `RedisIdWorker`/`IdType.INPUT`), `ChatMessage` (M6) — each carries `tenant_id` for logical isolation.
- **mq/** — RocketMQ pipeline (M2): `DocIngestMessage` (payload `{tenantId,kbId,docId}`), `DocIngestConsumer` (self-managed `DefaultMQPushConsumer`, the chunk→embed→write-vector worker).
- **cache/** — `SemanticCache` (M4): the semantic-cache component (lookup/save against the cache vector index + the cache trio: mutex `tryLock`/`unlock`/`awaitUnlock`, random-TTL save, null-value short-TTL).
- **governance/** — high-concurrency governance (M5): `RateLimiter` (Redis+Lua token bucket, `token_bucket.lua`; tenant+user double-layer), `TenantBulkhead` (in-JVM per-tenant `Semaphore` isolation), `LlmCircuitBreaker` (hand-rolled Redis state machine via `circuit_breaker.lua`; `allow`/`recordSuccess`/`recordFailure`/`state`), `FaultInjector` (demo fault-injection toggle in Redis `aics:fault:llm`).
- **ws/** — M6 realtime session: `ChatWebSocketHandler` (`TextWebSocketHandler` for `/ws/chat`), `WsHandshakeInterceptor` (query-token auth + ticket-participant check), `SessionRegistry` (in-JVM ticketId→sessions), `TicketMessageRelay` (Redis Pub/Sub `MessageListener`: `publish` to `ws:ticket:{id}` + deliver received frames to local sessions).
- **metrics/** — M7 stats: `MetricsCollector` — per-tenant cumulative counters in a Redis Hash `t:{tenantId}:metrics` via `HINCRBY` (chat requests / cache-eligible+hit / degraded / llm-calls / rate-limited / prompt+completion+total tokens). Side-channel: `null`-tenant-tolerant, swallows exceptions, never affects the chat path. `snapshot()` reads them back for the dashboard.
- **exception/** — `RateLimitException` (→ 429) and `OverloadException` (→ 503), mapped in `config/WebExceptionAdvice`.
- **dto/** — `Result` (unified response), `LoginUser` (auth payload), `RegisterFormDTO`, `AuthLoginFormDTO`, `KnowledgeBaseFormDTO`, `DocUploadDTO`, `ChatRequestDTO` (`message`/`kbId`/`conversationId`), `ChatResponseDTO` (`conversationId`/`answer`/`sources`/`cached`/`degraded`), `TransferRequestDTO` (M6: `conversationId`/`kbId`/`reason`/`lastQuestion`), `WsMessage` (M6: realtime + pub/sub payload).
- **constant/** — `IngestConstants` (RocketMQ topic/group, RediSearch index/prefix), `ChatConstants` (RAG topK/similarity threshold, multi-turn window/TTL, history key suffix, `NOT_FOUND_MARKER`, M5 `RATE_LIMITED_MESSAGE`/`DEGRADE_FALLBACK_ANSWER`), `CacheConstants` (M4: cache index/prefix, 0.92 hit threshold, answer/null TTLs + jitter, mutex lock key/TTL/wait), `GovernanceConstants` (M5: rate-limit key suffixes + tenant/user bucket capacity & refill, bulkhead permits, circuit-breaker window/min-calls/fail-rate/cooldown, LLM timeout, fault-inject key), `TicketConstants` (M6: ticket-id prefix, status/reason/role/msg-type enums, `/ws/chat` path + `ws:ticket:*` channel, claim-lock key/wait/lease, system-prompt templates), `MetricsConstants` (M7: metrics Hash suffix + counter field names + `ALL_FIELDS` for stable snapshot).
- **config/** — `MvcConfig` (interceptor chain; excludes `/ws/**` from `LoginInterceptor`), `RedissonConfig` (single-node Redisson → 6381), `RocketMQConfig` (native `DefaultMQProducer` bean), `VectorStoreConfig` (dedicated `JedisPooled` + **two** Spring AI `RedisVectorStore` beans: `vectorStore` = KB index `aics-kb-index` with `tenantId`/`kbId` TAG + `docId`/`docName` TEXT; `semanticCacheStore` = cache index `aics-cache-index` with `tenantId`/`kbId` TAG + `answer`/`sources`/`answered` TEXT), `WebSocketConfig` (M6: register `/ws/chat` handler + handshake interceptor + a `RedisMessageListenerContainer` subscribing `ws:ticket:*`), `MybatisConfig` (pagination), `WebExceptionAdvice`.

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

### High-concurrency governance (M5)
Four governance gates around the slow/expensive LLM call — the point is **not** to protect 百炼 but to protect **our own LLM quota/bill, our server threads, and tenant fairness**. Governance only kicks in on overload/failure; the normal path behaves exactly as M4. The request chain: `/chat(/stream)` → (controller request thread) **① rate limit** → (service) semantic-cache hit returns directly (no governance resources used) → **② bulkhead acquire** → **③ circuit-breaker allow** → LLM call (with timeout) success/failure → **④ degrade fallback**; bulkhead released in `finally`/`doFinally`.
- **① Rate limiting** (`governance/RateLimiter` + `LuaScripts/token_bucket.lua`): atomic refill+consume token bucket, **tenant + user double layer** (`t:{tenantId}:rl:chat` protects overall quota; `t:{tenantId}:rl:chat:u:{userId}` stops a single user spamming). Either bucket empty → `RateLimitException` → `WebExceptionAdvice` maps **HTTP 429**; the SSE path turns it into an `error` event. Reuses the dianping seckill/unlock `DefaultRedisScript` + `ClassPathResource` pattern.
- **② Isolation / bulkhead** (`governance/TenantBulkhead`): a lazily-created in-JVM `Semaphore` per tenant (`TENANT_BULKHEAD_PERMITS`). Acquired **only right before an actual LLM call** (cache hits don't take a permit). A tenant's burst can at most exhaust its own permits (then → degrade), never starving other tenants. (In-JVM = textbook bulkhead, single-node demo; the circuit breaker, by contrast, lives in Redis to be cross-instance.)
- **③ Circuit breaker** (`governance/LlmCircuitBreaker` + `LuaScripts/circuit_breaker.lua`): a hand-rolled `CLOSED/OPEN/HALF_OPEN` state machine (state + rolling-window counts in a Redis Hash `aics:cb:llm`, cross-instance). `allow()` before the call; `recordSuccess()`/`recordFailure()` after. Window fail-rate ≥ threshold with enough samples → **OPEN** (subsequent calls short-circuit in ms, no LLM); after cooldown one **HALF_OPEN** probe → success closes, failure re-opens. Thresholds in `GovernanceConstants`.
- **④ Degradation fallback**: on circuit-open / timeout / failure / bulkhead-overload, return a **static FAQ line** (`ChatConstants.DEGRADE_FALLBACK_ANSWER`, "转人工") with `degraded=true`. Deliberately **does not call the LLM/embedding again** (no cascading failure) and **does not backfill cache or save multi-turn history** (it's a transient line, not a real answer). Streaming degrade replays the FAQ per code-point with `meta` carrying `degraded:true`.
- **Timeout**: sync path runs the LLM call on a bounded executor with `Future.get(LLM_TIMEOUT)`; streaming path uses reactor `.timeout()`. A timeout counts as a circuit-breaker failure.
- **Load shedding (削峰)**: the synchronous/SSE chat path does not queue — "削峰" here = rate-limit + bulkhead reject-or-degrade on overload. True MQ-buffered peak shaving is the **M2 document pipeline** (already done).
- **Demo/observability** (chaos engineering): `governance/FaultInjector` (Redis toggle `aics:fault:llm`) forces the guarded LLM call to throw — used to deliberately trip the breaker without touching the real `API-KEY`. `GovernanceController` (ADMIN): `GET /governance/status` (breaker state, fault flag, bulkhead usage, bucket levels — also an M7 dashboard seed) and `POST /governance/fault?on=`.
- **Wiring note**: `ChatController` calls `RateLimiter` on the request thread (where `UserContext` still holds `userId`/`tenantId`); `ChatServiceImpl` funnels every LLM call (sync `generate`/`rawGenerate`, streaming `streamFromLlm`, multi-turn) through the bulkhead + breaker + timeout + degrade path. Thresholds live in `GovernanceConstants` (not externalized to yaml — tune in one place).

### Human-agent system (M6)
The "bot can't answer → transfer to human" closing loop. The high-concurrency story shifts here from **read-governance** to **write-contention**: multiple agents grab one ticket (seckill-homologous — only one wins, no double-assign). Roles: visitor = the logged-in `sys_user` who transfers; agent = same-tenant `role=AGENT` (ADMIN may double as agent in the demo). Tables: `ticket` (id from `RedisIdWorker`) + `chat_message` (`deploy/mysql/init/02-m6-ticket.sql`, applied manually).
- **Loop**: `/chat` degrade/no-answer or user request → (visitor) `POST /ticket/transfer` builds a `WAITING` ticket (idempotent per open `conversationId`) → (agent) `GET /ticket/pending` + `POST /ticket/{id}/claim` → assigned agent ↔ visitor chat over WebSocket → `POST /ticket/{id}/close`.
- **Ticket grab (the core, decided with user)**: **Redisson `RLock` (belt) + DB conditional UPDATE (suspenders), synchronous verdict, no MQ**. `TicketServiceImpl.claim`: `tryLock(lock:ticket:claim:{id}, wait=0)` mutexes concurrent grabs (loser yields immediately), then inside the lock `UPDATE ... WHERE id=? AND tenant_id=? AND status='WAITING'` — **affected-rows==1 means you won**; that `WHERE status='WAITING'` is an InnoDB row-lock-level CAS so even a failed lock can't double-assign. **MySQL is the ticket's single source of truth**, so the verdict is synchronous (immediate success/fail) — *not* the seckill "Redis deduct + MQ async DB write" pattern (grab contention is low; MQ would add a second source of truth and lose the synchronous result).
- **Realtime session (WebSocket + Redis Pub/Sub)**: endpoint `/ws/chat?ticketId=&token=`. **Handshake auth** (`WsHandshakeInterceptor`, since browsers can't set WS headers easily): token via query → login state / ticket belongs to tenant / connector is a **participant** (visitor or assigned agent) / ticket not closed; any failure rejects the handshake (401). `/ws/**` is excluded from the HTTP `LoginInterceptor` (auth done entirely at handshake). On a text frame → persist `chat_message` → `TicketMessageRelay.publish` to Redis channel `ws:ticket:{id}` → **every instance subscribes `ws:ticket:*`** and uses its local `SessionRegistry` (ticketId→sessions) to deliver to that ticket's participants (incl. sender echo). **Redis Pub/Sub, not RocketMQ**: chat is ephemeral/low-latency so pub/sub fan-out fits; MQ leans durable + latency (its stage is the M2 doc pipeline). Claim/close emit a `SYSTEM` notice (persisted + relayed).

### Stats dashboard + load test (M7)
The operational/governance read-out over everything built so far. The dashboard's job is **cumulative trend** (chat volume / cache-hit rate / degrade rate / rate-limited / token spend / agent efficiency) — complementary to M5 `GET /governance/status`, which is the **live gauge** (breaker state, bulkhead usage, bucket levels) at one instant.
- **Metric collection (Redis atomic counters)**: per-tenant Hash `t:{tenantId}:metrics`, `HINCRBY` atomic increments — cross-instance, zero new deps, same Redis as the M5 governance (chose *not* to add a MySQL event table or Micrometer/Prometheus). `metrics/MetricsCollector` is wired in as a side-channel at the chat funnels (`ChatServiceImpl`: request/cache-eligible/cache-hit/degraded/llm-call+tokens) and at `RateLimiter` (rate-limited before the 429 throw). It is `null`-tenant-tolerant and swallows its own exceptions — **metric failure never touches the answer path**.
- **Real token usage**: the LLM call switched from `.content()` to `.chatResponse()` to read `getMetadata().getUsage()` (prompt/completion/total — all `Integer` in Spring AI 1.0.0 GA; total falls back to prompt+completion). Sync path reads the response directly; **streaming path** uses `.stream().chatResponse()` + an `AtomicReference<Usage>` captured in `doOnNext` (DashScope sends usage on the final chunk) and recorded in the `doneEvent`. `degraded()`/`streamDegraded()` gained a `tenantId` param so a single chokepoint counts every degrade.
- **Dashboard endpoint**: `GET /dashboard/overview` (ADMIN) returns four blocks — **chat** (counts + server-computed hit-rate/degrade-rate), **tokens** (real usage + avg-per-call), **agent** (ticket status split + avg wait/handle seconds, aggregated from the `ticket` table's `create/assign/close_time` via `TIMESTAMPDIFF` in `TicketMapper.countByStatus/avgWaitSeconds/avgHandleSeconds`), and **governance** (reuses the M5 live gauges). No new table — metrics live in Redis, agent efficiency queries the existing `ticket` table.
- **Load test (`loadtest/`, two-track, decided with user)**: **JMeter** `chat-load.jmx` for `/chat` throughput (login → extract token → concurrent thread group; aggregate report + drives the dashboard up) — JMeter's strength, a résumé-grade artifact. **Hand-rolled PowerShell** for assertion-heavy concurrency where a GUI load tool is clumsy: `ticket-grab.ps1` (N agents grab one ticket → assert exactly one winner), `circuit-breaker.ps1` (fault-inject → trip OPEN → cooldown → auto-recover state machine), `rate-limit-isolation.ps1` (12 concurrent → 200/429 split + cross-tenant isolation). `_common.ps1` holds shared helpers (UTF-8 JSON, login, runspace-pool concurrency).

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
- **Distributed Lock**: `SimpleRedisLock` (Lua unlock in `LuaScripts/unlock.lua`) and Redisson `RLock` — Redisson `RLock` is reused for **agent ticket grabbing** (M6, `TicketServiceImpl.claim`, paired with a DB conditional UPDATE).
- **ID Generation**: `RedisIdWorker` — timestamp + per-day INCR sequence (chat conversationId, M6 ticket id).
- **Redis Stream / Pub/Sub**: dianping seckill stream experience → migrated to RocketMQ for the async doc pipeline (M2); the M6 realtime ticket message flow uses **Redis Pub/Sub** (`ws:ticket:*`) for low-latency cross-instance fan-out instead.
- Remaining ZSet/GEO/Bitmap patterns (blog likes, feed, geo, sign-in) live in the inactive legacy code as reference.

## Important Notes

- **Launch via PowerShell** for local runs — the `API-KEY` env var is user-scoped and hyphenated; bash won't reliably pass it to the JVM.
- `@EnableAspectJAutoProxy(exposeProxy = true)` is enabled (`AopContext.currentProxy()` in legacy `VoucherOrderServiceImpl`).
- Spring Boot 3 prohibits circular references by default — self-injected proxies (e.g. legacy `BlogServiceImpl`) use `@Lazy` to break the cycle.
- MyBatis-Plus 3.5.x `count()` returns `Long` (not `int`); pagination needs the separate `mybatis-plus-jsqlparser` module.
- After any meaningful change, append a `项目进度.md` changelog entry and commit (the repo is hosted privately on GitHub: `sinon1101/ai-customer-service-platform`).
