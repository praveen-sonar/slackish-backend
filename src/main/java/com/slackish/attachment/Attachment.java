package com.slackish.attachment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "attachments")
public class Attachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    /** Exactly one of messageId / dmMessageId is set. */
    private Long messageId;
    private Long dmMessageId;

    @Column(nullable = false)
    private Long uploaderId;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String contentType;

    private long size;

    /** Generated file name under app.attachments.dir; never derived from user input. */
    @Column(nullable = false, unique = true)
    private String storageKey;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getDmMessageId() { return dmMessageId; }
    public void setDmMessageId(Long dmMessageId) { this.dmMessageId = dmMessageId; }
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
