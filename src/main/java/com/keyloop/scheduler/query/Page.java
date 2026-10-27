package com.keyloop.scheduler.query;

import java.util.List;

/**
 * A cursor-paginated collection: {@code {items, nextCursor}} (spec 14.1); no cursor on the last.
 */
public record Page<T>(List<T> items, String nextCursor) {}
