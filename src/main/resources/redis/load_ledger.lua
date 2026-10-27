-- Rebuilds the confirmed (R) intervals of a dealership-day from a primary snapshot; live holds
-- (H members) are kept. Refused when a write happened since the snapshot was taken.
-- KEYS[1] ledger-loaded:{tag}   KEYS[2] ledger-gen:{tag}
-- ARGV: 1 tag, 2 generation read before the snapshot, 3 marker TTL ms, 4 ledger TTL ms,
--       5 n = number of resources, 6..5+n resource ids, then resourceId, member, score triples
-- Returns 1 when loaded, 0 when the snapshot is stale (caller retries).
local tag = ARGV[1]
local ledgerTtl = tonumber(ARGV[4])

local function ledger(id)
  return 'ledger:' .. tag .. ':' .. id
end

if (redis.call('GET', KEYS[2]) or '0') ~= ARGV[2] then
  return 0
end
local n = tonumber(ARGV[5])
for i = 6, 5 + n do
  local key = ledger(ARGV[i])
  for _, member in ipairs(redis.call('ZRANGE', key, 0, -1)) do
    if string.sub(member, 1, 2) == 'R:' then
      redis.call('ZREM', key, member)
    end
  end
end
for i = 6 + n, #ARGV - 2, 3 do
  redis.call('ZADD', ledger(ARGV[i]), tonumber(ARGV[i + 2]), ARGV[i + 1])
end
for i = 6, 5 + n do
  redis.call('PEXPIRE', ledger(ARGV[i]), ledgerTtl)
end
redis.call('SET', KEYS[1], '1', 'PX', ARGV[3])
return 1
