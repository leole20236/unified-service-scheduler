-- Releases a hold, but only for the user who owns it.
-- KEYS[1] hold:{tag}:<holdId>
-- ARGV: 1 tag, 2 holdId, 3 userId
-- Returns 1 when released, 0 when missing or owned by someone else.
local tag = ARGV[1]

local function ledger(id)
  return 'ledger:' .. tag .. ':' .. id
end

local h = redis.call('HMGET', KEYS[1], 'bayId', 'technicianId', 'bayMember', 'technicianMember', 'userId')
if not h[5] or h[5] ~= ARGV[3] then
  return 0
end
redis.call('ZREM', ledger(h[1]), h[3])
redis.call('ZREM', ledger(h[2]), h[4])
redis.call('DEL', KEYS[1])
local userHold = 'userhold:' .. tag .. ':' .. ARGV[3]
if redis.call('GET', userHold) == ARGV[2] then
  redis.call('DEL', userHold)
end
return 1
