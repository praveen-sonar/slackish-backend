package com.slackish.common;

import java.util.Locale;

/** Builds lower-cased, escaped {@code LIKE} patterns for queries that use {@code escape '\'}. */
public final class LikePatterns {
    private LikePatterns() {}

    public static String contains(String term) {
        String escaped = term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
