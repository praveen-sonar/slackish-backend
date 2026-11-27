package com.slackish.attachment;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelService;
import com.slackish.common.ApiException;
import com.slackish.common.CurrentUserResolver;
import com.slackish.dm.DmConversation;
import com.slackish.dm.DmMessage;
import com.slackish.dm.DmService;
import com.slackish.message.Message;
import com.slackish.message.MessageService;
import com.slackish.user.User;
import com.slackish.user.UserRepository;
import com.slackish.workspace.Membership;
import com.slackish.workspace.MembershipRepository;
import com.slackish.workspace.Role;
import com.slackish.workspace.Workspace;
import com.slackish.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AttachmentTest {

    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("slackish-attachments");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.attachments.dir", DIR::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired WorkspaceService workspaces;
    @Autowired ChannelService channels;
    @Autowired MessageService messages;
    @Autowired DmService dms;
    @Autowired AttachmentService attachments;

    Long alice, adam, amy, outsider;
    Workspace ws;
    Channel general;
    Message msg;

    private Long newUser(String name) {
        User u = new User();
        u.setEmail(name + "-" + UUID.randomUUID() + "@t.io");
        u.setDisplayName(name);
        u.setPasswordHash("x");
        return users.save(u).getId();
    }

    private void addMember(Long userId, Role role) {
        Membership m = new Membership();
        m.setWorkspaceId(ws.getId());
        m.setUserId(userId);
        m.setRole(role);
        memberships.save(m);
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpSession as(Long uid) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(CurrentUserResolver.SESSION_UID, uid);
        return s;
    }

    private static long storedFiles() throws IOException {
        try (var files = Files.list(DIR)) {
            return files.count();
        }
    }

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        adam = newUser("adam");
        amy = newUser("amy");
        outsider = newUser("outsider");
        ws = workspaces.create("Acme", "acme-" + UUID.randomUUID(), alice);
        addMember(adam, Role.ADMIN);
        addMember(amy, Role.MEMBER);
        general = channels.create(ws.getId(), "general", false, alice);
        channels.join(general.getId(), amy);
        msg = messages.post(general.getId(), alice, "see attached", null);
    }

    @Test
    void uploadListDownloadDeleteOverHttp() throws Exception {
        long before = storedFiles();
        String body = mvc.perform(multipart("/api/messages/{id}/attachments", msg.getId())
                        .file(file("../../etc/notes.txt", "hello file")).session(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("notes.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.uploaderId").value(alice))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        assertEquals(before + 1, storedFiles());

        mvc.perform(get("/api/messages/{id}/attachments", msg.getId()).session(as(amy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(get("/api/attachments/{id}", id).session(as(amy)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("notes.txt")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string("hello file"));

        mvc.perform(get("/api/attachments/{id}", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/attachments/{id}", id).session(as(outsider))).andExpect(status().isForbidden());

        mvc.perform(delete("/api/attachments/{id}", id).session(as(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/attachments/{id}", id).session(as(alice))).andExpect(status().isNotFound());
        assertEquals(before, storedFiles());
    }

    @Test
    void listIsOldestFirst() {
        Attachment a1 = attachments.uploadToMessage(msg.getId(), alice, file("a.txt", "1"));
        Attachment a2 = attachments.uploadToMessage(msg.getId(), alice, file("b.txt", "2"));
        List<Attachment> list = attachments.listForMessage(msg.getId(), alice);
        assertEquals(List.of(a1.getId(), a2.getId()), list.stream().map(Attachment::getId).toList());
    }

    @Test
    void uploadRules() {
        assertThrows(ApiException.class, () -> attachments.uploadToMessage(msg.getId(), amy, file("x.txt", "x")));
        assertThrows(ApiException.class, () -> attachments.uploadToMessage(msg.getId(), outsider, file("x.txt", "x")));
        assertThrows(ApiException.class, () -> attachments.uploadToMessage(msg.getId(), alice, file("x.txt", "")));
        byte[] big = new byte[(int) AttachmentService.MAX_BYTES + 1];
        ApiException ex = assertThrows(ApiException.class, () -> attachments.uploadToMessage(msg.getId(), alice,
                new MockMultipartFile("file", "big.bin", "application/octet-stream", big)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void privateChannelAttachmentsHiddenFromNonMembers() {
        Channel priv = channels.create(ws.getId(), "secret", true, alice);
        Message m = messages.post(priv.getId(), alice, "private", null);
        Attachment a = attachments.uploadToMessage(m.getId(), alice, file("p.txt", "p"));
        assertThrows(ApiException.class, () -> attachments.listForMessage(m.getId(), amy));
        assertThrows(ApiException.class, () -> attachments.download(a.getId(), amy));
    }

    @Test
    void deactivatedMemberLosesAccess() {
        Attachment a = attachments.uploadToMessage(msg.getId(), alice, file("a.txt", "a"));
        Membership m = memberships.findByWorkspaceIdAndUserId(ws.getId(), amy).orElseThrow();
        m.setActive(false);
        memberships.save(m);
        assertThrows(ApiException.class, () -> attachments.download(a.getId(), amy));
    }

    @Test
    void deleteAllowedForUploaderOrAdminOnly() {
        Message amyMsg = messages.post(general.getId(), amy, "mine", null);
        Attachment a = attachments.uploadToMessage(amyMsg.getId(), amy, file("a.txt", "a"));
        Attachment b = attachments.uploadToMessage(msg.getId(), alice, file("b.txt", "b"));

        assertThrows(ApiException.class, () -> attachments.delete(b.getId(), amy));
        assertThrows(ApiException.class, () -> attachments.delete(a.getId(), outsider));
        attachments.delete(a.getId(), adam); // admin, not even in the channel
        attachments.delete(b.getId(), alice);
        assertTrue(attachments.listForMessage(msg.getId(), alice).isEmpty());
        assertTrue(attachments.listForMessage(amyMsg.getId(), alice).isEmpty());
    }

    @Test
    void dmAttachmentsRestrictedToParticipants() throws Exception {
        DmConversation c = dms.create(ws.getId(), alice, adam);
        DmMessage dm = dms.send(c.getId(), alice, "file for you");
        Attachment a = attachments.uploadToDmMessage(dm.getId(), alice, file("dm.txt", "dm content"));

        assertEquals(1, attachments.listForDmMessage(dm.getId(), adam).size());
        assertThrows(ApiException.class, () -> attachments.listForDmMessage(dm.getId(), amy));
        assertThrows(ApiException.class, () -> attachments.uploadToDmMessage(dm.getId(), adam, file("x.txt", "x")));
        assertThrows(ApiException.class, () -> attachments.download(a.getId(), amy));
        assertThrows(ApiException.class, () -> attachments.delete(a.getId(), amy));

        mvc.perform(get("/api/attachments/{id}", a.getId()).session(as(adam)))
                .andExpect(status().isOk())
                .andExpect(content().string("dm content"));
        mvc.perform(get("/api/dms/messages/{id}/attachments", dm.getId()).session(as(adam)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dmMessageId").value(dm.getId()));
    }

    @Test
    void adminExportIncludesAttachmentMetadata() throws Exception {
        Attachment a = attachments.uploadToMessage(msg.getId(), alice, file("e.txt", "e"));
        mvc.perform(get("/api/admin/workspaces/{id}/export", ws.getId()).session(as(adam)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments[0].id").value(a.getId()))
                .andExpect(jsonPath("$.attachments[0].filename").value("e.txt"))
                .andExpect(jsonPath("$.attachments[0].storageKey").doesNotExist());
    }
}
