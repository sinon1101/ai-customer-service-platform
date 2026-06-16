--- 手搓熔断器(M5 高并发治理)。原子地维护 LLM 调用的熔断状态机,跨实例共享。
--- 复用点评 Redis+Lua 原子计数经验,给「调 LLM」这个动作装一个智能保险丝:
--- LLM 持续失败 → 跳闸(OPEN)直接走兜底,冷却后放探测(HALF_OPEN),恢复则合闸(CLOSED)。
---
--- 状态存于 Hash KEYS[1],字段:state / calls / fails / winStart / openedAt
--- ARGV[1] = op           "allow" | "success" | "failure"
--- ARGV[2] = nowMs        当前时间(毫秒)
--- ARGV[3] = windowMs     统计滚动窗口(毫秒)
--- ARGV[4] = minCalls     触发熔断的最小样本数
--- ARGV[5] = failRate     触发熔断的失败率阈值(0~1)
--- ARGV[6] = cooldownMs   OPEN 冷却时间(毫秒),到点放一个探测
--- 返回(allow):1 = 放行,0 = 拒绝(跳闸中)。record 操作返回 0。

local key       = KEYS[1]
local op        = ARGV[1]
local nowMs     = tonumber(ARGV[2])
local windowMs  = tonumber(ARGV[3])
local minCalls  = tonumber(ARGV[4])
local failRate  = tonumber(ARGV[5])
local cooldownMs = tonumber(ARGV[6])

local h = redis.call('HMGET', key, 'state', 'calls', 'fails', 'winStart', 'openedAt')
local state    = h[1] or 'CLOSED'
local calls    = tonumber(h[2]) or 0
local fails    = tonumber(h[3]) or 0
local winStart = tonumber(h[4]) or nowMs
local openedAt = tonumber(h[5]) or 0

-- 滚动窗口:超出窗口则重置计数
if nowMs - winStart >= windowMs then
    winStart = nowMs
    calls = 0
    fails = 0
end

local result = 0

if op == 'allow' then
    if state == 'CLOSED' then
        result = 1
    else
        -- OPEN / HALF_OPEN:按冷却间隔放行一个探测,其余拒绝
        if nowMs - openedAt >= cooldownMs then
            state = 'HALF_OPEN'
            openedAt = nowMs   -- 间隔下一个探测,避免探测风暴
            result = 1
        else
            result = 0
        end
    end

elseif op == 'success' then
    if state == 'HALF_OPEN' then
        -- 探测成功 → 合闸恢复,清零
        state = 'CLOSED'
        calls = 0
        fails = 0
        winStart = nowMs
        openedAt = 0
    else
        calls = calls + 1
    end

elseif op == 'failure' then
    if state == 'HALF_OPEN' then
        -- 探测失败 → 立即回到 OPEN,重新冷却
        state = 'OPEN'
        openedAt = nowMs
    else
        calls = calls + 1
        fails = fails + 1
        if calls >= minCalls and (fails / calls) >= failRate then
            state = 'OPEN'
            openedAt = nowMs
        end
    end
end

redis.call('HSET', key,
        'state', state, 'calls', calls, 'fails', fails,
        'winStart', winStart, 'openedAt', openedAt)
-- 设过期防止状态长期残留;空闲过期后重置为 CLOSED 也安全
local ttl = math.ceil((windowMs + cooldownMs) / 1000) + 5
redis.call('EXPIRE', key, ttl)

return result
