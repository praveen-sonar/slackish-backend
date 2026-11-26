package com.slackish.message;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelService;
import com.slackish.common.ApiException;
import com.slackish.dm.DmConversation;
import com.slackish.dm.DmService;
import com.slackish.user.User;
import com.slackish.user.UserRepository;
import com.slackish.workspace.Workspace;
import com.slackish.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MessagePagingAndSearchTest {

    @Autowired UserRepository users;
    @Autowired WorkspaceService workspaces;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired DmService dms;

    Long alice, bob, carol;
    Workspace ws;
    Channel pub, priv;

    private Long newUser(String name) {
        User u = new User();
        u.setEmail(name + "-" + UUID.randomUUID() + "@t.io");
        u.setDisplayName(name);
        u.setPasswordHash("x");
        return users.save(u).getId();
    }

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        bob = newUser("bob");
        carol = newUser("carol");
        ws = workspaces.create("T", "t-" + UUID.randomUUID(), alice);
        pub = channels.create(ws.getId(), "pub", false, alice);
        priv = channels.create(ws.getId(), "priv", true, alice);
    }

    @Test
    void paginationHasNoGapsOrDuplicates() {
        for (int i = 1; i <= 120; i++) {
            messages.post(pub.getId(), alice, "m" + i, null);
        }
        List<Message> first = messages.listChannel(pub.getId(), alice, null, null);
        assertEquals(50, first.size());
        assertEquals("m120", first.get(0).getBody());
        List<Message> second = messages.listChannel(pub.getId(), alice, first.get(49).getId(), 100);
        assertEquals(70, second.size());
        Set<Long> ids = new HashSet<>();
        first.forEach(m -> ids.add(m.getId()));
        second.forEach(m -> ids.add(m.getId()));
        assertEquals(120, ids.size());
        assertEquals("m1", second.get(69).getBody());
        assertTrue(messages.listChannel(pub.getId(), alice, second.get(69).getId(), null).isEmpty());
    }

    @Test
    void limitIsValidated() {
        assertThrows(ApiException.class, () -> messages.listChannel(pub.getId(), alice, null, 0));
        assertThrows(ApiException.class, () -> messages.listChannel(pub.getId(), alice, null, 101));
        assertDoesNotThrow(() -> messages.listChannel(pub.getId(), alice, null, 100));
    }

    @Test
    void searchRespectsPrivateChannelsDmsAndTenants() {
        workspaces.create("Other", "o-" + UUID.randomUUID(), carol); // carol is only in another tenant
        messages.post(pub.getId(), alice, "Needle in public", null);
        messages.post(priv.getId(), alice, "needle in private", null);
        DmConversation dm = dms.create(ws.getId(), alice, bob);
        dms.send(dm.getId(), alice, "NEEDLE in dm");

        List<MessageDtos.SearchResult> mine = messages.search(ws.getId(), alice, "needle", null, null);
        assertEquals(3, mine.size());
        assertEquals(Set.of("CHANNEL", "DM"), Set.copyOf(mine.stream().map(MessageDtos.SearchResult::type).toList()));

        // non-member of the workspace is rejected, even if a participant of a DM in it
        assertThrows(ApiException.class, () -> messages.search(ws.getId(), carol, "needle", null, null));
        assertThrows(ApiException.class, () -> messages.search(ws.getId(), alice, " ", null, null));
    }

    @Test
    void searchHidesPrivateChannelsAndOthersDmsFromOtherMembers() {
        Long dan = newUser("dan");
        com.slackish.workspace.Membership m = new com.slackish.workspace.Membership();
        m.setWorkspaceId(ws.getId());
        m.setUserId(dan);
        m.setRole(com.slackish.workspace.Role.MEMBER);
        membershipRepo.save(m);

        messages.post(pub.getId(), alice, "needle public", null);
        messages.post(priv.getId(), alice, "needle private", null);
        DmConversation dm = dms.create(ws.getId(), alice, bob);
        dms.send(dm.getId(), alice, "needle dm");

        List<MessageDtos.SearchResult> seen = messages.search(ws.getId(), dan, "needle", null, null);
        assertEquals(1, seen.size());
        assertEquals("needle public", seen.get(0).body());
    }

    @Autowired com.slackish.workspace.MembershipRepository membershipRepo;

    @Test
    void searchPagesByCursorWithoutDuplicates() {
        for (int i = 0; i < 5; i++) {
            messages.post(pub.getId(), alice, "needle " + i, null);
        }
        List<MessageDtos.SearchResult> p1 = messages.search(ws.getId(), alice, "needle", null, 3);
        List<MessageDtos.SearchResult> p2 = messages.search(ws.getId(), alice, "needle", p1.get(2).createdAt(), 3);
        assertEquals(3, p1.size());
        assertEquals(2, p2.size());
        Set<Long> ids = new HashSet<>();
        p1.forEach(r -> ids.add(r.id()));
        p2.forEach(r -> ids.add(r.id()));
        assertEquals(5, ids.size());
    }

    @Test
    void dmPagingRestrictedToParticipants() {
        DmConversation dm = dms.create(ws.getId(), alice, bob);
        for (int i = 0; i < 60; i++) {
            dms.send(dm.getId(), alice, "d" + i);
        }
        assertEquals(50, dms.messagesPage(dm.getId(), bob, null, null).size());
        assertThrows(ApiException.class, () -> dms.messagesPage(dm.getId(), carol, null, null));
    }
}
