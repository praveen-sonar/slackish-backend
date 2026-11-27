package com.slackish.attachment;

import com.slackish.common.Uid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/messages/{messageId}/attachments")
    public AttachmentDtos.AttachmentView uploadToMessage(@PathVariable Long messageId,
                                                         @RequestParam("file") MultipartFile file, @Uid Long uid) {
        return AttachmentDtos.AttachmentView.of(attachmentService.uploadToMessage(messageId, uid, file));
    }

    @GetMapping("/messages/{messageId}/attachments")
    public List<AttachmentDtos.AttachmentView> listForMessage(@PathVariable Long messageId, @Uid Long uid) {
        return attachmentService.listForMessage(messageId, uid).stream().map(AttachmentDtos.AttachmentView::of).toList();
    }

    @PostMapping("/dms/messages/{dmMessageId}/attachments")
    public AttachmentDtos.AttachmentView uploadToDmMessage(@PathVariable Long dmMessageId,
                                                           @RequestParam("file") MultipartFile file, @Uid Long uid) {
        return AttachmentDtos.AttachmentView.of(attachmentService.uploadToDmMessage(dmMessageId, uid, file));
    }

    @GetMapping("/dms/messages/{dmMessageId}/attachments")
    public List<AttachmentDtos.AttachmentView> listForDmMessage(@PathVariable Long dmMessageId, @Uid Long uid) {
        return attachmentService.listForDmMessage(dmMessageId, uid).stream()
                .map(AttachmentDtos.AttachmentView::of).toList();
    }

    /** Always served as a download, so uploaded HTML/SVG is never rendered on the API origin. */
    @GetMapping("/attachments/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id, @Uid Long uid) {
        Attachment a = attachmentService.get(id, uid);
        Resource content = attachmentService.content(a);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(a.getFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .contentLength(a.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content);
    }

    @DeleteMapping("/attachments/{id}")
    public void delete(@PathVariable Long id, @Uid Long uid) {
        attachmentService.delete(id, uid);
    }
}
