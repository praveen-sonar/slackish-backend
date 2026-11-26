package com.slackish.message;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelMember;
import com.slackish.channel.ChannelMemberRepository;
import com.slackish.channel.ChannelRepository;
import com.slackish.common.Errors;
import com.slackish.common.LikePatterns;
import com.slackish.common.Paging;
import com.slackish.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private final MessageRepository messages;
    private final ChannelMemberRepository channelMembers;
    private final ChannelRepository channels;
    private final WorkspaceService workspaceService;

    public MessageService(MessageRepository messages, ChannelMemberRepository channelMembers,
                          ChannelRepository channels, WorkspaceService workspaceService) {
        this.messages = messages;
        this.channelMembers = channelMembers;
        this.channels = channels;
        this.workspaceService = workspaceService;
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

    public List<Message> listChannel(Long channelId, Long userId, Long before, Integer limit) {
        requireChannelMember(channelId, userId);
        return messages.findByChannelIdAndIdLessThanOrderByIdDesc(channelId, Paging.before(before), Paging.limit(limit));
    }

    public List<Message> thread(Long parentId, Long userId) {
        Message parent = getById(parentId);
        requireChannelMember(parent.getChannelId(), userId);
        return messages.findByParentIdOrderByCreatedAtAsc(parentId);
    }

    /** Searches public channels of the workspace plus the private channels the caller belongs to. */
    public List<Message> search(Long workspaceId, Long userId, String query, Long before, Integer limit) {
        workspaceService.requireMember(workspaceId, userId);
        Set<Long> joined = channelMembers.findByUserId(userId).stream()
                .map(ChannelMember::getChannelId)
                .collect(Collectors.toSet());
        List<Long> visible = channels.findByWorkspaceId(workspaceId).stream()
                .filter(c -> !c.isPrivate() || joined.contains(c.getId()))
                .map(Channel::getId)
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        return messages.search(visible, Paging.before(before), LikePatterns.contains(query), Paging.limit(limit));
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

    private void requireChannelMember(Long channelId, Long userId) {
        if (!channelMembers.existsByChannelIdAndUserId(channelId, userId)) {
            throw Errors.forbidden("Not a member of this channel");
        }
    }
}
