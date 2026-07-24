-- ADR-006: replaces a reservation with actual usage once known,
-- adjusting the monthly counter by the difference. Safe to call even if
-- the reservation already expired (its 5-minute TTL is the safety net
-- for a request that crashed before reconciling): in that case the
-- reservation reads as 0 and the full actual usage is simply added.
--
-- KEYS[1] = q:tokens:{keyId}:{yyyyMM}
-- KEYS[2] = q:reserved:{requestId}
-- ARGV[1] = actual_tokens

local monthly_key = KEYS[1]
local reservation_key = KEYS[2]
local actual = tonumber(ARGV[1])

local reserved = tonumber(redis.call('GET', reservation_key) or '0')
local diff = actual - reserved

if diff ~= 0 then
    redis.call('INCRBY', monthly_key, diff)
end
redis.call('DEL', reservation_key)

return 1
