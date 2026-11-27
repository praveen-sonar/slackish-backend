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

    @PostMapping(value = "/messages/{messageId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentDtos.AttachmentView uploadToMessage(@PathVariable Long messageId,
                                                         @RequestParam("file") MultipartFile file, @Uid Long uid) {
        return AttachmentDtos.AttachmentView.of(attachmentService.uploadToMessage(messageId, uid, file));
    }

    @GetMapping("/messages/{messageId}/attachments")
    public List<AttachmentDtos.AttachmentView> listForMessage(@PathVariable Long messageId, @Uid Long uid) {
        return attachmentService.listForMessage(messageId, uid).stream().map(AttachmentDtos.AttachmentView::of).toList();
    }

    @PostMapping(value = "/dms/messages/{dmMessageId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentDtos.AttachmentView uploadToDmMessage(@PathVariable Long dmMessageId,
                                                           @RequestParam("file") MultipartFile file, @Uid Long uid) {
        return AttachmentDtos.AttachmentView.of(attachmentService.uploadToDmMessage(dmMessageId, uid, file));
    }

    @GetMapping("/dms/messages/{dmMessageId}/attachments")
    public List<AttachmentDtos.AttachmentView> listForDmMessage(@PathVariable Long dmMessageId, @Uid Long uid) {
        return attachmentService.listForDmMessage(dmMessageId, uid).stream().map(AttachmentDtos.AttachmentView::of).toList();
    }

    /** Always served as a download so uploaded HTML/SVG is never rendered inline on the API origin. */
    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> download(@PathVariable Long attachmentId, @Uid Long uid) {
        AttachmentService.Download d = attachmentService.download(attachmentId, uid);
        Attachment a = d.attachment();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .contentLength(a.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(a.getFilename()))
                .header("X-Content-Type-Options", "nosniff")
                .body(d.content());
    }

    private static String contentDisposition(String filename) {
        ContentDisposition.Builder b = ContentDisposition.attachment();
        boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(filename);
        return (ascii ? b.filename(filename) : b.filename(filename, StandardCharsets.UTF_8)).build().toString();
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public void delete(@PathVariable Long attachmentId, @Uid Long uid) {
        attachmentService.delete(attachmentId, uid);
    }
}
