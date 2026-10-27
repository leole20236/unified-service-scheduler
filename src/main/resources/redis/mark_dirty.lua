-- The database refused a write the ledger allowed: force a rebuild from the primary.
-- KEYS[1] ledger-loaded:{tag}   KEYS[2] ledger-gen:{tag}
-- ARGV: 1 ledger TTL ms
redis.call('DEL', KEYS[1])
redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[1])
return 1
