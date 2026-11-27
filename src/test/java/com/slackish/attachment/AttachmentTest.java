package com.slackish.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackish.channel.Channel;
import com.slackish.channel.ChannelRepository;
import com.slackish.common.CurrentUserResolver;
import com.slackish.dm.DmConversation;
import com.slackish.dm.DmConversationRepository;
import com.slackish.dm.DmMessage;
import com.slackish.dm.DmMessageRepository;
import com.slackish.message.Message;
import com.slackish.message.MessageRepository;
import com.slackish.user.UserRepository;
import com.slackish.workspace.Workspace;
import com.slackish.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Not @Transactional: delete commits the row and removes the file, and the test checks the disk.
// Its own H2 database keeps the committed rows away from other test classes.
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentTest {

    private static final String MESSAGE_ATTACHMENTS = "/api/messages/{id}/attachments";
    private static final String DM_ATTACHMENTS = "/api/dms/messages/{id}/attachments";
    private static final String ATTACHMENT = "/api/attachments/{id}";

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.dir", storageDir::toString);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:attachment-test;DB_CLOSE_DELAY=-1");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired ChannelRepository channels;
    @Autowired MessageRepository messages;
    @Autowired DmConversationRepository conversations;
    @Autowired DmMessageRepository dmMessages;

    @Test
    void uploadListDownloadAndDeleteOnChannelMessage() throws Exception {
        MockHttpSession alice = session("alice@acme.io");
        Long messageId = ownMessage("acme", "general", "alice@acme.io");

        JsonNode first = upload(MESSAGE_ATTACHMENTS, messageId, alice, file("notes.txt", "text/plain", "hello"));
        assertThat(first.get("filename").asText()).isEqualTo("notes.txt");
        assertThat(first.get("contentType").asText()).isEqualTo("text/plain");
        assertThat(first.get("size").asLong()).isEqualTo(5);
        assertThat(first.get("uploaderId").asLong()).isEqualTo(userId("alice@acme.io"));
        assertThat(first.get("messageId").asLong()).isEqualTo(messageId);
        assertThat(first.get("createdAt").isNull()).isFalse();
        JsonNode second = upload(MESSAGE_ATTACHMENTS, messageId, alice, file("b.log", "text/plain", "second"));

        JsonNode list = body(mvc.perform(get(MESSAGE_ATTACHMENTS, messageId).session(session("amy@acme.io"))));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(list.get(1).get("id").asLong()).isEqualTo(second.get("id").asLong());

        long id = first.get("id").asLong();
        mvc.perform(get(ATTACHMENT, id).session(session("amy@acme.io")))
                .andExpect(status().isOk())
                .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(header().string("Content-Disposition",
                        allOf(startsWith("attachment;"), containsString("filename*=UTF-8''notes.txt"))));

        assertThat(storedFiles()).isPositive();
        long before = storedFiles();
        mvc.perform(delete(ATTACHMENT, id).session(alice)).andExpect(status().isOk());
        mvc.perform(get(ATTACHMENT, id).session(alice)).andExpect(status().isNotFound());
        assertThat(storedFiles()).isEqualTo(before - 1);
        assertThat(body(mvc.perform(get(MESSAGE_ATTACHMENTS, messageId).session(alice)))).hasSize(1);
    }

    @Test
    void uploadListDownloadAndDeleteOnDmMessage() throws Exception {
        MockHttpSession alice = session("alice@acme.io");
        Long dmMessageId = ownDmMessage("alice@acme.io");

        JsonNode a = upload(DM_ATTACHMENTS, dmMessageId, alice, file("key.pem", "application/x-pem-file", "secret"));
        assertThat(a.get("dmMessageId").asLong()).isEqualTo(dmMessageId);

        MockHttpSession adam = session("adam@acme.io");
        assertThat(body(mvc.perform(get(DM_ATTACHMENTS, dmMessageId).session(adam)))).hasSize(1);
        mvc.perform(get(ATTACHMENT, a.get("id").asLong()).session(adam))
                .andExpect(status().isOk()).andExpect(content().string("secret"));

        mvc.perform(get(DM_ATTACHMENTS, dmMessageId).session(session("amy@acme.io"))).andExpect(status().isForbidden());
        mvc.perform(get(ATTACHMENT, a.get("id").asLong()).session(session("amy@acme.io")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ATTACHMENT, a.get("id").asLong()).session(session("bob@globex.io")))
                .andExpect(status().isForbidden());

        mvc.perform(delete(ATTACHMENT, a.get("id").asLong()).session(alice)).andExpect(status().isOk());
        assertThat(body(mvc.perform(get(DM_ATTACHMENTS, dmMessageId).session(alice)))).isEmpty();
    }

    @Test
    void onlyTheAuthorCanAttachAndOnlyMembersCanRead() throws Exception {
        Long messageId = ownMessage("acme", "general", "alice@acme.io");
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, messageId).file(file("x.txt", "text/plain", "x"))
                .session(session("amy@acme.io"))).andExpect(status().isForbidden());
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, messageId).file(file("x.txt", "text/plain", "x"))
                .session(session("bob@globex.io"))).andExpect(status().isForbidden());
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, messageId).file(file("x.txt", "text/plain", "x")))
                .andExpect(status().isUnauthorized());

        JsonNode a = upload(MESSAGE_ATTACHMENTS, messageId, session("alice@acme.io"), file("x.txt", "text/plain", "x"));
        mvc.perform(get(MESSAGE_ATTACHMENTS, messageId).session(session("bob@globex.io")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ATTACHMENT, a.get("id").asLong()).session(session("bob@globex.io")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteIsLimitedToUploaderAndAdmins() throws Exception {
        Long messageId = ownMessage("acme", "general", "amy@acme.io");
        MockHttpSession amy = session("amy@acme.io");
        long id = upload(MESSAGE_ATTACHMENTS, messageId, amy, file("a.txt", "text/plain", "a")).get("id").asLong();
        long other = upload(MESSAGE_ATTACHMENTS, messageId, amy, file("b.txt", "text/plain", "b")).get("id").asLong();

        mvc.perform(delete(ATTACHMENT, id).session(session("bob@globex.io"))).andExpect(status().isForbidden());
        mvc.perform(delete(ATTACHMENT, id).session(session("adam@acme.io"))).andExpect(status().isOk());
        mvc.perform(delete(ATTACHMENT, other).session(session("alice@acme.io"))).andExpect(status().isOk());
        mvc.perform(delete(ATTACHMENT, id).session(amy)).andExpect(status().isNotFound());

        long mine = upload(MESSAGE_ATTACHMENTS, ownMessage("acme", "general", "alice@acme.io"),
                session("alice@acme.io"), file("c.txt", "text/plain", "c")).get("id").asLong();
        mvc.perform(delete(ATTACHMENT, mine).session(amy)).andExpect(status().isForbidden());
    }

    @Test
    void rejectsEmptyOversizedAndMissingFiles() throws Exception {
        Long messageId = ownMessage("acme", "general", "alice@acme.io");
        MockHttpSession alice = session("alice@acme.io");
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, messageId).file(file("e.txt", "text/plain", "")).session(alice))
                .andExpect(status().isBadRequest());
        byte[] big = new byte[(int) AttachmentService.MAX_SIZE + 1];
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, messageId)
                        .file(new MockMultipartFile("file", "big.bin", "application/octet-stream", big)).session(alice))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, messageId).session(alice)).andExpect(status().isBadRequest());
        mvc.perform(multipart(MESSAGE_ATTACHMENTS, 999_999L).file(file("x.txt", "text/plain", "x")).session(alice))
                .andExpect(status().isNotFound());
    }

    @Test
    void stripsPathsFromFilenamesAndNormalizesContentType() throws Exception {
        Long messageId = ownMessage("acme", "general", "alice@acme.io");
        JsonNode a = upload(MESSAGE_ATTACHMENTS, messageId, session("alice@acme.io"),
                file("..\\..\\etc/passwd", "not a type", "x"));
        assertThat(a.get("filename").asText()).isEqualTo("passwd");
        assertThat(a.get("contentType").asText()).isEqualTo("application/octet-stream");
        assertThat(AttachmentService.cleanFilename("..")).isEqualTo("file");
        assertThat(AttachmentService.cleanFilename(null)).isEqualTo("file");
    }

    @Test
    void adminExportListsAttachmentMetadata() throws Exception {
        MockHttpSession alice = session("alice@acme.io");
        Long messageId = ownMessage("acme", "general", "alice@acme.io");
        long id = upload(MESSAGE_ATTACHMENTS, messageId, alice, file("export.txt", "text/plain", "data"))
                .get("id").asLong();

        JsonNode export = body(mvc.perform(get("/api/admin/workspaces/{id}/export", workspace("acme")).session(alice)));
        JsonNode listed = null;
        for (JsonNode n : export.get("attachments")) {
            if (n.get("id").asLong() == id) listed = n;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("filename").asText()).isEqualTo("export.txt");
        assertThat(listed.has("storageKey")).isFalse();

        JsonNode globex = body(mvc.perform(get("/api/admin/workspaces/{id}/export", workspace("globex"))
                .session(session("bob@globex.io"))));
        globex.get("attachments").forEach(n -> assertThat(n.get("id").asLong()).isNotEqualTo(id));
    }

    private JsonNode upload(String path, Long parentId, MockHttpSession s, MockMultipartFile f) throws Exception {
        return body(mvc.perform(multipart(path, parentId).file(f).session(s)));
    }

    private static MockMultipartFile file(String name, String type, String text) {
        return new MockMultipartFile("file", name, type, text.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static long storedFiles() throws Exception {
        try (Stream<Path> files = Files.list(storageDir)) {
            return files.count();
        }
    }

    private Long ownMessage(String slug, String channelName, String email) {
        Long channelId = channels.findByWorkspaceId(workspace(slug)).stream()
                .filter(c -> c.getName().equals(channelName)).map(Channel::getId).findFirst().orElseThrow();
        Message m = new Message();
        m.setChannelId(channelId);
        m.setUserId(userId(email));
        m.setBody("attachment test");
        return messages.save(m).getId();
    }

    private Long ownDmMessage(String email) {
        DmConversation c = conversations.findByWorkspaceId(workspace("acme")).stream().findFirst().orElseThrow();
        DmMessage m = new DmMessage();
        m.setConversationId(c.getId());
        m.setSenderId(userId(email));
        m.setBody("dm attachment test");
        return dmMessages.save(m).getId();
    }

    private Long userId(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    private MockHttpSession session(String email) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(CurrentUserResolver.SESSION_UID, userId(email));
        return s;
    }

    private Long workspace(String slug) {
        return workspaces.findAll().stream().filter(w -> w.getSlug().equals(slug))
                .map(Workspace::getId).findFirst().orElseThrow();
    }
}
