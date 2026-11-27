package com.slackish.attachment;

import java.time.Instant;

public class AttachmentDtos {
    public record AttachmentView(Long id, Long messageId, Long dmMessageId, String filename, String contentType,
                                 long size, Long uploaderId, Instant createdAt) {
        public static AttachmentView of(Attachment a) {
            return new AttachmentView(a.getId(), a.getMessageId(), a.getDmMessageId(), a.getFilename(),
                    a.getContentType(), a.getSize(), a.getUploaderId(), a.getCreatedAt());
        }
    }
}
