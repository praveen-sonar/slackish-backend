package com.slackish.dm;

import com.slackish.common.Errors;
import com.slackish.common.LikePatterns;
import com.slackish.common.Paging;
import com.slackish.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DmService {

    private final DmConversationRepository conversations;
    private final DmMessageRepository dmMessages;
    private final WorkspaceService workspaceService;

    public DmService(DmConversationRepository conversations, DmMessageRepository dmMessages,
                     WorkspaceService workspaceService) {
        this.conversations = conversations;
        this.dmMessages = dmMessages;
        this.workspaceService = workspaceService;
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

    public DmConversation get(Long conversationId, Long userId) {
        DmConversation c = conversations.findById(conversationId)
                .orElseThrow(() -> Errors.notFound("Conversation not found"));
        if (!isParticipant(c, userId)) {
            throw Errors.forbidden("Not a participant in this conversation");
        }
        return c;
    }

    /** Returns the DM message if the caller is a participant in its conversation. */
    public DmMessage getMessage(Long dmMessageId, Long userId) {
        DmMessage m = dmMessages.findById(dmMessageId).orElseThrow(() -> Errors.notFound("Message not found"));
        get(m.getConversationId(), userId);
        return m;
    }

    public List<DmMessage> messages(Long conversationId, Long userId, Long before, Integer limit) {
        get(conversationId, userId);
        return dmMessages.findByConversationIdAndIdLessThanOrderByIdDesc(
                conversationId, Paging.before(before), Paging.limit(limit));
    }

    /** Searches only the caller's own conversations in the workspace. */
    public List<DmMessage> search(Long workspaceId, Long userId, String query, Long before, Integer limit) {
        workspaceService.requireMember(workspaceId, userId);
        List<Long> mine = conversations.findByWorkspaceId(workspaceId).stream()
                .filter(c -> isParticipant(c, userId))
                .map(DmConversation::getId)
                .toList();
        if (mine.isEmpty()) {
            return List.of();
        }
        return dmMessages.search(mine, Paging.before(before), LikePatterns.contains(query), Paging.limit(limit));
    }

    public DmMessage send(Long conversationId, Long senderId, String body) {
        get(conversationId, senderId);
        DmMessage m = new DmMessage();
        m.setConversationId(conversationId);
        m.setSenderId(senderId);
        m.setBody(body);
        return dmMessages.save(m);
    }

    private static boolean isParticipant(DmConversation c, Long userId) {
        return c.getUserAId().equals(userId) || c.getUserBId().equals(userId);
    }
}
