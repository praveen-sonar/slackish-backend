package com.slackish.dm;

import com.slackish.common.Errors;
import com.slackish.common.Paging;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class DmService {

    private final DmConversationRepository conversations;
    private final DmMessageRepository dmMessages;

    public DmService(DmConversationRepository conversations, DmMessageRepository dmMessages) {
        this.conversations = conversations;
        this.dmMessages = dmMessages;
    }

    public List<DmConversation> forUser(Long userId) {
        return conversations.findByUserAIdOrUserBId(userId, userId);
    }

    public DmConversation create(Long workspaceId, Long userId, Long otherUserId) {
        DmConversation c = new DmConversation();
        c.setWorkspaceId(workspaceId);
        c.setUserAId(userId);
        c.setUserBId(otherUserId);
        return conversations.save(c);
    }

    public DmConversation get(Long conversationId) {
        return conversations.findById(conversationId)
                .orElseThrow(() -> Errors.notFound("Conversation not found"));
    }

    public List<DmMessage> messages(Long conversationId) {
        return dmMessages.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** Newest first. {@code before} is an exclusive message id cursor. Participants only. */
    public List<DmMessage> messagesPage(Long conversationId, Long userId, Long before, Integer limit) {
        DmConversation c = get(conversationId);
        if (!isParticipant(c, userId)) {
            throw Errors.forbidden("Not a participant in this conversation");
        }
        return dmMessages.findByConversationIdAndIdLessThanOrderByIdDesc(
                conversationId, before == null ? Long.MAX_VALUE : before, PageRequest.of(0, Paging.limit(limit)));
    }

    public List<DmMessage> search(Long workspaceId, Long userId, String q, Instant before, Pageable page) {
        List<Long> conversationIds = conversations.findByUserAIdOrUserBId(userId, userId).stream()
                .filter(c -> c.getWorkspaceId().equals(workspaceId))
                .map(DmConversation::getId)
                .toList();
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        return dmMessages.search(conversationIds, q, before, page);
    }

    private boolean isParticipant(DmConversation c, Long userId) {
        return c.getUserAId().equals(userId) || c.getUserBId().equals(userId);
    }

    public DmMessage send(Long conversationId, Long senderId, String body) {
        DmConversation c = get(conversationId);
        if (!isParticipant(c, senderId)) {
            throw Errors.forbidden("Not a participant in this conversation");
        }
        DmMessage m = new DmMessage();
        m.setConversationId(conversationId);
        m.setSenderId(senderId);
        m.setBody(body);
        return dmMessages.save(m);
    }
}
