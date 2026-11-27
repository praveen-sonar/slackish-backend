package com.slackish.message;

import com.slackish.dm.DmDtos;

import java.time.Instant;
import java.util.List;

public class MessageDtos {
    public record CreateMessageRequest(String body, Long parentId) {}
    public record EditMessageRequest(String body) {}
    public record MessageView(Long id, Long channelId, Long userId, String body,
                              Long parentId, Instant createdAt, Instant editedAt) {}
    /** Each list is newest first; page with {@code before} (channel ids) and {@code dmBefore} (DM ids). */
    public record SearchResults(List<MessageView> channelMessages, List<DmDtos.DmMessageView> dmMessages) {}
}
