-- F5.3: atomic check-and-add for the concurrency cap. Checking SCARD
-- then SADD as two separate calls is a race under concurrent requests,
-- same reasoning as the token bucket.
--
-- KEYS[1] = q:conc:{keyId}
-- ARGV[1] = cap
-- ARGV[2] = requestId
-- ARGV[3] = ttl_seconds
--
-- Returns 1 if acquired, 0 if the cap is already reached.

local key = KEYS[1]
local cap = tonumber(ARGV[1])
local request_id = ARGV[2]
local ttl = tonumber(ARGV[3])

local current = redis.call('SCARD', key)

if current >= cap then
    return 0
end

redis.call('SADD', key, request_id)
redis.call('EXPIRE', key, ttl)
return 1
