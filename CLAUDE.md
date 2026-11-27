# Slackish Backend

REST API for Slackish, a lightweight Slack-like team messaging app. Spring Boot 3.3.4, Java 21, Maven. Single deployable, in-memory H2 database, no frontend in this repo (the expected frontend origin is `http://localhost:5173`).

## Commands

```bash
mvn spring-boot:run     # start API on :8080 (H2 in-memory, seeded on startup)
mvn test                # run tests (none exist yet)
mvn package             # build the jar
```

- H2 console: http://localhost:8080/h2 (JDBC URL `jdbc:h2:mem:slackish`, user `sa`, empty password).
- Seed login (all users): password `password123`. Users: `alice@acme.io` (owner), `adam@acme.io` (admin), `amy@acme.io` (member), `bob@globex.io` (owner), `betty@globex.io` (member).
- Config lives in `src/main/resources/application.yml` (`app.jwt.secret`, `app.frontend-origin`, datasource, session cookie settings).

## Architecture

Layered monolith, packaged by feature under `com.slackish`. Each feature package normally contains the pieces below, and not every package has all of them:

| Layer | Naming | Role |
|-------|--------|------|
| Controller | `*Controller` | `@RestController`, HTTP mapping, takes the caller id via `@Uid Long uid` |
| DTOs | `*Dtos` | Java records grouped in one holder class per feature |
| Service | `*Service` | Business rules and authorization checks |
| Entity | plain class (`Channel`, `Message`, ...) | JPA entity |
| Repository | `*Repository` | Spring Data JPA interface |

Entities reference each other by plain `Long` id fields (e.g. `workspaceId`, `userId`). There are no JPA associations (`@ManyToOne` and similar), so joins are done in service code.

### Packages

- `auth`: register/login/logout/me, password reset (`PasswordResetToken`), bot API tokens (`BotController`), JWT issue/parse (`JwtService`, HS256 via jjwt 0.11.5).
- `user`: `User` entity (email, display name, BCrypt password hash), `UserController`.
- `workspace`: `Workspace` (the tenant), `Membership` (user ↔ workspace with `Role` and `active` flag), `Role` = `OWNER | ADMIN | MEMBER`. `WorkspaceService.requireMember/requireAdmin` are the tenant-level authorization primitives.
- `channel`: `Channel` (public or private, belongs to a workspace) and `ChannelMember`. Private channels are only listed for their members.
- `message`: channel `Message` with optional `parentId` for threaded replies, `editedAt` for edits. Channel listing is capped at the first 50 messages, oldest first.
- `dm`: `DmConversation` (two participants, `userAId`/`userBId`, per workspace) and `DmMessage`.
- `invitation`: single and bulk invitations with a UUID token and 7-day expiry. Accepting one creates or reactivates a `Membership` with the invited role.
- `admin`: `AdminController` for member deactivation, role change, audit log listing and full workspace export.
- `audit`: `AuditLog` entity and `AuditService.record(...)`. Currently written only by the admin actions.
- `common`: `ApiException` + `Errors` factory (`badRequest/unauthorized/forbidden/notFound/conflict`), `GlobalExceptionHandler` (returns `{"error", "status"}` JSON), `@Uid` annotation and `CurrentUserResolver`.
- `config`: `SecurityConfig` (CORS, BCrypt bean), `WebConfig` (registers `CurrentUserResolver`), `DataSeeder` (two demo tenants, Acme and Globex).

### Authentication and authorization flow

1. Spring Security is configured with `anyRequest().permitAll()`, CSRF disabled, and basic/form login/logout disabled. **Authentication and authorization are not enforced by the filter chain.** They happen inside handlers.
2. Controller methods that need a caller declare a `@Uid Long uid` parameter. `CurrentUserResolver` resolves it from either the HTTP session attribute `uid` (cookie-based login), or an `Authorization: Bearer <jwt>` header (bots/API clients). If neither is present it throws a 401 `ApiException`.
3. Services enforce tenant and role rules, usually by calling `WorkspaceService.requireMember` or `requireAdmin`. A handler that doesn't call one of these is unauthenticated-only, not tenant-checked.
4. Sessions never time out (`server.servlet.session.timeout: -1`). Cookie is `HttpOnly`, `SameSite=Lax`.

### Data model (H2, `ddl-auto: create-drop`)

```
User ──< Membership >── Workspace ──< Channel ──< ChannelMember >── User
                            │             └──< Message (parentId → thread)
                            ├──< DmConversation ──< DmMessage
                            ├──< Invitation
                            └──< AuditLog
PasswordResetToken >── User
```

The schema is recreated on every start, and there are no migrations. `DataSeeder` runs as a `CommandLineRunner` and only seeds when the `users` table is empty.

## Functional summary

- **Accounts:** register, login (session cookie), logout, `GET /api/auth/me`, forgot/reset password, `POST /api/bot/token` for a JWT.
- **Workspaces (multi-tenant):** a user can belong to many workspaces. The creator becomes `OWNER`.
- **Channels:** public or private, create/list/join/delete.
- **Messaging:** post, fetch, thread listing, edit (author only) in channels. Direct messages between two members.
- **Invitations:** invite by email (single or bulk), accept by token.
- **Admin:** change role, deactivate member, view audit log, export workspace data (members, channels, messages, DMs, invitations).

Endpoint list: see `README.md`.

## Conventions

- Constructor injection only, no Lombok. Entities use explicit getters and setters.
- Throw `Errors.*` from services and controllers. Don't build error `ResponseEntity`s by hand.
- New endpoints take `@Uid Long uid` and check membership or role via `WorkspaceService` before touching data.
- Keep DTOs as records in the feature's `*Dtos` class.
- Spring Data derived queries (`findByWorkspaceIdAndUserId`, ...) are the norm. Don't add JPA associations without a reason.

## Known gaps (observed in the service layer)

Worth knowing before changing related code. Some are probably intentional for a demo or training app, but none are documented as such in the code.

- `SecurityConfig` permits all requests and disables CSRF. Session fixation protection is off.
- `AuthService.authenticate` returns the user without a password check when the password is null or empty.
- Password-reset tokens are derivable (`userId-hash(email)`), valid for 30 days, are not checked for expiry on use, and are not deleted after use. `beginPasswordReset` returns the token to the caller and reveals whether an email exists.
- `AdminController.export` has no `requireAdmin` or `requireMember` check. `MessageService.post`, `ChannelService.join`/`delete`, `InvitationService.listForWorkspace`/`accept` and `DmService.get`/`messages`/`create` also lack the membership, ownership or participant checks that their neighbouring methods have.
- `InvitationService.accept` doesn't check expiry, status or that the invitation email matches the accepting user. `invite` only requires membership, so any member can invite with any role.
- `Membership.active` is set to `false` on deactivation, but `requireMember` doesn't check it.
- `GlobalExceptionHandler` returns raw exception messages for unexpected errors.
- `application.yml` contains a committed JWT signing secret, and JWTs carry no expiry.
- `DataSeeder` contains fake secrets and PII-like strings (AWS keys, Stripe key, salaries) as demo content.
