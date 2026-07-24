-- F5.2 / F5.6 / ADR-006: atomically check the monthly token budget and,
-- if there is room, reserve a pessimistic estimate against it before
-- dispatch. Single EVAL call so concurrent requests cannot collectively
-- overshoot the budget between check and reserve (F5.5).
--
-- KEYS[1] = q:tokens:{keyId}:{yyyyMM}   (monthly counter, includes reservations)
-- KEYS[2] = q:reserved:{requestId}      (this request's reservation)
-- ARGV[1] = monthly_budget
-- ARGV[2] = estimated_tokens
-- ARGV[3] = reservation_ttl_seconds
-- ARGV[4] = monthly_counter_ttl_seconds
--
-- Returns {1, new_total} if reserved, {0, current_total} if rejected.

local monthly_key = KEYS[1]
local reservation_key = KEYS[2]
local budget = tonumber(ARGV[1])
local estimate = tonumber(ARGV[2])
local reservation_ttl = tonumber(ARGV[3])
local monthly_ttl = tonumber(ARGV[4])

local current = tonumber(redis.call('GET', monthly_key) or '0')

if current + estimate > budget then
    return {0, current}
end

local new_total = redis.call('INCRBY', monthly_key, estimate)
redis.call('EXPIRE', monthly_key, monthly_ttl)
redis.call('SET', reservation_key, estimate, 'EX', reservation_ttl)

return {1, new_total}
