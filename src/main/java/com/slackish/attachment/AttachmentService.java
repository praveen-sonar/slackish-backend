package com.slackish.attachment;

import com.slackish.channel.Channel;
import com.slackish.channel.ChannelMemberRepository;
import com.slackish.channel.ChannelRepository;
import com.slackish.common.Errors;
import com.slackish.dm.DmConversation;
import com.slackish.dm.DmMessage;
import com.slackish.dm.DmMessageRepository;
import com.slackish.dm.DmService;
import com.slackish.message.Message;
import com.slackish.message.MessageRepository;
import com.slackish.workspace.Membership;
import com.slackish.workspace.Role;
import com.slackish.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class AttachmentService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FILENAME = 255;

    public record Download(Attachment attachment, Resource content) {}

    private final AttachmentRepository attachments;
    private final MessageRepository messages;
    private final ChannelRepository channels;
    private final ChannelMemberRepository channelMembers;
    private final DmMessageRepository dmMessages;
    private final DmService dmService;
    private final WorkspaceService workspaceService;
    private final Path dir;

    public AttachmentService(AttachmentRepository attachments, MessageRepository messages, ChannelRepository channels,
                             ChannelMemberRepository channelMembers, DmMessageRepository dmMessages, DmService dmService,
                             WorkspaceService workspaceService, @Value("${app.attachments.dir}") String dir) {
        this.attachments = attachments;
        this.messages = messages;
        this.channels = channels;
        this.channelMembers = channelMembers;
        this.dmMessages = dmMessages;
        this.dmService = dmService;
        this.workspaceService = workspaceService;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create attachments directory " + this.dir, e);
        }
    }

    public Attachment uploadToMessage(Long messageId, Long userId, MultipartFile file) {
        Message m = getMessage(messageId);
        Long workspaceId = requireChannelAccess(m, userId);
        if (!m.getUserId().equals(userId)) {
            throw Errors.forbidden("You can only attach files to your own messages");
        }
        Attachment a = new Attachment();
        a.setMessageId(messageId);
        return store(a, workspaceId, userId, file);
    }

    public Attachment uploadToDmMessage(Long dmMessageId, Long userId, MultipartFile file) {
        DmMessage m = getDmMessage(dmMessageId);
        Long workspaceId = requireDmAccess(m, userId);
        if (!m.getSenderId().equals(userId)) {
            throw Errors.forbidden("You can only attach files to your own messages");
        }
        Attachment a = new Attachment();
        a.setDmMessageId(dmMessageId);
        return store(a, workspaceId, userId, file);
    }

    /** Oldest first. */
    public List<Attachment> listForMessage(Long messageId, Long userId) {
        requireChannelAccess(getMessage(messageId), userId);
        return attachments.findByMessageIdOrderByIdAsc(messageId);
    }

    /** Oldest first. */
    public List<Attachment> listForDmMessage(Long dmMessageId, Long userId) {
        requireDmAccess(getDmMessage(dmMessageId), userId);
        return attachments.findByDmMessageIdOrderByIdAsc(dmMessageId);
    }

    public Download download(Long attachmentId, Long userId) {
        Attachment a = get(attachmentId);
        requireParentAccess(a, userId);
        Path path = resolve(a);
        if (!Files.isRegularFile(path)) {
            throw Errors.notFound("Attachment content not found");
        }
        return new Download(a, new FileSystemResource(path));
    }

    /** Uploader or a workspace admin/owner. Removes the stored file and the metadata. */
    public void delete(Long attachmentId, Long userId) {
        Attachment a = get(attachmentId);
        Membership membership = requireActiveMember(a.getWorkspaceId(), userId);
        boolean isUploader = a.getUploaderId().equals(userId);
        if (isUploader) {
            requireParentAccess(a, userId);
        } else if (membership.getRole() == Role.MEMBER) {
            throw Errors.forbidden("Only the uploader or a workspace admin can delete this attachment");
        }
        attachments.delete(a);
        try {
            Files.deleteIfExists(resolve(a));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete attachment content", e);
        }
    }

    public List<Attachment> listForWorkspace(Long workspaceId) {
        return attachments.findByWorkspaceIdOrderByIdAsc(workspaceId);
    }

    private Attachment store(Attachment a, Long workspaceId, Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw Errors.badRequest("File is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw Errors.badRequest("File exceeds the 10 MB limit");
        }
        a.setWorkspaceId(workspaceId);
        a.setUploaderId(userId);
        a.setFilename(sanitizeFilename(file.getOriginalFilename()));
        a.setContentType(sanitizeContentType(file.getContentType()));
        a.setSize(file.getSize());
        a.setStorageKey(UUID.randomUUID().toString());
        Path target = resolve(a);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store attachment", e);
        }
        try {
            return attachments.save(a);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
    }

    private Path resolve(Attachment a) {
        Path path = dir.resolve(a.getStorageKey()).normalize();
        if (!path.startsWith(dir)) {
            throw Errors.badRequest("Invalid attachment");
        }
        return path;
    }

    private Attachment get(Long id) {
        return attachments.findById(id).orElseThrow(() -> Errors.notFound("Attachment not found"));
    }

    private Message getMessage(Long id) {
        return messages.findById(id).filter(m -> !m.isDeleted())
                .orElseThrow(() -> Errors.notFound("Message not found"));
    }

    private DmMessage getDmMessage(Long id) {
        return dmMessages.findById(id).orElseThrow(() -> Errors.notFound("Message not found"));
    }

    private void requireParentAccess(Attachment a, Long userId) {
        if (a.getMessageId() != null) {
            requireChannelAccess(getMessage(a.getMessageId()), userId);
        } else {
            requireDmAccess(getDmMessage(a.getDmMessageId()), userId);
        }
    }

    /** Active workspace member and member of the message's channel. Returns the workspace id. */
    private Long requireChannelAccess(Message m, Long userId) {
        Channel c = channels.findById(m.getChannelId()).orElseThrow(() -> Errors.notFound("Channel not found"));
        requireActiveMember(c.getWorkspaceId(), userId);
        if (!channelMembers.existsByChannelIdAndUserId(c.getId(), userId)) {
            throw Errors.forbidden("Not a member of this channel");
        }
        return c.getWorkspaceId();
    }

    /** Active workspace member and participant in the conversation. Returns the workspace id. */
    private Long requireDmAccess(DmMessage m, Long userId) {
        DmConversation c = dmService.get(m.getConversationId());
        requireActiveMember(c.getWorkspaceId(), userId);
        if (!c.getUserAId().equals(userId) && !c.getUserBId().equals(userId)) {
            throw Errors.forbidden("Not a participant in this conversation");
        }
        return c.getWorkspaceId();
    }

    private Membership requireActiveMember(Long workspaceId, Long userId) {
        Membership m = workspaceService.requireMember(workspaceId, userId);
        if (!m.isActive()) {
            throw Errors.forbidden("Membership is deactivated");
        }
        return m;
    }

    private static String sanitizeFilename(String original) {
        String name = original == null ? null : StringUtils.getFilename(StringUtils.cleanPath(original));
        name = name == null ? "" : name.replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "file";
        }
        return name.length() > MAX_FILENAME ? name.substring(name.length() - MAX_FILENAME) : name;
    }

    private static String sanitizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            return MediaType.parseMediaType(contentType).toString();
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup after a failed save
        }
    }
}
