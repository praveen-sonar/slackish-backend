package com.slackish.attachment;

import java.time.Instant;

public class AttachmentDtos {
    /** Metadata only; messageId or dmMessageId is null depending on the parent. */
    public record AttachmentView(Long id, Long workspaceId, Long messageId, Long dmMessageId, String filename,
                                 String contentType, long size, Long uploaderId, Instant createdAt) {
        public static AttachmentView of(Attachment a) {
            return new AttachmentView(a.getId(), a.getWorkspaceId(), a.getMessageId(), a.getDmMessageId(),
                    a.getFilename(), a.getContentType(), a.getSize(), a.getUploaderId(), a.getCreatedAt());
        }
    }
}
