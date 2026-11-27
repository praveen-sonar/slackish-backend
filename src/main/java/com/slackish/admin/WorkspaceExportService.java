package com.slackish.admin;

import com.slackish.attachment.AttachmentDtos;
import com.slackish.attachment.AttachmentService;
import com.slackish.channel.Channel;
import com.slackish.channel.ChannelRepository;
import com.slackish.dm.DmConversationRepository;
import com.slackish.invitation.InvitationRepository;
import com.slackish.message.Message;
import com.slackish.message.MessageRepository;
import com.slackish.user.UserRepository;
import com.slackish.workspace.Membership;
import com.slackish.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the full workspace dump returned by the admin export endpoint. */
@Service
public class WorkspaceExportService {

    private final WorkspaceService workspaceService;
    private final UserRepository users;
    private final ChannelRepository channels;
    private final MessageRepository messages;
    private final DmConversationRepository conversations;
    private final InvitationRepository invitations;
    private final AttachmentService attachmentService;

    public WorkspaceExportService(WorkspaceService workspaceService, UserRepository users,
                                  ChannelRepository channels, MessageRepository messages,
                                  DmConversationRepository conversations, InvitationRepository invitations,
                                  AttachmentService attachmentService) {
        this.workspaceService = workspaceService;
        this.users = users;
        this.channels = channels;
        this.messages = messages;
        this.conversations = conversations;
        this.invitations = invitations;
        this.attachmentService = attachmentService;
    }

    public Map<String, Object> export(Long id) {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("workspace", workspaceService.find(id).orElse(null));
        List<Membership> ms = workspaceService.memberships(id);
        dump.put("memberships", ms);
        dump.put("members", ms.stream().map(m -> users.findById(m.getUserId()).orElse(null)).toList());
        List<Channel> chans = channels.findByWorkspaceId(id);
        dump.put("channels", chans);
        List<Message> msgs = new ArrayList<>();
        for (Channel c : chans) {
            msgs.addAll(messages.findByChannelId(c.getId()));
        }
        dump.put("messages", msgs);
        dump.put("conversations", conversations.findByWorkspaceId(id));
        dump.put("invitations", invitations.findByWorkspaceId(id));
        dump.put("attachments", attachmentService.listForWorkspace(id).stream()
                .map(AttachmentDtos.AttachmentView::of).toList());
        return dump;
    }
}
