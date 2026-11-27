package com.slackish.dm;

import com.slackish.common.Uid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dms")
public class DmController {

    private final DmService dmService;

    public DmController(DmService dmService) {
        this.dmService = dmService;
    }

    @GetMapping
    public List<DmDtos.ConversationView> list(@Uid Long uid) {
        return dmService.forUser(uid).stream().map(this::view).toList();
    }

    @PostMapping
    public DmDtos.ConversationView create(@RequestBody DmDtos.CreateConversationRequest req, @Uid Long uid) {
        return view(dmService.create(req.workspaceId(), uid, req.otherUserId()));
    }

    @GetMapping("/{conversationId}")
    public DmDtos.ConversationDetail get(@PathVariable Long conversationId,
                                         @RequestParam(required = false) Long before,
                                         @RequestParam(required = false) Integer limit, @Uid Long uid) {
        DmConversation c = dmService.get(conversationId, uid);
        List<DmDtos.DmMessageView> msgs = dmService.messages(conversationId, uid, before, limit).stream()
                .map(DmDtos.DmMessageView::of)
                .toList();
        return new DmDtos.ConversationDetail(view(c), msgs);
    }

    @PostMapping("/{conversationId}/messages")
    public DmDtos.DmMessageView send(@PathVariable Long conversationId,
                                     @RequestBody DmDtos.SendDmRequest req, @Uid Long uid) {
        return DmDtos.DmMessageView.of(dmService.send(conversationId, uid, req.body()));
    }

    private DmDtos.ConversationView view(DmConversation c) {
        return new DmDtos.ConversationView(c.getId(), c.getWorkspaceId(), c.getUserAId(), c.getUserBId());
    }
}
