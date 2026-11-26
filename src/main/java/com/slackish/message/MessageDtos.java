package com.slackish.message;

import java.time.Instant;

public class MessageDtos {
    public record CreateMessageRequest(String body, Long parentId) {}
    public record EditMessageRequest(String body) {}
    public record MessageView(Long id, Long channelId, Long userId, String body,
                              Long parentId, Instant createdAt, Instant editedAt) {}
    /** type is CHANNEL or DM; the id field not relevant to the type (channelId/conversationId) is null. */
    public record SearchResult(String type, Long id, Long channelId, Long conversationId, Long userId,
                               String body, Instant createdAt) {}
}
