package com.slackish.attachment;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelRepository;
import com.slackish.common.Errors;
import com.slackish.dm.DmMessage;
import com.slackish.dm.DmService;
import com.slackish.message.Message;
import com.slackish.message.MessageService;
import com.slackish.workspace.WorkspaceService;
import org.springframework.core.io.Resource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Service
public class AttachmentService {

    static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 255;

    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final MessageService messageService;
    private final ChannelRepository channels;
    private final DmService dmService;
    private final WorkspaceService workspaceService;

    public AttachmentService(AttachmentRepository attachments, AttachmentStorage storage,
                             MessageService messageService, ChannelRepository channels,
                             DmService dmService, WorkspaceService workspaceService) {
        this.attachments = attachments;
        this.storage = storage;
        this.messageService = messageService;
        this.channels = channels;
        this.dmService = dmService;
        this.workspaceService = workspaceService;
    }

    /** Workspace of the parent message and its author, resolved after the caller's read access is checked. */
    private record Parent(Long workspaceId, Long authorId) {}

    public Attachment uploadToMessage(Long messageId, Long userId, MultipartFile file) {
        Parent parent = channelParent(messageId, userId);
        Attachment a = newAttachment(parent, userId, file);
        a.setMessageId(messageId);
        return store(a, file);
    }

    public Attachment uploadToDmMessage(Long dmMessageId, Long userId, MultipartFile file) {
        Parent parent = dmParent(dmMessageId, userId);
        Attachment a = newAttachment(parent, userId, file);
        a.setDmMessageId(dmMessageId);
        return store(a, file);
    }

    public List<Attachment> listForMessage(Long messageId, Long userId) {
        channelParent(messageId, userId);
        return attachments.findByMessageIdOrderByIdAsc(messageId);
    }

    public List<Attachment> listForDmMessage(Long dmMessageId, Long userId) {
        dmParent(dmMessageId, userId);
        return attachments.findByDmMessageIdOrderByIdAsc(dmMessageId);
    }

    /** Returns the attachment if the caller can see the message it belongs to. */
    public Attachment get(Long id, Long userId) {
        Attachment a = find(id);
        if (a.getMessageId() != null) {
            channelParent(a.getMessageId(), userId);
        } else {
            dmParent(a.getDmMessageId(), userId);
        }
        return a;
    }

    public Resource content(Attachment a) {
        if (!storage.exists(a.getStorageKey())) {
            throw Errors.notFound("Attachment content not found");
        }
        return storage.load(a.getStorageKey());
    }

    /** Uploader or a workspace admin/owner. The row and the file go together: a failed file delete rolls back. */
    @Transactional
    public void delete(Long id, Long userId) {
        Attachment a = find(id);
        if (a.getUploaderId().equals(userId)) {
            workspaceService.requireMember(a.getWorkspaceId(), userId);
        } else {
            workspaceService.requireAdmin(a.getWorkspaceId(), userId);
        }
        attachments.delete(a);
        attachments.flush();
        storage.delete(a.getStorageKey());
    }

    public List<Attachment> listForWorkspace(Long workspaceId) {
        return attachments.findByWorkspaceIdOrderByIdAsc(workspaceId);
    }

    private Attachment find(Long id) {
        return attachments.findById(id).orElseThrow(() -> Errors.notFound("Attachment not found"));
    }

    private Parent channelParent(Long messageId, Long userId) {
        Message m = messageService.getVisible(messageId, userId);
        Long workspaceId = channels.findById(m.getChannelId()).map(Channel::getWorkspaceId)
                .orElseThrow(() -> Errors.notFound("Channel not found"));
        workspaceService.requireMember(workspaceId, userId);
        return new Parent(workspaceId, m.getUserId());
    }

    private Parent dmParent(Long dmMessageId, Long userId) {
        DmMessage m = dmService.getMessage(dmMessageId, userId);
        Long workspaceId = dmService.get(m.getConversationId(), userId).getWorkspaceId();
        workspaceService.requireMember(workspaceId, userId);
        return new Parent(workspaceId, m.getSenderId());
    }

    private Attachment newAttachment(Parent parent, Long userId, MultipartFile file) {
        if (!parent.authorId().equals(userId)) {
            throw Errors.forbidden("You can only attach files to your own messages");
        }
        if (file == null || file.isEmpty()) {
            throw Errors.badRequest("file is required");
        }
        if (file.getSize() > MAX_SIZE) {
            throw Errors.payloadTooLarge("Attachments are limited to 10 MB");
        }
        Attachment a = new Attachment();
        a.setWorkspaceId(parent.workspaceId());
        a.setUploaderId(userId);
        a.setFilename(cleanFilename(file.getOriginalFilename()));
        a.setContentType(cleanContentType(file.getContentType()));
        a.setSize(file.getSize());
        a.setStorageKey(UUID.randomUUID().toString());
        return a;
    }

    private Attachment store(Attachment a, MultipartFile file) {
        storage.save(a.getStorageKey(), file);
        try {
            return attachments.save(a);
        } catch (RuntimeException e) {
            storage.delete(a.getStorageKey());
            throw e;
        }
    }

    /** Keeps only the last path segment and drops control characters; the name is display-only. */
    static String cleanFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    static String cleanContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            String parsed = MediaType.parseMediaType(contentType).toString();
            return parsed.length() > MAX_NAME_LENGTH ? MediaType.APPLICATION_OCTET_STREAM_VALUE : parsed;
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }
}
