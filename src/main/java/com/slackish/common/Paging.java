package com.slackish.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Cursor paging for newest-first message lists: {@code ?before=<messageId>&limit=<n>}. */
public final class Paging {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private Paging() {}

    public static Pageable limit(Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw Errors.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        return PageRequest.of(0, n);
    }

    public static Long before(Long before) {
        return before == null ? Long.MAX_VALUE : before;
    }
}
