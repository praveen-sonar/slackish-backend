package com.slackish.admin;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelRepository;
import com.slackish.dm.DmConversationRepository;
import com.slackish.invitation.InvitationRepository;
import com.slackish.message.Message;
import com.slackish.message.MessageRepository;
import com.slackish.user.UserRepository;
import com.slackish.workspace.Membership;
import com.slackish.workspace.MembershipRepository;
import com.slackish.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkspaceExportService {

    private final MembershipRepository memberships;
    private final UserRepository users;
    private final ChannelRepository channels;
    private final MessageRepository messages;
    private final DmConversationRepository conversations;
    private final InvitationRepository invitations;
    private final WorkspaceRepository workspaces;

    public WorkspaceExportService(MembershipRepository memberships, UserRepository users,
                                  ChannelRepository channels, MessageRepository messages,
                                  DmConversationRepository conversations, InvitationRepository invitations,
                                  WorkspaceRepository workspaces) {
        this.memberships = memberships;
        this.users = users;
        this.channels = channels;
        this.messages = messages;
        this.conversations = conversations;
        this.invitations = invitations;
        this.workspaces = workspaces;
    }

    public Map<String, Object> export(Long id) {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("workspace", workspaces.findById(id).orElse(null));
        List<Membership> ms = memberships.findByWorkspaceId(id);
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
        return dump;
    }
}
