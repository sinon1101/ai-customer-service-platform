--- 令牌桶限流(M5 高并发治理)。原子地「补令牌 + 扣令牌」,避免并发下算错。
--- 复用点评 seckill/unlock 的 Redis+Lua 原子计数范式。
---
--- KEYS[1] = 桶 key(如 t:{tenantId}:rl:chat),用 Hash 存 {tokens, ts}
--- ARGV[1] = capacity      桶容量(满桶令牌数)
--- ARGV[2] = refillPerSec  每秒补充的令牌数(可为小数)
--- ARGV[3] = nowMs         当前时间(毫秒)
--- ARGV[4] = requested     本次申请的令牌数(一般为 1)
--- 返回:1 = 放行(已扣);0 = 拒绝(令牌不足)

local capacity     = tonumber(ARGV[1])
local refillPerSec = tonumber(ARGV[2])
local nowMs        = tonumber(ARGV[3])
local requested    = tonumber(ARGV[4])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local lastMs = tonumber(bucket[2])

-- 首次访问:桶视为满
if tokens == nil then
    tokens = capacity
    lastMs = nowMs
end

-- 按经过的时间补令牌(不超过容量)
local elapsed = math.max(0, nowMs - lastMs)
tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refillPerSec)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', nowMs)
-- 设过期防泄漏:最坏从空补满所需秒数 + 1 秒余量;空桶时至少留 1 秒
local ttl = math.ceil(capacity / refillPerSec) + 1
redis.call('EXPIRE', KEYS[1], ttl)

return allowed
