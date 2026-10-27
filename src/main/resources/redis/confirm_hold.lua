-- After a booking committed: turns the hold (if still present) into confirmed intervals.
-- KEYS[1] hold:{tag}:<holdId>   KEYS[2] ledger-gen:{tag}
-- ARGV: 1 tag, 2 holdId, 3 ledger TTL ms, 4.. resourceId, member, score triples (R members)
local tag = ARGV[1]
local ledgerTtl = tonumber(ARGV[3])

local function ledger(id)
  return 'ledger:' .. tag .. ':' .. id
end

local h = redis.call('HMGET', KEYS[1], 'bayId', 'technicianId', 'bayMember', 'technicianMember', 'userId')
if h[1] then
  redis.call('ZREM', ledger(h[1]), h[3])
  redis.call('ZREM', ledger(h[2]), h[4])
  redis.call('DEL', KEYS[1])
  local userHold = 'userhold:' .. tag .. ':' .. h[5]
  if redis.call('GET', userHold) == ARGV[2] then
    redis.call('DEL', userHold)
  end
end
for i = 4, #ARGV - 2, 3 do
  local key = ledger(ARGV[i])
  redis.call('ZADD', key, tonumber(ARGV[i + 2]), ARGV[i + 1])
  if redis.call('PTTL', key) < ledgerTtl then
    redis.call('PEXPIRE', key, ledgerTtl)
  end
end
redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ledgerTtl)
return 1
