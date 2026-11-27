package com.slackish.message;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelMemberRepository;
import com.slackish.channel.ChannelRepository;
import com.slackish.common.Errors;
import com.slackish.dm.DmService;
import com.slackish.workspace.WorkspaceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import com.slackish.common.Paging;
import java.util.Comparator;
import java.util.List;

@Service
public class MessageService {

    private final MessageRepository messages;
    private final ChannelMemberRepository channelMembers;
    private final ChannelRepository channels;
    private final WorkspaceService workspaceService;
    private final DmService dmService;

    public MessageService(MessageRepository messages, ChannelMemberRepository channelMembers,
                          ChannelRepository channels, WorkspaceService workspaceService, DmService dmService) {
        this.messages = messages;
        this.channelMembers = channelMembers;
        this.channels = channels;
        this.workspaceService = workspaceService;
        this.dmService = dmService;
    }

    public Message post(Long channelId, Long userId, String body, Long parentId) {
        Message m = new Message();
        m.setChannelId(channelId);
        m.setUserId(userId);
        m.setBody(body);
        m.setParentId(parentId);
        return messages.save(m);
    }

    public Message getById(Long id) {
        return messages.findById(id).orElseThrow(() -> Errors.notFound("Message not found"));
    }

    /** Newest first. {@code before} is an exclusive message id cursor. */
    public List<Message> listChannel(Long channelId, Long userId, Long before, Integer limit) {
        if (!channelMembers.existsByChannelIdAndUserId(channelId, userId)) {
            throw Errors.forbidden("Not a member of this channel");
        }
        return messages.findByChannelIdAndIdLessThanOrderByIdDesc(
                channelId, before == null ? Long.MAX_VALUE : before, PageRequest.of(0, Paging.limit(limit)));
    }

    /** Searches visible channel messages and the caller's own DMs, newest first. {@code before} is an exclusive createdAt cursor. */
    public List<MessageDtos.SearchResult> search(Long workspaceId, Long userId, String q, Instant before, Integer limit) {
        workspaceService.requireMember(workspaceId, userId);
        if (q == null || q.isBlank()) {
            throw Errors.badRequest("Query is required");
        }
        int size = Paging.limit(limit);
        Instant cursor = before == null ? Instant.MAX : before;
        PageRequest page = PageRequest.of(0, size);
        List<Long> channelIds = channels.findByWorkspaceId(workspaceId).stream()
                .filter(c -> !c.isPrivate() || channelMembers.existsByChannelIdAndUserId(c.getId(), userId))
                .map(Channel::getId)
                .toList();
        List<MessageDtos.SearchResult> results = new java.util.ArrayList<>();
        if (!channelIds.isEmpty()) {
            messages.search(channelIds, q, cursor, page)
                    .forEach(m -> results.add(new MessageDtos.SearchResult("CHANNEL", m.getId(), m.getChannelId(),
                            null, m.getUserId(), m.getBody(), m.getCreatedAt())));
        }
        dmService.search(workspaceId, userId, q, cursor, page)
                .forEach(m -> results.add(new MessageDtos.SearchResult("DM", m.getId(), null,
                        m.getConversationId(), m.getSenderId(), m.getBody(), m.getCreatedAt())));
        return results.stream()
                .sorted(Comparator.comparing(MessageDtos.SearchResult::createdAt).reversed())
                .limit(size)
                .toList();
    }

    public List<Message> thread(Long parentId, Long userId) {
        Message parent = getById(parentId);
        if (!channelMembers.existsByChannelIdAndUserId(parent.getChannelId(), userId)) {
            throw Errors.forbidden("Not a member of this channel");
        }
        return messages.findByParentIdOrderByCreatedAtAsc(parentId);
    }

    public Message edit(Long id, Long userId, String body) {
        Message m = getById(id);
        if (!m.getUserId().equals(userId)) {
            throw Errors.forbidden("You can only edit your own messages");
        }
        m.setBody(body);
        m.setEditedAt(Instant.now());
        return messages.save(m);
    }
}
