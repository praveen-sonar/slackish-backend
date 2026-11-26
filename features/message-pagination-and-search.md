# Suggested feature: message history pagination + search

## Context
Channel listing in `message` is capped at the first 50 messages, oldest first, with no paging. Once a channel passes 50 messages, newer messages can't be fetched through the API, and there is no way to find anything. It's the largest gap in the existing functionality, and it blocks most other features (unread counts, mentions, notifications).

## Proposed scope
1. **Cursor pagination** on channel messages and DM messages
   - Newest first, `?before=<messageId>&limit=<n>` (default 50, max 100).
   - Response stays a list of the existing message DTOs, so current clients still work.
2. **Search** `GET /api/workspaces/{wid}/search?q=...`
   - Matches channel messages (public channels, plus private channels the caller belongs to) and the caller's own DMs.
   - Case-insensitive, paged the same way.

## Where it fits
- `message/MessageRepository`: add derived queries (`findByChannelIdAndIdLessThanOrderByIdDesc` with `Pageable`, `...ContainingIgnoreCase`).
- `message/MessageService`: add paged fetch and search. Call `WorkspaceService.requireMember`, and check channel membership for private channels via `ChannelMember`.
- `dm/DmService`: same paging, restricted to conversation participants.
- DTOs: add a search-result record to `MessageDtos`. Don't introduce JPA associations (per CLAUDE.md).
- Use `Errors.*` for failures and `@Uid Long uid` on the new endpoints.


## Alternatives considered
Unread counts / mentions, reactions, and message delete. These are valuable, but they are less blocking than being unable to read past message 50.

## Verification
- `mvn spring-boot:run`, log in as `alice@acme.io`, and post more than 50 messages to a channel.
- Page with `before`/`limit` and confirm there are no gaps or duplicates.
- Search as `amy@acme.io` and `bob@globex.io`. Confirm neither sees private channels they aren't in, DMs of others, or the other tenant's data.
- Add the project's first tests (`mvn test`) for the pagination boundaries and the search access rules.
