-- The database refused a write the ledger allowed: force a rebuild from the primary before the
-- next use. Only the marker goes; the generation is bumped by write-throughs alone, so a burst of
-- conflicts cannot starve the rebuild.
-- KEYS[1] ledger-loaded:{tag}
redis.call('DEL', KEYS[1])
return 1
