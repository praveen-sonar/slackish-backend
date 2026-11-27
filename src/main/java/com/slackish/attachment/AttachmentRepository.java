package com.slackish.attachment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByMessageIdOrderByIdAsc(Long messageId);
    List<Attachment> findByDmMessageIdOrderByIdAsc(Long dmMessageId);
    List<Attachment> findByWorkspaceIdOrderByIdAsc(Long workspaceId);
}
