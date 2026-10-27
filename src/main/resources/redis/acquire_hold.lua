-- Atomically grants a hold on the first free (bay, technician) pair of one dealership-day.
-- KEYS[1] userhold:{tag}:<userId>   KEYS[2] hold:{tag}:<holdId> (new hold)
-- ARGV: 1 tag, 2 userId, 3 holdId, 4 start, 5 end, 6 bayStart, 7 bayEnd (epoch ms),
--       8 hold TTL ms, 9 ledger TTL ms, 10 payload JSON, 11.. bayId, technicianId pairs (ranked)
-- Returns {holdId, bayId, technicianId, expiresMs, previousHoldId}; holdId is '' when no pair
-- was free. The caller's previous hold of the day is always released first.
local tag = ARGV[1]
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local ledgerTtl = tonumber(ARGV[9])

local function ledger(id)
  return 'ledger:' .. tag .. ':' .. id
end

local function fields(member)
  local out = {}
  for part in string.gmatch(member, '[^:]+') do
    out[#out + 1] = part
  end
  return out
end

local function touch(key)
  if redis.call('PTTL', key) < ledgerTtl then
    redis.call('PEXPIRE', key, ledgerTtl)
  end
end

-- Whether a live interval of the set overlaps [s, e); expired holds met on the way are dropped.
local function busy(key, s, e)
  local hit = false
  for _, member in ipairs(redis.call('ZRANGEBYSCORE', key, '-inf', '(' .. e)) do
    local f = fields(member)
    if f[1] == 'H' and tonumber(f[5]) <= now then
      redis.call('ZREM', key, member)
    elseif tonumber(f[4]) > s then
      hit = true
    end
  end
  return hit
end

local previous = redis.call('GET', KEYS[1])
if previous then
  local previousKey = 'hold:' .. tag .. ':' .. previous
  local h = redis.call('HMGET', previousKey, 'bayId', 'technicianId', 'bayMember', 'technicianMember')
  if h[1] then
    redis.call('ZREM', ledger(h[1]), h[3])
    redis.call('ZREM', ledger(h[2]), h[4])
  end
  redis.call('DEL', previousKey, KEYS[1])
else
  previous = ''
end

local start, finish = tonumber(ARGV[4]), tonumber(ARGV[5])
local bayStart, bayEnd = tonumber(ARGV[6]), tonumber(ARGV[7])
local expires = now + tonumber(ARGV[8])
for i = 11, #ARGV - 1, 2 do
  local bay, technician = ARGV[i], ARGV[i + 1]
  if not busy(ledger(technician), start, finish) and not busy(ledger(bay), bayStart, bayEnd) then
    local holdId = ARGV[3]
    local bayMember = 'H:' .. holdId .. ':' .. ARGV[6] .. ':' .. ARGV[7] .. ':' .. expires
    local technicianMember = 'H:' .. holdId .. ':' .. ARGV[4] .. ':' .. ARGV[5] .. ':' .. expires
    redis.call('ZADD', ledger(bay), bayStart, bayMember)
    redis.call('ZADD', ledger(technician), start, technicianMember)
    touch(ledger(bay))
    touch(ledger(technician))
    redis.call('HSET', KEYS[2],
      'userId', ARGV[2], 'bayId', bay, 'technicianId', technician,
      'start', ARGV[4], 'end', ARGV[5], 'bayStart', ARGV[6], 'bayEnd', ARGV[7],
      'expiresAt', expires, 'payload', ARGV[10],
      'bayMember', bayMember, 'technicianMember', technicianMember)
    redis.call('PEXPIREAT', KEYS[2], expires)
    redis.call('SET', KEYS[1], holdId, 'PXAT', expires)
    return {holdId, bay, technician, tostring(expires), previous}
  end
end
return {'', '', '', '', previous}
