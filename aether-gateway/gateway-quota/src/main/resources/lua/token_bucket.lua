-- F5.1: atomic RPS token bucket. Single EVAL call, not three round trips
-- (check, refill, decrement done together) -- doing this as separate
-- Redis calls is a race under concurrent requests, per PRD section 12.2.
--
-- KEYS[1] = q:rps:{keyId}          (hash: tokens, last_refill)
-- ARGV[1] = capacity               (rps_limit, also the bucket's max size)
-- ARGV[2] = refill_rate_per_second (tokens added per second, == rps_limit)
-- ARGV[3] = now_millis
-- ARGV[4] = requested_tokens       (cost of this request, normally 1)
-- ARGV[5] = key_ttl_seconds
--
-- Returns 1 if allowed (and decrements), 0 if rejected.

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local last_refill = tonumber(redis.call('HGET', key, 'last_refill'))

if tokens == nil then
    tokens = capacity
    last_refill = now
end

local elapsed_seconds = math.max(0, now - last_refill) / 1000.0
local refilled = math.min(capacity, tokens + elapsed_seconds * refill_rate)

if refilled >= requested then
    redis.call('HSET', key, 'tokens', refilled - requested, 'last_refill', now)
    redis.call('EXPIRE', key, ttl)
    return 1
else
    redis.call('HSET', key, 'tokens', refilled, 'last_refill', now)
    redis.call('EXPIRE', key, ttl)
    return 0
end
