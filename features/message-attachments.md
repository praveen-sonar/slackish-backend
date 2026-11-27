# Suggested feature: file attachments on messages

## Context
Messages in Slackish are text-only. Teams need to share files such as screenshots, documents and logs in the conversation where they're discussed, instead of pasting links to other tools. Channel messages and direct messages should both support attachments.

## Proposed scope
1. **Upload**: `POST /api/messages/{messageId}/attachments` and `POST /api/dms/messages/{dmMessageId}/attachments`
   - Multipart upload, one file per request.
   - Max 10 MB per file.
   - Returns the attachment metadata: id, original filename, content type, size, uploader, created time.
2. **List**: `GET /api/messages/{messageId}/attachments` and `GET /api/dms/messages/{dmMessageId}/attachments`
   - Metadata only, oldest first.
3. **Download**: `GET /api/attachments/{attachmentId}`
   - Returns the file content with its original filename.
4. **Delete**: `DELETE /api/attachments/{attachmentId}`
   - Allowed for the uploader or a workspace admin/owner.
   - Removes both the stored file and the metadata.
5. **Admin export**: `AdminController.export` includes attachment metadata (not file content) for the workspace.

## Where it fits
- New `attachment` package, following the existing layering: `AttachmentController`, `AttachmentDtos`, `AttachmentService`, `Attachment` entity, `AttachmentRepository`.
- `Attachment` refers to its parent by plain `Long` ids (`messageId` or `dmMessageId`, `workspaceId`, `uploaderId`), like the other entities. No JPA associations (per CLAUDE.md).
- Files are stored on local disk under a configurable directory, `app.attachments.dir` in `application.yml`. Only metadata goes in H2.
- Use `@Uid Long uid` on the new endpoints, `Errors.*` for failures and `WorkspaceService` for membership and role checks, as for other new endpoints.
- `admin/AdminController`: add attachments to the export.

## Alternatives considered
Message reactions and message delete. They're useful but smaller, and attachments are the most requested gap for day-to-day collaboration.

## Verification
- `mvn test` passes, with new tests covering upload, list, download and delete.
- `mvn spring-boot:run`, then log in as `alice@acme.io`. Upload a file to a message in `#general`, list it, download it, and confirm the content matches. Delete it and confirm both the download and the stored file are gone.
- Repeat for a DM between `alice@acme.io` and `adam@acme.io`.
- Confirm the admin export for Acme lists the attachment metadata.
