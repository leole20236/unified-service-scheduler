package com.keyloop.scheduler.common.db;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A read that must see the latest committed data, so it is pinned to the primary. It always opens
 * its own read-write transaction (REQUIRES_NEW), so it reaches the primary even when called from a
 * read-only (replica) transaction. Use it on query entry points only: inside a write transaction
 * the nested transaction cannot see the outer transaction's uncommitted writes.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
public @interface PrimaryRead {}
