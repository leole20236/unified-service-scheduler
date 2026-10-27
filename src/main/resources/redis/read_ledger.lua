-- One consistent read of a dealership-day: Redis time, the viewer's own hold id and the members
-- of each requested resource's sorted set.
-- ARGV: 1 tag, 2 userId or '', 3.. resource ids
-- Returns {nowMs, ownHoldId or '', {members of resource 1}, {members of resource 2}, ...}
local tag = ARGV[1]
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local own = ''
if ARGV[2] ~= '' then
  own = redis.call('GET', 'userhold:' .. tag .. ':' .. ARGV[2]) or ''
end
local out = {tostring(now), own}
for i = 3, #ARGV do
  out[#out + 1] = redis.call('ZRANGE', 'ledger:' .. tag .. ':' .. ARGV[i], 0, -1)
end
return out
