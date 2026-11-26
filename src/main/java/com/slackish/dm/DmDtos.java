package com.slackish.dm;

import java.time.Instant;
import java.util.List;

public class DmDtos {
    public record CreateConversationRequest(Long workspaceId, Long otherUserId) {}
    public record SendDmRequest(String body) {}
    public record ConversationView(Long id, Long workspaceId, Long userAId, Long userBId) {}
    public record DmMessageView(Long id, Long conversationId, Long senderId, String body, Instant createdAt) {
        public static DmMessageView of(DmMessage m) {
            return new DmMessageView(m.getId(), m.getConversationId(), m.getSenderId(), m.getBody(), m.getCreatedAt());
        }
    }
    public record ConversationDetail(ConversationView conversation, List<DmMessageView> messages) {}
}
