-- Write-through of committed reservation changes (no hold involved).
-- KEYS[1] ledger-gen:{tag}
-- ARGV: 1 tag, 2 ledger TTL ms, 3 n = number of resources to scan, 4..3+n resource ids,
--       4+n m = number of removed reservation ids, then m ids,
--       then resourceId, member, score triples (R members to add)
local tag = ARGV[1]
local ledgerTtl = tonumber(ARGV[2])

local function ledger(id)
  return 'ledger:' .. tag .. ':' .. id
end

local n = tonumber(ARGV[3])
local mIndex = 4 + n
local m = tonumber(ARGV[mIndex])
if m > 0 then
  local removed = {}
  for i = mIndex + 1, mIndex + m do
    removed['R:' .. ARGV[i]] = true
  end
  for i = 4, 3 + n do
    local key = ledger(ARGV[i])
    for _, member in ipairs(redis.call('ZRANGE', key, 0, -1)) do
      local prefix = string.match(member, '^(R:[^:]+):')
      if prefix and removed[prefix] then
        redis.call('ZREM', key, member)
      end
    end
  end
end
for i = mIndex + m + 1, #ARGV - 2, 3 do
  local key = ledger(ARGV[i])
  redis.call('ZADD', key, tonumber(ARGV[i + 2]), ARGV[i + 1])
  if redis.call('PTTL', key) < ledgerTtl then
    redis.call('PEXPIRE', key, ledgerTtl)
  end
end
redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ledgerTtl)
return 1
