# ArtistApp Java backend — migration in progress

This Spring Boot module is being developed on the `migration/java-spring-boot` branch alongside the original Laravel backend. Laravel and the existing database are not modified by this module.

## Requirements
- Java 21
- Maven 3.9+
- MySQL with the existing `vrb_artistapp` database and Laravel's auxiliary tables (`publication_comments`, `event_chat_messages`, `direct_messages`, `app_notifications`).

## Configuration
Set these environment variables before running:
- `DB_URL`: defaults to `jdbc:mysql://localhost:3306/vrb_artistapp?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Europe/Madrid`
- `DB_USERNAME`: defaults to `root`
- `DB_PASSWORD`: your local MySQL password
- `JWT_SECRET`: random secret of at least 32 characters; replace the development default before deployment
- `JWT_EXPIRATION_MINUTES`: defaults to `720`
- `PORT`: defaults to `8081`
- `artistapp.media.public-root` can be overridden using the corresponding Spring environment variable/property. Default is `../backend/storage/app/public`.

Run from this directory:

```bash
mvn spring-boot:run
```

The Java backend uses port 8081 by default so it can run next to Laravel on port 8000.

## Implemented endpoint groups
- Authentication: `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/auth/me`, `POST /api/auth/logout`
- Public discovery: users, user community, publications, comments list, events, products, categories, product types and notices
- Account: profile read/update and following users
- Publications: create/edit/delete, likes, visibility, comments
- Events: create/edit/delete, participants, event chat and direct messages
- Store: product JSON CRUD, cart, checkout, order listing/status, cancellation and receipt confirmation
- Notifications: list and mark read
- Administration: summary, user listing/create/edit/delete, categories, notices and reports

## Important compatibility limitations
This is an incremental migration, not yet a drop-in replacement for the full Laravel API. It has not been validated end-to-end against the Angular app. Multipart image/file uploads, password reset/email delivery, the protected digital-product download flow, and several detailed order/admin behaviours still need to be ported and tested. Some endpoints currently support JSON payloads only, while the Laravel frontend may send `FormData`. Do not switch Angular's proxy to this service or deploy it as a replacement until these gaps are resolved and the build plus integration tests pass.

The module does not run database migrations automatically and must not be pointed at a production database until reviewed. It reads and writes the existing tables directly. Never use demo credentials or the development JWT secret in production.
