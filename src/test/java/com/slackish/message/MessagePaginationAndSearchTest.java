package com.slackish.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slackish.channel.Channel;
import com.slackish.channel.ChannelRepository;
import com.slackish.common.CurrentUserResolver;
import com.slackish.dm.DmConversation;
import com.slackish.dm.DmConversationRepository;
import com.slackish.user.UserRepository;
import com.slackish.workspace.Workspace;
import com.slackish.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessagePaginationAndSearchTest {

    private static final String SEARCH = "/api/workspaces/{wid}/search";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired ChannelRepository channels;
    @Autowired DmConversationRepository conversations;
    @Autowired MessageRepository messages;

    @Test
    void pagesChannelNewestFirstWithoutGapsOrDuplicates() throws Exception {
        Long general = channel("acme", "general");
        MockHttpSession alice = session("alice@acme.io");
        for (int i = 0; i < 120; i++) {
            mvc.perform(post("/api/channels/{id}/messages", general).session(alice)
                            .contentType("application/json").content("{\"body\":\"msg " + i + "\"}"))
                    .andExpect(status().isOk());
        }
        List<Long> expected = messages.findByChannelId(general).stream()
                .map(Message::getId).sorted((a, b) -> Long.compare(b, a)).toList();

        assertThat(ids(list(general, alice, null, null))).hasSize(50).isEqualTo(expected.subList(0, 50));

        List<Long> seen = new ArrayList<>();
        Long before = null;
        List<Long> page;
        do {
            page = ids(list(general, alice, before, 30));
            seen.addAll(page);
            before = page.isEmpty() ? before : page.get(page.size() - 1);
        } while (page.size() == 30);

        assertThat(seen).isEqualTo(expected);
        assertThat(new HashSet<>(seen)).hasSameSizeAs(seen);
    }

    @Test
    void rejectsLimitOutsideRange() throws Exception {
        Long general = channel("acme", "general");
        MockHttpSession alice = session("alice@acme.io");
        for (String limit : List.of("0", "101", "-1")) {
            mvc.perform(get("/api/channels/{id}/messages", general).param("limit", limit).session(alice))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/channels/{id}/messages", general).param("limit", "100").session(alice))
                .andExpect(status().isOk());
    }

    @Test
    void dmHistoryIsPagedAndRestrictedToParticipants() throws Exception {
        Long dm = conversation("acme");
        MockHttpSession alice = session("alice@acme.io");

        JsonNode first = body(mvc.perform(get("/api/dms/{id}", dm).param("limit", "1").session(alice)));
        JsonNode newest = first.get("messages");
        assertThat(newest).hasSize(1);
        assertThat(newest.get(0).get("body").asText()).startsWith("Will do.");

        JsonNode next = body(mvc.perform(get("/api/dms/{id}", dm)
                .param("before", newest.get(0).get("id").asText()).session(alice))).get("messages");
        assertThat(next).hasSize(1);
        assertThat(next.get(0).get("body").asText()).startsWith("Adam, can you rotate");

        mvc.perform(get("/api/dms/{id}", dm).session(session("amy@acme.io"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/dms/{id}", dm).session(session("bob@globex.io"))).andExpect(status().isForbidden());
    }

    @Test
    void searchIsCaseInsensitiveAndIncludesOwnPrivateChannelsAndDms() throws Exception {
        JsonNode r = search("acme", session("alice@acme.io"), "aws KEY");
        assertThat(bodies(r.get("channelMessages"))).anyMatch(b -> b.startsWith("Prod AWS key"));
        assertThat(bodies(r.get("dmMessages"))).anyMatch(b -> b.contains("rotate that AWS key"));
    }

    @Test
    void searchHidesPrivateChannelsAndOthersDms() throws Exception {
        JsonNode r = search("acme", session("amy@acme.io"), "aws");
        assertThat(r.get("channelMessages")).isEmpty();
        assertThat(r.get("dmMessages")).isEmpty();

        JsonNode deploy = search("acme", session("amy@acme.io"), "deploy");
        assertThat(bodies(deploy.get("channelMessages"))).containsExactly("Deploy is green, shipping v2.3 tonight.");
    }

    @Test
    void searchDoesNotCrossTenants() throws Exception {
        MockHttpSession bob = session("bob@globex.io");
        mvc.perform(get(SEARCH, workspace("acme")).param("q", "aws").session(bob)).andExpect(status().isForbidden());

        JsonNode r = search("globex", bob, "a");
        Set<Long> globexChannels = new HashSet<>(channels.findByWorkspaceId(workspace("globex")).stream()
                .map(Channel::getId).toList());
        r.get("channelMessages").forEach(m -> assertThat(globexChannels).contains(m.get("channelId").asLong()));
        assertThat(bodies(r.get("channelMessages"))).noneMatch(b -> b.contains("AWS"));
    }

    @Test
    void searchPagesWithCursorAndRequiresQuery() throws Exception {
        MockHttpSession alice = session("alice@acme.io");
        Long wid = workspace("acme");
        mvc.perform(get(SEARCH, wid).param("q", " ").session(alice)).andExpect(status().isBadRequest());

        JsonNode first = body(mvc.perform(get(SEARCH, wid).param("q", "a").param("limit", "2").session(alice)));
        List<Long> page1 = ids(first.get("channelMessages"));
        assertThat(page1).hasSize(2);
        JsonNode second = body(mvc.perform(get(SEARCH, wid).param("q", "a").param("limit", "2")
                .param("before", page1.get(1).toString()).session(alice)));
        assertThat(ids(second.get("channelMessages"))).allMatch(id -> id < page1.get(1));
    }

    @Test
    void searchTreatsLikeWildcardsLiterally() throws Exception {
        JsonNode r = search("acme", session("alice@acme.io"), "%");
        assertThat(r.get("channelMessages")).isEmpty();
        assertThat(r.get("dmMessages")).isEmpty();
    }

    private JsonNode list(Long channelId, MockHttpSession s, Long before, Integer limit) throws Exception {
        var req = get("/api/channels/{id}/messages", channelId).session(s);
        if (before != null) req.param("before", before.toString());
        if (limit != null) req.param("limit", limit.toString());
        return body(mvc.perform(req));
    }

    private JsonNode search(String slug, MockHttpSession s, String q) throws Exception {
        return body(mvc.perform(get(SEARCH, workspace(slug)).param("q", q).session(s)));
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private static List<String> bodies(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("body").asText()));
        return out;
    }

    private MockHttpSession session(String email) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(CurrentUserResolver.SESSION_UID, users.findByEmail(email).orElseThrow().getId());
        return s;
    }

    private Long workspace(String slug) {
        return workspaces.findAll().stream().filter(w -> w.getSlug().equals(slug))
                .map(Workspace::getId).findFirst().orElseThrow();
    }

    private Long channel(String slug, String name) {
        return channels.findByWorkspaceId(workspace(slug)).stream().filter(c -> c.getName().equals(name))
                .map(Channel::getId).findFirst().orElseThrow();
    }

    private Long conversation(String slug) {
        return conversations.findByWorkspaceId(workspace(slug)).stream()
                .map(DmConversation::getId).findFirst().orElseThrow();
    }
}
