package com.slackish.common;

public final class Paging {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private Paging() {}

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw Errors.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }
}
